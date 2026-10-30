package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FOREGROUND_PULL_DELAY_MS = 2500L
private const val FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS = 2 * 60_000L
private const val FULL_PULL_MIN_INTERVAL_MS = 10_000L
private const val PERIODIC_NUVIO_SYNC_PULL_INTERVAL_MS = 15 * 60_000L

internal enum class ProfileSyncStep {
    Addons,
    Plugins,
    ProfileSettings,
    ProviderCredentials,
    Library,
    ActiveWatchSource,
    Collections,
    HomeCatalogSettings,
    MediaServers,
}

internal data class ProfileSyncOperations(
    val pullAddons: suspend (Int) -> Unit,
    val pullPlugins: suspend (Int) -> Unit,
    val pullProfileSettings: suspend (Int) -> Unit,
    val syncProviderCredentials: suspend (Int) -> Unit,
    val pullLibrary: suspend (Int) -> Unit,
    val refreshActiveWatchSource: suspend (Int) -> Unit,
    val pullCollections: suspend (Int) -> Unit,
    val pullHomeCatalogSettings: suspend (Int) -> Unit,
    val syncMediaServers: suspend (Int) -> Unit = {},
)

internal data class ProfileActivitySyncOperations(
    val pullLibrary: suspend (Int) -> Unit,
    val pullWatchActivity: suspend (Int) -> Unit,
)

internal data class ProfileSyncResult(
    val failedSteps: Set<ProfileSyncStep>,
) {
    val succeeded: Boolean
        get() = failedSteps.isEmpty()
}

internal data class ProfilePullFreshness(
    val profileId: Int? = null,
    val completedAtEpochMs: Long = 0L,
) {
    fun isRecent(profileId: Int, nowEpochMs: Long, minIntervalMs: Long): Boolean =
        this.profileId == profileId && nowEpochMs - completedAtEpochMs < minIntervalMs

    fun recordIfSuccessful(
        profileId: Int,
        completedAtEpochMs: Long,
        result: ProfileSyncResult,
    ): ProfilePullFreshness =
        if (result.succeeded) {
            ProfilePullFreshness(
                profileId = profileId,
                completedAtEpochMs = completedAtEpochMs,
            )
        } else {
            this
        }
}

