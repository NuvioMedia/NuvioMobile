package com.nuvio.app.features.library

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.library.sync.LibrarySyncAdapter
import com.nuvio.app.features.library.sync.SupabaseLibrarySyncAdapter
import com.nuvio.app.features.library.sync.consumeCursorPages
import com.nuvio.app.features.library.sync.libraryDeltaPageSize
import com.nuvio.app.features.library.sync.librarySnapshotPageSize
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipApplyResult
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.supportsContentType
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode as resolveEffectiveLibrarySourceMode
import com.nuvio.app.features.tracking.providerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_local_tab_title
import nuvio.composeapp.generated.resources.library_other
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString



object LibraryRepository {
    private const val pushDebounceMs = 500L

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("LibraryRepository")

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val localState = LibraryLocalState()
    private val loadLock = SynchronizedObject()
    private val nuvioSyncMutex = Mutex()
    private val persistenceLock = SynchronizedObject()
    private val lastPersistedRevisionByProfile = mutableMapOf<Int, Long>()
    internal var syncAdapter: LibrarySyncAdapter = SupabaseLibrarySyncAdapter

    init {
        ensureTrackingProvidersRegistered()
        syncScope.launch {
            TrackingProviderRegistry.connectedProviderIds.collectLatest {
                TrackingProviderRegistry.connectedLibraryProviders().forEach(TrackingLibraryProvider::prepare)
                activeLibraryProvider()?.let { provider ->
                    refreshLibraryProvider(
                        provider = provider,
                        reason = "connection state change",
                        intent = provider.connectionRefreshIntent,
                    )
                }
                publish()
            }
        }
        syncScope.launch {
            TrackingSettingsRepository.uiState
                .map { it.librarySourceMode }
                .distinctUntilChanged()
                .collectLatest {
                    publish()
                    activeLibraryProvider()?.let { provider ->
                        provider.prepare()
                        refreshLibraryProviderAsync(provider)
                    }
                }
        }
        TrackingProviderRegistry.libraryProviders().forEach { provider ->
            syncScope.launch {
                provider.changes.collectLatest {
                    if (TrackingProviderRegistry.isAuthenticated(provider.providerId)) {
                        publish()
                    }
                }
            }
        }
    }

    fun ensureLoaded() {
        ensureTrackingProvidersRegistered()
        TrackingProviderRegistry.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TrackingProviderRegistry.libraryProviders().forEach(TrackingLibraryProvider::ensureLoaded)
        while (true) {
            val activeProfileId = ProfileRepository.activeProfileId
            val snapshot = localState.snapshot()
            if (snapshot.hasLoaded && snapshot.token.profileId == activeProfileId) break
            loadFromDisk(activeProfileId)
        }
        TrackingProviderRegistry.connectedLibraryProviders().forEach(TrackingLibraryProvider::prepare)
        activeLibraryProvider()?.let(::refreshLibraryProviderAsync)
    }

    fun onProfileChanged(profileId: Int) {
        val current = localState.snapshot()
        if (profileId == current.token.profileId && current.hasLoaded) return

        if (!loadFromDisk(profileId)) return
        TrackingProviderRegistry.libraryProviders().forEach(TrackingLibraryProvider::onProfileChanged)
        TrackingProviderRegistry.connectedLibraryProviders().forEach(TrackingLibraryProvider::prepare)
        activeLibraryProvider()?.let(::refreshLibraryProviderAsync)
    }

    fun clearLocalState() {
        val transition = synchronized(loadLock) { localState.reset() }
        transition.detachedPushJob?.cancel()
        TrackingProviderRegistry.libraryProviders().forEach(TrackingLibraryProvider::clearLocalState)
        _uiState.value = LibraryUiState()
    }

    internal fun runAccountStorageWipe(wipeStorage: () -> Unit) {
        synchronized(loadLock) {
            val transition = localState.reset()
            transition.detachedPushJob?.cancel()
            synchronized(persistenceLock) {
                try {
                    wipeStorage()
                } finally {
                    lastPersistedRevisionByProfile.clear()
                }
            }
        }
    }

    private fun loadFromDisk(profileId: Int): Boolean {
        var shouldPublish = false
        val loaded = synchronized(loadLock) {
            if (ProfileRepository.activeProfileId != profileId) return@synchronized false
            val current = localState.snapshot()
            if (current.hasLoaded && current.token.profileId == profileId) {
                return@synchronized true
            }

            val transition = localState.beginProfileLoad(profileId)
            transition.detachedPushJob?.cancel()
            shouldPublish = completeLoadFromDisk(transition.snapshot.token)
            shouldPublish
        }
        if (shouldPublish) publish()
        return loaded
    }

