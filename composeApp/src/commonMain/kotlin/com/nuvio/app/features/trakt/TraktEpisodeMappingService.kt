package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

private const val BASE_URL = "https://api.trakt.tv"

private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
private val COLLAPSED_SPACES = Regex("\\s+")
private val titleMutex = Mutex()
private val normalizedTitleCache = mutableMapOf<String, String>()

/**
 * Handles episode number remapping between addon metadata (which may use multi-season
 * numbering for anime) and Trakt (which often uses absolute/single-season numbering).
 *
 * Example: An addon lists "Attack on Titan" as S1E1–S1E25, S2E1–S2E12, etc.
 * Trakt may list it as S1E1–S1E87 (absolute numbering).
 *
 * This service detects the mismatch and provides bidirectional mapping.
 */
object TraktEpisodeMappingService {
    private val log = Logger.withTag("TraktEpMapSvc")
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheMutex = Mutex()
    private val mappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val reverseMappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val addonEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    private val traktEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    // In-flight dedup: prevents multiple concurrent coroutines from fetching
    // In-flight dedup: prevents multiple concurrent coroutines from fetching
    // the same show's addon episodes simultaneously.
    private val addonEpisodesInFlight = mutableMapOf<String, CompletableDeferred<List<EpisodeMappingEntry>>>()
    private val traktEpisodesInFlight = mutableMapOf<String, CompletableDeferred<List<EpisodeMappingEntry>>>()

    var normalizeTitleCount = 0L
    var normalizeTitleTimeNs = 0L
    var resolveAddonCalls = 0L
    var resolveAddonTimeMs = 0L
    var resolveForwardCalls = 0L
    var resolveForwardTimeMs = 0L
    var getAddonCalls = 0L
    var getAddonMisses = 0L
    var getAddonTimeMs = 0L
    var getTraktCalls = 0L
    var getTraktMisses = 0L
    var getTraktTimeMs = 0L

