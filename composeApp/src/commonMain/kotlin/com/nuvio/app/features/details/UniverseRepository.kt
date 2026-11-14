package com.nuvio.app.features.details

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** A title can have several relationships to the current movie or show. */
internal data class UniverseConnection(val item: MetaPreview, val relations: List<String>)
internal data class UniverseLoadState(
    val items: List<UniverseConnection>? = null,
    val loading: Boolean = false,
    val updatedAt: Long = 0,
)

internal object UniverseRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val _states = MutableStateFlow<Map<String, UniverseLoadState>>(emptyMap())
    val states = _states.asStateFlow()
    private val client = UniverseClient(
        request = { body ->
            httpRequestRaw(
                method = "POST",
                url = "https://graphql.imdb.com/",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to "https://www.imdb.com/",
                ),
                body = body,
            )
        },
        resolveImdb = { id, type -> TmdbService.tmdbToImdb(id, type) },
        nowMs = { Clock.System.now().toEpochMilliseconds() },
    )

    fun key(meta: MetaDetails): String = "${meta.type}:${meta.id}:${meta.imdbId.orEmpty()}"

    fun prefetch(meta: MetaDetails, refresh: Boolean = false) {
        if (!supportsUniverse(meta.type)) return
        // Do not contact the provider before the user has explicitly enabled this section.
        if (MetaScreenSettingsRepository.uiState.value.items.none { it.key == MetaScreenSectionKey.UNIVERSE && it.enabled }) return
        scope.launch {
            val key = key(meta)
            gate.withLock {
                val state = _states.value[key]
                if (state?.loading == true) return@launch
                if (!refresh && state != null && state.items != null && Clock.System.now().toEpochMilliseconds() - state.updatedAt < 30 * 60_000) return@launch
                publish(key, UniverseLoadState(items = state?.items, loading = true))
            }
            try {
                val result = client.load(meta, expandConnections = true) { partial ->
                    publish(key, UniverseLoadState(items = partial, loading = true))
                }
                publish(key, UniverseLoadState(items = result ?: _states.value[key]?.items, updatedAt = Clock.System.now().toEpochMilliseconds()))
            } catch (error: CancellationException) {
                publish(key, UniverseLoadState(items = _states.value[key]?.items))
                throw error
            }
        }
    }

    private fun publish(key: String, state: UniverseLoadState) {
        _states.update { old ->
            val next = old.toMutableMap()
            next[key] = state
            while (next.size > 64) next.remove(next.keys.first { it != key })
            next
        }
    }
}