    private fun completeLoadFromDisk(token: LibraryProfileToken): Boolean {
        val payload = LibraryStorage.loadPayload(token.profileId).orEmpty().trim()
        val storedPayload = if (payload.isNotEmpty()) {
            LibraryStoragePayloadCodec.decode(payload)
        } else {
            StoredLibraryPayload()
        }

        return localState.completeProfileLoad(
            token = token,
            activeProfileId = ProfileRepository.activeProfileId,
            items = storedPayload.items,
            deltaCursorEventId = storedPayload.deltaCursorEventId,
            deltaInitialized = storedPayload.deltaInitialized,
            pendingUpsertKeys = storedPayload.pendingUpsertKeys,
            pendingDeleteKeys = storedPayload.pendingDeleteKeys,
        ) != null
    }

    suspend fun pullFromServer(
        profileId: Int,
        refreshIntent: TrackingRefreshIntent = TrackingRefreshIntent.AUTOMATIC,
    ) {
        val operationToken = activeOperationToken(profileId) ?: run {
            log.d { "Skipping library pull for inactive profile $profileId" }
            return
        }
        var serializedOperationToken: LibraryProfileToken? = null

        activeLibraryProvider()?.let { provider ->
            refreshLibraryProvider(
                provider = provider,
                reason = "explicit pull",
                intent = refreshIntent,
            )
            if (!isActiveOperation(operationToken)) return
            publish()
            return
        }

        nuvioSyncMutex.withLock {
            val serializedToken = activeOperationToken(profileId) ?: return@withLock
            serializedOperationToken = serializedToken
            val pullSnapshot = localState.markPullStarted(serializedToken) ?: return@withLock

            try {
                if (!pullSnapshot.deltaInitialized) {
                    val cursorBeforeSnapshot = syncAdapter.getDeltaCursor(profileId)
                    val serverItems = syncAdapter.pullSnapshot(
                        profileId = profileId,
                        pageSize = librarySnapshotPageSize,
                    )
                    val applyResult = localState.applyServerItems(
                        pullSnapshot = pullSnapshot,
                        serverItems = serverItems,
                        cursorEventId = cursorBeforeSnapshot,
                    ) ?: return@withLock
                    persist(applyResult.snapshot)
                    publish()
                    if (applyResult.preservedLocalItems) {
                        log.i {
                            "Merged pending local library changes during snapshot bootstrap " +
                                "profile=$profileId items=${applyResult.snapshot.items.size}"
                        }
                    }
                }
                pullLibraryDelta(serializedToken, profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "Failed to pull library from server" }
            }
        }
        val completedToken = serializedOperationToken ?: operationToken
        val pendingSnapshot = localState.snapshot()
        if (pendingSnapshot.token == completedToken && isActiveOperation(completedToken)) {
            pushToServer(pendingSnapshot, delayMs = 0L)
        }
    }

    private suspend fun pullLibraryDelta(
        token: LibraryProfileToken,
        profileId: Int,
    ) {
        val initialSnapshot = localState.snapshot()
        if (initialSnapshot.token != token) return
        consumeCursorPages(
            initialCursor = initialSnapshot.deltaCursorEventId,
            pageSize = libraryDeltaPageSize,
            fetchPage = { cursor, limit ->
                if (isActiveOperation(token)) {
                    syncAdapter.pullDelta(
                        profileId = profileId,
                        sinceEventId = cursor,
                        limit = limit,
                    )
                } else {
                    emptyList()
                }
            },
            applyPage = { events, _ ->
                if (!isActiveOperation(token)) {
                    null
                } else {
                    localState.applyDeltaEvents(token, events)?.also { snapshot ->
                        persist(snapshot)
                        publish()
                    }?.deltaCursorEventId
                }
            },
        )
    }

    private fun activeOperationToken(profileId: Int): LibraryProfileToken? {
        if (ProfileRepository.activeProfileId != profileId) return null
        if (!loadFromDisk(profileId)) return null
        return localState.currentTokenIfLoaded(profileId)
            ?.takeIf { ProfileRepository.activeProfileId == profileId }
    }

    private fun isActiveOperation(token: LibraryProfileToken): Boolean =
        localState.isCurrent(token) && ProfileRepository.activeProfileId == token.profileId