    fun logAndResetStats() {
        log.i {
            """
            [TraktMappingStats]
            - resolveAddonEpisodeMapping: ${resolveAddonCalls} calls, ${resolveAddonTimeMs}ms total
            - resolveEpisodeMapping: ${resolveForwardCalls} calls, ${resolveForwardTimeMs}ms total
            - getAddonEpisodes: ${getAddonCalls} calls (${getAddonMisses} misses), ${getAddonTimeMs}ms total
            - getTraktEpisodes: ${getTraktCalls} calls (${getTraktMisses} misses), ${getTraktTimeMs}ms total
            - normalizeEpisodeTitle: ${normalizeTitleCount} calls, ${normalizeTitleTimeNs / 1_000_000.0}ms total
            """.trimIndent()
        }
        normalizeTitleCount = 0L
        normalizeTitleTimeNs = 0L
        resolveAddonCalls = 0L
        resolveAddonTimeMs = 0L
        resolveForwardCalls = 0L
        resolveForwardTimeMs = 0L
        getAddonCalls = 0L
        getAddonMisses = 0L
        getAddonTimeMs = 0L
        getTraktCalls = 0L
        getTraktMisses = 0L
        getTraktTimeMs = 0L
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Pre-fetches both addon and Trakt episode data for [contentIds] in parallel so that
     * subsequent mapping calls hit caches. Limits concurrency to [concurrency].
     */
    suspend fun prefetchEpisodes(
        contentIds: List<String>,
        concurrency: Int = 8
    ) {
        val unique = contentIds.distinct()
        if (unique.isEmpty()) return
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            unique.forEach { contentId ->
                launch {
                    semaphore.withPermit {
                        getAddonEpisodes(contentId, "series")
                    }
                }
                val showLookupId = resolveShowLookupId(contentId, null)
                if (showLookupId != null) {
                    launch {
                        semaphore.withPermit {
                            getTraktEpisodes(showLookupId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves the Trakt-side season/episode for a given addon season/episode.
     * Used when pushing watched status TO Trakt (forward mapping: addon → Trakt).
     *
     * Returns null if no remapping is needed (same structure) or if mapping fails.
     */
    suspend fun resolveEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String? = null,
    ): EpisodeMappingEntry? {
        val startTime = TimeSource.Monotonic.markNow()
        resolveForwardCalls++
        try {
            val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
            cacheMutex.withLock {
                mappingCache[key]?.let { return it }
            }

            val requestedSeason = season ?: return null
            val requestedEpisode = episode ?: return null
            val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return null
            val resolvedContentType = contentType?.takeIf { it.isNotBlank() } ?: return null

            val addonEpisodes = getAddonEpisodes(resolvedContentId, resolvedContentType)
            if (addonEpisodes.isEmpty()) return null

            val showLookupId = resolveShowLookupId(contentId = resolvedContentId, videoId = videoId) ?: return null
            val traktEpisodes = getTraktEpisodes(showLookupId)
            if (traktEpisodes.isEmpty()) return null

            if (hasSameSeasonStructure(addonEpisodes, traktEpisodes)) {
                return null
            }

            val mapped = remapEpisodeByTitleOrIndex(
                requestedSeason = requestedSeason,
                requestedEpisode = requestedEpisode,
                requestedVideoId = videoId,
                requestedTitle = episodeTitle,
                addonEpisodes = addonEpisodes,
                traktEpisodes = traktEpisodes,
            ) ?: return null

            cacheMutex.withLock {
                mappingCache[key] = mapped
            }
            return mapped
        } finally {
            resolveForwardTimeMs += startTime.elapsedNow().inWholeMilliseconds
        }
    }

    /**
     * Resolves the addon-side season/episode for a given Trakt season/episode.
     * Used when reading progress FROM Trakt to find the correct addon episode
     * (reverse mapping: Trakt → addon).
     *
     * Returns null if no remapping is needed or if mapping fails.
     */
    suspend fun resolveAddonEpisodeMapping(
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String? = null,
    ): EpisodeMappingEntry? {
        val startTime = TimeSource.Monotonic.markNow()
        resolveAddonCalls++
        try {
            val requestedSeason = season ?: return null
            val requestedEpisode = episode ?: return null
            val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return null
            val resolvedContentType = contentType?.takeIf { it.isNotBlank() } ?: return null

            val reverseKey = reverseCacheKey(
                contentId = resolvedContentId,
                contentType = resolvedContentType,
                season = requestedSeason,
                episode = requestedEpisode,
                title = episodeTitle,
            )
            cacheMutex.withLock {
                reverseMappingCache[reverseKey]?.let { return it }
            }

            val addonEpisodes = getAddonEpisodes(resolvedContentId, resolvedContentType)
            if (addonEpisodes.isEmpty()) return null

            val showLookupId = resolveShowLookupId(contentId = resolvedContentId, videoId = null) ?: return null
            val traktEpisodes = getTraktEpisodes(showLookupId)
            if (traktEpisodes.isEmpty()) return null

            val addonHasEpisode = addonEpisodes.any {
                it.season == requestedSeason && it.episode == requestedEpisode
            }
            if (addonHasEpisode && hasSameSeasonStructure(addonEpisodes, traktEpisodes)) {
                return null
            }

            val mapped = reverseRemapEpisodeByTitleOrIndex(
                requestedSeason = requestedSeason,
                requestedEpisode = requestedEpisode,
                requestedTitle = episodeTitle,
                addonEpisodes = addonEpisodes,
                traktEpisodes = traktEpisodes,
            ) ?: return null

            cacheMutex.withLock {
                reverseMappingCache[reverseKey] = mapped
            }
            return mapped
        } finally {
            resolveAddonTimeMs += startTime.elapsedNow().inWholeMilliseconds
        }
    }

    suspend fun getCachedEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
    ): EpisodeMappingEntry? {
        val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
        return cacheMutex.withLock { mappingCache[key] }
    }

    suspend fun prefetchEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
    ): EpisodeMappingEntry? {
        return resolveEpisodeMapping(contentId, contentType, videoId, season, episode)
    }

    fun clearCache() {
        mappingCache.clear()
        reverseMappingCache.clear()
        addonEpisodesCache.clear()
        traktEpisodesCache.clear()
    }

    // ── Season structure comparison ───────────────────────────────────────

    internal fun hasSameSeasonStructure(
        addonEpisodes: List<EpisodeMappingEntry>,
        traktEpisodes: List<EpisodeMappingEntry>,
    ): Boolean {
        val addonPerSeason = addonEpisodes.groupBy { it.season }.mapValues { it.value.size }
        val traktPerSeason = traktEpisodes.groupBy { it.season }.mapValues { it.value.size }
        return addonPerSeason == traktPerSeason
    }

    // ── Forward mapping: addon → Trakt ──────────────────────────────────

    internal suspend fun remapEpisodeByTitleOrIndex(
        requestedSeason: Int,
        requestedEpisode: Int,
        requestedVideoId: String?,
        requestedTitle: String?,
        addonEpisodes: List<EpisodeMappingEntry>,
        traktEpisodes: List<EpisodeMappingEntry>,
    ): EpisodeMappingEntry? {
        return remapEpisodeBetweenLists(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedVideoId = requestedVideoId,
            requestedTitle = requestedTitle,
            sourceEpisodes = addonEpisodes,
            targetEpisodes = traktEpisodes,
        )
    }

    // ── Reverse mapping: Trakt → addon ──────────────────────────────────

    internal suspend fun reverseRemapEpisodeByTitleOrIndex(
        requestedSeason: Int,
        requestedEpisode: Int,
        requestedTitle: String?,
        addonEpisodes: List<EpisodeMappingEntry>,
        traktEpisodes: List<EpisodeMappingEntry>,
    ): EpisodeMappingEntry? {
        return remapEpisodeBetweenLists(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedVideoId = null,
            requestedTitle = requestedTitle,
            sourceEpisodes = traktEpisodes,
            targetEpisodes = addonEpisodes,
        )
    }

    private suspend fun remapEpisodeBetweenLists(
        requestedSeason: Int,
        requestedEpisode: Int,
        requestedVideoId: String?,
        requestedTitle: String?,
        sourceEpisodes: List<EpisodeMappingEntry>,
        targetEpisodes: List<EpisodeMappingEntry>,
    ): EpisodeMappingEntry? {
        if (sourceEpisodes.isEmpty() || targetEpisodes.isEmpty()) return null

        val orderedSourceEpisodes = sourceEpisodes
            .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
        val orderedTargetEpisodes = targetEpisodes
            .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))

        val currentSourceEpisode = requestedVideoId
            ?.takeIf { it.isNotBlank() }
            ?.let { videoId -> orderedSourceEpisodes.firstOrNull { it.videoId == videoId } }
            ?: orderedSourceEpisodes.firstOrNull {
                it.season == requestedSeason && it.episode == requestedEpisode
            }
            ?: return null

        val normalizedTitle = normalizeEpisodeTitle(requestedTitle ?: currentSourceEpisode.title)
        if (isUsefulEpisodeTitle(normalizedTitle)) {
            val titleMatches = mutableListOf<EpisodeMappingEntry>()
            for (episode in orderedTargetEpisodes) {
                if (normalizeEpisodeTitle(episode.title) == normalizedTitle) {
                    titleMatches.add(episode)
                }
            }
            if (titleMatches.size == 1) {
                return titleMatches.first()
            }
        }

        val sourceIndex = orderedSourceEpisodes.indexOf(currentSourceEpisode)
        if (sourceIndex !in orderedTargetEpisodes.indices) return null

        return orderedTargetEpisodes[sourceIndex]
    }

    // ── Addon episodes fetching (with dedup) ───────────────────────────

    internal suspend fun getAddonEpisodes(
        contentId: String,
        contentType: String,
    ): List<EpisodeMappingEntry> {
        getAddonCalls++
        val cacheKey = addonEpisodesCacheKey(contentId, contentType)

        // Fast path: cache hit
        cacheMutex.withLock {
            addonEpisodesCache[cacheKey]?.let { return it }
        }

        getAddonMisses++
        val startTime = TimeSource.Monotonic.markNow()
        // Dedup: if another coroutine is already fetching this show, await its result.
        val existingDeferred = cacheMutex.withLock { addonEpisodesInFlight[cacheKey] }
        if (existingDeferred != null) {
            return try { existingDeferred.await() } catch (_: Exception) { emptyList() }
        }

        // Register ourselves as the in-flight fetcher.
        val deferred = CompletableDeferred<List<EpisodeMappingEntry>>()
        val weOwn = cacheMutex.withLock {
            // Double-check: cache or another flight may have appeared while we waited.
            addonEpisodesCache[cacheKey]?.let { return it }
            if (addonEpisodesInFlight.containsKey(cacheKey)) {
                false
            } else {
                addonEpisodesInFlight[cacheKey] = deferred
                true
            }
        }
        if (!weOwn) {
            val other = cacheMutex.withLock { addonEpisodesInFlight[cacheKey] }
            return try { other?.await() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        return try {
            val addonEpisodes = fetchAddonEpisodes(contentId, contentType)
            cacheMutex.withLock { addonEpisodesCache[cacheKey] = addonEpisodes }
            deferred.complete(addonEpisodes)
            addonEpisodes
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            deferred.completeExceptionally(e)
            emptyList()
        } finally {
            cacheMutex.withLock { addonEpisodesInFlight.remove(cacheKey) }
            getAddonTimeMs += startTime.elapsedNow().inWholeMilliseconds
        }
    }

    private suspend fun fetchAddonEpisodes(
        contentId: String,
        contentType: String,
    ): List<EpisodeMappingEntry> {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            }
        }.distinct()
        if (typeCandidates.isEmpty()) return emptyList()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val meta = withTimeoutOrNull(3_500L) {
                    MetaDetailsRepository.fetch(type = type, id = candidateId)
                } ?: continue
                val episodes = meta.videos.toEpisodeMappingEntries()
                if (episodes.isNotEmpty()) return episodes
            }
        }
        return emptyList()
    }

