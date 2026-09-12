package com.nuvio.app.features.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class TrackingProviderId(
    val storageId: String,
    val displayName: String,
) {
    TRAKT("trakt", "Trakt"),
    SIMKL("simkl", "Simkl"),
    MDBLIST("mdblist", "MDBList");

    companion object {
        fun fromStorage(value: String?): TrackingProviderId? =
            entries.firstOrNull { provider ->
                provider.storageId.equals(value?.trim(), ignoreCase = true) ||
                    provider.name.equals(value?.trim(), ignoreCase = true)
            }
    }
}

enum class TrackingCapability {
    AUTHENTICATION,
    LIBRARY_READ,
    LIBRARY_WRITE,
    WATCHED_READ,
    WATCHED_WRITE,
    PROGRESS_READ,
    PROGRESS_WRITE,
    SCROBBLE,
    COMMENTS,
    RECOMMENDATIONS,
}

data class TrackingProviderDescriptor(
    val id: TrackingProviderId,
    val displayName: String,
    val capabilities: Set<TrackingCapability>,
)

interface TrackingProfileStore {
    val providerId: TrackingProviderId

    fun onProfileChanged()
    fun clearLocalState()
    fun removeStoredProfile(profileId: Int)
}

interface TrackingAuthProvider : TrackingProfileStore {
    val descriptor: TrackingProviderDescriptor
    val accountGeneration: Long
        get() = 0L
    val isAuthenticated: StateFlow<Boolean>
    override val providerId: TrackingProviderId
        get() = descriptor.id

    fun ensureLoaded()
    fun handleAuthCallback(url: String): Boolean = false
}

object TrackingProviderRegistry {
    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authProviders = mutableMapOf<TrackingProviderId, TrackingAuthProvider>()
    private val authObservationJobs = mutableMapOf<TrackingProviderId, Job>()
    private val profileStores = mutableSetOf<TrackingProfileStore>()
    private val listWriters = mutableMapOf<TrackingProviderId, TrackingListWriter>()
    private val historyWriters = mutableMapOf<TrackingProviderId, TrackingHistoryWriter>()
    private val scrobblers = mutableMapOf<TrackingProviderId, TrackingScrobbler>()
    private val libraryProviders = mutableMapOf<TrackingProviderId, TrackingLibraryProvider>()
    private val watchedProviders = mutableMapOf<TrackingProviderId, TrackingWatchedProvider>()
    private val progressProviders = mutableMapOf<TrackingProviderId, TrackingProgressProvider>()

    private val _connectedProviderIds = MutableStateFlow<Set<TrackingProviderId>>(emptySet())
    val connectedProviderIds: StateFlow<Set<TrackingProviderId>> = _connectedProviderIds.asStateFlow()

    fun register(provider: TrackingAuthProvider) {
        val previousJob = synchronized(lock) {
            authProviders[provider.descriptor.id] = provider
            profileStores += provider
            authObservationJobs.remove(provider.descriptor.id)
        }
        previousJob?.cancel()
        val observationJob = scope.launch {
            provider.isAuthenticated.collectLatest { isAuthenticated ->
                _connectedProviderIds.update { connected ->
                    if (isAuthenticated) connected + provider.providerId else connected - provider.providerId
                }
            }
        }
        synchronized(lock) {
            authObservationJobs[provider.descriptor.id] = observationJob
        }
    }

    fun registerProfileStore(store: TrackingProfileStore) = synchronized(lock) {
        profileStores += store
    }

    fun registerListWriter(writer: TrackingListWriter) = synchronized(lock) {
        listWriters[writer.providerId] = writer
    }

    fun registerHistoryWriter(writer: TrackingHistoryWriter) = synchronized(lock) {
        historyWriters[writer.providerId] = writer
    }

    fun registerScrobbler(scrobbler: TrackingScrobbler) = synchronized(lock) {
        scrobblers[scrobbler.providerId] = scrobbler
    }

    fun registerLibraryProvider(provider: TrackingLibraryProvider) = synchronized(lock) {
        libraryProviders[provider.providerId] = provider
    }

    fun registerWatchedProvider(provider: TrackingWatchedProvider) = synchronized(lock) {
        watchedProviders[provider.providerId] = provider
    }

    fun registerProgressProvider(provider: TrackingProgressProvider) = synchronized(lock) {
        progressProviders[provider.providerId] = provider
    }

    fun authProvider(id: TrackingProviderId): TrackingAuthProvider? = synchronized(lock) {
        authProviders[id]
    }

    fun isAuthenticated(id: TrackingProviderId): Boolean =
        authProvider(id)?.also(TrackingAuthProvider::ensureLoaded)?.isAuthenticated?.value == true