    suspend fun toggleSaved(
        item: LibraryItem,
        confirmedRemovalProviders: Set<TrackingProviderId> = emptySet(),
    ): TrackingMembershipApplyResult {
        ensureLoaded()

        activeLibraryProvider()?.let { provider ->
            val providerMembership = provider.membership(item)
            val desiredMembership = provider.toggledDefaultMembership(providerMembership)
            log.i {
                "toggleSaved routed to ${provider.providerId.storageId} library source " +
                    "item=${item.id} type=${item.type} profile=${localState.snapshot().token.profileId}"
            }
            return applyMembershipChanges(
                item = item,
                desiredMembership = desiredMembership,
                confirmedRemovalProviders = confirmedRemovalProviders,
                targetProviderIds = setOf(provider.providerId),
                updateLocal = false,
            )
        }

        return toggleLocalSavedInternal(item)
    }

    fun toggleLocalSaved(item: LibraryItem) {
        ensureLoaded()
        toggleLocalSavedInternal(item)
    }

    private fun toggleLocalSavedInternal(item: LibraryItem): TrackingMembershipApplyResult {
        val result = localState.toggle(
            item.copy(savedAtEpochMs = LibraryClock.nowEpochMs()),
        )
        if (result.isSaved) {
            log.i {
                "Saving local library item item=${item.id} type=${item.type} " +
                    "profile=${result.snapshot.token.profileId}"
            }
        } else {
            log.i {
                "Removing local library item id=${item.id} type=${item.type} " +
                    "profile=${result.snapshot.token.profileId}"
            }
        }
        persist(result.snapshot)
        publish()
        pushToServer(result.snapshot)
        return TrackingMembershipApplyResult()
    }

    fun save(item: LibraryItem) {
        ensureLoaded()
        val snapshot = localState.upsert(item.copy(savedAtEpochMs = LibraryClock.nowEpochMs()))
        log.i {
            "Saving local library item item=${item.id} type=${item.type} profile=${snapshot.token.profileId}"
        }
        persist(snapshot)
        publish()
        pushToServer(snapshot)
    }

    fun remove(id: String) {
        ensureLoaded()
        val result = localState.removeById(id)
        if (result.affectedCount > 0) {
            log.i {
                "Removing local library item id=$id profile=${result.snapshot.token.profileId} " +
                    "removed=${result.affectedCount}"
            }
            persist(result.snapshot)
            publish()
            pushToServer(result.snapshot)
        }
    }

    private fun remove(id: String, type: String) {
        ensureLoaded()
        val result = localState.remove(id, type)
        if (result.affectedCount > 0) {
            log.i {
                "Removing local library item id=$id type=$type profile=${result.snapshot.token.profileId}"
            }
            persist(result.snapshot)
            publish()
            pushToServer(result.snapshot)
        }
    }

    fun isSaved(id: String, type: String? = null): Boolean {
        ensureLoaded()

        activeLibraryProvider()?.let { provider -> return provider.contains(id, type) }

        return if (type != null) {
            localState.contains(id, type)
        } else {
            localState.containsId(id)
        }
    }

    fun savedItem(id: String): LibraryItem? {
        ensureLoaded()

        activeLibraryProvider()?.let { provider -> return provider.find(id) }

        return localState.findById(id)
    }

    fun libraryListTabs(item: LibraryItem? = null): List<TrackingLibraryTab> =
        libraryTabsWithLocal(
            TrackingProviderRegistry.connectedLibraryProviders()
                .flatMap { provider -> provider.snapshot().tabs },
        ).filter { tab -> item == null || tab.supportsContentType(item.type) }

    suspend fun getMembershipSnapshot(item: LibraryItem): Map<String, Boolean> {
        ensureLoaded()
        val inLocal = localState.contains(item.id, item.type)
        val memberships = linkedMapOf<String, Boolean>()
        TrackingProviderRegistry.connectedLibraryProviders().forEach { provider ->
            memberships += provider.membership(item)
        }
        return libraryMembershipWithLocal(inLocal = inLocal, providerMembership = memberships)
    }

    suspend fun applyMembershipChanges(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        confirmedRemovalProviders: Set<TrackingProviderId> = emptySet(),
    ): TrackingMembershipApplyResult = applyMembershipChanges(
        item = item,
        desiredMembership = desiredMembership,
        confirmedRemovalProviders = confirmedRemovalProviders,
        targetProviderIds = null,
        updateLocal = true,
    )

