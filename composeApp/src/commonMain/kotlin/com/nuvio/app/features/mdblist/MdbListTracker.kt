package com.nuvio.app.features.mdblist

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object MdbListTracker : TrackingAuthProvider {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)
    internal val store = MdbListAuthStore(PlatformMdbListAuthPersistence, activeProfile.value)
    private val configuration = MdbListConfiguration(MdbListConfig.CLIENT_ID, AppVersionConfig.VERSION_NAME)
    private val http = MdbListHttpClient(MdbListNetworkEngine(configuration))
    val auth = MdbListAuthRepository(http, configuration, store)
    internal val api = MdbListApiClient(http, auth, store)
    val sync = MdbListSyncRepository(PlatformMdbListSyncStorage, store, api, activeProfile, coroutineScope)
    val library = MdbListLibraryService(api, sync, store, activeProfile, coroutineScope)
    private val history = MdbListHistoryService(api, sync)
    private val scrobble = MdbListScrobbleService(api, sync)
    val account = MdbListAccountController(auth, store, coroutineScope, { api.refreshUser(it) })
    private val authenticated = MutableStateFlow(store.state.value.isAuthenticated)
    override val isAuthenticated = authenticated.asStateFlow()
    override val accountGeneration: Long get() = store.scope().generation
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.MDBLIST, "MDBList", setOf(
            TrackingCapability.AUTHENTICATION, TrackingCapability.WATCHED_READ, TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ, TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE,
            TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
        )
    )
    val writes = MdbListTrackingWrites(sync, history, scrobble)
    val progressProvider = MdbListTrackingProgressProvider(sync, scrobble, store, activeProfile, ::ensureLoaded)
    val watchedProvider = MdbListWatchedSyncAdapter(sync, history, store, activeProfile)
    val libraryProvider = MdbListTrackingLibraryProvider(library, sync, ::ensureLoaded)

    init {
        coroutineScope.launch {
            store.state.collectLatest { state ->
                authenticated.value = state.scope.profileId == activeProfile.value && state.isAuthenticated
            }
        }
    }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerHistoryWriter(writes)
        TrackingProviderRegistry.registerScrobbler(writes)
        TrackingProviderRegistry.registerProgressProvider(progressProvider)
        TrackingProviderRegistry.registerWatchedProvider(watchedProvider)
        TrackingProviderRegistry.registerLibraryProvider(libraryProvider)
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun onProfileChanged() {
        account.stopPolling()
        activeProfile.value = ProfileRepository.activeProfileId
        store.selectProfile(activeProfile.value)
        authenticated.value = store.state.value.isAuthenticated
    }

    override fun clearLocalState() {
        account.stopPolling()
        store.clearAllProfiles()
        PlatformMdbListSyncStorage.clearAll()
        authenticated.value = false
    }

    override fun removeStoredProfile(profileId: Int) {
        store.removeProfile(profileId)
        val scope = store.scope()
        coroutineScope.launch {
            PlatformMdbListSyncStorage.remove(profileId) { store.checkScope(scope) }
        }
    }
}