    fun connectedProviderIdsSnapshot(): Set<TrackingProviderId> {
        providerSnapshot().forEach(TrackingAuthProvider::ensureLoaded)
        return providerSnapshot()
            .filterTo(linkedSetOf()) { provider -> provider.isAuthenticated.value }
            .mapTo(linkedSetOf()) { provider -> provider.descriptor.id }
    }

    fun providersWith(capability: TrackingCapability): List<TrackingAuthProvider> =
        providerSnapshot()
            .filter { provider -> capability in provider.descriptor.capabilities }
            .sortedBy { provider -> provider.descriptor.id.ordinal }

    fun listWriter(id: TrackingProviderId): TrackingListWriter? = synchronized(lock) {
        listWriters[id]
    }

    fun historyWriter(id: TrackingProviderId): TrackingHistoryWriter? = synchronized(lock) {
        historyWriters[id]
    }

    fun scrobbler(id: TrackingProviderId): TrackingScrobbler? = synchronized(lock) {
        scrobblers[id]
    }

    fun libraryProvider(id: TrackingProviderId): TrackingLibraryProvider? = synchronized(lock) {
        libraryProviders[id]
    }

    fun libraryProviders(): List<TrackingLibraryProvider> = synchronized(lock) {
        libraryProviders.entries
            .sortedBy { (id, _) -> id.ordinal }
            .map { (_, provider) -> provider }
    }

    fun watchedProvider(id: TrackingProviderId): TrackingWatchedProvider? = synchronized(lock) {
        watchedProviders[id]
    }

    fun progressProvider(id: TrackingProviderId): TrackingProgressProvider? = synchronized(lock) {
        progressProviders[id]
    }

    fun progressProviders(): List<TrackingProgressProvider> = synchronized(lock) {
        progressProviders.entries
            .sortedBy { (id, _) -> id.ordinal }
            .map { (_, provider) -> provider }
    }

    fun connectedListWriters(): List<TrackingListWriter> =
        connectedPorts(listWriters, TrackingCapability.LIBRARY_WRITE)

    fun connectedHistoryWriters(): List<TrackingHistoryWriter> =
        connectedPorts(historyWriters, TrackingCapability.WATCHED_WRITE)

    fun connectedScrobblers(): List<TrackingScrobbler> =
        connectedPorts(scrobblers, TrackingCapability.SCROBBLE)

    fun connectedLibraryProviders(): List<TrackingLibraryProvider> =
        connectedPorts(libraryProviders, TrackingCapability.LIBRARY_READ)

    fun connectedWatchedProviders(): List<TrackingWatchedProvider> =
        connectedPorts(watchedProviders, TrackingCapability.WATCHED_READ)

    fun connectedProgressProviders(): List<TrackingProgressProvider> =
        connectedPorts(progressProviders, TrackingCapability.PROGRESS_READ)

    fun handleAuthCallback(url: String): Boolean =
        providersWith(TrackingCapability.AUTHENTICATION)
            .any { provider -> provider.handleAuthCallback(url) }

    fun ensureLoaded() {
        providerSnapshot().forEach(TrackingAuthProvider::ensureLoaded)
    }

    fun onProfileChanged() {
        profileStoreSnapshot().forEach(TrackingProfileStore::onProfileChanged)
    }

    fun clearLocalState() {
        profileStoreSnapshot().forEach(TrackingProfileStore::clearLocalState)
    }

    fun removeStoredProfiles(profileIds: Iterable<Int>) {
        val stores = profileStoreSnapshot()
        profileIds.forEach { profileId ->
            stores.forEach { store -> store.removeStoredProfile(profileId) }
        }
    }

    private fun providerSnapshot(): List<TrackingAuthProvider> = synchronized(lock) {
        authProviders.values.toList()
    }

    private fun profileStoreSnapshot(): List<TrackingProfileStore> = synchronized(lock) {
        profileStores.toList()
    }

    private fun <T> connectedPorts(
        ports: Map<TrackingProviderId, T>,
        capability: TrackingCapability,
    ): List<T> {
        val candidates = synchronized(lock) { ports.entries.map { it.key to it.value } }
        return candidates
            .asSequence()
            .filter { (id, _) ->
                val provider = authProvider(id)
                provider != null &&
                    capability in provider.descriptor.capabilities &&
                    provider.also(TrackingAuthProvider::ensureLoaded).isAuthenticated.value
            }
            .sortedBy { (id, _) -> id.ordinal }
            .map { (_, port) -> port }
            .toList()
    }
}
