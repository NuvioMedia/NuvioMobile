package com.nuvio.app.features.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberHeroStretchState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.firstEnabledManifestError
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.findPlaybackTargetForProgress
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SeriesPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.components.HomeContinueWatchingSectionBottomPadding
import com.nuvio.app.features.home.components.ContinueWatchingLayout
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watched.resolveWatchedBadgesBulk
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.isMalformedNextUpSeedContentId
import com.nuvio.app.features.watchprogress.isSeriesTypeForContinueWatching
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import com.nuvio.app.features.watchprogress.resolvedProgressKey
import com.nuvio.app.features.watchprogress.shouldTreatAsInProgressForContinueWatching
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watchprogress.toUpNextContinueWatchingItem
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.isReleasedBy
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.home.components.HomeCollectionRowSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.nuvio.app.features.home.components.continueWatchingHeroViewportReserveHeight
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    animateCollectionGifs: Boolean = true,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    continueWatchingDisintegrationRequest: DisintegrationRequest<String>? = null,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
        CollectionRepository.initialize()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            WatchProgressSourceCoordinator.ensureStarted()
        }
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val homeUiState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val homeSettingsUiState by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeListState = rememberLazyListState()
    val continueWatchingListState = rememberLazyListState()
    val upcomingListState = rememberLazyListState()
    val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
    val continueWatchingPreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val effectiveWatchProgressSource = watchProgressUiState.source
    val cloudLibraryUiState by CloudLibraryRepository.uiState.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val trackingSettingsUiState by remember {
        TrackingSettingsRepository.ensureLoaded()
        TrackingSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    var observedOfflineState by remember { mutableStateOf(false) }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect {
            homeListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(networkStatusUiState.condition) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (observedOfflineState) {
                    observedOfflineState = false
                    HomeRepository.refresh(addonsUiState.addons.enabledAddons(), force = true)
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    val progressProviderOwnsCompletedHistory = remember(effectiveWatchProgressSource) {
        WatchProgressRepository.activeProviderOwnsCompletedHistoryProjection()
    }
    val continueWatchingCutoffEpochMs = remember(
        effectiveWatchProgressSource,
        trackingSettingsUiState.continueWatchingDaysCap,
    ) {
        WatchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(
            daysCap = trackingSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
        )
    }

    val nextUpWatchedItems = remember(watchedUiState.items, progressProviderOwnsCompletedHistory) {
        if (progressProviderOwnsCompletedHistory) emptyList() else watchedUiState.items
    }

    val effectiveWatchProgressEntries = remember(
        watchProgressUiState.entries,
        watchProgressUiState.hiddenContentIds,
        continueWatchingCutoffEpochMs,
    ) {
        val visibleProviderEntries = watchProgressUiState.entries.filterNot { entry ->
            entry.parentMetaId in watchProgressUiState.hiddenContentIds ||
                WatchProgressRepository.isDroppedShow(entry.parentMetaId)
        }
        filterEntriesForContinueWatchingWindow(
            entries = visibleProviderEntries,
            cutoffEpochMs = continueWatchingCutoffEpochMs,
        )
    }

    val allNextUpSeedCandidates = remember(
        watchProgressUiState.entries,
        watchProgressUiState.hiddenContentIds,
        nextUpWatchedItems,
        progressProviderOwnsCompletedHistory,
        continueWatchingPreferences.upNextFromFurthestEpisode,
    ) {
        buildHomeNextUpSeedCandidates(
            progressEntries = watchProgressUiState.entries,
            watchedItems = nextUpWatchedItems,
            providerOwnsCompletedHistory = progressProviderOwnsCompletedHistory,
            preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
            shouldUseProgressSeed = WatchProgressRepository::shouldUseAsNextUpSeed,
            isContentHidden = { contentId ->
                contentId in watchProgressUiState.hiddenContentIds ||
                    WatchProgressRepository.isDroppedShow(contentId)
            },
        )
    }

    val recentNextUpSeedCandidates = remember(
        allNextUpSeedCandidates,
        continueWatchingCutoffEpochMs,
    ) {
        filterHomeNextUpCandidatesForContinueWatchingWindow(
            candidates = allNextUpSeedCandidates,
            cutoffEpochMs = continueWatchingCutoffEpochMs,
        )
    }

    val activeNextUpSeedContentIds = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates.mapTo(mutableSetOf()) { candidate -> candidate.content.id }
    }

    val currentNextUpSeedByContentId = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates.associate { candidate ->
            candidate.content.id to (candidate.seasonNumber to candidate.episodeNumber)
        }.toMap()
    }

    val visibleContinueWatchingEntries = remember(effectiveWatchProgressEntries) {
        effectiveWatchProgressEntries.continueWatchingEntries(limit = HomeContinueWatchingMaxRecentProgressItems)
    }

    val watchProgressSeedKey = remember(watchProgressUiState.entries) {
        watchProgressUiState.entries.map { entry ->
            Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        }
    }

    LaunchedEffect(visibleContinueWatchingEntries) {
        if (visibleContinueWatchingEntries.any(WatchProgressEntry::isCloudLibraryProgressEntry)) {
            CloudLibraryRepository.ensureLoaded()
        }
    }

    val latestCompletedAtBySeries = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates
            .groupBy { candidate -> candidate.content.id }
            .mapValues { (_, candidates) -> candidates.maxOfOrNull { candidate -> candidate.markedAtEpochMs } ?: Long.MIN_VALUE }
    }

    val nextUpSuppressedSeriesIds = remember(visibleContinueWatchingEntries, latestCompletedAtBySeries) {
        visibleContinueWatchingEntries
            .asSequence()
            .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
            .filter { entry ->
                shouldTreatAsActiveInProgressForNextUpSuppression(
                    progress = entry,
                    latestCompletedAt = latestCompletedAtBySeries[entry.parentMetaId],
                )
            }
            .map { entry -> entry.parentMetaId }
            .filter(String::isNotBlank)
            .toSet()
    }

    val completedSeriesCandidates = remember(recentNextUpSeedCandidates, nextUpSuppressedSeriesIds) {
        recentNextUpSeedCandidates.filter { candidate ->
            candidate.content.id !in nextUpSuppressedSeriesIds
        }
    }
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: 1
    val cwCacheGeneration by ContinueWatchingEnrichmentCache.generation.collectAsStateWithLifecycle()
    var hasUserScrolledContinueWatching by remember(activeProfileId) { mutableStateOf(false) }
    var hasUserScrolledUpcoming by remember(activeProfileId) { mutableStateOf(false) }

    LaunchedEffect(activeProfileId, continueWatchingListState) {
        snapshotFlow { continueWatchingListState.isScrollInProgress }.collect { isScrolling ->
            if (isScrolling) hasUserScrolledContinueWatching = true
        }
    }

    LaunchedEffect(activeProfileId, upcomingListState) {
        snapshotFlow { upcomingListState.isScrollInProgress }.collect { isScrolling ->
            if (isScrolling) hasUserScrolledUpcoming = true
        }
    }

    var nextUpItemsBySeries by remember(activeProfileId, effectiveWatchProgressSource) {
        mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap())
    }
    var processedNextUpContentIds by remember(activeProfileId, effectiveWatchProgressSource) {
        mutableStateOf<Set<String>>(emptySet())
    }

    LaunchedEffect(activeProfileId, effectiveWatchProgressSource, cwCacheGeneration) {
        nextUpItemsBySeries = emptyMap()
        processedNextUpContentIds = emptySet()
    }

    val cachedSnapshots = remember(activeProfileId, effectiveWatchProgressSource, cwCacheGeneration) {
        ContinueWatchingEnrichmentCache.getSnapshots(
            profileId = activeProfileId,
            source = effectiveWatchProgressSource,
        )
    }
    val shouldValidateMissingNextUpSeeds = remember(
        watchProgressUiState.hasLoadedRemoteProgress,
        watchedUiState.isLoaded,
        watchedUiState.hasLoadedRemoteItems,
        progressProviderOwnsCompletedHistory,
    ) {
        isHomeNextUpSeedSourceLoaded(
            providerOwnsCompletedHistory = progressProviderOwnsCompletedHistory,
            hasLoadedRemoteProgress = watchProgressUiState.hasLoadedRemoteProgress,
            hasLoadedWatchedItems = watchedUiState.isLoaded,
            hasLoadedRemoteWatchedItems = watchedUiState.hasLoadedRemoteItems,
        )
    }
    val cachedNextUpItems = remember(
        cachedSnapshots.first,
        continueWatchingPreferences.dismissedNextUpKeys,
        activeNextUpSeedContentIds,
        currentNextUpSeedByContentId,
        progressProviderOwnsCompletedHistory,
        watchProgressUiState.hasLoadedRemoteProgress,
        shouldValidateMissingNextUpSeeds,
        processedNextUpContentIds,
        nextUpItemsBySeries,
        continueWatchingPreferences.showUnairedNextUp,
        watchedUiState.isLoaded,
        watchProgressUiState.hiddenContentIds,
    ) {
        cachedSnapshots.first.mapNotNull { cached ->
            if (
                shouldValidateMissingNextUpSeeds &&
                cached.contentId !in activeNextUpSeedContentIds
            ) {
                return@mapNotNull null
            }
            val currentSeed = currentNextUpSeedByContentId[cached.contentId]
            if (currentSeed != null) {
                val (currentSeason, currentEpisode) = currentSeed
                if (
                    hasHomeNextUpSeedChangedFromCache(
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        cachedSeason = cached.seedSeason,
                        cachedEpisode = cached.seedEpisode,
                    )
                ) {
                    return@mapNotNull null
                }
            }
            if (
                progressProviderOwnsCompletedHistory &&
                watchProgressUiState.hasLoadedRemoteProgress &&
                cached.contentId in processedNextUpContentIds &&
                cached.contentId !in nextUpItemsBySeries.keys
            ) {
                return@mapNotNull null
            }
            if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in continueWatchingPreferences.dismissedNextUpKeys) {
                return@mapNotNull null
            }
            if (!cachedNextUpHasAired(cached) && !continueWatchingPreferences.showUnairedNextUp) {
                return@mapNotNull null
            }
            if (
                cached.contentId in watchProgressUiState.hiddenContentIds ||
                WatchProgressRepository.isDroppedShow(cached.contentId)
            ) {
                return@mapNotNull null
            }
            val item = cached.toContinueWatchingItem() ?: return@mapNotNull null
            val sortTimestamp = if (item.isReleaseAlert) {
                com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(item.released) ?: cached.lastWatched
            } else {
                cached.lastWatched
            }
            cached.contentId to (sortTimestamp to item)
        }.toMap()
    }
    val cachedInProgressItems = remember(
        cachedSnapshots.second,
        effectiveWatchProgressSource,
        watchProgressUiState.hiddenContentIds,
    ) {
        cachedSnapshots.second.mapNotNull { cached ->
            if (
                cached.contentId in watchProgressUiState.hiddenContentIds ||
                WatchProgressRepository.isDroppedShow(cached.contentId)
            ) {
                return@mapNotNull null
            }
            cached.resolvedProgressKey() to cached.toContinueWatchingItem()
        }.toMap()
    }

    val effectivNextUpItems = remember(
        nextUpItemsBySeries,
        cachedNextUpItems,
        continueWatchingPreferences.dismissedNextUpKeys,
        activeNextUpSeedContentIds,
        currentNextUpSeedByContentId,
        shouldValidateMissingNextUpSeeds,
        processedNextUpContentIds,
    ) {
        val liveNextUpItems = filterNextUpItemsByCurrentSeeds(
            nextUpItemsBySeries = nextUpItemsBySeries,
            activeSeedContentIds = activeNextUpSeedContentIds,
            currentSeedByContentId = currentNextUpSeedByContentId,
            shouldDropItemsWithoutActiveSeed = shouldValidateMissingNextUpSeeds,
        ).filterValues { (_, item) ->
            nextUpDismissKey(
                item.parentMetaId,
                item.nextUpSeedSeasonNumber,
                item.nextUpSeedEpisodeNumber,
            ) !in continueWatchingPreferences.dismissedNextUpKeys
        }
        mergeHomeNextUpItemsWithCache(
            resolvedItems = liveNextUpItems,
            cachedItems = cachedNextUpItems,
            conclusivelyProcessedContentIds = processedNextUpContentIds,
        )
    }

    val allContinueWatchingItems = remember(
        visibleContinueWatchingEntries,
        cachedInProgressItems,
        effectivNextUpItems,
        nextUpSuppressedSeriesIds,
        continueWatchingPreferences.sortMode,
        cloudLibraryUiState,
    ) {
        buildHomeContinueWatchingItems(
            visibleEntries = visibleContinueWatchingEntries,
            cachedInProgressByProgressKey = cachedInProgressItems,
            nextUpItemsBySeries = effectivNextUpItems,
            nextUpSuppressedSeriesIds = nextUpSuppressedSeriesIds,
            sortMode = continueWatchingPreferences.sortMode,
            todayIsoDate = CurrentDateProvider.todayIsoDate(),
            cloudLibraryUiState = cloudLibraryUiState,
        )
    }
    val (continueWatchingItems, upcomingItems) = remember(
        allContinueWatchingItems,
        continueWatchingPreferences.sortMode,
    ) {
        splitUpcomingItems(
            items = allContinueWatchingItems,
            mode = continueWatchingPreferences.sortMode,
        )
    }
    val hasContinueWatchingRows = continueWatchingItems.isNotEmpty() || upcomingItems.isNotEmpty()

    LaunchedEffect(activeProfileId, continueWatchingItems.isNotEmpty(), hasUserScrolledContinueWatching) {
        if (!hasUserScrolledContinueWatching && continueWatchingItems.isNotEmpty()) {
            snapshotFlow {
                continueWatchingListState.firstVisibleItemIndex to
                    continueWatchingListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                if (
                    !hasUserScrolledContinueWatching &&
                    !continueWatchingListState.isScrollInProgress &&
                    (index != 0 || offset != 0)
                ) {
                    continueWatchingListState.scrollToItem(0)
                }
            }
        }
    }
    LaunchedEffect(activeProfileId, upcomingItems.isNotEmpty(), hasUserScrolledUpcoming) {
        if (!hasUserScrolledUpcoming && upcomingItems.isNotEmpty()) {
            snapshotFlow {
                upcomingListState.firstVisibleItemIndex to
                    upcomingListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                if (
                    !hasUserScrolledUpcoming &&
                    !upcomingListState.isScrollInProgress &&
                    (index != 0 || offset != 0)
                ) {
                    upcomingListState.scrollToItem(0)
                }
            }
        }
    }
    val enabledAddons = remember(addonsUiState.addons) {
        addonsUiState.addons.enabledAddons()
    }
    val availableManifests = remember(enabledAddons) {
        enabledAddons.mapNotNull { addon -> addon.manifest }
    }

    val metaProviderKey = remember(availableManifests) {
        availableManifests
            .filter { manifest -> manifest.resources.any { resource -> resource.name == "meta" } }
            .map { manifest -> manifest.transportUrl }
            .sorted()
    }
    val metaProviderReadinessKey = remember(enabledAddons) {
        enabledAddons
            .sortedBy { addon -> addon.manifestUrl }
            .joinToString(separator = "|") { addon ->
                "${addon.manifestUrl}:${addon.manifest != null}:${addon.isRefreshing}:${addon.errorMessage.orEmpty()}"
            }
    }
    var nextUpResolutionRetryAttempt by remember(
        activeProfileId,
        effectiveWatchProgressSource,
        completedSeriesCandidates,
        metaProviderKey,
        metaProviderReadinessKey,
        networkStatusUiState.condition,
        continueWatchingPreferences.showUnairedNextUp,
        continueWatchingPreferences.upNextFromFurthestEpisode,
        continueWatchingPreferences.dismissedNextUpKeys,
        cwCacheGeneration,
    ) {
        mutableStateOf(0)
    }

    LaunchedEffect(collections, enabledAddons) {
        HomeCatalogSettingsRepository.syncCollections(collections)
        HomeRepository.applyCurrentSettings()
        if (collections.any { it.folders.isNotEmpty() }) {
            HomeRepository.refresh(enabledAddons, force = true)
        }
    }

    LaunchedEffect(
        completedSeriesCandidates,
        metaProviderKey,
        metaProviderReadinessKey,
        networkStatusUiState.condition,
        nextUpResolutionRetryAttempt,
        continueWatchingPreferences.showUnairedNextUp,
        continueWatchingPreferences.upNextFromFurthestEpisode,
        continueWatchingPreferences.dismissedNextUpKeys,
        watchProgressSeedKey,
        visibleContinueWatchingEntries,
        nextUpWatchedItems,
        watchedUiState.isLoaded,
        watchedUiState.hasLoadedRemoteItems,
        watchProgressUiState.hasLoadedRemoteProgress,
        activeProfileId,
        effectiveWatchProgressSource,
        cwCacheGeneration,
    ) {
        if (
            !isHomeNextUpSeedSourceLoaded(
                providerOwnsCompletedHistory = progressProviderOwnsCompletedHistory,
                hasLoadedRemoteProgress = watchProgressUiState.hasLoadedRemoteProgress,
                hasLoadedWatchedItems = watchedUiState.isLoaded,
                hasLoadedRemoteWatchedItems = watchedUiState.hasLoadedRemoteItems,
            )
        ) {
            return@LaunchedEffect
        }

        if (completedSeriesCandidates.isEmpty()) {
            nextUpItemsBySeries = emptyMap()
            processedNextUpContentIds = emptySet()
            saveContinueWatchingSnapshots(
                profileId = activeProfileId,
                source = effectiveWatchProgressSource,
                cacheGeneration = cwCacheGeneration,
                nextUpItemsBySeries = emptyMap(),
                visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                todayIsoDate = CurrentDateProvider.todayIsoDate(),
                seedLastWatchedMap = emptyMap(),
            )
            return@LaunchedEffect
        }

        withContext(Dispatchers.Default) {
            val cachedResolvedNextUpItems = completedSeriesCandidates.mapNotNull { candidate ->
                val cached = cachedNextUpItems[candidate.content.id] ?: return@mapNotNull null
                val item = cached.second
                if (
                    item.nextUpSeedSeasonNumber != candidate.seasonNumber ||
                    item.nextUpSeedEpisodeNumber != candidate.episodeNumber
                ) {
                    return@mapNotNull null
                }
                if (!hasUsableHomeNextUpMetadata(item)) {
                    return@mapNotNull null
                }
                candidate.content.id to cached
            }.toMap()
            val candidatesToResolve = completedSeriesCandidates.filter { candidate ->
                candidate.content.id !in cachedResolvedNextUpItems
            }
            val resolutionPlan = planHomeNextUpResolutionCandidates(candidatesToResolve)
            val resolutionCandidates = resolutionPlan.initialCandidates
            val deferredResolutionCandidates = resolutionPlan.deferredCandidates
            val seedLastWatchedMap = completedSeriesCandidates.associate { it.content.id to it.markedAtEpochMs }
            if (candidatesToResolve.isEmpty()) {
                val cachedResults = mergeHomeNextUpItemsWithCache(
                    resolvedItems = cachedResolvedNextUpItems,
                    cachedItems = cachedNextUpItems,
                    conclusivelyProcessedContentIds = cachedResolvedNextUpItems.keys,
                )
                withContext(Dispatchers.Main) {
                    nextUpItemsBySeries = cachedResults
                    processedNextUpContentIds = cachedResolvedNextUpItems.keys
                }
                saveContinueWatchingSnapshots(
                    profileId = activeProfileId,
                    source = effectiveWatchProgressSource,
                    cacheGeneration = cwCacheGeneration,
                    nextUpItemsBySeries = cachedResults,
                    visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                    todayIsoDate = CurrentDateProvider.todayIsoDate(),
                    seedLastWatchedMap = seedLastWatchedMap,
                )
                return@withContext
            }

            val todayIsoDate = CurrentDateProvider.todayIsoDate()
            val semaphore = Semaphore(NEXT_UP_RESOLUTION_CONCURRENCY)
            val freshResults = mutableMapOf<String, Pair<Long, ContinueWatchingItem>>()
            val processedFreshContentIds = mutableSetOf<String>()

            suspend fun resolveCandidatesStreaming(
                candidates: List<CompletedSeriesCandidate>,
            ) {
                if (candidates.isEmpty()) return

                val results = Channel<HomeNextUpCandidateResolution>(Channel.UNLIMITED)
                candidates.forEach { completedEntry ->
                    launch {
                        val attempt = try {
                            semaphore.withPermit {
                                resolveHomeNextUpCandidate(
                                    completedEntry = completedEntry,
                                    watchProgressEntries = watchProgressUiState.entries,
                                    watchedItems = nextUpWatchedItems,
                                    cachedFallbackItem = cachedNextUpItems[completedEntry.content.id]?.second,
                                    todayIsoDate = todayIsoDate,
                                    preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
                                    showUnairedNextUp = continueWatchingPreferences.showUnairedNextUp,
                                    dismissedNextUpKeys = continueWatchingPreferences.dismissedNextUpKeys,
                                    providerOwnsCompletedHistory = progressProviderOwnsCompletedHistory,
                                )
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            HomeNextUpResolutionAttempt.transientFailure()
                        }
                        results.send(
                            HomeNextUpCandidateResolution(
                                candidate = completedEntry,
                                attempt = attempt,
                            ),
                        )
                    }
                }

                repeat(candidates.size) {
                    val resolution = results.receive()
                    if (resolution.attempt.isConclusive) {
                        processedFreshContentIds += resolution.candidate.content.id
                    }

                    var changed = false
                    resolution.attempt.resolved?.let { (contentId, item) ->
                        if (cachedResolvedNextUpItems.size + freshResults.size < HomeContinueWatchingMaxRecentProgressItems) {
                            val previous = freshResults.put(contentId, item)
                            changed = previous != item
                        }
                    }

                    if (changed || resolution.attempt.isConclusive) {
                        val resolvedResults = cachedResolvedNextUpItems + freshResults
                        val conclusiveContentIds = cachedResolvedNextUpItems.keys + processedFreshContentIds
                        val progressiveResults = mergeHomeNextUpItemsWithCache(
                            resolvedItems = resolvedResults,
                            cachedItems = cachedNextUpItems,
                            conclusivelyProcessedContentIds = conclusiveContentIds,
                        )
                        withContext(Dispatchers.Main) {
                            nextUpItemsBySeries = progressiveResults
                            processedNextUpContentIds = conclusiveContentIds
                        }
                        saveContinueWatchingSnapshots(
                            profileId = activeProfileId,
                            source = effectiveWatchProgressSource,
                            cacheGeneration = cwCacheGeneration,
                            nextUpItemsBySeries = progressiveResults,
                            visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                            todayIsoDate = todayIsoDate,
                            seedLastWatchedMap = seedLastWatchedMap,
                        )
                    }
                    yield()
                }
                results.close()
            }

            resolveCandidatesStreaming(candidates = resolutionCandidates)

            val resolvedResults = cachedResolvedNextUpItems + freshResults
            val conclusiveContentIds = cachedResolvedNextUpItems.keys + processedFreshContentIds
            val results = mergeHomeNextUpItemsWithCache(
                resolvedItems = resolvedResults,
                cachedItems = cachedNextUpItems,
                conclusivelyProcessedContentIds = conclusiveContentIds,
            )
            withContext(Dispatchers.Main) {
                nextUpItemsBySeries = results
                processedNextUpContentIds = conclusiveContentIds
            }

            saveContinueWatchingSnapshots(
                profileId = activeProfileId,
                source = effectiveWatchProgressSource,
                cacheGeneration = cwCacheGeneration,
                nextUpItemsBySeries = results,
                visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                todayIsoDate = todayIsoDate,
                seedLastWatchedMap = seedLastWatchedMap,
            )

            if (deferredResolutionCandidates.isNotEmpty()) {
                resolveCandidatesStreaming(
                    candidates = deferredResolutionCandidates,
                )

                val deferredResolvedResults = cachedResolvedNextUpItems + freshResults
                val deferredConclusiveContentIds = cachedResolvedNextUpItems.keys + processedFreshContentIds
                val deferredResults = mergeHomeNextUpItemsWithCache(
                    resolvedItems = deferredResolvedResults,
                    cachedItems = cachedNextUpItems,
                    conclusivelyProcessedContentIds = deferredConclusiveContentIds,
                )
                withContext(Dispatchers.Main) {
                    nextUpItemsBySeries = deferredResults
                    processedNextUpContentIds = deferredConclusiveContentIds
                }
                saveContinueWatchingSnapshots(
                    profileId = activeProfileId,
                    source = effectiveWatchProgressSource,
                    cacheGeneration = cwCacheGeneration,
                    nextUpItemsBySeries = deferredResults,
                    visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                    todayIsoDate = todayIsoDate,
                    seedLastWatchedMap = seedLastWatchedMap,
                )
            }

            val transientContentIds = candidatesToResolve
                .asSequence()
                .map { candidate -> candidate.content.id }
                .filterNot { contentId -> contentId in processedFreshContentIds }
                .toList()
            if (
                transientContentIds.isNotEmpty() &&
                nextUpResolutionRetryAttempt < MAX_NEXT_UP_RESOLUTION_RETRIES &&
                networkStatusUiState.condition == NetworkCondition.Online
            ) {
                val retryDelayMs = NEXT_UP_RESOLUTION_RETRY_BASE_DELAY_MS *
                    (1L shl nextUpResolutionRetryAttempt)
                delay(retryDelayMs)
                withContext(Dispatchers.Main) {
                    nextUpResolutionRetryAttempt += 1
                }
            }
        }
    }

    val hasActiveAddons = enabledAddons.any { it.manifest != null }
    val addonManifestsLoading = enabledAddons.any { it.isRefreshing }
    val addonManifestErrorMessage = enabledAddons.firstEnabledManifestError()
    val isResolvingHeroSources = addonManifestsLoading || homeUiState.isLoading
    val visibleCollections = remember(collections) {
        collections.filter { it.folders.isNotEmpty() }
    }
    var firstCatalogReported by remember { mutableStateOf(false) }

    LaunchedEffect(
        homeUiState.sections.firstOrNull()?.key,
        visibleCollections.isNotEmpty(),
        onFirstCatalogRendered,
    ) {
        if (firstCatalogReported) return@LaunchedEffect
        val hasAnyVisibleContent = homeUiState.sections.isNotEmpty() ||
            visibleCollections.isNotEmpty()
        if (hasAnyVisibleContent) {
            firstCatalogReported = true
            onFirstCatalogRendered?.invoke()
        }
    }
    val collectionsMap = remember(visibleCollections) {
        visibleCollections.associateBy { "collection_${it.id}" }
    }
    val sectionsMap = remember(homeUiState.sections) {
        homeUiState.sections.associateBy(HomeCatalogSection::key)
    }
    val enabledHomeItems = remember(homeSettingsUiState.items) {
        homeSettingsUiState.items.filter { it.enabled }
    }
    val keyedEnabledHomeItems = remember(enabledHomeItems) {
        enabledHomeItems.withDuplicateSafeLazyKeys(HomeCatalogSettingsItem::key)
    }
    LaunchedEffect(
        watchedUiState.items,
        watchProgressUiState.entries,
    ) {
        resolveWatchedBadgesBulk(
            watchedItems = watchedUiState.items,
            progressEntries = watchProgressUiState.entries,
        )
    }
    val hasRenderableCollectionRows = remember(enabledHomeItems, collectionsMap) {
        enabledHomeItems.any { item ->
            item.isCollection && collectionsMap[item.key] != null
        }
    }
    val hasRenderableHomeRows = homeUiState.sections.isNotEmpty() || hasRenderableCollectionRows
    val showHeroSlot = shouldShowHomeHeroSlot(
        heroEnabled = homeSettingsUiState.heroEnabled,
        hasHeroItems = homeUiState.heroItems.isNotEmpty(),
        isResolvingHeroSources = isResolvingHeroSources,
        hasRenderableHomeRows = hasRenderableHomeRows,
    )
    val showHeroSkeleton = showHeroSlot &&
        homeUiState.heroItems.isEmpty() &&
        isResolvingHeroSources
    val isInitialHomeContentLoading = shouldShowInitialHomeLoading(
        hasRenderableHomeRows = hasRenderableHomeRows,
        addonManifestsLoading = addonManifestsLoading,
        homeCatalogLoading = homeUiState.isLoading,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val homeSectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)
        val posterCardStyle = rememberPosterCardStyleUiState()
        val nativeBottomNavigationOverlayHeight =
            if (LocalNuvioBottomNavigationOverlayPadding.current > 0.dp) {
                nuvioSafeBottomPadding()
            } else {
                0.dp
            }
        val mobileHeroBelowSectionHeightHint = remember(
            maxWidth.value,
            continueWatchingPreferences.isVisible,
            continueWatchingPreferences.style,
            hasContinueWatchingRows,
            continueWatchingLayout,
            posterCardStyle.widthDp,
            nativeBottomNavigationOverlayHeight,
        ) {
            if (
                maxWidth.value < 600f &&
                continueWatchingPreferences.isVisible &&
                hasContinueWatchingRows
            ) {
                continueWatchingHeroViewportReserveHeight(
                    style = continueWatchingPreferences.style,
                    layout = continueWatchingLayout,
                    basePosterWidthDp = posterCardStyle.widthDp,
                ) + nativeBottomNavigationOverlayHeight
            } else {
                null
            }
        }

        val heroStretchState = rememberHeroStretchState(homeListState)
        val heroStretchModifier = if (showHeroSlot) {
            Modifier.nestedScroll(heroStretchState.nestedScrollConnection)
        } else {
            Modifier
        }

        NuvioScreen(
            modifier = Modifier.fillMaxSize().then(heroStretchModifier),
            horizontalPadding = 0.dp,
            topPadding = if (showHeroSlot) 0.dp else null,
            listState = homeListState,
        ) {
            if (showHeroSlot) {
                item {
                    when {
                        showHeroSkeleton -> HomeSkeletonHero(
                            modifier = Modifier,
                            viewportHeight = maxHeight,
                            mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                        )

                        homeUiState.heroItems.isNotEmpty() -> HomeHeroSection(
                            items = homeUiState.heroItems,
                            modifier = Modifier,
                            viewportHeight = maxHeight,
                            mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                            listState = homeListState,
                            stretchPx = { heroStretchState.stretchPx },
                            onItemClick = onPosterClick,
                        )

                        else -> HomeHeroReservedSpace(
                            modifier = Modifier,
                            viewportHeight = maxHeight,
                            mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                        )
                    }
                }
            }

            when {
                isInitialHomeContentLoading -> {
                    homeContinueWatchingSections(
                        preferences = continueWatchingPreferences,
                        continueWatchingItems = continueWatchingItems,
                        upcomingItems = upcomingItems,
                        dataSourceKey = effectiveWatchProgressSource,
                        sectionPadding = homeSectionPadding,
                        layout = continueWatchingLayout,
                        continueWatchingListState = continueWatchingListState,
                        upcomingListState = upcomingListState,
                        onItemClick = onContinueWatchingClick,
                        onItemLongPress = onContinueWatchingLongPress,
                        disintegrationRequest = continueWatchingDisintegrationRequest,
                    )
                    items(3) {
                        HomeSkeletonRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                !hasActiveAddons && !hasRenderableCollectionRows -> {
                    homeContinueWatchingSections(
                        preferences = continueWatchingPreferences,
                        continueWatchingItems = continueWatchingItems,
                        upcomingItems = upcomingItems,
                        dataSourceKey = effectiveWatchProgressSource,
                        sectionPadding = homeSectionPadding,
                        layout = continueWatchingLayout,
                        continueWatchingListState = continueWatchingListState,
                        upcomingListState = upcomingListState,
                        onItemClick = onContinueWatchingClick,
                        onItemLongPress = onContinueWatchingLongPress,
                        disintegrationRequest = continueWatchingDisintegrationRequest,
                    )
                    item {
                        when {
                            networkStatusUiState.isOfflineLike && addonManifestErrorMessage != null -> {
                                NuvioNetworkOfflineCard(
                                    condition = networkStatusUiState.condition,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    onRetry = {
                                        NetworkStatusRepository.requestRefresh(force = true)
                                        AddonRepository.refreshAll()
                                    },
                                )
                            }

                            addonManifestErrorMessage != null -> {
                                HomeEmptyStateCard(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    title = stringResource(Res.string.home_load_failed_title),
                                    message = addonManifestErrorMessage,
                                    actionLabel = stringResource(Res.string.action_retry),
                                    onActionClick = {
                                        NetworkStatusRepository.requestRefresh(force = true)
                                        AddonRepository.refreshAll()
                                    },
                                )
                            }

                            else -> {
                                HomeEmptyStateCard(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    title = stringResource(Res.string.compose_search_empty_no_active_addons_title),
                                    message = stringResource(Res.string.home_empty_no_active_addons_message),
                                )
                            }
                        }
                    }
                }

                homeUiState.sections.isEmpty() && homeUiState.heroItems.isEmpty() &&
                    (!continueWatchingPreferences.isVisible || !hasContinueWatchingRows) &&
                    !hasRenderableCollectionRows -> {
                    item {
                        val loadFailed = !homeUiState.errorMessage.isNullOrBlank()
                        if (networkStatusUiState.isOfflineLike && loadFailed) {
                            NuvioNetworkOfflineCard(
                                condition = networkStatusUiState.condition,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onRetry = {
                                    NetworkStatusRepository.requestRefresh(force = true)
                                    HomeRepository.refresh(addonsUiState.addons.enabledAddons(), force = true)
                                },
                            )
                        } else {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = stringResource(
                                    if (loadFailed) {
                                        Res.string.home_load_failed_title
                                    } else {
                                        Res.string.home_empty_no_rows_title
                                    },
                                ),
                                message = homeUiState.errorMessage
                                    ?: stringResource(Res.string.home_empty_no_rows_message),
                                actionLabel = if (loadFailed) stringResource(Res.string.action_retry) else null,
                                onActionClick = if (loadFailed) {
                                    {
                                        NetworkStatusRepository.requestRefresh(force = true)
                                        HomeRepository.refresh(addonsUiState.addons.enabledAddons(), force = true)
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                else -> {
                    homeContinueWatchingSections(
                        preferences = continueWatchingPreferences,
                        continueWatchingItems = continueWatchingItems,
                        upcomingItems = upcomingItems,
                        dataSourceKey = effectiveWatchProgressSource,
                        sectionPadding = homeSectionPadding,
                        layout = continueWatchingLayout,
                        continueWatchingListState = continueWatchingListState,
                        upcomingListState = upcomingListState,
                        onItemClick = onContinueWatchingClick,
                        onItemLongPress = onContinueWatchingLongPress,
                        disintegrationRequest = continueWatchingDisintegrationRequest,
                    )

                    keyedEnabledHomeItems.forEach { keyedSettingsItem ->
                        val settingsItem = keyedSettingsItem.value
                        if (settingsItem.isCollection) {
                            val collection = collectionsMap[settingsItem.key]
                            if (collection != null) {
                                item(key = keyedSettingsItem.lazyKey) {
                                    HomeCollectionRowSection(
                                        collection = collection,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        sectionPadding = homeSectionPadding,
                                        animateGifs = animateCollectionGifs,
                                        onFolderClick = onFolderClick,
                                    )
                                }
                            }
                        } else {
                            val section = sectionsMap[settingsItem.key]
                            if (section != null && section.items.isNotEmpty()) {
                                item(key = keyedSettingsItem.lazyKey) {
                                    HomeCatalogRowSection(
                                        section = section,
                                        entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        sectionPadding = homeSectionPadding,
                                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                        watchedKeys = watchedUiState.watchedKeys,
                                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                                        onPosterClick = onPosterClick,
                                        onPosterLongClick = onPosterLongClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.homeContinueWatchingSections(
    preferences: ContinueWatchingPreferencesUiState,
    continueWatchingItems: List<ContinueWatchingItem>,
    upcomingItems: List<ContinueWatchingItem>,
    dataSourceKey: WatchProgressSource,
    sectionPadding: Dp,
    layout: ContinueWatchingLayout,
    continueWatchingListState: LazyListState,
    upcomingListState: LazyListState,
    onItemClick: ((ContinueWatchingItem) -> Unit)?,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)?,
    disintegrationRequest: DisintegrationRequest<String>?,
) {
    if (!preferences.isVisible) return

    if (continueWatchingItems.isNotEmpty()) {
        item(key = HOME_CONTINUE_WATCHING_SECTION_KEY) {
            HomeContinueWatchingSection(
                items = continueWatchingItems,
                dataSourceKey = dataSourceKey,
                style = preferences.style,
                useEpisodeThumbnails = preferences.useEpisodeThumbnails,
                blurNextUp = preferences.blurNextUp,
                modifier = Modifier.padding(bottom = HomeContinueWatchingSectionBottomPadding),
                sectionPadding = sectionPadding,
                layout = layout,
                listState = continueWatchingListState,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
                disintegrationRequest = disintegrationRequest,
            )
        }
    }

    if (upcomingItems.isNotEmpty()) {
        item(key = HOME_UPCOMING_SECTION_KEY) {
            HomeContinueWatchingSection(
                items = upcomingItems,
                dataSourceKey = dataSourceKey,
                style = preferences.style,
                useEpisodeThumbnails = preferences.useEpisodeThumbnails,
                blurNextUp = preferences.blurNextUp,
                modifier = Modifier.padding(bottom = HomeContinueWatchingSectionBottomPadding),
                title = stringResource(Res.string.upcoming_section_title),
                sectionPadding = sectionPadding,
                layout = layout,
                listState = upcomingListState,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
                disintegrationRequest = disintegrationRequest,
            )
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
private const val HOME_CONTINUE_WATCHING_SECTION_KEY = "home_continue_watching"
private const val HOME_UPCOMING_SECTION_KEY = "home_upcoming"
internal const val HomeContinueWatchingMaxRecentProgressItems = 300
internal const val HomeNextUpInitialResolutionLimit = 32
private const val NEXT_UP_RESOLUTION_CONCURRENCY = 4
private const val MAX_NEXT_UP_RESOLUTION_RETRIES = 3
private const val NEXT_UP_RESOLUTION_RETRY_BASE_DELAY_MS = 1_500L

private fun String.isHomeSeriesLikeType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")

internal data class HomeNextUpResolutionPlan(
    val initialCandidates: List<CompletedSeriesCandidate>,
    val deferredCandidates: List<CompletedSeriesCandidate>,
)

internal fun planHomeNextUpResolutionCandidates(
    candidates: List<CompletedSeriesCandidate>,
): HomeNextUpResolutionPlan =
    HomeNextUpResolutionPlan(
        initialCandidates = candidates.take(HomeNextUpInitialResolutionLimit),
        deferredCandidates = candidates.drop(HomeNextUpInitialResolutionLimit),
    )

internal fun filterEntriesForContinueWatchingWindow(
    entries: List<WatchProgressEntry>,
    cutoffEpochMs: Long?,
): List<WatchProgressEntry> = cutoffEpochMs
    ?.let { cutoff -> entries.filter { entry -> entry.lastUpdatedEpochMs >= cutoff } }
    ?: entries

internal fun filterHomeNextUpCandidatesForContinueWatchingWindow(
    candidates: List<CompletedSeriesCandidate>,
    cutoffEpochMs: Long?,
): List<CompletedSeriesCandidate> = cutoffEpochMs
    ?.let { cutoff -> candidates.filter { candidate -> candidate.markedAtEpochMs >= cutoff } }
    ?: candidates

internal fun buildHomeNextUpSeedCandidates(
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    providerOwnsCompletedHistory: Boolean,
    preferFurthestEpisode: Boolean,
    nowEpochMs: Long,
    shouldUseProgressSeed: (WatchProgressEntry, Long) -> Boolean = { entry, _ ->
        entry.shouldUseAsCompletedSeedForContinueWatching()
    },
    isContentHidden: (String) -> Boolean = { false },
): List<CompletedSeriesCandidate> {
    val progressSeeds = progressEntries
        .asSequence()
        .filterNot { entry -> isContentHidden(entry.parentMetaId) }
        .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
        .filter { entry -> entry.seasonNumber != null && entry.episodeNumber != null && entry.seasonNumber != 0 }
        .filter { entry -> !isMalformedNextUpSeedContentId(entry.parentMetaId) }
        .filter { entry -> shouldUseProgressSeed(entry, nowEpochMs) }
        .toList()
    val watchedSeeds = if (providerOwnsCompletedHistory) {
        emptyList()
    } else {
        watchedItems.filter { item ->
            !isContentHidden(item.id) &&
                item.type.isSeriesTypeForContinueWatching() &&
                item.season != null &&
                item.episode != null &&
                item.season != 0 &&
                !isMalformedNextUpSeedContentId(item.id)
        }
    }

    return WatchingState.latestCompletedBySeries(
        progressEntries = progressSeeds,
        watchedItems = watchedSeeds,
        preferFurthestEpisode = preferFurthestEpisode,
    ).mapNotNull { (content, completed) ->
        if (!content.type.isSeriesTypeForContinueWatching()) return@mapNotNull null
        if (completed.seasonNumber == 0) return@mapNotNull null
        if (isMalformedNextUpSeedContentId(content.id)) return@mapNotNull null
        CompletedSeriesCandidate(
            content = content,
            seasonNumber = completed.seasonNumber,
            episodeNumber = completed.episodeNumber,
            markedAtEpochMs = completed.markedAtEpochMs,
        )
    }.sortedWith(
        compareByDescending<CompletedSeriesCandidate> { candidate -> candidate.markedAtEpochMs }
            .thenByDescending { candidate -> candidate.seasonNumber }
            .thenByDescending { candidate -> candidate.episodeNumber },
    )
}

internal fun filterNextUpItemsByCurrentSeeds(
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    activeSeedContentIds: Set<String>,
    currentSeedByContentId: Map<String, Pair<Int, Int>>,
    shouldDropItemsWithoutActiveSeed: Boolean,
): Map<String, Pair<Long, ContinueWatchingItem>> =
    nextUpItemsBySeries.filter { (contentId, pair) ->
        if (shouldDropItemsWithoutActiveSeed && contentId !in activeSeedContentIds) {
            return@filter false
        }
        val item = pair.second
        val currentSeed = currentSeedByContentId[contentId] ?: return@filter true
        item.nextUpSeedSeasonNumber == currentSeed.first &&
            item.nextUpSeedEpisodeNumber == currentSeed.second
    }

internal fun isHomeNextUpSeedSourceLoaded(
    providerOwnsCompletedHistory: Boolean,
    hasLoadedRemoteProgress: Boolean,
    hasLoadedWatchedItems: Boolean,
    hasLoadedRemoteWatchedItems: Boolean,
): Boolean = hasLoadedRemoteProgress && (
    providerOwnsCompletedHistory || (hasLoadedWatchedItems && hasLoadedRemoteWatchedItems)
)

internal fun cachedNextUpHasAired(
    cached: CachedNextUpItem,
    nowEpochMs: Long = WatchProgressClock.nowEpochMs(),
): Boolean =
    com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(cached.released)
        ?.let { releaseEpochMs -> nowEpochMs >= releaseEpochMs }
        ?: cached.hasAired

internal fun hasHomeNextUpSeedChangedFromCache(
    currentSeason: Int,
    currentEpisode: Int,
    cachedSeason: Int?,
    cachedEpisode: Int?,
): Boolean {
    if (cachedSeason == null || cachedEpisode == null) return false
    return currentSeason != cachedSeason || currentEpisode != cachedEpisode
}

internal fun hasUsableHomeNextUpMetadata(item: ContinueWatchingItem): Boolean {
    val hasResolvedTitle = item.title.isNotBlank() &&
        !item.title.equals(item.parentMetaId, ignoreCase = true)
    val hasArtwork = listOf(
        item.imageUrl,
        item.poster,
        item.background,
        item.episodeThumbnail,
    ).any { value -> !value.isNullOrBlank() }
    return hasResolvedTitle && hasArtwork
}

internal fun mergeHomeNextUpItemsWithCache(
    resolvedItems: Map<String, Pair<Long, ContinueWatchingItem>>,
    cachedItems: Map<String, Pair<Long, ContinueWatchingItem>>,
    conclusivelyProcessedContentIds: Set<String>,
): Map<String, Pair<Long, ContinueWatchingItem>> {
    val retainedCachedItems = cachedItems.filterKeys { contentId ->
        contentId !in conclusivelyProcessedContentIds || contentId in resolvedItems
    }
    val resolvedItemsWithCacheFallback = resolvedItems.mapValues { (contentId, pair) ->
        pair.first to pair.second.withFallbackMetadata(cachedItems[contentId]?.second)
    }
    return retainedCachedItems + resolvedItemsWithCacheFallback
}

internal enum class HomeNextUpCandidateMetadataOutcome {
    Ready,
    Dismissed,
    Transient,
}

internal data class HomeNextUpCandidateMetadataDecision(
    val item: ContinueWatchingItem,
    val outcome: HomeNextUpCandidateMetadataOutcome,
)

internal fun classifyHomeNextUpCandidateMetadata(
    freshItem: ContinueWatchingItem,
    cachedFallbackItem: ContinueWatchingItem?,
    dismissedNextUpKeys: Set<String>,
): HomeNextUpCandidateMetadataDecision {
    val mergedItem = freshItem.withFallbackMetadata(cachedFallbackItem)
    val dismissKey = nextUpDismissKey(
        mergedItem.parentMetaId,
        mergedItem.nextUpSeedSeasonNumber,
        mergedItem.nextUpSeedEpisodeNumber,
    )
    val outcome = when {
        dismissKey in dismissedNextUpKeys -> HomeNextUpCandidateMetadataOutcome.Dismissed
        hasUsableHomeNextUpMetadata(mergedItem) -> HomeNextUpCandidateMetadataOutcome.Ready
        else -> HomeNextUpCandidateMetadataOutcome.Transient
    }
    return HomeNextUpCandidateMetadataDecision(
        item = mergedItem,
        outcome = outcome,
    )
}

private suspend fun resolveHomeNextUpCandidate(
    completedEntry: CompletedSeriesCandidate,
    watchProgressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    cachedFallbackItem: ContinueWatchingItem?,
    todayIsoDate: String,
    preferFurthestEpisode: Boolean,
    showUnairedNextUp: Boolean,
    dismissedNextUpKeys: Set<String>,
    providerOwnsCompletedHistory: Boolean,
): HomeNextUpResolutionAttempt {
    val contentId = completedEntry.content.id
    val meta = try {
        MetaDetailsRepository.fetch(
            type = completedEntry.content.type,
            id = contentId,
        )
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }
    if (meta == null) {
        return HomeNextUpResolutionAttempt.transientFailure()
    }

    val resolvedProgressEntries = WatchProgressRepository.prepareNextUpProgressEntries(
        entries = watchProgressEntries,
        contentId = contentId,
    )
    val resolvedWatchedItems = watchedItems
    val resolvedWatchedKeys = resolvedWatchedItems.mapTo(linkedSetOf()) { item ->
        watchedItemKey(item.type, item.id, item.season, item.episode)
    }

    if (!providerOwnsCompletedHistory) {
        WatchedRepository.reconcileFullyWatchedSeriesState(
            meta = meta,
            todayIsoDate = todayIsoDate,
            isEpisodeWatched = { episode ->
                watchedItemKey(meta.type, meta.id, episode.season, episode.episode) in resolvedWatchedKeys
            },
            isEpisodeCompleted = { episode ->
                val playbackId = meta.episodePlaybackId(episode)
                resolvedProgressEntries.any { entry ->
                    entry.videoId == playbackId && entry.isEffectivelyCompleted
                }
            },
        )
    }

    val action = meta.seriesPrimaryAction(
        content = completedEntry.content,
        entries = resolvedProgressEntries,
        watchedItems = resolvedWatchedItems,
        todayIsoDate = todayIsoDate,
        preferFurthestEpisode = preferFurthestEpisode,
        showUnairedNextUp = showUnairedNextUp,
    )
    if (action == null) {
        return HomeNextUpResolutionAttempt.conclusiveNone()
    }
    if (action.resumePositionMs != null) {
        return HomeNextUpResolutionAttempt.conclusiveNone()
    }

    val nextEpisode = meta.videoForSeriesAction(action)
    if (nextEpisode == null) {
        return HomeNextUpResolutionAttempt.conclusiveNone()
    }
    val metadataDecision = classifyHomeNextUpCandidateMetadata(
        freshItem = completedEntry.toContinueWatchingSeed(meta)
            .toUpNextContinueWatchingItem(nextEpisode),
        cachedFallbackItem = cachedFallbackItem,
        dismissedNextUpKeys = dismissedNextUpKeys,
    )
    val item = metadataDecision.item
    if (metadataDecision.outcome == HomeNextUpCandidateMetadataOutcome.Dismissed) {
        return HomeNextUpResolutionAttempt.conclusiveNone()
    }
    if (metadataDecision.outcome == HomeNextUpCandidateMetadataOutcome.Transient) {
        return HomeNextUpResolutionAttempt.transientFailure()
    }

    val sortTimestamp = if (item.isReleaseAlert) {
        com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(item.released) ?: completedEntry.markedAtEpochMs
    } else {
        completedEntry.markedAtEpochMs
    }
    return HomeNextUpResolutionAttempt.success(
        contentId to (sortTimestamp to item),
    )
}

private fun MetaDetails.videoForSeriesAction(action: SeriesPrimaryAction): MetaVideo? {
    if (action.seasonNumber != null && action.episodeNumber != null) {
        videos.firstOrNull { video ->
            video.season == action.seasonNumber &&
                video.episode == action.episodeNumber
        }?.let { return it }
    }
    return videos.firstOrNull { video ->
        com.nuvio.app.features.watchprogress.buildPlaybackVideoId(
            parentMetaId = id,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        ) == action.videoId || video.id == action.videoId
    }
}

private fun shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgressEntry,
    latestCompletedAt: Long?,
): Boolean {
    if (!progress.shouldTreatAsInProgressForContinueWatching()) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastUpdatedEpochMs >= latestCompletedAt
}

internal fun buildHomeContinueWatchingItems(
    visibleEntries: List<WatchProgressEntry>,
    cachedInProgressByProgressKey: Map<String, ContinueWatchingItem> = emptyMap(),
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    nextUpSuppressedSeriesIds: Set<String>? = null,
    sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    todayIsoDate: String = "",
    cloudLibraryUiState: CloudLibraryUiState? = null,
): List<ContinueWatchingItem> {
    val suppressedSeriesIds = nextUpSuppressedSeriesIds
        ?: visibleEntries
            .asSequence()
            .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
            .map { entry -> entry.parentMetaId }
            .filter(String::isNotBlank)
            .toSet()

    val candidates = buildList {
        addAll(
            visibleEntries.map { entry ->
                val liveItem = entry.toContinueWatchingItem()
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
                    item = liveItem
                        .withFallbackMetadata(
                            fallback = cachedInProgressByProgressKey[entry.resolvedProgressKey()],
                            preserveFallbackPlaybackIdentity = true,
                        )
                        .withCloudLibraryMetadata(cloudLibraryUiState),
                    isProgressEntry = true,
                )
            },
        )
        addAll(
            nextUpItemsBySeries.values.mapNotNull { (lastUpdatedEpochMs, item) ->
                if (item.parentMetaId in suppressedSeriesIds) return@mapNotNull null
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = lastUpdatedEpochMs,
                    item = item,
                    isProgressEntry = false,
                )
            },
        )
    }

    // Deduplicate by series/content id first (order-stable)
    val seen = mutableSetOf<String>()
    val deduplicated = candidates
        .sortedWith(
            compareByDescending<HomeContinueWatchingCandidate> { it.lastUpdatedEpochMs }
                .thenByDescending { it.isProgressEntry },
        )
        .filter { candidate -> candidate.item.shouldDisplayInContinueWatching() }
        .filter { candidate ->
            val key = candidate.item.parentMetaId.ifBlank { candidate.item.videoId }
            seen.add(key)
        }

    return when (sortMode) {
        ContinueWatchingSortMode.DEFAULT,
        ContinueWatchingSortMode.SPLIT_UPCOMING,
        -> deduplicated.map(HomeContinueWatchingCandidate::item)
        ContinueWatchingSortMode.STREAMING_STYLE -> applyStreamingStyleSort(deduplicated, todayIsoDate)
    }
}

/**
 * Splits unaired next-up episodes into a dedicated row when [ContinueWatchingSortMode.SPLIT_UPCOMING]
 * is active. The main row keeps its recency ordering, while Upcoming is ordered by the nearest
 * release instant with unknown dates last.
 */
internal fun splitUpcomingItems(
    items: List<ContinueWatchingItem>,
    mode: ContinueWatchingSortMode,
    nowEpochMs: Long = WatchProgressClock.nowEpochMs(),
): Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>> {
    if (mode != ContinueWatchingSortMode.SPLIT_UPCOMING) {
        return items to emptyList()
    }

    val (upcoming, main) = items.partition { item ->
        item.isNextUp && parseReleaseDateToEpochMs(item.released)
            ?.let { releaseEpochMs -> releaseEpochMs > nowEpochMs } == true
    }
    val sortedUpcoming = upcoming.sortedWith { first, second ->
        val firstRelease = parseReleaseDateToEpochMs(first.released)
        val secondRelease = parseReleaseDateToEpochMs(second.released)
        when {
            firstRelease == null && secondRelease == null -> 0
            firstRelease == null -> 1
            secondRelease == null -> -1
            else -> firstRelease.compareTo(secondRelease)
        }
    }
    return main to sortedUpcoming
}

private fun applyStreamingStyleSort(
    candidates: List<HomeContinueWatchingCandidate>,
    todayIsoDate: String,
): List<ContinueWatchingItem> {
    val (released, unreleased) = candidates.partition { candidate ->
        val item = candidate.item
        if (!item.isNextUp) {
            true // in-progress items are always "released"
        } else {
            val itemReleased = item.released
            if (itemReleased.isNullOrBlank() || todayIsoDate.isBlank()) {
                true // no date info → treat as released
            } else {
                isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = itemReleased)
            }
        }
    }

    // Released: most recently watched first (already sorted by dedup pass)
    val sortedReleased = released.map(HomeContinueWatchingCandidate::item)

    // Unaired: soonest air date first; unknown dates go to the end
    val sortedUnreleased = unreleased
        .sortedWith { a, b ->
            val dateA = a.item.released?.takeIf { it.isNotBlank() }
            val dateB = b.item.released?.takeIf { it.isNotBlank() }
            when {
                dateA == null && dateB == null -> 0
                dateA == null -> 1
                dateB == null -> -1
                else -> dateA.compareTo(dateB)
            }
        }
        .map(HomeContinueWatchingCandidate::item)

    return sortedReleased + sortedUnreleased
}

internal data class CompletedSeriesCandidate(
    val content: WatchingContentRef,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

private data class HomeContinueWatchingCandidate(
    val lastUpdatedEpochMs: Long,
    val item: ContinueWatchingItem,
    val isProgressEntry: Boolean,
)

private data class HomeNextUpCandidateResolution(
    val candidate: CompletedSeriesCandidate,
    val attempt: HomeNextUpResolutionAttempt,
)

private data class HomeNextUpResolutionAttempt(
    val resolved: Pair<String, Pair<Long, ContinueWatchingItem>>?,
    val isConclusive: Boolean,
) {
    companion object {
        fun success(
            resolved: Pair<String, Pair<Long, ContinueWatchingItem>>,
        ): HomeNextUpResolutionAttempt =
            HomeNextUpResolutionAttempt(
                resolved = resolved,
                isConclusive = true,
            )

        fun conclusiveNone(): HomeNextUpResolutionAttempt =
            HomeNextUpResolutionAttempt(
                resolved = null,
                isConclusive = true,
            )

        fun transientFailure(): HomeNextUpResolutionAttempt =
            HomeNextUpResolutionAttempt(
                resolved = null,
                isConclusive = false,
            )
    }
}

private fun saveContinueWatchingSnapshots(
    profileId: Int,
    source: WatchProgressSource,
    cacheGeneration: Int,
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    visibleContinueWatchingEntries: List<WatchProgressEntry>,
    todayIsoDate: String,
    seedLastWatchedMap: Map<String, Long>,
) {
    val nextUpCache = nextUpItemsBySeries.mapNotNull { (contentId, pair) ->
        val item = pair.second
        CachedNextUpItem(
            contentId = contentId,
            contentType = item.parentMetaType,
            name = item.title,
            poster = item.poster,
            backdrop = item.background,
            logo = item.logo,
            videoId = item.videoId,
            season = item.seasonNumber,
            episode = item.episodeNumber,
            episodeTitle = item.episodeTitle,
            episodeThumbnail = item.episodeThumbnail,
            pauseDescription = item.pauseDescription,
            released = item.released,
            hasAired = item.released?.let { released ->
                isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = released)
            } ?: true,
            lastWatched = seedLastWatchedMap[contentId] ?: pair.first,
            sortTimestamp = pair.first,
            seedSeason = item.nextUpSeedSeasonNumber,
            seedEpisode = item.nextUpSeedEpisodeNumber,
            isReleaseAlert = item.isReleaseAlert,
            isNewSeasonRelease = item.isNewSeasonRelease,
        )
    }
    val inProgressCache = buildHomeInProgressCacheSnapshot(
        visibleEntries = visibleContinueWatchingEntries,
        cachedEntries = ContinueWatchingEnrichmentCache.getInProgressSnapshot(
            profileId = profileId,
            source = source,
        ),
    )
    ContinueWatchingEnrichmentCache.saveSnapshots(
        profileId = profileId,
        source = source,
        generation = cacheGeneration,
        nextUp = nextUpCache,
        inProgress = inProgressCache,
    )
}

internal fun buildHomeInProgressCacheSnapshot(
    visibleEntries: List<WatchProgressEntry>,
    cachedEntries: List<CachedInProgressItem>,
): List<CachedInProgressItem> {
    val cachedByProgressKey = cachedEntries.associateBy(CachedInProgressItem::resolvedProgressKey)
    return visibleEntries.map { entry ->
        val item = entry
            .toContinueWatchingItem()
            .withFallbackMetadata(
                fallback = cachedByProgressKey[entry.resolvedProgressKey()]?.toContinueWatchingItem(),
                preserveFallbackPlaybackIdentity = true,
            )
        CachedInProgressItem(
            contentId = entry.parentMetaId,
            contentType = entry.contentType,
            name = item.title,
            poster = item.poster,
            backdrop = item.background,
            logo = item.logo,
            videoId = entry.videoId,
            season = entry.seasonNumber,
            episode = entry.episodeNumber,
            episodeTitle = item.episodeTitle,
            episodeThumbnail = item.episodeThumbnail,
            pauseDescription = item.pauseDescription,
            position = entry.lastPositionMs,
            duration = entry.durationMs,
            lastWatched = entry.lastUpdatedEpochMs,
            progressPercent = entry.progressPercent,
            progressKey = entry.resolvedProgressKey(),
        )
    }
}

private fun CompletedSeriesCandidate.toContinueWatchingSeed(meta: com.nuvio.app.features.details.MetaDetails) =
    WatchProgressEntry(
        contentType = content.type,
        parentMetaId = content.id,
        parentMetaType = content.type,
        videoId = "${content.id}:${seasonNumber}:${episodeNumber}",
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = markedAtEpochMs,
        isCompleted = true,
    )

private fun ContinueWatchingItem.shouldDisplayInContinueWatching(): Boolean =
    isNextUp || progressFraction < 0.995f

private fun CachedNextUpItem.toContinueWatchingItem(): ContinueWatchingItem? {
    val alertState = com.nuvio.app.features.watchprogress.calculateReleaseAlertState(
        seedLastUpdatedEpochMs = lastWatched,
        seedSeasonNumber = seedSeason,
        nextSeasonNumber = season,
        releasedIso = released,
    )
    val resolvedPoster = poster.nonBlankOrNull()
    val resolvedBackdrop = backdrop.nonBlankOrNull()
    val resolvedEpisodeThumbnail = episodeThumbnail.nonBlankOrNull()
    return ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = name,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
        ),
        imageUrl = resolvedEpisodeThumbnail ?: resolvedBackdrop ?: resolvedPoster,
        logo = logo.nonBlankOrNull(),
        poster = resolvedPoster,
        background = resolvedBackdrop,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle.nonBlankOrNull(),
        episodeThumbnail = resolvedEpisodeThumbnail,
        pauseDescription = pauseDescription.nonBlankOrNull(),
        released = released.nonBlankOrNull(),
        isNextUp = true,
        nextUpSeedSeasonNumber = seedSeason,
        nextUpSeedEpisodeNumber = seedEpisode,
        resumePositionMs = 0L,
        resumeProgressFraction = null,
        durationMs = 0L,
        progressFraction = 0f,
        isReleaseAlert = alertState.isReleaseAlert,
        isNewSeasonRelease = alertState.isNewSeasonRelease,
    )
}

private fun CachedInProgressItem.toContinueWatchingItem(): ContinueWatchingItem {
    val explicitResumeProgressFraction = progressPercent
        ?.takeIf { duration <= 0L && it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val normalizedProgressFraction = progressPercent
        ?.let { (it / 100f).coerceIn(0f, 1f) }
        ?: if (duration > 0L) {
            (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val resolvedPoster = poster.nonBlankOrNull()
    val resolvedBackdrop = backdrop.nonBlankOrNull()
    val resolvedEpisodeThumbnail = episodeThumbnail.nonBlankOrNull()

    return ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = name,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
        ),
        imageUrl = resolvedEpisodeThumbnail ?: resolvedBackdrop ?: resolvedPoster,
        logo = logo.nonBlankOrNull(),
        poster = resolvedPoster,
        background = resolvedBackdrop,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle.nonBlankOrNull(),
        episodeThumbnail = resolvedEpisodeThumbnail,
        pauseDescription = pauseDescription.nonBlankOrNull(),
        isNextUp = false,
        nextUpSeedSeasonNumber = null,
        nextUpSeedEpisodeNumber = null,
        resumePositionMs = if (explicitResumeProgressFraction != null) 0L else position,
        resumeProgressFraction = explicitResumeProgressFraction,
        durationMs = duration,
        progressFraction = normalizedProgressFraction,
    )
}

private fun ContinueWatchingItem.withFallbackMetadata(
    fallback: ContinueWatchingItem?,
    preserveFallbackPlaybackIdentity: Boolean = false,
): ContinueWatchingItem {
    val nonBlankFallbackTitle = fallback?.title?.takeIf { it.isNotBlank() }
    val fallbackHasPlaceholderTitle = fallback?.hasPlaceholderHomeTitle() == true
    val fallbackTitle = nonBlankFallbackTitle
        ?.takeUnless { fallbackHasPlaceholderTitle }

    return copy(
        title = when {
            title.isBlank() && nonBlankFallbackTitle != null -> nonBlankFallbackTitle
            hasPlaceholderHomeTitle() && fallbackTitle != null -> fallbackTitle
            else -> title
        },
        subtitle = when {
            subtitle.isBlank() -> fallback?.subtitle?.takeIf { it.isNotBlank() }.orEmpty()
            preserveFallbackPlaybackIdentity && !fallback?.subtitle.isNullOrBlank() -> fallback.subtitle
            else -> subtitle
        },
        imageUrl = imageUrl.orNonBlank(fallback?.imageUrl),
        logo = logo.orNonBlank(fallback?.logo),
        poster = poster.orNonBlank(fallback?.poster),
        background = background.orNonBlank(fallback?.background),
        videoId = if (preserveFallbackPlaybackIdentity) {
            fallback?.videoId?.takeIf { it.isNotBlank() } ?: videoId
        } else {
            videoId
        },
        episodeTitle = episodeTitle.orNonBlank(fallback?.episodeTitle),
        episodeThumbnail = episodeThumbnail.orNonBlank(fallback?.episodeThumbnail),
        pauseDescription = pauseDescription.orNonBlank(fallback?.pauseDescription),
        released = released.orNonBlank(fallback?.released),
    )
}

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

private fun String?.orNonBlank(fallback: String?): String? =
    nonBlankOrNull() ?: fallback.nonBlankOrNull()

private fun ContinueWatchingItem.withCloudLibraryMetadata(
    cloudLibraryUiState: CloudLibraryUiState?,
): ContinueWatchingItem {
    if (!isCloudLibraryContinueWatchingItem() || cloudLibraryUiState == null) return this
    val target = cloudLibraryUiState.findPlaybackTargetForProgress(
        contentId = parentMetaId,
        videoId = videoId,
    ) ?: return this
    val fileName = target.file.name.trim().takeIf { it.isNotBlank() }
        ?: target.item.name.trim().takeIf { it.isNotBlank() }
        ?: return this
    return copy(
        title = fileName,
        pauseDescription = pauseDescription
            ?: target.item.name.takeIf { itemName -> itemName.isNotBlank() && itemName != fileName },
    )
}

private fun ContinueWatchingItem.hasPlaceholderHomeTitle(): Boolean {
    val normalizedTitle = title.trim()
    return normalizedTitle.equals(parentMetaId, ignoreCase = true) ||
        (isCloudLibraryContinueWatchingItem() && normalizedTitle.equals(videoId, ignoreCase = true))
}

private fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private fun WatchProgressEntry.isCloudLibraryProgressEntry(): Boolean =
    contentType.equals(CloudLibraryContentType, ignoreCase = true) ||
        parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)
