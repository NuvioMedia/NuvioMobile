package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProfileStore
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklSyncRepository : TrackingProfileStore {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    private val log = Logger.withTag("SimklSync")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshGate = SimklRefreshGate()
    private val snapshotMutex = Mutex()
    private val refreshRequestSequence = atomic(0L)
    private val engine = SimklSyncEngine(
        remote = SimklApiSyncRemote(),
        nowEpochMs = SimklPlatformClock::nowEpochMs,
    )

    private val _state = MutableStateFlow(SimklSyncUiState())
    val state: StateFlow<SimklSyncUiState> = _state.asStateFlow()

    private var hasLoaded = false
    private var profileGeneration = 0L

    init {
        TrackingProviderRegistry.registerProfileStore(this)
    }

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val snapshot = SimklSyncStorage.loadPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklSyncSnapshot>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl sync snapshot: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklSyncSnapshot()
        _state.value = SimklSyncUiState(snapshot = snapshot, hasLoaded = true)
        SimklWatchDiagnostics.logSnapshot(stage = "cache-load", snapshot = snapshot)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        refreshAsync(intent, SimklRefreshOrigin.UNKNOWN)
    }

    internal fun refreshAsync(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ) {
        scope.launch { refresh(intent, origin) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent): Boolean =
        refresh(intent, SimklRefreshOrigin.MANUAL_SYNC)

    internal suspend fun refresh(
        intent: TrackingRefreshIntent,
        origin: SimklRefreshOrigin,
    ): Boolean {
        ensureLoaded()
        val requestId = refreshRequestSequence.incrementAndGet()
        val requestedGeneration = profileGeneration
        val requestedProfileId = ProfileRepository.activeProfileId
        val before = _state.value
        SimklWatchDiagnostics.logRefreshRequest(
            requestId = requestId,
            origin = origin,
            intent = intent,
            profileId = requestedProfileId,
            profileGeneration = requestedGeneration,
            authenticated = SimklAuthRepository.isAuthenticated.value,
            snapshot = before.snapshot,
            errorMessage = before.errorMessage,
        )
        val outcome = refreshGate.runIfNeeded(
            profileGeneration = requestedGeneration,
            shouldRun = {
                val current = _state.value
                val authenticated = SimklAuthRepository.isAuthenticated.value
                val nowEpochMs = SimklPlatformClock.nowEpochMs()
                val eligible = requestedGeneration == profileGeneration &&
                    authenticated &&
                    shouldRunSimklRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = nowEpochMs,
                        hasError = current.errorMessage != null,
                    )
                SimklWatchDiagnostics.logRefreshDecision(
                    requestId = requestId,
                    origin = origin,
                    intent = intent,
                    nowEpochMs = nowEpochMs,
                    lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                    authenticated = authenticated,
                    hasError = current.errorMessage != null,
                    eligible = eligible,
                )
                eligible
            },
        ) {
            refreshSnapshot(requestedGeneration)
        }
        SimklWatchDiagnostics.logRefreshCompletion(
            requestId = requestId,
            origin = origin,
            intent = intent,
            outcome = outcome,
            before = before,
            after = _state.value,
        )
        val completed = _state.value
        return requestedGeneration == profileGeneration &&
            requestedProfileId == ProfileRepository.activeProfileId &&
            SimklAuthRepository.isAuthenticated.value &&
            completed.hasLoaded &&
            completed.errorMessage == null
    }

    private suspend fun refreshSnapshot(generation: Long) = snapshotMutex.withLock {
        val profileId = ProfileRepository.activeProfileId
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)

        val result = try {
            engine.synchronize(previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl sync failed: ${error.message}" }
            if (generation == profileGeneration && profileId == ProfileRepository.activeProfileId) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync Simkl",
                )
            }
            return@withLock
        }

        if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
            return@withLock
        }
        SimklAuthRepository.synchronizeUserSettings(result.activities?.settings?.all)
        if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
            return@withLock
        }
        SimklSyncStorage.savePayload(json.encodeToString(result))
        _state.value = SimklSyncUiState(
            snapshot = result,
            hasLoaded = true,
        )
        SimklWatchDiagnostics.logSnapshot(stage = "network-commit", snapshot = result)
    }

    internal suspend fun commitPlaybackRemoval(sessionIds: Set<Long>) {
        if (sessionIds.isEmpty()) return
        ensureLoaded()
        snapshotMutex.withLock {
            val current = _state.value
            val updatedPlayback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (updatedPlayback.size == current.snapshot.playback.size) return@withLock
            val updatedSnapshot = current.snapshot.copy(playback = updatedPlayback)
            SimklSyncStorage.savePayload(json.encodeToString(updatedSnapshot))
            _state.value = current.copy(snapshot = updatedSnapshot)
            SimklWatchDiagnostics.logSnapshot(stage = "playback-removal", snapshot = updatedSnapshot)
        }
    }

    internal suspend fun commitScrobble(result: SimklScrobbleResult) {
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        snapshotMutex.withLock {
            if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
                return@withLock
            }
            val current = _state.value
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock
            SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (generation == profileGeneration && profileId == ProfileRepository.activeProfileId) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "scrobble-commit", snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitMutation(receipt: SimklMutationReceipt) {
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        snapshotMutex.withLock {
            if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
                return@withLock
            }
            val current = _state.value
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (snapshot == current.snapshot) return@withLock
            SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (generation == profileGeneration && profileId == ProfileRepository.activeProfileId) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "mutation-commit", snapshot = snapshot)
            }
        }
    }

    /**
     * Stores the runs the account's rewatch sessions make, so a rewatch the user just confirmed shows
     * up in Continue Watching at once instead of after the next sync. The sessions come from the
     * caller, which read them to answer whether the write landed.
     */
    internal suspend fun adoptRewatchSessions(sessions: List<SimklLibraryEntry>) {
        val runs = runCatching {
            deriveSimklRewatchRuns(
                entries = sessions,
                animeIdPreference = TrackingSettingsRepository.uiState.value.simklAnimeIdPreference,
                minimumRunEpisodes = TrackingSettingsRepository.uiState.value
                    .simklRewatchNextUpMode.minimumRunEpisodes,
            )
        }.getOrElse { error ->
            log.w { "Could not read the runs out of the rewatch sessions: ${error.message}" }
            return
        }
        ensureLoaded()
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        snapshotMutex.withLock {
            if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
                return@withLock
            }
            val current = _state.value
            if (
                current.snapshot.rewatchRuns == runs &&
                current.snapshot.rewatchSessions == sessions
            ) {
                return@withLock
            }
            val snapshot = current.snapshot.copy(rewatchRuns = runs, rewatchSessions = sessions)
            SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (generation == profileGeneration && profileId == ProfileRepository.activeProfileId) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "rewatch-sessions", snapshot = snapshot)
            }
        }
    }

    /**
     * Re-derives the runs after the user changed how much of a rewatch should be offered.
     *
     * The sessions of the last read are kept on the snapshot, so the row follows the setting at once
     * instead of at the next sync, and it works offline. With nothing read yet there is nothing to
     * re-derive, and the next sync picks the setting up on its own.
     */
    internal suspend fun refreshRewatchRuns() {
        ensureLoaded()
        val sessions = _state.value.snapshot.rewatchSessions
        if (sessions.isEmpty()) return
        val settings = TrackingSettingsRepository.uiState.value
        val runs = runCatching {
            deriveSimklRewatchRuns(
                entries = sessions,
                animeIdPreference = settings.simklAnimeIdPreference,
                minimumRunEpisodes = settings.simklRewatchNextUpMode.minimumRunEpisodes,
            )
        }.getOrElse { error ->
            log.w { "Could not re-derive the runs after a setting change: ${error.message}" }
            return
        }
        val generation = profileGeneration
        val profileId = ProfileRepository.activeProfileId
        snapshotMutex.withLock {
            if (generation != profileGeneration || profileId != ProfileRepository.activeProfileId) {
                return@withLock
            }
            val current = _state.value
            if (current.snapshot.rewatchRuns == runs) return@withLock
            val snapshot = current.snapshot.copy(rewatchRuns = runs)
            SimklSyncStorage.savePayload(json.encodeToString(snapshot))
            if (generation == profileGeneration && profileId == ProfileRepository.activeProfileId) {
                _state.value = current.copy(snapshot = snapshot)
                SimklWatchDiagnostics.logSnapshot(stage = "rewatch-next-up-mode", snapshot = snapshot)
            }
        }
    }

    override fun onProfileChanged() {
        profileGeneration += 1L
        hasLoaded = false
        _state.value = SimklSyncUiState()
        ensureLoaded()
    }

    override fun clearLocalState() {
        profileGeneration += 1L
        hasLoaded = false
        _state.value = SimklSyncUiState()
        SimklSyncStorage.savePayload("")
    }

    /**
     * Bumps the projection version to force downstream collectors (CW, library, watched badges)
     * to recompute their projections without re-fetching from the network.
     * Call this when a setting that affects projection output changes (e.g. anime ID preference).
     */
    fun invalidateProjections() {
        val current = _state.value
        _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklSyncStorage.removeProfile(profileId)
    }
}