    private suspend fun applyMembershipChanges(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        confirmedRemovalProviders: Set<TrackingProviderId>,
        targetProviderIds: Set<TrackingProviderId>?,
        updateLocal: Boolean,
    ): TrackingMembershipApplyResult {
        ensureLoaded()
        val localDesired = desiredMembership[LOCAL_LIBRARY_LIST_KEY] == true
        val currentlyInLocal = localState.contains(item.id, item.type)
        val profileId = localState.snapshot().token.profileId
        val providerChanges = TrackingProviderRegistry.connectedLibraryProviders()
            .filter { provider -> targetProviderIds == null || provider.providerId in targetProviderIds }
            .mapNotNull { provider ->
                val providerListKeys = provider.snapshot().tabs.mapTo(mutableSetOf(), TrackingLibraryTab::key)
                val providerMembership = desiredMembership.filterKeys(providerListKeys::contains)
                providerMembership.takeIf { membership -> membership.isNotEmpty() }?.let { membership ->
                    provider to membership
                }
            }
        val requiredConfirmations = providerChanges.mapNotNull { (provider, providerMembership) ->
            provider.membershipRemovalConfirmation(item, providerMembership)
                ?.takeUnless { confirmation -> confirmation.providerId in confirmedRemovalProviders }
        }
        log.i {
            "Applying library membership item=${item.id} type=${item.type} profile=$profileId " +
                "localDesired=$localDesired currentlyInLocal=$currentlyInLocal " +
                "connectedProviders=${TrackingProviderRegistry.connectedProviderIdsSnapshot()}"
        }
        if (requiredConfirmations.isNotEmpty()) {
            return TrackingMembershipApplyResult(
                requiredRemovalConfirmations = requiredConfirmations,
            )
        }
        if (updateLocal && localDesired != currentlyInLocal) {
            if (localDesired) {
                save(item)
            } else {
                remove(item.id, item.type)
            }
        }

        var firstFailure: Throwable? = null
        val resolutions = mutableListOf<TrackingMembershipResolution>()
        providerChanges.forEach { (provider, providerMembership) ->
            try {
                provider.applyMembership(
                    profileId = profileId,
                    item = item,
                    desiredMembership = providerMembership,
                    destructiveRemovalConfirmed = provider.providerId in confirmedRemovalProviders,
                )?.let(resolutions::add)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                log.e(error) { "Failed to update ${provider.providerId.storageId} library membership" }
            }
        }
        publish()
        firstFailure?.let { throw it }
        return TrackingMembershipApplyResult(resolutions = resolutions)
    }

    suspend fun removeFromList(
        item: LibraryItem,
        listKey: String,
        confirmedRemovalProviders: Set<TrackingProviderId> = emptySet(),
    ): TrackingMembershipApplyResult {
        ensureLoaded()
        val targetProvider = TrackingProviderRegistry.connectedLibraryProviders()
            .firstOrNull { provider -> provider.snapshot().tabs.any { tab -> tab.key == listKey } }
        val currentMembership = if (listKey == LOCAL_LIBRARY_LIST_KEY) {
            mapOf(LOCAL_LIBRARY_LIST_KEY to localState.contains(item.id, item.type))
        } else {
            targetProvider?.membership(item).orEmpty()
        }
        val desiredMembership = libraryMembershipWithRemovedList(
            currentMembership = currentMembership,
            listKey = listKey,
        )
        return applyMembershipChanges(
            item = item,
            desiredMembership = desiredMembership,
            confirmedRemovalProviders = confirmedRemovalProviders,
            targetProviderIds = targetProvider?.let { provider -> setOf(provider.providerId) }.orEmpty(),
            updateLocal = listKey == LOCAL_LIBRARY_LIST_KEY,
        )
    }

