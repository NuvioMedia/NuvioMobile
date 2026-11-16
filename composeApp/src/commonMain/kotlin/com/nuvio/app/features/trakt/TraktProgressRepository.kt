package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktShowProgress
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.shouldReplaceProgressSnapshotEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.trakt.tv"
private const val TRAKT_COMPLETION_PERCENT_THRESHOLD = 90f
private const val HISTORY_PAGE_LIMIT = 1000
private const val HISTORY_MAX_PAGES = 5
private const val HISTORY_MAX_PAGES_ALL = 20
private const val MAX_RECENT_EPISODE_HISTORY_ENTRIES = 300
private const val METADATA_FETCH_TIMEOUT_MS = 3_500L
private const val METADATA_FETCH_CONCURRENCY = 5
private const val METADATA_HYDRATION_LIMIT = 110
private const val REFRESH_BASE_INTERVAL_MS = 60L * 1000L
private const val EPISODE_PROGRESS_CACHE_TTL_MS = 30L * 60L * 1000L
private const val EPISODE_PROGRESS_FETCH_THROTTLE_MS = 60L * 1000L
private const val OPTIMISTIC_PROGRESS_TTL_MS = 3L * 60L * 1000L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
private const val AMBIGUOUS_ID_MARKER = "__ambiguous__"

data class TraktProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val hasLoadedRemoteProgress: Boolean = false,
)

private data class TraktMetadataHydrationResult(
    val entries: List<WatchProgressEntry>,
    val meta: MetaDetails?,
)

object TraktProgressRepository {
    private data class OptimisticProgressEntry(
        val progress: WatchProgressEntry,
        val expiresAtMs: Long,
    )

    private val log = Logger.withTag("TraktProgress")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


    private val _uiState = MutableStateFlow(TraktProgressUiState())
    val uiState: StateFlow<TraktProgressUiState> = _uiState.asStateFlow()

    private val hiddenProgressShowIds = MutableStateFlow<Set<String>>(emptySet())
    private val optimisticProgress = MutableStateFlow<Map<String, OptimisticProgressEntry>>(emptyMap())

    private var hasLoaded = false
    private var refreshRequestId: Long = 0L
    private val refreshJobMutex = Mutex()
    private var inFlightRefresh: Deferred<Unit>? = null
    private var metadataHydrationJob: Job? = null
    private var remoteEntriesSnapshot: List<WatchProgressEntry> = emptyList()
    private var refreshIntervalMs = REFRESH_BASE_INTERVAL_MS
    private var lastKnownMoviesWatchedAt: String? = null
    private var lastKnownEpisodeActivityFingerprint: String? = null
    private var lastKnownActivityFingerprint: String? = null
    private val episodeProgressMutex = Mutex()
    private val episodeProgressFetchedAtMsByContentId = mutableMapOf<String, Long>()
    private val episodeProgressLastAttemptAtMsByContentId = mutableMapOf<String, Long>()
    private val inFlightEpisodeProgressContentIds = mutableSetOf<String>()
    private var watchedShowEpisodesById: Map<String, Set<Pair<Int, Int>>> = emptyMap()
    private var showIdToTraktPathId: Map<String, String> = emptyMap()
    private var showIdSiblingsMap: Map<String, Set<String>> = emptyMap()

    fun getShowIdSiblings(): Map<String, Set<String>> = showIdSiblingsMap