internal suspend fun runOrderedProfileSync(
    profileId: Int,
    pluginsEnabled: Boolean,
    operations: ProfileSyncOperations,
    onFailure: (ProfileSyncStep, Throwable) -> Unit = { _, _ -> },
): ProfileSyncResult {
    val failureLock = SynchronizedObject()
    val failedSteps = mutableSetOf<ProfileSyncStep>()

    suspend fun runStep(
        step: ProfileSyncStep,
        operation: suspend (Int) -> Unit,
    ) {
        try {
            operation(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            synchronized(failureLock) {
                failedSteps += step
            }
            onFailure(step, error)
        }
    }

    runStep(ProfileSyncStep.ProfileSettings, operations.pullProfileSettings)
    runStep(ProfileSyncStep.ProviderCredentials, operations.syncProviderCredentials)
    runStep(ProfileSyncStep.Addons, operations.pullAddons)
    if (pluginsEnabled) {
        runStep(ProfileSyncStep.Plugins, operations.pullPlugins)
    }

    coroutineScope {
        launch {
            runStep(ProfileSyncStep.Library, operations.pullLibrary)
        }
        launch {
            runStep(ProfileSyncStep.ActiveWatchSource, operations.refreshActiveWatchSource)
        }
        launch {
            runStep(ProfileSyncStep.Collections, operations.pullCollections)
        }
        launch {
            runStep(ProfileSyncStep.HomeCatalogSettings, operations.pullHomeCatalogSettings)
        }
        launch {
            runStep(ProfileSyncStep.MediaServers, operations.syncMediaServers)
        }
    }
    return ProfileSyncResult(
        failedSteps = synchronized(failureLock) { failedSteps.toSet() },
    )
}

internal suspend fun runActivityProfileSync(
    profileId: Int,
    pullLibrary: Boolean = true,
    pullWatchActivity: Boolean = true,
    operations: ProfileActivitySyncOperations,
    onFailure: (ProfileSyncStep, Throwable) -> Unit = { _, _ -> },
): ProfileSyncResult {
    val failureLock = SynchronizedObject()
    val failedSteps = mutableSetOf<ProfileSyncStep>()

    suspend fun runStep(
        step: ProfileSyncStep,
        operation: suspend (Int) -> Unit,
    ) {
        try {
            operation(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            synchronized(failureLock) {
                failedSteps += step
            }
            onFailure(step, error)
        }
    }

    coroutineScope {
        if (pullLibrary) {
            launch {
                runStep(ProfileSyncStep.Library, operations.pullLibrary)
            }
        }
        if (pullWatchActivity) {
            launch {
                runStep(ProfileSyncStep.ActiveWatchSource, operations.pullWatchActivity)
            }
        }
    }
    return ProfileSyncResult(
        failedSteps = synchronized(failureLock) { failedSteps.toSet() },
    )
}

internal enum class ProfileSyncRequestResult {
    Started,
    Coalesced,
    Replaced,
}

internal enum class ProfileSyncRequestKind {
    Activity,
    Full,
}

internal class ProfileSyncRequestGate {
    private val lock = SynchronizedObject()
    private var activeProfileId: Int? = null
    private var activeKind: ProfileSyncRequestKind? = null
    private var activeJob: Job? = null

    fun launch(
        scope: CoroutineScope,
        profileId: Int,
        kind: ProfileSyncRequestKind = ProfileSyncRequestKind.Full,
        block: suspend () -> Unit,
    ): ProfileSyncRequestResult {
        lateinit var newJob: Job
        var previousJob: Job? = null
        val result = synchronized(lock) {
            val active = activeJob?.takeUnless(Job::isCompleted)
            if (active != null && activeProfileId == profileId) {
                val activeRequestKind = activeKind
                if (activeRequestKind == ProfileSyncRequestKind.Full || kind == ProfileSyncRequestKind.Activity) {
                    return ProfileSyncRequestResult.Coalesced
                }
            }

            previousJob = active
            val requestResult = if (active == null) {
                ProfileSyncRequestResult.Started
            } else {
                ProfileSyncRequestResult.Replaced
            }

            newJob = scope.launch(start = CoroutineStart.LAZY) {
                block()
            }
            activeProfileId = profileId
            activeKind = kind
            activeJob = newJob
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (activeJob === newJob) {
                        activeJob = null
                        activeProfileId = null
                        activeKind = null
                    }
                }
            }
            requestResult
        }

        previousJob?.cancel()
        newJob.start()
        return result
    }

    fun cancel() {
        val job = synchronized(lock) {
            activeJob.also {
                activeJob = null
                activeProfileId = null
                activeKind = null
            }
        }
        job?.cancel()
    }
}

object SyncManager {
    private val log = Logger.withTag("SyncManager")
    private val syncRequestGate = ProfileSyncRequestGate()
    private val accountScopeLock = SynchronizedObject()
    private var accountScopeJob: Job = SupervisorJob()
    private var accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
    private val pullStateLock = SynchronizedObject()
    private var foregroundPullJob: Job? = null
    private var foregroundPullProfileId: Int? = null
    private var periodicNuvioSyncPullJob: Job? = null
    private var periodicNuvioSyncProfileId: Int? = null
    private var activityPullFreshness = ProfilePullFreshness()
    private var fullPullFreshness = ProfilePullFreshness()