    private fun pushToServer(
        snapshot: LibraryLocalSnapshot,
        delayMs: Long = pushDebounceMs,
    ) {
        if (!snapshot.hasPendingPush) return
        val authState = AuthRepository.state.value
        val profileId = snapshot.token.profileId
        if (authState !is AuthState.Authenticated) {
            log.w { "Skipping library push: auth state is ${authState::class.simpleName} profile=$profileId" }
            return
        }
        if (authState.isAnonymous) {
            log.w { "Skipping library push: anonymous auth user=${authState.userId} profile=$profileId" }
            return
        }
        val pushJob = syncScope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            nuvioSyncMutex.withLock {
                if (!localState.isCurrent(snapshot)) {
                    val current = localState.snapshot()
                    log.d {
                        "Skipping stale debounced library push scheduled=${snapshot.token} " +
                            "current=${current.token} scheduledRevision=${snapshot.revision} " +
                            "currentRevision=${current.revision}"
                    }
                    return@withLock
                }
                val currentAuthState = AuthRepository.state.value
                if (currentAuthState !is AuthState.Authenticated || currentAuthState.isAnonymous) {
                    return@withLock
                }
                runCatching {
                    val itemsByKey = snapshot.items.associateBy { item ->
                        libraryItemKey(item.id, item.type)
                    }
                    val upsertItems = snapshot.pendingUpsertKeys.mapNotNull { key ->
                        itemsByKey[libraryItemKey(key.contentId, key.contentType)]
                    }
                    syncAdapter.pushItems(profileId, upsertItems)
                    syncAdapter.deleteItems(profileId, snapshot.pendingDeleteKeys)
                    localState.markPushCompleted(snapshot)?.let(::persist)
                    log.i {
                        "Library delta push completed profile=$profileId " +
                            "upserts=${upsertItems.size} deletes=${snapshot.pendingDeleteKeys.size}"
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) {
                        "Failed to push library delta profile=$profileId " +
                            "upserts=${snapshot.pendingUpsertKeys.size} deletes=${snapshot.pendingDeleteKeys.size}"
                    }
                }
            }
        }
        pushJob.invokeOnCompletion { localState.clearPushJob(pushJob) }

        val installResult = localState.installPushJob(snapshot, pushJob)
        if (!installResult.installed) {
            pushJob.cancel()
            return
        }
        installResult.detachedPushJob?.cancel()
        pushJob.start()
    }

    private fun publish() {
        val localSnapshot = localState.snapshot()
        val sourceMode = effectiveLibrarySourceMode()
        activeLibraryProvider(sourceMode)?.let { provider ->
            val providerSnapshot = provider.snapshot()
            val newUiState = LibraryUiState(
                sourceMode = sourceMode,
                items = providerSnapshot.items,
                sections = providerSnapshot.sections,
                isLoaded = providerSnapshot.hasLoaded,
                isLoading = providerSnapshot.isLoading,
                errorMessage = providerSnapshot.errorMessage,
            )
            localState.runIfTokenCurrent(localSnapshot.token) {
                _uiState.value = newUiState
            }
            return
        }

        val items = localSnapshot.items
            .sortedByDescending { it.savedAtEpochMs }
        val sections = items
            .groupBy { it.type }
            .map { (type, typeItems) ->
                LibrarySection(
                    type = type,
                    displayTitle = type.toLibraryDisplayTitle(),
                    items = typeItems.sortedByDescending { it.savedAtEpochMs },
                )
            }
            .sortedBy { it.displayTitle }

        val newUiState = LibraryUiState(
            sourceMode = LibrarySourceMode.LOCAL,
            items = items,
            sections = sections,
            isLoaded = localSnapshot.hasLoaded,
            isLoading = localSnapshot.isLoading,
            errorMessage = null,
        )
        localState.runIfCurrent(localSnapshot) {
            _uiState.value = newUiState
            triggerLibraryEnrichmentAsync(items)
        }
    }

    private var libraryEnrichmentJob: kotlinx.coroutines.Job? = null

    fun triggerLibraryEnrichmentAsync(items: List<LibraryItem> = _uiState.value.sections.flatMap { it.items }, force: Boolean = false) {
        val tmdbSettings = com.nuvio.app.features.tmdb.TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled || !tmdbSettings.hasApiKey) return

        if (libraryEnrichmentJob?.isActive == true && !force) return

        val itemsNeedingEnrichment = items.filter { item ->
            if (force) return@filter true
            val date = item.rawReleaseDate?.trim().orEmpty().ifBlank { item.releaseInfo?.trim().orEmpty() }
            date.isBlank() || !date.contains(Regex("\\b(18|19|20)\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}\\b"))
        }
        if (itemsNeedingEnrichment.isEmpty()) return

        libraryEnrichmentJob = syncScope.launch {
            var updatedAny = false
            for (item in itemsNeedingEnrichment) {
                try {
                    val enriched = com.nuvio.app.features.tmdb.TmdbMetadataService.enrichLibraryItem(item, tmdbSettings)
                    val newDate = enriched.rawReleaseDate ?: enriched.releaseInfo
                    if (!newDate.isNullOrBlank() && newDate != item.rawReleaseDate) {
                        val snapshot = localState.upsert(enriched)
                        persist(snapshot)
                        updatedAny = true
                        publish()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.w(e) { "Failed to enrich library item ${item.id}" }
                }
            }
        }
    }

    private fun persist(snapshot: LibraryLocalSnapshot) {
        val payload = LibraryStoragePayloadCodec.encode(snapshot)
        synchronized(persistenceLock) {
            val profileId = snapshot.token.profileId
            val lastPersistedRevision = lastPersistedRevisionByProfile[profileId] ?: Long.MIN_VALUE
            if (snapshot.revision <= lastPersistedRevision) return@synchronized
            localState.runIfCurrent(snapshot) {
                LibraryStorage.savePayload(profileId, payload)
                lastPersistedRevisionByProfile[profileId] = snapshot.revision
            }
        }
    }

    private fun refreshLibraryProviderAsync(provider: TrackingLibraryProvider) {
        syncScope.launch {
            refreshLibraryProvider(
                provider = provider,
                reason = "background refresh",
                intent = TrackingRefreshIntent.AUTOMATIC,
            )
            publish()
        }
    }

    private suspend fun refreshLibraryProvider(
        provider: TrackingLibraryProvider,
        reason: String,
        intent: TrackingRefreshIntent,
    ) {
        log.i {
            "Tracking library refresh request provider=${provider.providerId.storageId} " +
                "reason=$reason intent=$intent"
        }
        try {
            provider.refresh(intent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) {
                "Failed to refresh ${provider.providerId.storageId} library during $reason"
            }
        }
    }

    private fun selectedLibrarySourceMode(): LibrarySourceMode {
        TrackingSettingsRepository.ensureLoaded()
        return TrackingSettingsRepository.uiState.value.librarySourceMode
    }

    private fun effectiveLibrarySourceMode(): LibrarySourceMode =
        resolveEffectiveLibrarySourceMode(
            requestedSource = selectedLibrarySourceMode(),
            isProviderAuthenticated = { providerId ->
                TrackingProviderRegistry.libraryProvider(providerId) != null &&
                    TrackingProviderRegistry.isAuthenticated(providerId)
            },
        )

    private fun activeLibraryProvider(
        sourceMode: LibrarySourceMode = effectiveLibrarySourceMode(),
    ): TrackingLibraryProvider? =
        sourceMode.providerId?.let(TrackingProviderRegistry::libraryProvider)
}