/** Null means unavailable; an empty list means the provider returned no connections. */
internal class UniverseClient(
    private val request: suspend (String) -> RawHttpResponse,
    private val resolveImdb: suspend (Int, String) -> String?,
    private val nowMs: () -> Long,
    private val timeoutMs: Long = 6_000,
) {
    private data class Cached(val at: Long, val items: List<UniverseConnection>)
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Cached>()
    private val requestLocks = Array(8) { Mutex() }

    suspend fun load(
        meta: MetaDetails,
        expandConnections: Boolean = false,
        onPartial: (List<UniverseConnection>) -> Unit = {},
    ): List<UniverseConnection>? {
        if (!supportsUniverse(meta.type)) return emptyList()
        return try {
            withTimeoutOrNull(if (expandConnections) timeoutMs * 2 + 500 else timeoutMs) {
                val imdbId = universeImdbId(meta.imdbId) ?: universeImdbId(meta.id)
                    ?: universeTmdbId(meta.id)?.let { resolveImdb(it, meta.type) }?.let(::universeImdbId)
                    ?: return@withTimeoutOrNull emptyList()
                val direct = withTimeoutOrNull(timeoutMs) { fetchConnections(imdbId) } ?: return@withTimeoutOrNull null
                onPartial(direct)
                if (!expandConnections || direct.isEmpty()) return@withTimeoutOrNull direct
                // Expand sequel, spin-off and remake edges, including reboots tagged as remakes.
                // Never mix in recommendations or incidental references.
                val related = withTimeoutOrNull(timeoutMs) {
                    coroutineScope {
                        direct.take(6).map { parent ->
                            async { fetchConnections(parent.item.id).orEmpty() }
                        }.awaitAll().flatten()
                    }
                }.orEmpty()
                val known = direct.associateByTo(linkedMapOf()) { it.item.id }
                related.filter { it.item.id != imdbId }.forEach { entry ->
                    if (entry.item.id !in known) known[entry.item.id] = entry.copy(relations = listOf("Franchise connection"))
                }
                known.values.toList()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchConnections(imdbId: String): List<UniverseConnection>? {
        val lock = requestLocks[(imdbId.hashCode() and Int.MAX_VALUE) % requestLocks.size]
        return lock.withLock request@{
            mutex.withLock {
                cache[imdbId]?.takeIf { nowMs() - it.at in 0..CACHE_TTL_MS }?.items
            }?.let { return@request it }
            try {
                val body = buildJsonObject {
                    put("query", UNIVERSE_QUERY)
                    putJsonObject("variables") { put("id", imdbId) }
                }.toString()
                val response = request(body)
                if (response.status != 200) return@request null
                val items = parseUniverse(response.body, imdbId) ?: return@request null
                mutex.withLock {
                    cache.remove(imdbId)
                    cache[imdbId] = Cached(nowMs(), items)
                    while (cache.size > MAX_CACHE_TITLES) cache.remove(cache.keys.first())
                }
                items
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 30 * 60_000L
        const val MAX_CACHE_TITLES = 64
    }
}

internal fun supportsUniverse(type: String): Boolean =
    type.lowercase() in setOf("movie", "film", "series", "show", "tv", "tvshow")

internal fun universeImdbId(value: String?): String? = value?.trim()
    ?.split(':', '/', '?', '&')?.firstOrNull { Regex("tt[0-9]+").matches(it) }

internal fun universeTmdbId(value: String): Int? = value.trim().takeIf { it.startsWith("tmdb:") }
    ?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }

internal fun parseUniverse(payload: String, sourceImdbId: String): List<UniverseConnection>? {
    val root = Json.parseToJsonElement(payload) as? JsonObject ?: return null
    if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) return null
    val title = (root["data"] as? JsonObject)?.get("title") as? JsonObject ?: return null
    val edges = (title["connections"] as? JsonObject)?.get("edges") as? JsonArray ?: return null
    val titles = linkedMapOf<String, UniverseConnection>()
    for (edge in edges) {
        val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: continue
        val associated = node["associatedTitle"] as? JsonObject ?: continue
        val id = universeImdbId(associated.text("id")) ?: continue
        if (id == sourceImdbId) continue
        val name = (associated["titleText"] as? JsonObject)?.text("text") ?: continue
        val relation = (node["category"] as? JsonObject)?.text("text") ?: continue
        val old = titles[id]
        if (old != null) {
            titles[id] = old.copy(relations = (old.relations + relation).distinct())
            continue
        }
        val type = (associated["titleType"] as? JsonObject)?.text("id")
        val series = type in setOf("tvSeries", "tvMiniSeries")
        // Only full movies and series; no shorts, episodes, videos or brief specials.
        if (type !in setOf("movie", "tvMovie") && !series) continue
        val seconds = (associated["runtime"] as? JsonObject)?.text("seconds")?.toIntOrNull()
        if (seconds != null && seconds < if (series) 600 else 2400) continue
        val year = (associated["releaseYear"] as? JsonObject)?.text("year")
        titles[id] = UniverseConnection(
            item = MetaPreview(
                id = id,
                type = if (series) "series" else "movie",
                name = name,
                poster = (associated["primaryImage"] as? JsonObject)?.text("url"),
                releaseInfo = year,
                rawReleaseDate = (associated["releaseDate"] as? JsonObject)?.let { date ->
                    val y = date.text("year")?.toIntOrNull()
                    val m = date.text("month")?.toIntOrNull()
                    val d = date.text("day")?.toIntOrNull()
                    if (y != null && m != null && d != null) "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}" else null
                },
                voteCount = (associated["ratingsSummary"] as? JsonObject)?.text("voteCount")?.toIntOrNull(),
            ),
            relations = listOf(relation),
        )
    }
    return titles.values.toList()
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

// Independent Kotlin implementation; no Debrify application code is transplanted.
internal val UNIVERSE_QUERY = """
    query NuvioUniverse(${ '$' }id: ID!) {
      title(id: ${ '$' }id) {
        connections(first: 50, filter: { categories: [
          "followed_by", "follows", "spin_off", "spin_off_from", "remake_of", "remade_as"
        ] }) {
          edges { node {
            category { text }
            associatedTitle {
              id
              titleText { text }
              titleType { id }
              primaryImage { url }
              releaseYear { year }
              releaseDate { year month day }
              runtime { seconds }
              ratingsSummary { voteCount }
            }
          } }
        }
      }
    }
""".trimIndent()