    private val profileSyncOperations = ProfileSyncOperations(
        pullAddons = { profileId -> AddonRepository.pullFromServer(profileId) },
        pullPlugins = { profileId -> PluginRepository.pullFromServer(profileId) },
        pullProfileSettings = { profileId -> ProfileSettingsSync.pull(profileId) },
        syncProviderCredentials = { profileId -> ProviderCredentialSync.syncFromRemote(profileId) },
        pullLibrary = { profileId -> LibraryRepository.pullFromServer(profileId) },
        refreshActiveWatchSource = { profileId ->
            val result = WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = true)
            check(result.succeeded) {
                "Active watch source refresh was incomplete: " +
                    "progress=${result.progressRefreshed} watched=${result.watchedHistoryRefreshed}"
            }
        },
        pullCollections = { profileId -> CollectionSyncService.pullFromServer(profileId) },
        pullHomeCatalogSettings = { profileId -> HomeCatalogSettingsSyncService.pullFromServer(profileId) },
        syncMediaServers = { profileId -> MediaServerSync.syncFromRemote(profileId) },
    )
    private val profileActivitySyncOperations = ProfileActivitySyncOperations(
        pullLibrary = { profileId -> LibraryRepository.pullFromServer(profileId) },
        pullWatchActivity = { profileId ->
            val result = WatchProgressSourceCoordinator.refreshActiveSource(profileId = profileId, force = false)
            check(result.succeeded) {
                "Active watch source refresh was incomplete: " +
                    "progress=${result.progressRefreshed} watched=${result.watchedHistoryRefreshed}"
            }
        },
    )

    fun pullAllForProfile(profileId: Int) {
        startFullProfilePull(profileId = profileId, reason = "requested")
    }

    internal fun cancelAccountSync() {
        syncRequestGate.cancel()
        val previousAccountJob = synchronized(accountScopeLock) {
            accountScopeJob.also {
                accountScopeJob = SupervisorJob()
                accountScope = CoroutineScope(accountScopeJob + Dispatchers.Default)
            }
        }
        previousAccountJob.cancel()
        val foregroundJob = synchronized(pullStateLock) {
            foregroundPullJob.also {
                foregroundPullJob = null
                foregroundPullProfileId = null
                activityPullFreshness = ProfilePullFreshness()
                fullPullFreshness = ProfilePullFreshness()
            }
        }
        foregroundJob?.cancel()
        stopPeriodicNuvioSyncPull()
    }

    private fun accountScopeSnapshot(): CoroutineScope = synchronized(accountScopeLock) {
        accountScope
    }

    fun requestForegroundPull(profileId: Int, force: Boolean = false) {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return

        if (!force && hasRecentActivityPull(profileId)) {
            return
        }
        lateinit var requestJob: Job
        var previousJob: Job? = null
        synchronized(pullStateLock) {
            if (
                !force &&
                foregroundPullJob?.isCompleted == false &&
                foregroundPullProfileId == profileId
            ) {
                return
            }

            previousJob = foregroundPullJob
            requestJob = accountScopeSnapshot().launch(start = CoroutineStart.LAZY) {
                try {
                    if (!force) {
                        delay(FOREGROUND_PULL_DELAY_MS)
                    }
                    if (!force && hasRecentActivityPull(profileId)) return@launch
                    if (ProfileRepository.activeProfileId != profileId) return@launch
                    startActivityProfilePull(profileId = profileId, reason = "foreground")
                } finally {
                    synchronized(pullStateLock) {
                        if (foregroundPullJob === requestJob) {
                            foregroundPullJob = null
                            foregroundPullProfileId = null
                        }
                    }
                }
            }
            foregroundPullProfileId = profileId
            foregroundPullJob = requestJob
        }
        previousJob?.cancel()
        requestJob.start()
    }

    private fun hasRecentActivityPull(profileId: Int): Boolean =
        synchronized(pullStateLock) {
            activityPullFreshness.isRecent(
                profileId = profileId,
                nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                minIntervalMs = FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS,
            )
        }

    private fun startFullProfilePull(
        profileId: Int,
        reason: String,
    ) {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return
        if (ProfileRepository.activeProfileId != profileId) return
        if (hasRecentFullPull(profileId)) return

        val result = syncRequestGate.launch(
            scope = accountScopeSnapshot(),
            profileId = profileId,
            kind = ProfileSyncRequestKind.Full,
        ) {
            val currentAuthState = AuthRepository.state.value
            if (currentAuthState !is AuthState.Authenticated || currentAuthState.isAnonymous) return@launch
            if (ProfileRepository.activeProfileId != profileId) return@launch

            log.i { "Full profile sync started profile=$profileId reason=$reason" }
            WatchProgressSourceCoordinator.pauseAutomaticTransitions()
            val syncResult = try {
                runOrderedProfileSync(
                    profileId = profileId,
                    pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
                    operations = profileSyncOperations,
                    onFailure = { step, error ->
                        log.e(error) { "Full profile sync step failed profile=$profileId step=$step" }
                    },
                )
            } finally {
                WatchProgressSourceCoordinator.resumeAutomaticTransitions()
            }
            synchronized(pullStateLock) {
                activityPullFreshness = activityPullFreshness.recordIfSuccessful(
                    profileId = profileId,
                    completedAtEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                    result = syncResult,
                )
                fullPullFreshness = fullPullFreshness.recordIfSuccessful(
                    profileId = profileId,
                    completedAtEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                    result = syncResult,
                )
            }
            if (!syncResult.succeeded) {
                log.w {
                    "Full profile sync incomplete profile=$profileId reason=$reason " +
                        "failedSteps=${syncResult.failedSteps}"
                }
            }
            log.i { "Full profile sync completed profile=$profileId reason=$reason" }
        }

        when (result) {
            ProfileSyncRequestResult.Started -> Unit
            ProfileSyncRequestResult.Coalesced -> {
                log.d { "Full profile sync coalesced profile=$profileId reason=$reason" }
            }
            ProfileSyncRequestResult.Replaced -> {
                log.d { "Full profile sync replaced stale profile request with profile=$profileId reason=$reason" }
            }
        }
    }

    private fun hasRecentFullPull(profileId: Int): Boolean =
        synchronized(pullStateLock) {
            fullPullFreshness.isRecent(
                profileId = profileId,
                nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                minIntervalMs = FULL_PULL_MIN_INTERVAL_MS,
            )
        }

    private fun startActivityProfilePull(
        profileId: Int,
        reason: String,
        pullLibrary: Boolean = true,
        pullWatchActivity: Boolean = true,
    ) {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return
        if (ProfileRepository.activeProfileId != profileId) return

        val result = syncRequestGate.launch(
            scope = accountScopeSnapshot(),
            profileId = profileId,
            kind = ProfileSyncRequestKind.Activity,
        ) {
            val currentAuthState = AuthRepository.state.value
            if (currentAuthState !is AuthState.Authenticated || currentAuthState.isAnonymous) return@launch
            if (ProfileRepository.activeProfileId != profileId) return@launch

            log.i { "Activity sync started profile=$profileId reason=$reason" }
            val syncResult = runActivityProfileSync(
                profileId = profileId,
                pullLibrary = pullLibrary,
                pullWatchActivity = pullWatchActivity,
                operations = profileActivitySyncOperations,
                onFailure = { step, error ->
                    log.e(error) { "Activity sync step failed profile=$profileId step=$step" }
                },
            )
            synchronized(pullStateLock) {
                activityPullFreshness = activityPullFreshness.recordIfSuccessful(
                    profileId = profileId,
                    completedAtEpochMs = EpisodeReleaseDatePlatform.nowEpochMs(),
                    result = syncResult,
                )
            }
            if (!syncResult.succeeded) {
                log.w {
                    "Activity sync incomplete profile=$profileId reason=$reason failedSteps=${syncResult.failedSteps}"
                }
            }
            log.i { "Activity sync completed profile=$profileId reason=$reason" }
        }

        if (result == ProfileSyncRequestResult.Coalesced) {
            log.d { "Activity sync coalesced profile=$profileId reason=$reason" }
        }
    }

    fun startPeriodicNuvioSyncPull(profileId: Int) {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            stopPeriodicNuvioSyncPull()
            return
        }
        if (periodicNuvioSyncPullJob?.isActive == true && periodicNuvioSyncProfileId == profileId) return

        stopPeriodicNuvioSyncPull()
        periodicNuvioSyncProfileId = profileId
        periodicNuvioSyncPullJob = accountScopeSnapshot().launch {
            while (isActive) {
                delay(PERIODIC_NUVIO_SYNC_PULL_INTERVAL_MS)

                val currentAuthState = AuthRepository.state.value
                if (currentAuthState !is AuthState.Authenticated || currentAuthState.isAnonymous) {
                    continue
                }
                if (ProfileRepository.activeProfileId != profileId) {
                    continue
                }

                TrackingProviderRegistry.ensureLoaded()
                TrackingSettingsRepository.ensureLoaded()

                val settings = TrackingSettingsRepository.uiState.value
                val shouldPullLibrary = effectiveLibrarySourceMode(
                    requestedSource = settings.librarySourceMode,
                    isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
                ) == LibrarySourceMode.LOCAL
                val shouldPullWatchProgress = effectiveWatchProgressSource(
                    requestedSource = settings.watchProgressSource,
                    isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
                ) == WatchProgressSource.NUVIO_SYNC

                if (!shouldPullLibrary && !shouldPullWatchProgress) {
                    continue
                }

                startActivityProfilePull(
                    profileId = profileId,
                    reason = "periodic",
                    pullLibrary = shouldPullLibrary,
                    pullWatchActivity = shouldPullWatchProgress,
                )
            }
        }
    }

    fun stopPeriodicNuvioSyncPull() {
        periodicNuvioSyncPullJob?.cancel()
        periodicNuvioSyncPullJob = null
        periodicNuvioSyncProfileId = null
    }

}