internal const val LOCAL_LIBRARY_LIST_KEY = "local"
private const val DEFAULT_LOCAL_LIBRARY_TAB_TITLE = "Nuvio Library"
private const val DEFAULT_LIBRARY_OTHER_TITLE = "Other"

internal fun localLibraryListTab(): TrackingLibraryTab =
    TrackingLibraryTab(
        key = LOCAL_LIBRARY_LIST_KEY,
        title = localizedStringOrDefault(
            resource = Res.string.library_local_tab_title,
            fallback = DEFAULT_LOCAL_LIBRARY_TAB_TITLE,
        ),
        providerId = null,
        kind = TrackingLibraryTabKind.WATCHLIST,
    )

internal fun libraryTabsWithLocal(providerTabs: List<TrackingLibraryTab>): List<TrackingLibraryTab> =
    listOf(localLibraryListTab()) + providerTabs

internal fun libraryMembershipWithLocal(
    inLocal: Boolean,
    providerMembership: Map<String, Boolean> = emptyMap(),
): Map<String, Boolean> =
    linkedMapOf<String, Boolean>(LOCAL_LIBRARY_LIST_KEY to inLocal).apply {
        putAll(providerMembership)
    }

internal fun libraryMembershipWithRemovedList(
    currentMembership: Map<String, Boolean>,
    listKey: String,
): Map<String, Boolean> =
    currentMembership.toMutableMap().apply {
        this[listKey] = false
    }



internal fun String.toLibraryDisplayTitle(): String {
    val normalized = trim()
    if (normalized.isBlank()) return localizedLibraryOtherTitle()

    return normalized
        .split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { char -> char.uppercase() }
        }
        .ifBlank { localizedLibraryOtherTitle() }
}

private fun localizedLibraryOtherTitle(): String =
    localizedStringOrDefault(
        resource = Res.string.library_other,
        fallback = DEFAULT_LIBRARY_OTHER_TITLE,
    )

private fun localizedStringOrDefault(resource: StringResource, fallback: String): String =
    runCatching { runBlocking { getString(resource) } }
        .getOrDefault(fallback)
