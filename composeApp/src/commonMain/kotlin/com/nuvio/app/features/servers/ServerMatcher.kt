package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

internal data class MatchRequest(
    val kind: ServerMediaKind,
    val parentId: String,
    val ids: TrackingExternalIds,
    val season: Int? = null,
    val episode: Int? = null,
)

internal class LibraryIndex(entries: List<ServerIndexEntry>) {
    private val idsByItem = entries.associate { it.itemId to it.ids }
    private val itemsByKey: Map<String, Set<String>> = entries
        .flatMap { entry -> entry.ids.keys().map { it to entry.itemId } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.toSet() }

    fun lookup(ids: TrackingExternalIds): List<String> =
        ids.keys()
            .flatMap { itemsByKey[it].orEmpty() }
            .distinct()
            .filterNot { itemId -> idsByItem[itemId]?.conflictsWith(ids) == true }
}

internal object ServerMatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val indexes = mutableMapOf<String, IndexBuild>()
    private val convertedImdb = mutableMapOf<Long, String>()

    fun request(type: String, videoId: String, season: Int?, episode: Int?): MatchRequest? {
        if (ServerItemRef.isServerId(videoId)) return null
        val kind = ServerMediaKind.fromContentType(type) ?: return null
        val parts = videoId.split(':')
        val parentId = if (videoId.startsWith("tt")) parts[0] else parts.take(2).joinToString(":")
        val metaImdb = MetaDetailsRepository.peek(type, parentId)?.imdbId?.takeIf { it.startsWith("tt") }
        val parsed = parseTrackingExternalIds(videoId).mergeMissing(TrackingExternalIds(imdb = metaImdb))
        val ids = TrackingExternalIds(imdb = parsed.imdb, tmdb = parsed.tmdb, tvdb = parsed.tvdb)
        if (!ids.hasAny) return null
        if (kind == ServerMediaKind.MOVIE) return MatchRequest(kind, parentId, ids)
        val requestedSeason = season ?: parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return null
        val requestedEpisode = episode ?: parts.lastOrNull()?.toIntOrNull() ?: return null
        return MatchRequest(kind, parentId, ids, requestedSeason, requestedEpisode)
    }

    fun supports(connection: ServerConnection, kind: ServerMediaKind): Boolean =
        connection.selectedLibraries(kind).isNotEmpty() &&
            ServerRepository.provider(connection)?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true

    suspend fun match(connection: ServerConnection, request: MatchRequest, forceRefresh: Boolean): List<ServerItemRef> {
        val ids = withConvertedImdb(request)
        val itemIds = connection.selectedLibraries(request.kind)
            .flatMap { library -> index(connection, library, forceRefresh).lookup(ids) }
            .distinct()
        if (request.kind == ServerMediaKind.MOVIE) return itemIds.map { ServerItemRef(connection.id, it) }
        val season = request.season ?: return emptyList()
        val episode = request.episode ?: return emptyList()
        val catalogDate = MetaDetailsRepository.peek(request.kind.contentType, request.parentId)
            ?.videos
            ?.firstOrNull { it.season == season && it.episode == episode }
            ?.released
        return itemIds.mapNotNull { seriesId ->
            val match = ServerRepository.call(connection.id) { provider, session ->
                provider.findEpisode(session, seriesId, season, episode)
            } ?: return@mapNotNull null
            match.takeIf { datesCompatible(catalogDate, it.premiereDate) }?.let { ServerItemRef(connection.id, it.itemId) }
        }
    }

    fun warm() {
        ServerRepository.enabledConnections().forEach { connection ->
            ServerMediaKind.entries.filter { supports(connection, it) }.forEach { kind ->
                connection.selectedLibraries(kind).forEach { library -> build(connection, library, forceRefresh = false) }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            indexes.clear()
            convertedImdb.clear()
        }
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun index(connection: ServerConnection, library: ServerLibrary, forceRefresh: Boolean): LibraryIndex {
        val build = build(connection, library, forceRefresh)
        return withTimeoutOrNull(INDEX_WAIT_MS) { build.result.await() }
            ?: throw ServerException(ServerFailure.INCOMPLETE)
    }

    private fun build(connection: ServerConnection, library: ServerLibrary, forceRefresh: Boolean): IndexBuild {
        val key = "${connection.id}:${library.id}"
        val revision = ServerRepository.uiState.value.revision
        val now = nowMs()
        val build = synchronized(lock) {
            indexes[key]?.takeIf { existing ->
                !forceRefresh && !existing.failed && existing.revision == revision && now - existing.startedAtMs < INDEX_TTL_MS
            }?.let { return it }
            IndexBuild(revision, now).also { indexes[key] = it }
        }
        scope.launch {
            try {
                build.result.complete(LibraryIndex(fetchEntries(connection, library)))
            } catch (error: Throwable) {
                build.failed = true
                build.result.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        return build
    }

    private suspend fun fetchEntries(connection: ServerConnection, library: ServerLibrary): List<ServerIndexEntry> {
        val entries = mutableListOf<ServerIndexEntry>()
        while (true) {
            val page = ServerRepository.call(connection.id) { provider, session ->
                provider.externalIdIndex(session, library, entries.size, INDEX_PAGE_SIZE)
            }
            entries += page.items
            val total = page.totalCount
            if (page.items.size < INDEX_PAGE_SIZE || (total != null && entries.size >= total)) return entries
        }
    }

    private suspend fun withConvertedImdb(request: MatchRequest): TrackingExternalIds {
        val ids = request.ids
        val tmdb = ids.tmdb
        if (ids.imdb != null || tmdb == null) return ids
        synchronized(lock) { convertedImdb[tmdb] }?.let { return ids.copy(imdb = it) }
        val imdb = withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            runCatching { TmdbService.tmdbToImdb(tmdb.toInt(), request.kind.contentType) }.getOrNull()
        }?.takeIf { it.startsWith("tt") } ?: return ids
        synchronized(lock) { convertedImdb[tmdb] = imdb }
        return ids.copy(imdb = imdb)
    }

    private class IndexBuild(val revision: Int, val startedAtMs: Long) {
        val result = CompletableDeferred<LibraryIndex>()
        @Volatile
        var failed = false
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private const val INDEX_PAGE_SIZE = 500
    private const val INDEX_TTL_MS = 10 * 60_000L
    private const val INDEX_WAIT_MS = 12_000L
    private const val CONVERSION_TIMEOUT_MS = 5_000L
}

internal fun datesCompatible(catalogDate: String?, serverDate: String?): Boolean {
    val catalogDay = catalogDate?.epochDay() ?: return true
    val serverDay = serverDate?.epochDay() ?: return true
    return abs(catalogDay - serverDay) <= MAX_AIR_DATE_DRIFT_DAYS
}

private fun String.epochDay(): Long? =
    runCatching { Instant.parse("${take(10)}T00:00:00Z").toEpochMilliseconds() / 86_400_000L }.getOrNull()

private fun TrackingExternalIds.keys(): List<String> = listOfNotNull(
    imdb?.let { "imdb:${it.lowercase()}" },
    tmdb?.let { "tmdb:$it" },
    tvdb?.let { "tvdb:$it" },
)

private fun TrackingExternalIds.conflictsWith(other: TrackingExternalIds): Boolean =
    (imdb != null && other.imdb != null && !imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && other.tmdb != null && tmdb != other.tmdb) ||
        (tvdb != null && other.tvdb != null && tvdb != other.tvdb)

private const val MAX_AIR_DATE_DRIFT_DAYS = 2L