    // ── Trakt episodes fetching ─────────────────────────────────────────

    private suspend fun getTraktEpisodes(showLookupId: String): List<EpisodeMappingEntry> {
        getTraktCalls++
        cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
        }

        val existingDeferred = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
        if (existingDeferred != null) {
            return try { existingDeferred.await() } catch (_: Exception) { emptyList() }
        }

        val deferred = CompletableDeferred<List<EpisodeMappingEntry>>()
        val weOwn = cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
            if (traktEpisodesInFlight.containsKey(showLookupId)) {
                false
            } else {
                traktEpisodesInFlight[showLookupId] = deferred
                true
            }
        }
        if (!weOwn) {
            val other = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
            return try { other?.await() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        getTraktMisses++
        val startTime = TimeSource.Monotonic.markNow()
        val headers = TraktAuthRepository.authorizedHeaders() ?: run {
            cleanupTraktFlight(showLookupId)
            return emptyList()
        }

        // Trakt API: GET /shows/{id}/seasons?extended=episodes
        val url = "$BASE_URL/shows/$showLookupId/seasons?extended=episodes"
        val payload = runCatching {
            httpGetTextWithHeaders(url = url, headers = headers)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.w { "getTraktEpisodes: seasons request failed id=$showLookupId: ${e.message}" }
        }.getOrNull() ?: run {
            getTraktTimeMs += startTime.elapsedNow().inWholeMilliseconds
            cleanupTraktFlight(showLookupId)
            return emptyList()
        }

        val traktEpisodes = parseTraktSeasonsPayload(payload)
        cacheMutex.withLock {
            traktEpisodesCache[showLookupId] = traktEpisodes
        }
        deferred.complete(traktEpisodes)
        cleanupTraktFlight(showLookupId)
        getTraktTimeMs += startTime.elapsedNow().inWholeMilliseconds
        return traktEpisodes
    }

    private suspend fun cleanupTraktFlight(showLookupId: String) {
        cacheMutex.withLock { traktEpisodesInFlight.remove(showLookupId) }
    }

    private fun parseTraktSeasonsPayload(payload: String): List<EpisodeMappingEntry> {
        val seasons = runCatching {
            json.decodeFromString<List<TraktSeasonDto>>(payload)
        }.getOrNull() ?: return emptyList()

        return seasons
            .asSequence()
            .filter { (it.number ?: 0) > 0 } // Skip specials (season 0)
            .sortedBy { it.number }
            .flatMap { seasonDto ->
                seasonDto.episodes.orEmpty().asSequence().mapNotNull { episodeDto ->
                    val seasonNumber = episodeDto.season ?: seasonDto.number ?: return@mapNotNull null
                    val episodeNumber = episodeDto.number ?: return@mapNotNull null
                    EpisodeMappingEntry(
                        season = seasonNumber,
                        episode = episodeNumber,
                        title = episodeDto.title,
                    )
                }
            }
            .toList()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun resolveShowLookupId(contentId: String?, videoId: String?): String? {
        val contentIds = parseTraktContentIds(contentId)
        if (contentIds.hasAnyId()) {
            return when {
                !contentIds.imdb.isNullOrBlank() -> contentIds.imdb
                contentIds.trakt != null -> contentIds.trakt.toString()
                !contentIds.slug.isNullOrBlank() -> contentIds.slug
                else -> null
            }
        }

        val videoIds = parseTraktContentIds(videoId)
        return when {
            !videoIds.imdb.isNullOrBlank() -> videoIds.imdb
            videoIds.trakt != null -> videoIds.trakt.toString()
            !videoIds.slug.isNullOrBlank() -> videoIds.slug
            else -> null
        }
    }

    private fun TraktExternalIds.hasAnyId(): Boolean =
        !imdb.isNullOrBlank() || trakt != null || !slug.isNullOrBlank()

    private fun cacheKey(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
    ): String? {
        val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedContentType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedSeason = season ?: return null
        val resolvedEpisode = episode ?: return null
        val resolvedVideoId = videoId?.trim().orEmpty()
        return "$resolvedContentType|$resolvedContentId|$resolvedVideoId|$resolvedSeason|$resolvedEpisode"
    }

    private fun reverseCacheKey(
        contentId: String,
        contentType: String,
        season: Int,
        episode: Int,
        title: String?,
    ): String {
        val normalizedTitle = title?.trim()?.lowercase().orEmpty()
        return "reverse|${contentType.trim().lowercase()}|${contentId.trim()}|$season|$episode|$normalizedTitle"
    }

    private fun addonEpisodesCacheKey(contentId: String, contentType: String): String {
        return "${contentType.trim().lowercase()}|${contentId.trim()}"
    }

    private fun List<MetaVideo>.toEpisodeMappingEntries(): List<EpisodeMappingEntry> {
        return asSequence()
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                if (season <= 0) return@mapNotNull null
                EpisodeMappingEntry(
                    season = season,
                    episode = episode,
                    title = video.title.takeIf { it.isNotBlank() },
                    videoId = video.id.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.videoId ?: "${it.season}:${it.episode}" }
            .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
            .toList()
    }

    private suspend fun normalizeEpisodeTitle(title: String?): String {
        val startTime = TimeSource.Monotonic.markNow()
        normalizeTitleCount++
        try {
            if (title == null) return ""
            return titleMutex.withLock {
                normalizedTitleCache.getOrPut(title) {
                    title.lowercase()
                        .replace(NON_ALPHANUMERIC, " ")
                        .trim()
                        .replace(COLLAPSED_SPACES, " ")
                }
            }
        } finally {
            normalizeTitleTimeNs += startTime.elapsedNow().inWholeNanoseconds
        }
    }

    private fun isUsefulEpisodeTitle(normalizedTitle: String): Boolean {
        if (normalizedTitle.isBlank()) return false
        if (normalizedTitle.matches(Regex("episode \\d+"))) return false
        if (normalizedTitle.matches(Regex("ep \\d+"))) return false
        if (normalizedTitle.matches(Regex("e \\d+"))) return false
        return true
    }
}

// ── Data classes ────────────────────────────────────────────────────────

data class EpisodeMappingEntry(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val videoId: String? = null,
)

// ── Trakt API DTOs for seasons endpoint ─────────────────────────────────

@Serializable
private data class TraktSeasonDto(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: List<TraktSeasonEpisodeDto>? = null,
)

@Serializable
private data class TraktSeasonEpisodeDto(
    @SerialName("number") val number: Int? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("title") val title: String? = null,
)