    init {
        scope.launch {
            while (true) {
                delay(refreshIntervalMs)
                TraktAuthRepository.ensureLoaded()
                TraktSettingsRepository.ensureLoaded()
                if (!shouldUseTraktProgress(
                        isAuthenticated = TraktAuthRepository.isAuthenticated.value,
                        source = TraktSettingsRepository.uiState.value.watchProgressSource,
                    )
                ) {
                    resetRefreshInterval()
                    continue
                }

                runCatching {
                    refreshIfActivityChanged()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Periodic Trakt activity refresh failed: ${error.message}" }
                }
                resetRefreshInterval()
            }
        }
    }

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
    }

    fun isShowHiddenFromProgress(contentId: String): Boolean {
        val ids = hiddenProgressShowIds.value
        if (ids.isEmpty()) return false
        val parsed = parseTraktContentIds(contentId)
        val keys = buildList {
            add(contentId)
            parsed.imdb?.takeIf { it.isNotBlank() }?.let { add(it) }
            parsed.tmdb?.let { add("tmdb:$it") }
            parsed.trakt?.let { add("trakt:$it") }
            showIdSiblingsMap[contentId]?.forEach { add(it) }
        }
        return keys.any { ids.contains(it) }
    }

    fun onProfileChanged() {
        invalidateInFlightRefreshes()
        hasLoaded = false
        remoteEntriesSnapshot = emptyList()
        optimisticProgress.value = emptyMap()
        hiddenProgressShowIds.value = emptySet()
        resetActivitySnapshot()
        resetShowProgressCaches()
        _uiState.value = TraktProgressUiState()
        ensureLoaded()
    }

    fun clearLocalState() {
        invalidateInFlightRefreshes()
        hasLoaded = false
        remoteEntriesSnapshot = emptyList()
        optimisticProgress.value = emptyMap()
        hiddenProgressShowIds.value = emptySet()
        resetActivitySnapshot()
        resetShowProgressCaches()
        _uiState.value = TraktProgressUiState()
    }

    fun refreshAsync() {
        scope.launch {
            refreshNow()
        }
    }

    suspend fun refreshNow() {
        ensureLoaded()
        val refresh = refreshJobMutex.withLock {
            inFlightRefresh?.takeIf { it.isActive } ?: scope.async {
                refreshNowInternal()
            }.also { inFlightRefresh = it }
        }

        try {
            refresh.await()
        } finally {
            refreshJobMutex.withLock {
                if (inFlightRefresh == refresh && refresh.isCompleted) {
                    inFlightRefresh = null
                }
            }
        }
    }

    suspend fun invalidateAndRefresh() {
        ensureLoaded()
        invalidateInFlightRefreshes()
        resetActivitySnapshot()
        episodeProgressMutex.withLock {
            episodeProgressFetchedAtMsByContentId.clear()
            episodeProgressLastAttemptAtMsByContentId.clear()
            inFlightEpisodeProgressContentIds.clear()
        }
        refreshNow()
    }

    suspend fun refreshEpisodeProgress(
        contentId: String,
        forceRefresh: Boolean = false,
    ) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        val cacheKey = canonicalLookupKey(normalizedContentId)
        val now = TraktPlatformClock.nowEpochMs()

        var shouldFetch = forceRefresh
        episodeProgressMutex.withLock {
            val lastFetchedAt = episodeProgressFetchedAtMsByContentId[cacheKey] ?: 0L
            val isFresh = lastFetchedAt > 0L && now - lastFetchedAt <= EPISODE_PROGRESS_CACHE_TTL_MS
            if (!forceRefresh && isFresh) return

            val lastAttemptAt = episodeProgressLastAttemptAtMsByContentId[cacheKey] ?: 0L
            if (!forceRefresh && now - lastAttemptAt < EPISODE_PROGRESS_FETCH_THROTTLE_MS) return

            if (!inFlightEpisodeProgressContentIds.add(cacheKey)) return
            episodeProgressLastAttemptAtMsByContentId[cacheKey] = now
            shouldFetch = true
        }

        if (!shouldFetch) return

        try {
            val entries = fetchEpisodeProgressEntries(headers = headers, contentId = normalizedContentId)
            remoteEntriesSnapshot = mergeNewestByVideoId(remoteEntriesSnapshot + entries)
            reconcileOptimisticProgress(remoteEntriesSnapshot)
            publishProgressState(entries = remoteEntriesSnapshot)
            episodeProgressMutex.withLock {
                episodeProgressFetchedAtMsByContentId[cacheKey] = TraktPlatformClock.nowEpochMs()
            }
            if (entries.isNotEmpty()) {
                launchHydration(requestId = refreshRequestId, entries = _uiState.value.entries)
            }
        } finally {
            episodeProgressMutex.withLock {
                inFlightEpisodeProgressContentIds.remove(cacheKey)
            }
        }
    }

    private suspend fun refreshIfActivityChanged() {
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        if (hasActivityChanged(headers) || !_uiState.value.hasLoadedRemoteProgress) {
            refreshNow()
        }
    }

    private suspend fun hasActivityChanged(headers: Map<String, String>): Boolean {
        val activities = runCatching {
            val endpoint = "$BASE_URL/sync/last_activities"
            val payload = httpGetTextWithHeaders(
                url = endpoint,
                headers = headers,
            )
            json.decodeFromString<TraktLastActivitiesResponse>(
                payload,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }.getOrNull() ?: return !_uiState.value.hasLoadedRemoteProgress

        val moviesWatchedAt = activities.movies?.watchedAt
        if (moviesWatchedAt != lastKnownMoviesWatchedAt) {
            lastKnownMoviesWatchedAt = moviesWatchedAt
        }

        val episodeFingerprint = listOfNotNull(
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt,
        ).joinToString("|")
        if (episodeFingerprint != lastKnownEpisodeActivityFingerprint) {
            lastKnownEpisodeActivityFingerprint = episodeFingerprint
            episodeProgressMutex.withLock {
                episodeProgressFetchedAtMsByContentId.clear()
            }
        }

        val fingerprint = listOfNotNull(
            activities.movies?.pausedAt,
            activities.movies?.watchedAt,
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt,
        ).joinToString("|")
        val changed = fingerprint != lastKnownActivityFingerprint
        lastKnownActivityFingerprint = fingerprint
        return changed
    }

    private fun resetRefreshInterval() {
        refreshIntervalMs = REFRESH_BASE_INTERVAL_MS
    }

    private fun resetActivitySnapshot() {
        lastKnownMoviesWatchedAt = null
        lastKnownEpisodeActivityFingerprint = null
        lastKnownActivityFingerprint = null
        refreshIntervalMs = REFRESH_BASE_INTERVAL_MS
    }

    private fun resetShowProgressCaches() {
        watchedShowEpisodesById = emptyMap()
        showIdToTraktPathId = emptyMap()
        showIdSiblingsMap = emptyMap()
        episodeProgressFetchedAtMsByContentId.clear()
        episodeProgressLastAttemptAtMsByContentId.clear()
        inFlightEpisodeProgressContentIds.clear()
    }

    private suspend fun refreshNowInternal() {
        ensureLoaded()
        val requestId = nextRefreshRequestId()
        val headers = TraktAuthRepository.authorizedHeaders()
        if (headers == null) {
            remoteEntriesSnapshot = emptyList()
            optimisticProgress.value = emptyMap()
            _uiState.value = TraktProgressUiState()
            return
        }

        publishProgressState(isLoading = true, errorMessage = null)

        val playbackEntries = runCatching {
            fetchPlaybackEntries(headers)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to refresh Trakt progress: ${error.message}" }
        }.getOrNull()

        if (playbackEntries == null) {
            if (!isLatestRefreshRequest(requestId)) return
            // The activity fingerprint already counts this change, so without clearing it the next
            // poll would see nothing new and the last snapshot would stay until the next activity.
            lastKnownActivityFingerprint = null
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = getString(Res.string.trakt_progress_load_failed),
            )
            return
        }
        if (!isLatestRefreshRequest(requestId)) return

        // Merge new playback entries into the existing state rather than replacing it wholesale.
        // This prevents the CW list from briefly losing "next up" seeds (like One Piece) for the
        // ~2.8s gap between fetchPlaybackEntries completing and the full sync finishing.
        val existingEntries = remoteEntriesSnapshot
        remoteEntriesSnapshot = if (existingEntries.isEmpty()) {
            playbackEntries
        } else {
            mergeNewestByVideoId(existingEntries + playbackEntries)
        }
        publishProgressState(
            entries = remoteEntriesSnapshot,
            isLoading = true,
            errorMessage = null,
            hasLoadedRemoteProgress = false,
        )

        val completedEntries = runCatching {
            coroutineScope {
                val history = async { fetchHistoryEntries(headers) }
                val watchedShowSeeds = async { fetchWatchedShowSeedEntries(headers) }
                val hiddenShows = async { fetchHiddenShowIds(headers) }
                
                val list = history.await() + watchedShowSeeds.await()
                hiddenShows.await()?.let { hiddenProgressShowIds.value = it }
                list
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt history snapshot: ${error.message}" }
        }.getOrNull()

        if (completedEntries == null) {
            if (!isLatestRefreshRequest(requestId)) return
            publishProgressState(
                isLoading = false,
                errorMessage = null,
                hasLoadedRemoteProgress = false,
            )
            return
        }

        if (!isLatestRefreshRequest(requestId)) return

        remoteEntriesSnapshot = mergeNewestByVideoId(playbackEntries + completedEntries)
        reconcileOptimisticProgress(remoteEntriesSnapshot)

        publishProgressState(
            entries = remoteEntriesSnapshot,
            isLoading = false,
            errorMessage = null,
            hasLoadedRemoteProgress = true,
        )

        val visibleEntries = _uiState.value.entries
        if (visibleEntries.isNotEmpty()) {
            launchHydration(requestId = requestId, entries = visibleEntries)
        }
    }

    private fun launchHydration(
        requestId: Long,
        entries: List<WatchProgressEntry>,
    ) {
        metadataHydrationJob?.cancel()
        metadataHydrationJob = scope.launch {
            runCatching {
                hydrateEntriesFromAddonMeta(
                    requestId = requestId,
                    entries = entries,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to hydrate Trakt metadata: ${error.message}" }
            }
        }
    }

    private fun publishProgressState(
        entries: List<WatchProgressEntry> = remoteEntriesSnapshot,
        isLoading: Boolean = _uiState.value.isLoading,
        errorMessage: String? = _uiState.value.errorMessage,
        hasLoadedRemoteProgress: Boolean = _uiState.value.hasLoadedRemoteProgress,
    ) {
        val publishedEntries = mergeWithActiveOptimistic(entries)
        _uiState.value = TraktProgressUiState(
            entries = publishedEntries,
            isLoading = isLoading,
            errorMessage = errorMessage,
            hasLoadedRemoteProgress = hasLoadedRemoteProgress,
        )
    }

    private fun mergeWithActiveOptimistic(entries: List<WatchProgressEntry>): List<WatchProgressEntry> {
        val optimisticEntries = activeOptimisticEntries()
        if (optimisticEntries.isEmpty()) {
            return entries.sortedByDescending { it.lastUpdatedEpochMs }
        }
        return mergeNewestByVideoId(entries + optimisticEntries)
    }

    private fun activeOptimisticEntries(
        now: Long = TraktPlatformClock.nowEpochMs(),
    ): List<WatchProgressEntry> {
        val current = optimisticProgress.value
        if (current.isEmpty()) return emptyList()
        val active = current.filterValues { entry -> entry.expiresAtMs > now }
        if (active.size != current.size) {
            optimisticProgress.value = active
        }
        return active.values.map { it.progress }
    }

    private fun putOptimisticProgress(entry: WatchProgressEntry) {
        val now = TraktPlatformClock.nowEpochMs()
        optimisticProgress.update { current ->
            val active = current
                .filterValues { optimistic -> optimistic.expiresAtMs > now }
                .toMutableMap()
            val existing = active[entry.videoId]?.progress
            if (existing == null || shouldReplaceProgressSnapshotEntry(existing = existing, candidate = entry)) {
                active[entry.videoId] = OptimisticProgressEntry(
                    progress = entry,
                    expiresAtMs = now + OPTIMISTIC_PROGRESS_TTL_MS,
                )
            }
            active
        }
    }

    private fun removeOptimisticProgress(
        shouldRemove: (WatchProgressEntry) -> Boolean,
    ) {
        optimisticProgress.update { current ->
            current.filterValues { optimistic -> !shouldRemove(optimistic.progress) }
        }
    }

    private fun reconcileOptimisticProgress(remoteEntries: List<WatchProgressEntry>) {
        if (remoteEntries.isEmpty() || optimisticProgress.value.isEmpty()) return
        val remoteByVideoId = remoteEntries.associateBy { it.videoId }
        val now = TraktPlatformClock.nowEpochMs()
        optimisticProgress.update { current ->
            current.filter { (videoId, optimistic) ->
                if (optimistic.expiresAtMs <= now) return@filter false
                val remoteEntry = remoteByVideoId[videoId] ?: return@filter true
                !remoteConfirmsOptimisticEntry(
                    remote = remoteEntry,
                    optimistic = optimistic.progress,
                )
            }
        }
    }

    private fun remoteConfirmsOptimisticEntry(
        remote: WatchProgressEntry,
        optimistic: WatchProgressEntry,
    ): Boolean {
        val normalizedRemote = remote.normalizedCompletion()
        val normalizedOptimistic = optimistic.normalizedCompletion()
        val remoteNewEnough = normalizedRemote.lastUpdatedEpochMs >= normalizedOptimistic.lastUpdatedEpochMs - 60_000L
        if (normalizedOptimistic.isEffectivelyCompleted) {
            return normalizedRemote.isEffectivelyCompleted && remoteNewEnough
        }

        val closeEnough = abs(normalizedRemote.progressFraction - normalizedOptimistic.progressFraction) <= 0.03f
        return closeEnough && remoteNewEnough
    }

    fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (!TraktAuthRepository.isAuthenticated.value) return
        val normalizedEntry = entry.normalizedCompletion()
        putOptimisticProgress(normalizedEntry)
        if (normalizedEntry.isCompleted && normalizedEntry.seasonNumber != null && normalizedEntry.episodeNumber != null) {
            optimisticallyAddWatchedEpisode(
                contentId = normalizedEntry.parentMetaId,
                season = normalizedEntry.seasonNumber,
                episode = normalizedEntry.episodeNumber,
            )
        }
        publishProgressState()
    }

    fun applyOptimisticRemoval(videoId: String) {
        if (!TraktAuthRepository.isAuthenticated.value) return
        if (videoId.isBlank()) return
        removeOptimisticProgress { it.videoId == videoId }
        remoteEntriesSnapshot = remoteEntriesSnapshot.filterNot { it.videoId == videoId }
        publishProgressState()
    }

    fun applyOptimisticRemoval(
        contentId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        if (!TraktAuthRepository.isAuthenticated.value) return
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        if (!hasCompleteTraktEpisodeContext(seasonNumber, episodeNumber)) return
        val shouldRemove: (WatchProgressEntry) -> Boolean = { entry ->
            entry.matchesTraktRemovalContext(
                contentId = normalizedContentId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }
        removeOptimisticProgress(shouldRemove)
        remoteEntriesSnapshot = remoteEntriesSnapshot.filterNot(shouldRemove)
        if (seasonNumber != null && episodeNumber != null) {
            optimisticallyRemoveWatchedEpisode(
                contentId = normalizedContentId,
                season = seasonNumber,
                episode = episodeNumber,
            )
        }
        publishProgressState()
    }

    suspend fun removeProgress(
        contentId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return
        if (!hasCompleteTraktEpisodeContext(seasonNumber, episodeNumber)) return
        val headers = TraktAuthRepository.authorizedHeaders() ?: return

        applyOptimisticRemoval(
            contentId = normalizedContentId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        val playbackMovies = runCatching {
            json.decodeFromString<List<TraktPlaybackItem>>(
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/playback/movies",
                    headers = headers,
                ),
            )
        }.getOrDefault(emptyList())
        val playbackEpisodes = runCatching {
            json.decodeFromString<List<TraktPlaybackItem>>(
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/playback/episodes",
                    headers = headers,
                ),
            )
        }.getOrDefault(emptyList())

        playbackMovies
            .filter { item -> normalizeTraktContentId(item.movie?.ids, fallback = item.movie?.title) == normalizedContentId }
            .forEach { item ->
                item.id?.let { playbackId ->
                    runCatching {
                        httpRequestRaw(
                            method = "DELETE",
                            url = "$BASE_URL/sync/playback/$playbackId",
                            headers = headers,
                            body = "",
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Failed to delete Trakt movie playback $playbackId: ${error.message}" }
                    }
                }
            }

        playbackEpisodes
            .filter { item ->
                val sameContent = normalizeTraktContentId(item.show?.ids, fallback = item.show?.title) == normalizedContentId
                val sameEpisode = if (seasonNumber != null && episodeNumber != null) {
                    item.episode?.season == seasonNumber && item.episode.number == episodeNumber
                } else {
                    true
                }
                sameContent && sameEpisode
            }
            .forEach { item ->
                item.id?.let { playbackId ->
                    runCatching {
                        httpRequestRaw(
                            method = "DELETE",
                            url = "$BASE_URL/sync/playback/$playbackId",
                            headers = headers,
                            body = "",
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Failed to delete Trakt episode playback $playbackId: ${error.message}" }
                    }
                }
            }

        refreshNow()
    }

    /** Null when a page fails, so the caller keeps the last hidden set instead of a partial one. */
    private suspend fun fetchHiddenShowIds(headers: Map<String, String>): Set<String>? {
        val items = readAllTraktPages(
            pageLimit = 1000,
            fetchPage = { page, limit ->
                httpGetTextWithHeaders(
                    url = "$BASE_URL/users/hidden/dropped?type=show&page=$page&limit=$limit",
                    headers = headers,
                )
            },
            decodePage = { payload -> json.decodeFromString<List<TraktHiddenItem>>(payload) },
        ) ?: return null

        val allIds = mutableSetOf<String>()
        for (item in items) {
            val ids = item.show?.ids ?: continue
            ids.imdb?.takeIf { it.isNotBlank() }?.let { allIds.add(it) }
            ids.tmdb?.let { allIds.add("tmdb:$it") }
            ids.trakt?.let { allIds.add("trakt:$it") }
        }
        return allIds
    }

    private suspend fun fetchPlaybackEntries(headers: Map<String, String>): List<WatchProgressEntry> = withContext(Dispatchers.Default) {
        val moviesEndpoint = "$BASE_URL/sync/playback/movies"
        val episodesEndpoint = "$BASE_URL/sync/playback/episodes"
        val payloads = coroutineScope {
            val moviesPayload = async {
                httpGetTextWithHeaders(
                    url = moviesEndpoint,
                    headers = headers,
                )
            }
            val episodesPayload = async {
                httpGetTextWithHeaders(
                    url = episodesEndpoint,
                    headers = headers,
                )
            }

            awaitAll(moviesPayload, episodesPayload)
        }

        val moviesPayload = payloads[0]
        val episodesPayload = payloads[1]

        val moviePlayback = json.decodeFromString<List<TraktPlaybackItem>>(moviesPayload)
        val episodePlayback = json.decodeFromString<List<TraktPlaybackItem>>(episodesPayload)

        val inProgressMovies = moviePlayback.mapIndexedNotNull { index, item ->
            mapPlaybackMovie(item = item, fallbackIndex = index)
        }

        val inProgressEpisodes = episodePlayback.mapIndexedNotNull { index, item ->
            mapPlaybackEpisode(item = item, fallbackIndex = index)
        }

        val merged = mergeNewestByVideoId(inProgressMovies + inProgressEpisodes)
        merged
    }

    private suspend fun fetchHistoryEntries(headers: Map<String, String>): List<WatchProgressEntry> = withContext(Dispatchers.Default) {
        val (episodeHistory, movieHistory) = coroutineScope {
            val episodeHistory = async {
                fetchRecentEpisodeHistoryEntries(headers) ?: error("Trakt episode history request failed")
            }
            val movieHistory = async { fetchRecentMovieHistoryEntries(headers) }
            episodeHistory.await() to movieHistory.await()
        }

        mergeNewestByVideoId(episodeHistory + movieHistory)
    }

    private suspend fun fetchRecentEpisodeHistoryEntries(
        headers: Map<String, String>,
    ): List<WatchProgressEntry>? {
        val cutoffMs = recentWatchCutoffMs()
        return collectRecentTraktEpisodeHistory(
            maxPages = if (isAllHistoryWindow()) HISTORY_MAX_PAGES_ALL else HISTORY_MAX_PAGES,
            cutoffMs = cutoffMs,
            fetchPage = { page ->
                val url = buildString {
                    append("$BASE_URL/sync/history/episodes?page=$page&limit=$HISTORY_PAGE_LIMIT")
                    cutoffMs?.let { append("&start_at=").append(epochMsToTraktIso(it)) }
                }
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            },
            decodePage = { body -> json.decodeFromString<List<TraktHistoryEpisodeItem>>(body) },
            mapItem = { item, fallbackIndex -> mapHistoryEpisode(item = item, fallbackIndex = fallbackIndex) },
        )
    }

    private suspend fun fetchRecentMovieHistoryEntries(
        headers: Map<String, String>,
    ): List<WatchProgressEntry> {
        val cutoffMs = recentWatchCutoffMs()
        val url = buildString {
            append("$BASE_URL/sync/history/movies?limit=$HISTORY_PAGE_LIMIT")
            cutoffMs?.let { append("&start_at=").append(epochMsToTraktIso(it)) }
        }
        val payload = httpGetTextWithHeaders(url = url, headers = headers)
        val mapped = json.decodeFromString<List<TraktHistoryMovieItem>>(payload)
            .mapIndexedNotNull { index, item -> mapHistoryMovie(item = item, fallbackIndex = index) }
            .filter { entry -> cutoffMs == null || entry.lastUpdatedEpochMs >= cutoffMs }
            .distinctBy { entry -> entry.videoId }
        return mapped
    }

    private suspend fun fetchWatchedShowSeedEntries(
        headers: Map<String, String>,
    ): List<WatchProgressEntry> = withContext(Dispatchers.Default) {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        val useFurthestEpisode = ContinueWatchingPreferencesRepository.uiState.value.upNextFromFurthestEpisode
        val watchedShows = TraktWatchedShowSnapshotRepository.get(headers)
        updateWatchedShowCaches(watchedShows)
        val mapped = fixAmbiguousWatchedShowSeeds(
            watchedShows.mapNotNull { item ->
                mapWatchedShowSeed(
                    item = item,
                    useFurthestEpisode = useFurthestEpisode,
                )
            },
        )
            .sortedByDescending { entry -> entry.lastUpdatedEpochMs }
        mapped
    }

    private fun updateWatchedShowCaches(items: List<TraktWatchedShowSnapshotItem>) {
        val siblingsMap = mutableMapOf<String, MutableSet<String>>()

        items.forEach { item ->
            val keys = watchedShowLookupKeys(item.show?.ids?.toExternalIds())
            if (keys.size <= 1) return@forEach
            for (key in keys) {
                val existing = siblingsMap[key]
                if (existing != null) {
                    existing.clear()
                    existing.add(AMBIGUOUS_ID_MARKER)
                } else {
                    siblingsMap[key] = (keys - key).toMutableSet()
                }
            }
        }

        val ambiguousIds = siblingsMap.entries
            .filter { (_, siblings) -> AMBIGUOUS_ID_MARKER in siblings }
            .mapTo(mutableSetOf()) { (key, _) -> key }

        val episodesByKey = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        val pathIdsByKey = mutableMapOf<String, String>()

        items.forEach { item ->
            val ids = item.show?.ids?.toExternalIds() ?: return@forEach
            val keys = watchedShowLookupKeys(ids).filter { it !in ambiguousIds }
            if (keys.isEmpty()) return@forEach

            val traktPathId = ids.slug?.takeIf { it.isNotBlank() } ?: ids.trakt?.toString()
            if (traktPathId != null) {
                keys.forEach { key -> pathIdsByKey[key] = traktPathId }
            }

            val episodes = mutableSetOf<Pair<Int, Int>>()
            item.seasons.orEmpty()
                .filter { (it.number ?: 0) > 0 }
                .forEach { season ->
                    val seasonNumber = season.number ?: return@forEach
                    season.episodes.orEmpty()
                        .filter { episode -> (episode.number ?: 0) > 0 && (episode.plays ?: 1) > 0 }
                        .forEach { episode ->
                            val episodeNumber = episode.number ?: return@forEach
                            episodes.add(seasonNumber to episodeNumber)
                        }
                }

            if (episodes.isNotEmpty()) {
                keys.forEach { key ->
                    episodesByKey.getOrPut(key) { mutableSetOf() }.addAll(episodes)
                }
            }
        }

        watchedShowEpisodesById = episodesByKey.mapValues { (_, episodes) -> episodes.toSet() }
        showIdToTraktPathId = pathIdsByKey
        showIdSiblingsMap = siblingsMap.mapValues { (_, siblings) -> siblings.toSet() }
    }

    private fun fixAmbiguousWatchedShowSeeds(
        seeds: List<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val ambiguousIds = showIdSiblingsMap.entries
            .filter { (_, siblings) -> AMBIGUOUS_ID_MARKER in siblings }
            .mapTo(mutableSetOf()) { (key, _) -> key }
        if (ambiguousIds.isEmpty()) return seeds

        return seeds.map { seed ->
            if (!seed.parentMetaId.startsWith("tt") || seed.parentMetaId !in ambiguousIds) {
                seed
            } else {
                val tmdbSibling = showIdSiblingsMap[seed.parentMetaId]
                    ?.firstOrNull { it.startsWith("tmdb:") }
                if (tmdbSibling == null) {
                    seed
                } else {
                    val remappedVideoId = if (seed.seasonNumber != null && seed.episodeNumber != null) {
                        buildPlaybackVideoId(
                            parentMetaId = tmdbSibling,
                            seasonNumber = seed.seasonNumber,
                            episodeNumber = seed.episodeNumber,
                            fallbackVideoId = null,
                        )
                    } else {
                        tmdbSibling
                    }
                    seed.copy(parentMetaId = tmdbSibling, videoId = remappedVideoId)
                }
            }
        }
    }

    private suspend fun fetchEpisodeProgressEntries(
        headers: Map<String, String>,
        contentId: String,
    ): List<WatchProgressEntry> = withContext(Dispatchers.Default) {
        val pathId = resolveToTraktAcceptedId(headers = headers, contentId = contentId)
        val response = httpRequestRaw(
            method = "GET",
            url = "$BASE_URL/shows/$pathId/progress/watched?hidden=false&specials=false&count_specials=false",
            headers = headers,
            body = "",
        )
        if (response.status !in 200..299) return@withContext emptyList()

        val progress = runCatching {
            json.decodeFromString<TraktShowProgressResponse>(response.body)
        }.getOrNull() ?: return@withContext emptyList()

        val completed = mutableListOf<WatchProgressEntry>()
        progress.seasons.orEmpty()
            .filter { season -> (season.number ?: 0) > 0 }
            .forEach { season ->
                val seasonNumber = season.number ?: return@forEach
                season.episodes.orEmpty()
                    .filter { episode -> episode.completed == true && (episode.number ?: 0) > 0 }
                    .forEach { episode ->
                        val episodeNumber = episode.number ?: return@forEach
                        completed += mapShowProgressEpisode(
                            contentId = contentId,
                            season = seasonNumber,
                            episode = episodeNumber,
                            lastWatchedAt = episode.lastWatchedAt,
                        )
                    }
            }

        val inProgress = fetchPlaybackEntries(headers)
            .filter { entry ->
                entry.parentMetaId == contentId &&
                    entry.seasonNumber != null &&
                    entry.episodeNumber != null
            }

        mergeNewestByVideoId(completed + inProgress)
    }

    private suspend fun resolveToTraktAcceptedId(
        headers: Map<String, String>,
        contentId: String,
    ): String {
        val parsed = parseTraktContentIds(contentId)
        parsed.imdb?.takeIf { it.isNotBlank() }?.let { return it }
        parsed.trakt?.let { return it.toString() }

        val tmdb = parsed.tmdb
        if (tmdb != null) {
            showIdToTraktPathId["tmdb:$tmdb"]?.let { return it }
            runCatching {
                TmdbService.tmdbToImdb(tmdbId = tmdb, mediaType = "series")
                    ?: TmdbService.tmdbToImdb(tmdbId = tmdb, mediaType = "movie")
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

            val response = runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = "$BASE_URL/search/tmdb/$tmdb?type=show",
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
            if (response != null && response.status in 200..299) {
                val result = runCatching {
                    json.decodeFromString<List<TraktSearchResult>>(response.body)
                }.getOrDefault(emptyList()).firstOrNull()
                result?.show?.ids?.let { ids ->
                    ids.imdb?.takeIf { it.isNotBlank() }?.let { return it }
                    ids.slug?.takeIf { it.isNotBlank() }?.let { return it }
                    ids.trakt?.let { return it.toString() }
                }
            }
            return "tmdb:$tmdb"
        }

        return contentId
    }

    private fun mapShowProgressEpisode(
        contentId: String,
        season: Int,
        episode: Int,
        lastWatchedAt: String?,
    ): WatchProgressEntry {
        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = contentId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = contentId,
                seasonNumber = season,
                episodeNumber = episode,
                fallbackVideoId = null,
            ),
            title = contentId,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = null,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = rankedTimestamp(lastWatchedAt, fallbackIndex = 0),
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktShowProgress,
        )
    }

    private fun watchedShowLookupKeys(ids: TraktExternalIds?): List<String> {
        if (ids == null) return emptyList()
        return buildList {
            ids.imdb?.takeIf { it.isNotBlank() }?.let { add(it) }
            ids.tmdb?.let { add("tmdb:$it") }
            ids.trakt?.let { add("trakt:$it") }
            ids.slug?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private fun canonicalLookupKey(contentId: String): String {
        val canonical = normalizeTraktContentId(
            ids = parseTraktContentIds(contentId),
            fallback = contentId.trim(),
        )
        return canonical.takeIf { it.isNotBlank() } ?: contentId.trim()
    }

    private fun optimisticallyAddWatchedEpisode(contentId: String, season: Int, episode: Int) {
        updateWatchedEpisodeCache(contentId = contentId, season = season, episode = episode, add = true)
    }

    private fun optimisticallyRemoveWatchedEpisode(contentId: String, season: Int, episode: Int) {
        updateWatchedEpisodeCache(contentId = contentId, season = season, episode = episode, add = false)
    }

    private fun updateWatchedEpisodeCache(
        contentId: String,
        season: Int,
        episode: Int,
        add: Boolean,
    ) {
        val key = contentId.trim()
        if (key.isBlank()) return
        val keysToUpdate = showIdSiblingsMap[key]
            ?.let { siblings -> (siblings + key).filter { it != AMBIGUOUS_ID_MARKER && !it.startsWith("trakt:") } }
            ?: listOf(key)
        val updated = watchedShowEpisodesById.toMutableMap()
        var changed = false
        keysToUpdate.forEach { lookupKey ->
            val current = updated[lookupKey].orEmpty()
            val pair = season to episode
            val next = if (add) current + pair else current - pair
            if (next != current) {
                updated[lookupKey] = next
                changed = true
            }
        }
        if (changed) {
            watchedShowEpisodesById = updated
        }
    }

    private fun mergeNewestByVideoId(entries: List<WatchProgressEntry>): List<WatchProgressEntry> {
        val mergedByVideoId = linkedMapOf<String, WatchProgressEntry>()
        entries.forEach { rawEntry ->
            val entry = rawEntry.normalizedCompletion()
            val existing = mergedByVideoId[entry.videoId]
            if (existing == null || shouldReplaceProgressSnapshotEntry(existing = existing, candidate = entry)) {
                mergedByVideoId[entry.videoId] = entry
            }
        }

        return mergedByVideoId.values
            .toList()
            .sortedByDescending { it.lastUpdatedEpochMs }
    }

    private fun mergeEntriesPreferRichMetadata(
        current: List<WatchProgressEntry>,
        hydrated: List<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val merged = current.associateBy { it.videoId }.toMutableMap()
        hydrated.forEach { candidate ->
            val existing = merged[candidate.videoId]
            if (existing == null || shouldReplaceEntry(existing = existing, candidate = candidate)) {
                merged[candidate.videoId] = candidate
            }
        }
        return merged.values.toList()
    }

    private fun shouldReplaceEntry(existing: WatchProgressEntry, candidate: WatchProgressEntry): Boolean {
        if (candidate.lastUpdatedEpochMs != existing.lastUpdatedEpochMs) {
            return candidate.lastUpdatedEpochMs > existing.lastUpdatedEpochMs
        }
        return metadataScore(candidate) > metadataScore(existing)
    }

    private fun metadataScore(entry: WatchProgressEntry): Int {
        var score = 0
        if (!entry.logo.isNullOrBlank()) score += 1
        if (!entry.poster.isNullOrBlank()) score += 1
        if (!entry.background.isNullOrBlank()) score += 1
        if (!entry.episodeTitle.isNullOrBlank()) score += 1
        if (!entry.episodeThumbnail.isNullOrBlank()) score += 1
        if (!entry.pauseDescription.isNullOrBlank()) score += 1
        return score
    }

    private fun nextRefreshRequestId(): Long {
        refreshRequestId += 1L
        return refreshRequestId
    }

    private fun invalidateInFlightRefreshes() {
        refreshRequestId += 1L
        inFlightRefresh?.cancel()
        inFlightRefresh = null
        metadataHydrationJob?.cancel()
        metadataHydrationJob = null
    }

    private fun isLatestRefreshRequest(requestId: Long): Boolean = refreshRequestId == requestId

    private suspend fun hydrateEntriesFromAddonMeta(
        requestId: Long,
        entries: List<WatchProgressEntry>,
    ) = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope

        val entriesByContent = entries
            .groupBy { entry -> entry.parentMetaType to entry.parentMetaId }
            .entries
            .take(METADATA_HYDRATION_LIMIT)

        val semaphore = Semaphore(METADATA_FETCH_CONCURRENCY)
        val results = Channel<TraktMetadataHydrationResult>(Channel.UNLIMITED)
        entriesByContent.forEach { (key, contentEntries) ->
            launch {
                val (metaType, metaId) = key
                val meta = semaphore.withPermit {
                    fetchAddonMetaForHydration(
                        metaType = metaType,
                        metaId = metaId,
                    )
                }
                results.send(
                    TraktMetadataHydrationResult(
                        entries = contentEntries,
                        meta = meta,
                    ),
                )
            }
        }

        repeat(entriesByContent.size) {
            val result = results.receive()
            if (!isLatestRefreshRequest(requestId)) {
                throw CancellationException("Superseded Trakt metadata hydration request $requestId")
            }
            val meta = result.meta ?: return@repeat
            val hydrated = hydrateEntriesWithAddonMeta(
                entries = result.entries,
                meta = meta,
            )
            if (hydrated.isEmpty()) return@repeat

            val hydratedByVideoId = hydrated.associateBy { entry -> entry.videoId }
            val remoteVideoIds = remoteEntriesSnapshot.mapTo(mutableSetOf()) { entry -> entry.videoId }
            val remoteHydrated = hydrated.filter { entry -> entry.videoId in remoteVideoIds }
            var changed = false
            if (remoteHydrated.isNotEmpty()) {
                val merged = mergeEntriesPreferRichMetadata(
                    current = remoteEntriesSnapshot,
                    hydrated = remoteHydrated,
                )
                if (merged != remoteEntriesSnapshot) {
                    remoteEntriesSnapshot = merged
                    changed = true
                }
            }

            optimisticProgress.update { current ->
                val updated = current.mapValues { (videoId, optimistic) ->
                    val hydratedEntry = hydratedByVideoId[videoId] ?: return@mapValues optimistic
                    val normalizedHydrated = hydratedEntry.normalizedCompletion()
                    if (shouldReplaceEntry(existing = optimistic.progress, candidate = normalizedHydrated)) {
                        optimistic.copy(progress = normalizedHydrated)
                    } else {
                        optimistic
                    }
                }
                if (updated != current) {
                    changed = true
                }
                updated
            }

            if (changed) {
                publishProgressState(
                    isLoading = false,
                    errorMessage = null,
                )
            }
        }
        results.close()
    }

    private suspend fun fetchAddonMetaForHydration(
        metaType: String,
        metaId: String,
    ): MetaDetails? {
        val normalizedType = when (metaType.lowercase()) {
            "movie", "film" -> "movie"
            else -> "series"
        }
        return try {
            withTimeoutOrNull(METADATA_FETCH_TIMEOUT_MS) {
                MetaDetailsRepository.fetch(type = normalizedType, id = metaId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun hydrateEntriesWithAddonMeta(
        entries: List<WatchProgressEntry>,
        meta: MetaDetails,
    ): List<WatchProgressEntry> =
        entries.map { entry ->
            var resolvedSeason = entry.seasonNumber
            var resolvedEpisode = entry.episodeNumber

            val episode = if (resolvedSeason != null && resolvedEpisode != null) {
                val directMatch = meta.videos.firstOrNull { video ->
                    video.season == resolvedSeason && video.episode == resolvedEpisode
                }
                if (directMatch != null) {
                    directMatch
                } else {
                    val remapped = runCatching {
                        TraktEpisodeMappingService.resolveAddonEpisodeMapping(
                            contentId = entry.parentMetaId,
                            contentType = "series",
                            season = resolvedSeason,
                            episode = resolvedEpisode,
                            episodeTitle = entry.episodeTitle,
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                    }.getOrNull()
                    if (remapped != null) {
                        resolvedSeason = remapped.season
                        resolvedEpisode = remapped.episode
                        meta.videos.firstOrNull { video ->
                            video.season == remapped.season && video.episode == remapped.episode
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            entry.copy(
                title = entry.title.takeIf { it.isNotBlank() } ?: meta.name,
                logo = entry.logo?.takeIf(String::isNotBlank) ?: meta.logo,
                poster = entry.poster?.takeIf(String::isNotBlank) ?: meta.poster,
                background = entry.background?.takeIf(String::isNotBlank) ?: meta.background,
                seasonNumber = if (entry.isCompleted) entry.seasonNumber else (resolvedSeason ?: entry.seasonNumber),
                episodeNumber = if (entry.isCompleted) entry.episodeNumber else (resolvedEpisode ?: entry.episodeNumber),
                episodeTitle = entry.episodeTitle?.takeIf(String::isNotBlank) ?: episode?.title,
                episodeThumbnail = entry.episodeThumbnail?.takeIf(String::isNotBlank) ?: episode?.thumbnail,
                pauseDescription = entry.pauseDescription?.takeIf(String::isNotBlank)
                    ?: episode?.overview?.takeIf(String::isNotBlank)
                    ?: meta.description?.takeIf(String::isNotBlank),
            )
        }

    private fun mapPlaybackMovie(item: TraktPlaybackItem, fallbackIndex: Int): WatchProgressEntry? {
        val movie = item.movie ?: return null
        val parentMetaId = normalizeTraktContentId(movie.ids, fallback = movie.title)
        if (parentMetaId.isBlank()) return null

        val progressPercent = normalizeTraktProgressPercent(item.progress) ?: return null
        if (progressPercent <= 0f) return null

        return WatchProgressEntry(
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = parentMetaId,
            title = movie.title ?: parentMetaId,
            lastPositionMs = 0L,
            durationMs = 0L,
            lastUpdatedEpochMs = rankedTimestamp(item.pausedAt, fallbackIndex),
            isCompleted = progressPercent >= TRAKT_COMPLETION_PERCENT_THRESHOLD,
            progressPercent = progressPercent,
            source = WatchProgressSourceTraktPlayback,
        ).normalizedCompletion()
    }

    private fun mapPlaybackEpisode(item: TraktPlaybackItem, fallbackIndex: Int): WatchProgressEntry? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val parentMetaId = normalizeTraktContentId(show.ids, fallback = show.title)
        if (parentMetaId.isBlank()) return null

        val progressPercent = normalizeTraktProgressPercent(item.progress) ?: return null
        if (progressPercent <= 0f) return null

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = season,
                episodeNumber = number,
                fallbackVideoId = episode.ids?.trakt?.let { "trakt:$it" },
            ),
            title = show.title ?: parentMetaId,
            seasonNumber = season,
            episodeNumber = number,
            episodeTitle = episode.title,
            lastPositionMs = 0L,
            durationMs = 0L,
            lastUpdatedEpochMs = rankedTimestamp(item.pausedAt, fallbackIndex),
            isCompleted = progressPercent >= TRAKT_COMPLETION_PERCENT_THRESHOLD,
            progressPercent = progressPercent,
            source = WatchProgressSourceTraktPlayback,
        ).normalizedCompletion()
    }

    private fun mapHistoryEpisode(item: TraktHistoryEpisodeItem, fallbackIndex: Int): WatchProgressEntry? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val parentMetaId = normalizeTraktContentId(show.ids, fallback = show.title)
        if (parentMetaId.isBlank()) return null

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = season,
                episodeNumber = number,
                fallbackVideoId = episode.ids?.trakt?.let { "trakt:$it" },
            ),
            title = show.title ?: parentMetaId,
            seasonNumber = season,
            episodeNumber = number,
            episodeTitle = episode.title,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = rankedTimestamp(item.watchedAt, fallbackIndex),
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktHistory,
        )
    }

    private fun mapHistoryMovie(item: TraktHistoryMovieItem, fallbackIndex: Int): WatchProgressEntry? {
        val movie = item.movie ?: return null
        val parentMetaId = normalizeTraktContentId(movie.ids, fallback = movie.title)
        if (parentMetaId.isBlank()) return null

        return WatchProgressEntry(
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = parentMetaId,
            title = movie.title ?: parentMetaId,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = rankedTimestamp(item.watchedAt, fallbackIndex),
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktHistory,
        )
    }

    private fun mapWatchedShowSeed(
        item: TraktWatchedShowSnapshotItem,
        useFurthestEpisode: Boolean,
    ): WatchProgressEntry? {
        val show = item.show ?: return null
        val parentMetaId = normalizeTraktContentId(show.ids?.toExternalIds(), fallback = show.title)
        if (parentMetaId.isBlank()) return null

        val completedEpisode = item.seasons.orEmpty()
            .asSequence()
            .filter { season -> (season.number ?: 0) > 0 }
            .flatMap { season ->
                val seasonNumber = season.number ?: return@flatMap emptySequence()
                season.episodes.orEmpty()
                    .asSequence()
                    .filter { episode -> (episode.number ?: 0) > 0 && (episode.plays ?: 1) > 0 }
                    .mapNotNull { episode ->
                        val episodeNumber = episode.number ?: return@mapNotNull null
                        TraktWatchedShowEpisodeSeed(
                            season = seasonNumber,
                            episode = episodeNumber,
                            watchedAt = rankedTimestamp(
                                isoDate = episode.lastWatchedAt ?: item.lastWatchedAt,
                                fallbackIndex = 0,
                            ),
                        )
                    }
            }
            .maxWithOrNull(
                if (useFurthestEpisode) {
                    compareBy<TraktWatchedShowEpisodeSeed>(
                        { it.season },
                        { it.episode },
                        { it.watchedAt },
                    )
                } else {
                    compareBy<TraktWatchedShowEpisodeSeed>(
                        { it.watchedAt },
                        { it.season },
                        { it.episode },
                    )
                },
            ) ?: return null

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = completedEpisode.season,
                episodeNumber = completedEpisode.episode,
                fallbackVideoId = null,
            ),
            title = show.title ?: parentMetaId,
            seasonNumber = completedEpisode.season,
            episodeNumber = completedEpisode.episode,
            episodeTitle = null,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = completedEpisode.watchedAt,
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktShowProgress,
        )
    }

    private fun normalizeTraktProgressPercent(rawProgress: Float?): Float? {
        val value = rawProgress ?: return null
        if (!value.isFinite()) return null
        val normalized = when {
            value <= 1f -> value * 100f
            else -> value
        }
        return normalized.coerceIn(0f, 100f)
    }

    private fun recentWatchCutoffMs(): Long? {
        val daysCap = normalizeTraktContinueWatchingDaysCap(
            TraktSettingsRepository.uiState.value.continueWatchingDaysCap,
        )
        if (daysCap == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) return null
        return TraktPlatformClock.nowEpochMs() - (daysCap.toLong() * MILLIS_PER_DAY)
    }

    private fun isAllHistoryWindow(): Boolean =
        normalizeTraktContinueWatchingDaysCap(
            TraktSettingsRepository.uiState.value.continueWatchingDaysCap,
        ) == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL

    private fun epochMsToTraktIso(epochMs: Long): String {
        val totalSeconds = epochMs.coerceAtLeast(0L) / 1000L
        val second = (totalSeconds % 60).toInt()
        val minute = ((totalSeconds / 60) % 60).toInt()
        val hour = ((totalSeconds / 3600) % 24).toInt()
        var days = (totalSeconds / 86400).toInt()

        var year = 1970
        while (true) {
            val daysInYear = if (isLeapYear(year)) 366 else 365
            if (days < daysInYear) break
            days -= daysInYear
            year += 1
        }

        val monthDays = if (isLeapYear(year)) {
            intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        } else {
            intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        }
        var monthIndex = 0
        while (monthIndex < monthDays.size && days >= monthDays[monthIndex]) {
            days -= monthDays[monthIndex]
            monthIndex += 1
        }
        val month = monthIndex + 1
        val day = days + 1

        return "${year.pad4()}-${month.pad2()}-${day.pad2()}T${hour.pad2()}:${minute.pad2()}:${second.pad2()}.000Z"
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

    private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"
    private fun Int.pad4(): String = toString().padStart(4, '0')

    private fun rankedTimestamp(isoDate: String?, fallbackIndex: Int): Long {
        isoDate
            ?.takeIf { it.isNotBlank() }
            ?.let(TraktPlatformClock::parseIsoDateTimeToEpochMs)
            ?.let { return it }
        return TraktPlatformClock.nowEpochMs() - (fallbackIndex * 1_000L)
    }
}

/**
 * Reads every page of a paged Trakt list. Returns null as soon as a page fails or can't be decoded,
 * so a partial list is never taken for the full one.
 */
internal suspend fun <T> readAllTraktPages(
    pageLimit: Int,
    fetchPage: suspend (page: Int, limit: Int) -> String,
    decodePage: (String) -> List<T>,
): List<T>? {
    val items = mutableListOf<T>()
    var page = 1
    while (true) {
        val pageItems = try {
            decodePage(fetchPage(page, pageLimit))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        items += pageItems
        if (pageItems.size < pageLimit) return items
        page++
    }
}

/**
 * Walks the recent episode history pages and keeps the newest entry per show. Returns null when a
 * page answers with an error or can't be decoded, so a partial history is never taken for the full one.
 */
internal suspend fun <T> collectRecentTraktEpisodeHistory(
    maxPages: Int,
    cutoffMs: Long?,
    fetchPage: suspend (page: Int) -> RawHttpResponse,
    decodePage: (String) -> List<T>,
    mapItem: (item: T, fallbackIndex: Int) -> WatchProgressEntry?,
): List<WatchProgressEntry>? {
    val resultsByShow = linkedMapOf<String, WatchProgressEntry>()
    var fallbackIndex = 0
    var page = 1

    while (page <= maxPages && resultsByShow.size < MAX_RECENT_EPISODE_HISTORY_ENTRIES) {
        val response = fetchPage(page)
        if (response.status !in 200..299) return null

        val items = runCatching { decodePage(response.body) }.getOrNull() ?: return null
        if (items.isEmpty()) break

        var shouldStop = false
        for (item in items) {
            val entry = mapItem(item, fallbackIndex++) ?: continue
            if (cutoffMs != null && entry.lastUpdatedEpochMs < cutoffMs) {
                shouldStop = true
                continue
            }
            if (!resultsByShow.containsKey(entry.parentMetaId)) {
                resultsByShow[entry.parentMetaId] = entry
            }
            if (resultsByShow.size >= MAX_RECENT_EPISODE_HISTORY_ENTRIES) {
                shouldStop = true
                break
            }
        }

        val pageCount = response.headerInt("x-pagination-page-count")
        if (items.size < HISTORY_PAGE_LIMIT || shouldStop || (pageCount != null && page >= pageCount)) break
        page += 1
    }

    return resultsByShow.values.toList()
}

private fun RawHttpResponse.headerInt(name: String): Int? =
    headers[name.lowercase()]
        ?.substringBefore(',')
        ?.trim()
        ?.toIntOrNull()

internal fun hasCompleteTraktEpisodeContext(
    seasonNumber: Int?,
    episodeNumber: Int?,
): Boolean = (seasonNumber == null) == (episodeNumber == null)

internal fun WatchProgressEntry.matchesTraktRemovalContext(
    contentId: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): Boolean {
    val normalizedContentId = contentId.trim()
    if (normalizedContentId.isBlank()) return false
    if (!hasCompleteTraktEpisodeContext(seasonNumber, episodeNumber)) return false
    if (parentMetaId != normalizedContentId) return false
    return seasonNumber == null || (
        this.seasonNumber == seasonNumber && this.episodeNumber == episodeNumber
    )
}

@Serializable
private data class TraktPlaybackItem(
    @SerialName("id") val id: Long? = null,
    @SerialName("progress") val progress: Float? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
)

@Serializable
private data class TraktLastActivitiesResponse(
    @SerialName("all") val all: String? = null,
    @SerialName("movies") val movies: TraktLastActivitiesMedia? = null,
    @SerialName("episodes") val episodes: TraktLastActivitiesMedia? = null,
)

@Serializable
private data class TraktLastActivitiesMedia(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
)

@Serializable
private data class TraktHistoryEpisodeItem(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
)

@Serializable
private data class TraktHistoryMovieItem(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
)

private data class TraktWatchedShowEpisodeSeed(
    val season: Int,
    val episode: Int,
    val watchedAt: Long,
)

@Serializable
private data class TraktMedia(
    @SerialName("title") val title: String? = null,
    @SerialName("ids") val ids: TraktExternalIds? = null,
)

@Serializable
private data class TraktEpisode(
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("ids") val ids: TraktExternalIds? = null,
)

@Serializable
private data class TraktShowProgressResponse(
    @SerialName("aired") val aired: Int? = null,
    @SerialName("completed") val completed: Int? = null,
    @SerialName("seasons") val seasons: List<TraktShowProgressSeason>? = null,
)

@Serializable
private data class TraktShowProgressSeason(
    @SerialName("number") val number: Int? = null,
    @SerialName("episodes") val episodes: List<TraktShowProgressEpisode>? = null,
)

@Serializable
private data class TraktShowProgressEpisode(
    @SerialName("number") val number: Int? = null,
    @SerialName("completed") val completed: Boolean? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

@Serializable
private data class TraktSearchResult(
    @SerialName("type") val type: String? = null,
    @SerialName("score") val score: Float? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
)

@Serializable
private data class TraktHiddenItem(
    @SerialName("hidden_at") val hiddenAt: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
)
