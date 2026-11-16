package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val name: String? = null,
    val year: Int? = null,
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

internal class ServerMatcher(
    private val cachedMeta: (String, String) -> MetaDetails? = MetaDetailsRepository::peek,
    private val fetchMeta: suspend (String, String) -> MetaDetails? = { type, id -> MetaDetailsRepository.fetch(type, id) },
    private val originalTitle: (Long, ServerMediaKind) -> String? = { id, kind ->
        TmdbMetadataService.cachedOriginalTitle(id.toString(), kind.contentType)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val log = Logger.withTag("ServerMatcher")
    private val lock = SynchronizedObject()
    private val indexes = mutableMapOf<String, IndexBuild>()
    private val convertedImdb = mutableMapOf<Long, String>()
    private val lookups = linkedMapOf<LookupKey, TitleLookup>()
    private val titleNames = linkedMapOf<String, TitleName>()

    fun request(type: String, videoId: String, season: Int?, episode: Int?): MatchRequest? {
        if (ServerItemRef.isServerId(videoId)) return null
        val kind = ServerMediaKind.fromContentType(type) ?: return null
        val parts = videoId.split(':')
        val parentSize = if (videoId.startsWith("tt")) 1 else 2
        val parentId = parts.take(parentSize).joinToString(":")
        val meta = cachedMeta(type, parentId)
        val metaImdb = meta?.imdbId?.takeIf { it.startsWith("tt") }
        val ids = parseTrackingExternalIds(parentId).mergeMissing(TrackingExternalIds(imdb = metaImdb))
        if (ids.catalogIds().isEmpty()) return null
        val nameKey = "${kind.contentType}:$parentId"
        val title = synchronized(lock) {
            (meta?.titleName() ?: titleNames.remove(nameKey))?.also {
                titleNames.putBounded(nameKey, it, TITLE_NAME_CACHE_SIZE)
            }
        }
        val request = MatchRequest(kind, parentId, ids, name = title?.name, year = title?.year)
        if (kind == ServerMediaKind.MOVIE) return request
        val position = parts.drop(parentSize).map { it.toIntOrNull() }.takeIf { it.size == 2 }
        val requestedSeason = season ?: position?.get(0) ?: return null
        val requestedEpisode = episode ?: position?.get(1) ?: return null
        return request.copy(season = requestedSeason, episode = requestedEpisode)
    }

    fun supports(connection: ServerConnection, kind: ServerMediaKind): Boolean =
        connection.selectedLibraries(kind).isNotEmpty() &&
            ServerRepository.provider(connection)?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true

    suspend fun match(connection: ServerConnection, request: MatchRequest, forceRefresh: Boolean): List<ServerItemRef> {
        val itemIds = titleItemIds(connection, request, forceRefresh)
        if (request.kind == ServerMediaKind.MOVIE) return itemIds.map { ServerItemRef(connection.id, it) }
        val season = request.season ?: return emptyList()
        val episode = request.episode ?: return emptyList()
        val catalogDate = cachedMeta(request.kind.contentType, request.parentId)
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

    private suspend fun titleItemIds(connection: ServerConnection, request: MatchRequest, forceRefresh: Boolean): List<String> {
        val ids = withConvertedIds(request)
        val libraries = connection.selectedLibraries(request.kind)
        val original = ids.tmdb?.let { originalTitle(it, request.kind) }?.takeIf { it.isNotBlank() }
        val query = ServerTitleQuery(ids, request.name, request.year, original)
        lookupItemIds(connection, request.kind, query, libraries, forceRefresh)?.let { return it }
        if (request.name == null) {
            fetchTitleName(request)?.let { title ->
                val named = query.copy(name = title.name, year = title.year)
                lookupItemIds(connection, request.kind, named, libraries, forceRefresh)?.let { return it }
            }
        }
        return libraries.flatMap { index(connection, it, forceRefresh).lookup(ids) }.distinct()
    }

    private suspend fun lookupItemIds(
        connection: ServerConnection,
        kind: ServerMediaKind,
        query: ServerTitleQuery,
        libraries: List<ServerLibrary>,
        forceRefresh: Boolean,
    ): List<String>? {
        val key = LookupKey(connection.id, kind, query)
        val revision = ServerRepository.uiState.value.revision
        val now = nowMs()
        val lookup = synchronized(lock) {
            lookups.remove(key)?.takeIf {
                !forceRefresh && !it.failed && it.revision == revision && now - it.startedAtMs < LOOKUP_TTL_MS
            }?.also { lookups[key] = it }
                ?: TitleLookup(revision, now).also { lookup ->
                    lookups.putBounded(key, lookup, LOOKUP_CACHE_SIZE)
                    scope.launch {
                        try {
                            lookup.result.complete(findTitle(connection, query, libraries))
                        } catch (error: Throwable) {
                            lookup.failed = true
                            lookup.result.completeExceptionally(error)
                            if (error is CancellationException) throw error
                        }
                    }
                }
        }
        var answered = false
        val itemIds = withTimeoutOrNull(LOOKUP_WAIT_MS) { lookup.result.await().also { answered = true } }
        if (!answered) throw ServerException(ServerFailure.INCOMPLETE)
        return itemIds
    }

    private suspend fun findTitle(
        connection: ServerConnection,
        query: ServerTitleQuery,
        libraries: List<ServerLibrary>,
    ): List<String>? = coroutineScope {
        val found = libraries.map { library ->
            async { ServerRepository.call(connection.id) { provider, session -> provider.lookupTitle(session, library, query) } }
        }.awaitAll()
        if (found.any { it == null }) return@coroutineScope null
        found.flatMap { verifiedTitleMatches(it.orEmpty(), query) }.distinct()
    }

    private suspend fun fetchTitleName(request: MatchRequest): TitleName? {
        val title = withTimeoutOrNull(META_WAIT_MS) {
            try {
                fetchMeta(request.kind.contentType, request.parentId)?.titleName()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        } ?: return null
        synchronized(lock) { titleNames.putBounded("${request.kind.contentType}:${request.parentId}", title, TITLE_NAME_CACHE_SIZE) }
        return title
    }

    fun clear() {
        synchronized(lock) {
            indexes.clear()
            lookups.clear()
            titleNames.clear()
            convertedImdb.clear()
        }
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun index(connection: ServerConnection, library: ServerLibrary, forceRefresh: Boolean): LibraryIndex {
        val build = build(connection, library, forceRefresh)
        return withTimeoutOrNull(INDEX_WAIT_MS) { build.result.await() } ?: run {
            log.w { "${connection.providerId} library ${library.name} was not indexed within ${INDEX_WAIT_MS}ms" }
            throw ServerException(ServerFailure.INCOMPLETE)
        }
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
                val entries = fetchEntries(connection, library)
                log.i { "Indexed ${entries.size} items in ${connection.providerId} library ${library.name} in ${nowMs() - now}ms" }
                build.result.complete(LibraryIndex(entries))
            } catch (error: Throwable) {
                build.failed = true
                build.result.completeExceptionally(error)
                if (error is CancellationException) throw error
                log.w(error) { "Indexing ${connection.providerId} library ${library.name} failed" }
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

    private suspend fun withConvertedIds(request: MatchRequest): TrackingExternalIds {
        val ids = withConvertedImdb(request)
        if (ids.tmdb != null) return ids
        val imdb = ids.imdb ?: return ids
        val tmdb = withTimeoutOrNull(CONVERSION_TIMEOUT_MS) {
            runCatching { TmdbService.ensureTmdbId(imdb, request.kind.contentType) }.getOrNull()
        }?.toLongOrNull() ?: return ids
        return ids.copy(tmdb = tmdb)
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

    private data class TitleName(val name: String, val year: Int?)

    private fun MetaDetails.titleName(): TitleName? = name.takeIf { it.isNotBlank() }?.let {
        TitleName(it, releaseInfo?.let { release -> YEAR.find(release)?.value?.toIntOrNull() })
    }

    private data class LookupKey(val connectionId: String, val kind: ServerMediaKind, val query: ServerTitleQuery)

    private class TitleLookup(val revision: Int, val startedAtMs: Long) {
        val result = CompletableDeferred<List<String>?>()
        @Volatile
        var failed = false
    }

    private fun <K, V> MutableMap<K, V>.putBounded(key: K, value: V, limit: Int) {
        remove(key)
        put(key, value)
        if (size > limit) remove(keys.first())
    }

    companion object {
        val shared = ServerMatcher()
        private val YEAR = Regex("""\b\d{4}\b""")
        private const val INDEX_PAGE_SIZE = 500
        private const val INDEX_TTL_MS = 10 * 60_000L
        private const val INDEX_WAIT_MS = 12_000L
        private const val LOOKUP_TTL_MS = 10 * 60_000L
        private const val LOOKUP_WAIT_MS = 6_000L
        private const val LOOKUP_CACHE_SIZE = 300
        private const val META_WAIT_MS = 3_000L
        private const val TITLE_NAME_CACHE_SIZE = 200
        private const val CONVERSION_TIMEOUT_MS = 5_000L
    }
}

internal fun datesCompatible(catalogDate: String?, serverDate: String?): Boolean {
    val catalogDay = catalogDate?.epochDay() ?: return true
    val serverDay = serverDate?.epochDay() ?: return true
    return abs(catalogDay - serverDay) <= MAX_AIR_DATE_DRIFT_DAYS
}

private fun String.epochDay(): Long? =
    runCatching { Instant.parse("${take(10)}T00:00:00Z").toEpochMilliseconds() / 86_400_000L }.getOrNull()

internal fun TrackingExternalIds.catalogIds(): List<String> = listOfNotNull(
    imdb,
    tmdb?.let { "tmdb:$it" },
    tvdb?.let { "tvdb:$it" },
    kitsu?.let { "kitsu:$it" },
    mal?.let { "mal:$it" },
    anilist?.let { "anilist:$it" },
    anidb?.let { "anidb:$it" },
    trakt?.let { "trakt:$it" },
    simkl?.let { "simkl:$it" },
)

private fun TrackingExternalIds.keys(): List<String> = catalogIds().map { it.lowercase() }

private fun TrackingExternalIds.conflictsWith(other: TrackingExternalIds): Boolean {
    val mine = catalogIds().associateBy { it.lowercase().substringBefore(':', "imdb") }
    return other.catalogIds().any { id ->
        val existing = mine[id.lowercase().substringBefore(':', "imdb")]
        existing != null && !existing.equals(id, ignoreCase = true)
    }
}

private const val MAX_AIR_DATE_DRIFT_DAYS = 2L
