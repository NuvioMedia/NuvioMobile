package com.nuvio.app

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.DisintegrationRequestController
import com.nuvio.app.core.ui.NativeTabBridge
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioContinueWatchingActionSheet
import com.nuvio.app.core.ui.NuvioFloatingPrompt
import com.nuvio.app.core.ui.NuvioPosterZoomActionOverlay
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomAnchorHolder
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.PosterZoomOverlayExitAnimation
import com.nuvio.app.core.ui.TrackingListPickerDialog
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.localizedContinueWatchingSubtitle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.platformExitApp
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryPlaybackResult
import com.nuvio.app.features.cloud.CloudLibraryPlaybackTargetLookupResult
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.cloud.providerPosterUrl
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.buildAddonCatalogRefreshSignature
import com.nuvio.app.features.home.components.shouldBlurContinueWatchingArtwork
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.PendingTrackingMembershipRemoval
import com.nuvio.app.features.library.TrackingMembershipRemovalConfirmationHost
import com.nuvio.app.features.library.executeTrackingMembershipOperation
import com.nuvio.app.features.library.librarySectionItemKey
import com.nuvio.app.features.library.showTrackingMembershipRewriteFeedback
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.prepareExternalPlayerLaunch
import com.nuvio.app.features.player.rememberExternalPlayerLauncher
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.AccountSettingsScreen
import com.nuvio.app.features.settings.AddonsSettingsScreen
import com.nuvio.app.features.settings.ContinueWatchingSettingsScreen
import com.nuvio.app.features.settings.HomescreenSettingsScreen
import com.nuvio.app.features.settings.LicensesAttributionsSettingsScreen
import com.nuvio.app.features.settings.MetaScreenSettingsScreen
import com.nuvio.app.features.settings.PluginsSettingsScreen
import com.nuvio.app.features.settings.SupportersContributorsSettingsScreen
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingMembershipApplyResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.tracking.toggleTrackingLibraryMembership
import com.nuvio.app.features.updater.AppUpdaterHost
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.rememberAppUpdaterController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import com.nuvio.app.features.watchprogress.continueWatchingItemKey
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.navigation.*
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun MainAppContent(
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    showLaunchOverlay: Boolean = true,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    appGateController: AppGateController? = null,
    onRootContentReady: ((Boolean) -> Unit)? = null,
    onSwitchProfile: () -> Unit = {},
) {
        val navBackStack = rememberNavBackStack(navigationSavedStateConfiguration, initialRoute)
        val routeDisposalDecorator = remember {
            RouteDisposalNavEntryDecorator<NavKey> { key ->
                if (key is AppRoute) disposeRoute(key)
            }
        }
        val navController = remember(navBackStack, onNavigate, onGoBack, onReplace) {
            NuvioNavigator(
                backStack = navBackStack,
                onExternalNavigate = onNavigate,
                onExternalBack = onGoBack,
                onExternalReplace = onReplace,
            )
        }
        val appUpdaterController = rememberAppUpdaterController()
        if (ownsAppRuntime) {
            remember {
                EpisodeReleaseNotificationsRepository.ensureLoaded()
            }
            remember {
                CollectionSyncService.startObserving()
            }
            remember {
                ProfileSettingsSync.startObserving()
            }
        }
        val hapticFeedback = LocalHapticFeedback.current
        val focusManager = LocalFocusManager.current
        val uriHandler = LocalUriHandler.current
        val coroutineScope = rememberCoroutineScope()
        var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
        var searchFocusRequestCount by remember { mutableStateOf(0) }
        val homeScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val searchScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val searchListState = rememberLazyListState()
        val libraryScrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val settingsRootActionRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val currentRoute = navBackStack.lastOrNull() as? AppRoute
        val liquidGlassNativeTabBarEnabled by remember {
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled
        }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarSupported = remember { isLiquidGlassNativeTabBarSupported() }
        var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
        var selectedPosterActionTarget by remember { mutableStateOf<PosterActionTarget?>(null) }
        var selectedPosterAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        val posterOverlayHazeState = rememberHazeState()
        var selectedContinueWatchingForActions by remember { mutableStateOf<ContinueWatchingItem?>(null) }
        var selectedContinueWatchingZoomAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
        val libraryDisintegrationRequests = remember { DisintegrationRequestController<String>() }
        val continueWatchingDisintegrationRequests = remember { DisintegrationRequestController<String>() }
        var requestedSettingsPageName by rememberSaveable { mutableStateOf<String?>(null) }
        var showLibraryListPicker by remember { mutableStateOf(false) }
        var pickerItem by remember { mutableStateOf<LibraryItem?>(null) }
        var pickerTitle by remember { mutableStateOf("") }
        var pickerTabs by remember { mutableStateOf<List<TrackingLibraryTab>>(emptyList()) }
        var pickerMembership by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var pickerPending by remember { mutableStateOf(false) }
        var pickerError by remember { mutableStateOf<String?>(null) }
        var pendingTrackingRemoval by remember { mutableStateOf<PendingTrackingMembershipRemoval?>(null) }
        val trackingListsUpdateFailedMessage = stringResource(Res.string.tracking_lists_update_failed)
        val addonsUiState by remember {
            AddonRepository.initialize()
            AddonRepository.uiState
        }.collectAsStateWithLifecycle()
        val libraryUiState by remember {
            LibraryRepository.ensureLoaded()
            LibraryRepository.uiState
        }.collectAsStateWithLifecycle()
        val authState by AuthRepository.state.collectAsStateWithLifecycle()
        val openPosterActions: (PosterActionTarget) -> Unit = { target ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            focusManager.clearFocus(force = true)
            selectedPosterAnchor = PosterZoomAnchorHolder.consume()
            coroutineScope.launch {
                withFrameNanos { }
                selectedPosterActionTarget = target
            }
        }
        val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
        val launchOverlayProfile = profileState.activeProfile ?: profileState.profiles.firstOrNull()
    val playerSettingsUiState by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pSettingsUiState by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val downloadsUiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by remember {
        NetworkStatusRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadedProviderLabel = stringResource(Res.string.provider_downloaded)
    val externalPlayerNotConfiguredText = stringResource(Res.string.external_player_not_configured)
    val externalPlayerUnavailableText = stringResource(Res.string.external_player_unavailable)
    val externalPlayerFailedText = stringResource(Res.string.external_player_failed)
    val failedOpenBrowserText = stringResource(Res.string.settings_trakt_failed_open_browser)
    val cloudLibraryPlayFailedText = stringResource(Res.string.cloud_library_play_failed)
    val cloudLibraryPlayDisabledText = stringResource(Res.string.cloud_library_play_disabled)
    val cloudLibraryPlayNotConnectedText = stringResource(Res.string.cloud_library_play_not_connected)
    val nativeTabHomeTitle = stringResource(Res.string.compose_nav_home)
    val nativeTabSearchTitle = stringResource(Res.string.compose_nav_search)
    val nativeTabLibraryTitle = stringResource(Res.string.compose_nav_library)
    val nativeTabProfileTitle = stringResource(Res.string.compose_nav_profile)
    val nativeSwitchProfileTitle = stringResource(Res.string.compose_settings_root_switch_profile_title)
    val nativeAddProfileTitle = stringResource(Res.string.compose_profile_add_profile)
    val homescreenSettingsTitle = stringResource(Res.string.compose_settings_page_homescreen)
    val metaScreenSettingsTitle = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingSettingsTitle = stringResource(Res.string.compose_settings_page_continue_watching)
    val debridSettingsTitle = stringResource(Res.string.compose_settings_page_debrid)
    val downloadsSettingsTitle = stringResource(Res.string.compose_settings_root_downloads_title)
    val addonsSettingsTitle = stringResource(Res.string.compose_settings_page_addons)
    val pluginsSettingsTitle = stringResource(Res.string.compose_settings_page_plugins)
    val accountSettingsTitle = stringResource(Res.string.compose_settings_page_account)
    val supportersSettingsTitle = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesSettingsTitle = stringResource(Res.string.compose_settings_page_licenses_attributions)
    val collectionsTitle = stringResource(Res.string.collections_header)
    val newCollectionTitle = stringResource(Res.string.collections_new)
    val detailsFallbackTitle = stringResource(Res.string.meta_section_details_title)
    val isRemoteLibrarySource = libraryUiState.sourceMode != LibrarySourceMode.LOCAL
    val appContentGeneration = if (ownsAppRuntime && appGateController != null) {
        val generation by appGateController.contentGeneration.collectAsStateWithLifecycle()
        generation
    } else {
        0
    }
    var initialHomeReady by rememberSaveable(ownsAppRuntime, appContentGeneration) {
        mutableStateOf(!ownsAppRuntime)
    }
    var offlineLaunchRouteHandled by rememberSaveable { mutableStateOf(false) }
    var networkToastBaselineReady by rememberSaveable { mutableStateOf(false) }
    var lastNetworkToastCondition by rememberSaveable { mutableStateOf(NetworkCondition.Unknown.name) }
    var watchSourceReconnectPending by remember { mutableStateOf(false) }
    val homeCatalogRefreshKey = remember(addonsUiState.addons) {
        buildAddonCatalogRefreshSignature(addonsUiState.addons)
    }

    LaunchedEffect(appContentGeneration, homeCatalogRefreshKey) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val enabledAddons = addonsUiState.addons.enabledAddons()
        if (enabledAddons.isWaitingForFirstEnabledManifest()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
        HomeRepository.refresh(enabledAddons)
    }

    fun activateTab(tab: AppScreenTab) {
        if (useNativeNavigation && onActivate != null) {
            onActivate(tab)
        } else {
            selectedTab = tab
        }
    }

    fun handleRootTabClick(tab: AppScreenTab) {
        if (selectedTab != tab) {
            activateTab(tab)
            return
        }

        when (tab) {
            AppScreenTab.Home -> homeScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Search -> {
                searchFocusRequestCount++
                searchScrollToTopRequests.tryEmit(Unit)
            }
            AppScreenTab.Library -> libraryScrollToTopRequests.tryEmit(Unit)
            AppScreenTab.Settings -> settingsRootActionRequests.tryEmit(Unit)
        }
    }

    LaunchedEffect(
        liquidGlassNativeTabBarSupported,
        liquidGlassNativeTabBarEnabled,
        useNativeNavigation,
        currentRoute,
        selectedTab,
    ) {
        NativeTabBridge.requestedTabs.collectLatest { requestedTab ->
            val requestedAppTab = requestedTab.toAppScreenTab()
            if (
                useNativeNavigation &&
                currentRoute is TabsRoute &&
                requestedAppTab == selectedTab
            ) {
                handleRootTabClick(requestedAppTab)
            } else if (
                !useNativeNavigation &&
                liquidGlassNativeTabBarSupported &&
                liquidGlassNativeTabBarEnabled
            ) {
                handleRootTabClick(requestedAppTab)
            }
        }
    }

    LaunchedEffect(
        nativeTabHomeTitle,
        nativeTabSearchTitle,
        nativeTabLibraryTitle,
        nativeTabProfileTitle,
        nativeSwitchProfileTitle,
        nativeAddProfileTitle,
        onTabTitles,
    ) {
        NativeTabBridge.publishTabTitles(
            home = nativeTabHomeTitle,
            search = nativeTabSearchTitle,
            library = nativeTabLibraryTitle,
            profile = nativeTabProfileTitle,
        )
        onTabTitles?.invoke(
            nativeTabHomeTitle,
            nativeTabSearchTitle,
            nativeTabLibraryTitle,
            nativeTabProfileTitle,
            nativeSwitchProfileTitle,
            nativeAddProfileTitle,
        )
    }

    LaunchedEffect(selectedTab) {
        NativeTabBridge.publishSelectedTab(selectedTab.toNativeNavigationTab())
        if (selectedTab != AppScreenTab.Search) {
            searchFocusRequestCount = 0
        }
    }

    var profileSwitchLoading by remember { mutableStateOf(false) }

    val rootContentReady = !ownsAppRuntime || (initialHomeReady && !profileSwitchLoading)
    val launchOverlayVisible = ownsAppRuntime && showLaunchOverlay && !rootContentReady
    val launchOverlayState = remember(ownsAppRuntime, showLaunchOverlay) {
        MutableTransitionState(
            launchOverlayVisible,
        )
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        rootContentReady,
        ownsAppRuntime,
        onRootContentReady,
    ) {
        if (ownsAppRuntime) {
            onRootContentReady?.invoke(rootContentReady)
        }
    }

    LaunchedEffect(
        currentRoute,
        liquidGlassNativeTabBarSupported,
        liquidGlassNativeTabBarEnabled,
        initialHomeReady,
        profileSwitchLoading,
        useNativeNavigation,
    ) {
        val visible = !useNativeNavigation &&
            liquidGlassNativeTabBarSupported &&
            liquidGlassNativeTabBarEnabled &&
            initialHomeReady &&
            !profileSwitchLoading &&
            currentRoute is TabsRoute
        NativeTabBridge.publishTabBarVisible(visible)
    }

    DisposableEffect(Unit) {
        onDispose {
            NativeTabBridge.publishTabBarVisible(false)
        }
    }

    LaunchedEffect(appContentGeneration) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        EpisodeReleaseNotificationsRepository.refreshAsync()
        kotlinx.coroutines.delay(2_000)
        initialHomeReady = true
    }

    LaunchedEffect(networkStatusUiState.condition) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val condition = networkStatusUiState.condition
        if (!networkToastBaselineReady) {
            if (condition == NetworkCondition.Checking || condition == NetworkCondition.Unknown) {
                return@LaunchedEffect
            }
            networkToastBaselineReady = true
            lastNetworkToastCondition = condition.name
            return@LaunchedEffect
        }

        val previousConditionName = lastNetworkToastCondition
        if (previousConditionName == condition.name) return@LaunchedEffect

        when (condition) {
            NetworkCondition.NoInternet -> {
                NuvioToastController.show(getString(Res.string.network_no_internet_connection))
            }

            NetworkCondition.ServersUnreachable -> {
                NuvioToastController.show(getString(Res.string.network_cannot_reach_servers))
            }

            NetworkCondition.Online -> {
                if (
                    previousConditionName == NetworkCondition.NoInternet.name ||
                    previousConditionName == NetworkCondition.ServersUnreachable.name
                ) {
                    MemberAccessRepository.refresh()
                    NuvioToastController.show(getString(Res.string.network_back_online))
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }

        lastNetworkToastCondition = condition.name
    }

    LaunchedEffect(
        networkStatusUiState.condition,
        (authState as? AuthState.Authenticated)?.userId,
        profileState.activeProfile?.profileIndex,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> watchSourceReconnectPending = true

            NetworkCondition.Online -> {
                if (!watchSourceReconnectPending) return@LaunchedEffect

                val profileId = profileState.activeProfile?.profileIndex
                    ?: ProfileRepository.activeProfileId
                val authenticatedState = authState as? AuthState.Authenticated
                if (authenticatedState != null && !authenticatedState.isAnonymous) {
                    SyncManager.requestForegroundPull(profileId = profileId)
                    watchSourceReconnectPending = false
                } else {
                    val result = WatchProgressSourceCoordinator.refreshActiveSource(
                        profileId = profileId,
                        force = true,
                    )
                    if (result.succeeded) {
                        watchSourceReconnectPending = false
                    }
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    LaunchedEffect(
        initialHomeReady,
        offlineLaunchRouteHandled,
        networkStatusUiState.condition,
        downloadsUiState.completedItems,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (!initialHomeReady || offlineLaunchRouteHandled) return@LaunchedEffect

        when (networkStatusUiState.condition) {
            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> return@LaunchedEffect

            NetworkCondition.Online -> {
                offlineLaunchRouteHandled = true
            }

            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                offlineLaunchRouteHandled = true
                val hasPlayableDownload = downloadsUiState.completedItems.any {
                    DownloadsRepository.playableLocalFileUri(it) != null
                }
                if (hasPlayableDownload) {
                    activateTab(AppScreenTab.Settings)
                    navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    LaunchedEffect(authState, profileState.activeProfile?.profileIndex) {
        if (!ownsAppRuntime) return@LaunchedEffect
        val authenticatedState = authState as? AuthState.Authenticated
        val activeProfileId = profileState.activeProfile?.profileIndex
        val syncProfileId = activeProfileId?.takeIf {
            authenticatedState != null && !authenticatedState.isAnonymous
        }
        syncProfileId?.let(SyncManager::pullAllForProfile)
        try {
            AppForegroundMonitor.events().collect { visibility ->
                when (visibility) {
                    AppVisibility.Foreground -> {
                        NetworkStatusRepository.requestForegroundRefresh()
                        DeviceSessionRegistration.registerIfAuthenticated()
                        MemberAccessRepository.refreshIfStale()
                        if (syncProfileId != null) {
                            SyncManager.startPeriodicNuvioSyncPull(syncProfileId)
                            SyncManager.requestForegroundPull(syncProfileId)
                        } else {
                            SyncManager.stopPeriodicNuvioSyncPull()
                        }
                    }
                    AppVisibility.Background -> SyncManager.stopPeriodicNuvioSyncPull()
                }
            }
        } finally {
            SyncManager.stopPeriodicNuvioSyncPull()
        }
    }
    var resumePromptItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastExternalPlayerLaunch by remember { mutableStateOf<PlayerLaunch?>(null) }
    val activePlaybackProfileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val launchExternalPlayer = rememberExternalPlayerLauncher { result ->
        if (result != null && result.positionMs > 0L) {
            coroutineScope.launch {
                val durationMs = result.durationMs
                // Guard: debrid cache-sync placeholders and error clips report a short
                // duration reaching completion. Skip scrobble + progress for those.
                if (durationMs != null && isShortPlaceholderDuration(durationMs)) return@launch
                val progressPercent = if (durationMs != null && durationMs > 0L) {
                    (result.positionMs.toFloat() / durationMs.toFloat() * 100f).coerceIn(0f, 100f)
                } else {
                    null
                }
                val playerLaunch = lastExternalPlayerLaunch
                if (progressPercent != null && playerLaunch != null) {
                    val trackingMedia = buildTrackingMediaReference(
                        contentType = playerLaunch.parentMetaType,
                        parentMetaId = playerLaunch.parentMetaId,
                        videoId = playerLaunch.videoId,
                        title = playerLaunch.title,
                        seasonNumber = playerLaunch.seasonNumber,
                        episodeNumber = playerLaunch.episodeNumber,
                        episodeTitle = playerLaunch.episodeTitle,
                    )
                    if (trackingMedia.hasResolvableIdentity) {
                        runCatching {
                            TrackingScrobbleCoordinator.scrobble(
                                profileId = playerLaunch.profileId,
                                action = TrackingScrobbleAction.STOP,
                                event = TrackingScrobbleEvent(
                                    media = trackingMedia,
                                    progressPercent = progressPercent.toDouble(),
                                ),
                            )
                        }
                    }
                }
                playerLaunch?.let { playerLaunch ->
                    val session = WatchProgressPlaybackSession(
                        profileId = playerLaunch.profileId,
                        contentType = playerLaunch.contentType ?: playerLaunch.parentMetaType,
                        parentMetaId = playerLaunch.parentMetaId,
                        parentMetaType = playerLaunch.parentMetaType,
                        videoId = playerLaunch.videoId ?: playerLaunch.parentMetaId,
                        title = playerLaunch.title,
                        logo = playerLaunch.logo,
                        poster = playerLaunch.poster,
                        background = playerLaunch.background,
                        seasonNumber = playerLaunch.seasonNumber,
                        episodeNumber = playerLaunch.episodeNumber,
                        episodeTitle = playerLaunch.episodeTitle,
                        episodeThumbnail = playerLaunch.episodeThumbnail,
                        providerName = playerLaunch.providerName,
                        providerAddonId = playerLaunch.providerAddonId,
                        lastStreamTitle = playerLaunch.streamTitle,
                        lastSourceUrl = playerLaunch.sourceUrl,
                    )
                    val snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = !result.endedByUser,
                        durationMs = durationMs ?: 0L,
                        positionMs = result.positionMs,
                    )
                    WatchProgressRepository.upsertPlaybackProgress(
                        session = session,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }
    val continueWatchingPreferencesUiState by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    LaunchedEffect(
        initialHomeReady,
        profileSwitchLoading,
        profileState.activeProfile?.profileIndex,
        continueWatchingPreferencesUiState.showResumePromptOnLaunch,
    ) {
        if (!ownsAppRuntime) return@LaunchedEffect
        if (!initialHomeReady || profileSwitchLoading) return@LaunchedEffect
        if (resumePromptItem != null) return@LaunchedEffect
        if (continueWatchingPreferencesUiState.showResumePromptOnLaunch) {
            resumePromptItem = ResumePromptRepository.consumeResumePrompt()
        }
    }

    LaunchedEffect(currentRoute) {
        val inPlaybackFlow = currentRoute is StreamRoute || currentRoute is PlayerRoute
        if (inPlaybackFlow) {
            resumePromptItem = null
        }
    }

        LaunchedEffect(navController) {
            if (!ownsAppRuntime) return@LaunchedEffect
            AppDeepLinkRepository.pendingDeepLink.collectLatest { deepLink ->
                when (deepLink) {
                    is AppDeepLink.Meta -> {
                        activateTab(AppScreenTab.Home)
                        val routeTitle = runCatching {
                            MetaDetailsRepository.fetch(deepLink.type, deepLink.id)?.name
                        }.getOrNull().orEmpty().ifBlank { detailsFallbackTitle }
                        navController.navigate(
                            DetailRoute(
                                type = deepLink.type,
                                id = deepLink.id,
                                title = routeTitle,
                            )
                        ) {
                            launchSingleTop = true
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    is AppDeepLink.AddonInstall -> {
                        activateTab(AppScreenTab.Settings)
                        navController.navigate(AddonsSettingsRoute(addonsSettingsTitle)) {
                            launchSingleTop = true
                        }
                        NuvioToastController.show(getString(Res.string.addons_modal_checking_title))
                        AddonRepository.initialize()
                        when (val result = AddonRepository.addAddon(deepLink.manifestUrl)) {
                            is AddAddonResult.Success -> {
                                NuvioToastController.show(
                                    getString(Res.string.addons_modal_success_message, result.manifest.name),
                                )
                            }

                            is AddAddonResult.Error -> {
                                NuvioToastController.show(result.message)
                            }
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    AppDeepLink.Downloads -> {
                        activateTab(AppScreenTab.Settings)
                        navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) {
                            launchSingleTop = true
                        }
                        AppDeepLinkRepository.markConsumed(deepLink)
                    }

                    null -> Unit
                }
            }
        }

        suspend fun openExternalPlayback(launch: PlayerLaunch): Boolean {
            lastExternalPlayerLaunch = launch

            val bingeGroup = launch.bingeGroup
            if (bingeGroup != null && launch.parentMetaId.isNotBlank()) {
                BingeGroupCacheRepository.save(launch.parentMetaId, bingeGroup)
            }

            val baseRequest = launch.toExternalPlayerPlaybackRequest()
            val shouldForwardSubtitles = playerSettingsUiState.externalPlayerForwardSubtitles &&
                !playerSettingsUiState.preferredSubtitleLanguage.equals(SubtitleLanguageOption.NONE, ignoreCase = true)
            val shouldSendSkipSegments = playerSettingsUiState.externalPlayerSendSkipSegments
            if (shouldForwardSubtitles) {
                StreamsRepository.setOverlayVisible(true, getString(Res.string.streams_loading_subtitles))
            } else if (shouldSendSkipSegments) {
                StreamsRepository.setOverlayVisible(true, getString(Res.string.streams_loading_skip_segments))
            }
            val enrichedRequest = prepareExternalPlayerLaunch(
                request = baseRequest,
                type = launch.contentType ?: launch.parentMetaType,
                videoId = launch.videoId ?: launch.parentMetaId,
                contentId = launch.parentMetaId,
                forwardSubtitles = playerSettingsUiState.externalPlayerForwardSubtitles,
                sendSkipSegments = shouldSendSkipSegments,
                preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                onOverlayMessage = { _ -> },
            )
            StreamsRepository.setOverlayVisible(false)
            return when (
                val intentResult = ExternalPlayerPlatform.buildIntent(
                    request = enrichedRequest,
                    playerId = playerSettingsUiState.externalPlayerId,
                )
            ) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    }
                    launched
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                    false
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                    false
                }
            }
        }

        fun openDownloadedItem(item: DownloadItem) {
            val sourceUrl = DownloadsRepository.playableLocalFileUri(item) ?: return
            val resumeEntry = item.videoId
                .takeIf { it.isNotBlank() }
                ?.let(WatchProgressRepository::progressForVideo)
                ?.takeIf { it.isResumable }

            val playerLaunch = PlayerLaunch(
                profileId = activePlaybackProfileId,
                title = item.title,
                sourceUrl = sourceUrl,
                sourceHeaders = emptyMap(),
                sourceResponseHeaders = emptyMap(),
                externalSubtitles = emptyList(),
                streamType = null,
                logo = item.logo,
                poster = item.poster,
                background = item.background,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                episodeThumbnail = item.episodeThumbnail,
                streamTitle = item.streamTitle,
                streamSubtitle = item.streamSubtitle,
                providerName = item.providerName,
                providerAddonId = item.providerAddonId,
                contentType = item.contentType,
                videoId = item.videoId,
                parentMetaId = item.parentMetaId,
                parentMetaType = item.parentMetaType,
                initialPositionMs = resumeEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L,
                initialProgressFraction = resumeEntry?.progressFraction?.takeIf { it > 0f },
            )
            if (playerSettingsUiState.externalPlayerEnabled) {
                coroutineScope.launch { openExternalPlayback(playerLaunch) }
                return
            }
            val launchId = PlayerLaunchStore.put(playerLaunch)
            navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
        }

        fun openExternalStreamUrl(url: String): Boolean {
            val opened = runCatching {
                uriHandler.openUri(url)
            }.isSuccess
            if (!opened) {
                NuvioToastController.show(failedOpenBrowserText)
            }
            return opened
        }

        suspend fun launchCloudLibraryFile(
            item: CloudLibraryItem,
            file: CloudLibraryFile,
            resumePositionMs: Long? = null,
            resumeProgressFraction: Float? = null,
            startFromBeginning: Boolean = false,
        ): Boolean {
            return when (
                val resolved = CloudLibraryRepository.resolvePlayback(
                    item = item,
                    file = file,
                )
            ) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackTitle = resolved.filename
                        ?.takeIf { it.isNotBlank() }
                        ?: file.name.ifBlank { item.name }
                    val playerLaunch = PlayerLaunch(
                        profileId = activePlaybackProfileId,
                        title = playbackTitle,
                        sourceUrl = resolved.url,
                        streamTitle = playbackTitle,
                        streamSubtitle = item.name.takeIf { it != playbackTitle },
                        providerName = item.providerName,
                        providerAddonId = "cloud:${item.providerId}",
                        poster = item.providerPosterUrl(),
                        contentType = CloudLibraryContentType,
                        videoId = item.playbackVideoId(file),
                        parentMetaId = item.stableKey,
                        parentMetaType = CloudLibraryContentType,
                        initialPositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L),
                        initialProgressFraction = if (startFromBeginning) null else resumeProgressFraction,
                    )
                    if (playerSettingsUiState.externalPlayerEnabled) {
                        openExternalPlayback(playerLaunch)
                        true
                    } else {
                        val launchId = PlayerLaunchStore.put(playerLaunch)
                        navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
                        true
                    }
                }

                else -> false
            }
        }

        fun launchPlaybackWithDownloadPreference(
            type: String,
            videoId: String,
            parentMetaId: String,
            parentMetaType: String,
            title: String,
            logo: String?,
            poster: String?,
            background: String?,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeTitle: String?,
            episodeThumbnail: String?,
            pauseDescription: String?,
            resumePositionMs: Long?,
            resumeProgressFraction: Float?,
            manualSelection: Boolean,
            startFromBeginning: Boolean,
        ) {
            val targetResumePositionMs = if (startFromBeginning) 0L else (resumePositionMs ?: 0L)
            val targetResumeProgressFraction = if (startFromBeginning) null else resumeProgressFraction

            if (!manualSelection) {
                val downloadedItem = DownloadsRepository.findPlayableDownload(
                    parentMetaId = parentMetaId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    videoId = videoId,
                )
                val localSourceUrl = downloadedItem?.let(DownloadsRepository::playableLocalFileUri)
                if (!localSourceUrl.isNullOrBlank()) {
                    val playerLaunch = PlayerLaunch(
                        profileId = activePlaybackProfileId,
                        title = title,
                        sourceUrl = localSourceUrl,
                        sourceHeaders = emptyMap(),
                        sourceResponseHeaders = emptyMap(),
                        externalSubtitles = emptyList(),
                        logo = logo,
                        poster = poster,
                        background = background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                        streamTitle = downloadedItem.streamTitle.ifBlank { title },
                        streamSubtitle = downloadedItem.streamSubtitle,
                        pauseDescription = pauseDescription,
                        providerName = downloadedItem.providerName.ifBlank { downloadedProviderLabel },
                        providerAddonId = downloadedItem.providerAddonId,
                        contentType = type,
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        parentMetaType = parentMetaType,
                        initialPositionMs = targetResumePositionMs,
                        initialProgressFraction = targetResumeProgressFraction,
                    )
                    if (playerSettingsUiState.externalPlayerEnabled) {
                        coroutineScope.launch { openExternalPlayback(playerLaunch) }
                        return
                    }
                    val launchId = PlayerLaunchStore.put(playerLaunch)
                    navController.navigate(PlayerRoute(launchId = launchId, title = playerLaunch.title))
                    return
                }
            }

            val streamLaunchId = StreamLaunchStore.put(
                StreamLaunch(
                    profileId = activePlaybackProfileId,
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = if (startFromBeginning) 0L else resumePositionMs,
                    resumeProgressFraction = targetResumeProgressFraction,
                    manualSelection = manualSelection,
                    startFromBeginning = startFromBeginning,
                ),
            )
            navController.navigate(
                StreamRoute(launchId = streamLaunchId, title = title),
            )
        }

        val onPlay: ContentPlayAction =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    manualSelection = false,
                    startFromBeginning = false,
                )
            }

        val onPlayManually: ContentPlayAction =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, resumePositionMs ->
                launchPlaybackWithDownloadPreference(
                    type = type,
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    parentMetaType = parentMetaType,
                    title = title,
                    logo = logo,
                    poster = poster,
                    background = background,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    episodeThumbnail = episodeThumbnail,
                    pauseDescription = pauseDescription,
                    resumePositionMs = resumePositionMs,
                    resumeProgressFraction = null,
                    manualSelection = true,
                    startFromBeginning = false,
                )
            }

        val onCatalogClick: (HomeCatalogSection) -> Unit = { section ->
            val launchId = CatalogLaunchStore.put(
                CatalogLaunch(
                    title = section.title,
                    subtitle = section.subtitle,
                    target = section.target,
                ),
            )
            navController.navigate(
                CatalogRoute(
                    launchId = launchId,
                    title = section.title,
                    subtitle = section.subtitle,
                ),
            )
        }

        val librarySectionSubtitle = when (libraryUiState.sourceMode) {
            LibrarySourceMode.LOCAL -> stringResource(Res.string.compose_catalog_subtitle_library)
            LibrarySourceMode.TRAKT -> stringResource(Res.string.compose_catalog_subtitle_trakt_library)
            LibrarySourceMode.SIMKL -> stringResource(Res.string.compose_catalog_subtitle_simkl_library)
        }

        val onLibrarySectionViewAllClick: (LibrarySection, LibrarySortOption) -> Unit = { section, sortOption ->
            val launchId = CatalogLaunchStore.put(
                CatalogLaunch(
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                    target = CatalogTarget.Library(
                        contentType = section.items.firstOrNull()?.type ?: "movie",
                        sectionType = section.type,
                        sortOption = sortOption,
                    ),
                ),
            )
            navController.navigate(
                CatalogRoute(
                    launchId = launchId,
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                ),
            )
        }

        val openContinueWatching: (ContinueWatchingItem, Boolean, Boolean) -> Unit = { item, manualSelection, startFromBeginning ->
            resumePromptItem = null
            if (item.isCloudLibraryContinueWatchingItem()) {
                coroutineScope.launch {
                    when (
                        val lookup = CloudLibraryRepository.findPlaybackTargetForProgressResult(
                            contentId = item.parentMetaId,
                            videoId = item.videoId,
                        )
                    ) {
                        is CloudLibraryPlaybackTargetLookupResult.Found -> {
                            val launched = launchCloudLibraryFile(
                                item = lookup.target.item,
                                file = lookup.target.file,
                                resumePositionMs = item.resumePositionMs,
                                resumeProgressFraction = item.resumeProgressFraction,
                                startFromBeginning = startFromBeginning,
                            )
                            if (!launched) {
                                NuvioToastController.show(cloudLibraryPlayFailedText)
                            }
                        }

                        CloudLibraryPlaybackTargetLookupResult.Disabled -> {
                            NuvioToastController.show(cloudLibraryPlayDisabledText)
                        }

                        is CloudLibraryPlaybackTargetLookupResult.NotConnected -> {
                            val providerName = lookup.providerName?.takeIf { it.isNotBlank() }
                            NuvioToastController.show(
                                providerName?.let { name ->
                                    getString(Res.string.cloud_library_play_provider_not_connected, name)
                                }
                                    ?: cloudLibraryPlayNotConnectedText,
                            )
                        }

                        CloudLibraryPlaybackTargetLookupResult.NotFound -> {
                            NuvioToastController.show(cloudLibraryPlayFailedText)
                        }
                    }
                }
            } else {
                launchPlaybackWithDownloadPreference(
                    type = item.parentMetaType,
                    videoId = item.videoId,
                    parentMetaId = item.parentMetaId,
                    parentMetaType = item.parentMetaType,
                    title = item.title,
                    logo = item.logo,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.episodeTitle,
                    episodeThumbnail = item.episodeThumbnail,
                    pauseDescription = item.pauseDescription,
                    resumePositionMs = item.resumePositionMs,
                    resumeProgressFraction = item.resumeProgressFraction,
                    manualSelection = manualSelection,
                    startFromBeginning = startFromBeginning,
                )
            }
        }

        val onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, false)
        }

        val onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, false, true)
        }

        val onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = { item ->
            openContinueWatching(item, true, false)
        }

        val onContinueWatchingRemove: (ContinueWatchingItem) -> Unit = { item ->
            continueWatchingDisintegrationRequests.arm(continueWatchingItemKey(item))
            if (item.isNextUp) {
                ContinueWatchingPreferencesRepository.addDismissedNextUpKey(
                    nextUpDismissKey(
                        item.parentMetaId,
                        item.nextUpSeedSeasonNumber,
                        item.nextUpSeedEpisodeNumber,
                    ),
                )
            } else {
                WatchProgressRepository.removeProgress(contentId = item.parentMetaId)
            }
        }

        val onContinueWatchingLongPress: (ContinueWatchingItem) -> Unit = { item ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            val zoomAnchor = PosterZoomAnchorHolder.consume()
            selectedContinueWatchingZoomAnchor = zoomAnchor
            selectedContinueWatchingForActions = item
        }

        AppUpdaterHost(
            controller = appUpdaterController,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selectedPosterActionTarget != null || selectedContinueWatchingZoomAnchor != null) {
                            Modifier.hazeSource(state = posterOverlayHazeState)
                        } else {
                            Modifier
                        },
                    )
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalUseNativeNavigation provides useNativeNavigation,
                    LocalNativeNavigationBarHidden provides (currentRoute?.hidesNavigationBar == true),
                ) {
                NavDisplay(
                    backStack = navBackStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { navController.popBackStack() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                        routeDisposalDecorator,
                    ),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    entryProvider = entryProvider<NavKey> {
                entry<TabsRoute> {
                    MainTabsDestination(
                        selectedTab = selectedTab,
                        initialHomeReady = initialHomeReady,
                        rootRouteActive = currentRoute is TabsRoute,
                        useTabletFloatingTabBar = useTabletFloatingTabBar,
                        useNativeNavigation = useNativeNavigation,
                        useNativeTabBar = useNativeTabBar,
                        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                        liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                        requests = AppTabRequests(
                            homeScrollToTopRequests = homeScrollToTopRequests,
                            searchScrollToTopRequests = searchScrollToTopRequests,
                            libraryScrollToTopRequests = libraryScrollToTopRequests,
                            settingsRootActionRequests = settingsRootActionRequests,
                        ),
                        state = AppTabState(
                            searchListState = searchListState,
                            homeContentGeneration = appContentGeneration,
                            searchFocusRequestCount = searchFocusRequestCount,
                            rootActionsEnabled = currentRoute is TabsRoute,
                            animateHomeCollectionGifs = currentRoute is TabsRoute,
                            libraryDisintegrationRequest = libraryDisintegrationRequests.current,
                            continueWatchingDisintegrationRequest = continueWatchingDisintegrationRequests.current,
                            requestedSettingsPageName = requestedSettingsPageName,
                        ),
                        actions = { isTabletLayout ->
                            AppTabActions(
                                onCatalogClick = onCatalogClick,
                                onPosterClick = { meta ->
                                    navController.navigate(
                                        DetailRoute(type = meta.type, id = meta.id, title = meta.name),
                                    )
                                },
                                onPosterLongClick = { meta ->
                                    openPosterActions(PosterActionTarget(preview = meta))
                                },
                                onLibraryPosterClick = { item ->
                                    navController.navigate(
                                        DetailRoute(type = item.type, id = item.id, title = item.name),
                                    )
                                },
                                onLibraryPosterLongClick = { item, section ->
                                    openPosterActions(
                                        PosterActionTarget(
                                            preview = item.toMetaPreview(),
                                            libraryItem = item,
                                            libraryListKey = section.type,
                                        ),
                                    )
                                },
                                onLibrarySectionViewAllClick = onLibrarySectionViewAllClick,
                                onCloudFilePlay = { item, file ->
                                    coroutineScope.launch {
                                        val resumeItem = WatchProgressRepository
                                            .progressForVideo(
                                                videoId = item.playbackVideoId(file),
                                                parentMetaId = item.id,
                                            )
                                            ?.takeIf { it.isResumable }
                                            ?.toContinueWatchingItem()
                                        if (
                                            !launchCloudLibraryFile(
                                                item = item,
                                                file = file,
                                                resumePositionMs = resumeItem?.resumePositionMs,
                                                resumeProgressFraction = resumeItem?.resumeProgressFraction,
                                            )
                                        ) {
                                            NuvioToastController.show(cloudLibraryPlayFailedText)
                                        }
                                    }
                                },
                                onConnectCloudClick = {
                                    if (useNativeNavigation && !isTabletLayout) {
                                        activateTab(AppScreenTab.Settings)
                                        navController.navigate(
                                            SettingsPageRoute(
                                                pageName = "Debrid",
                                                title = debridSettingsTitle,
                                            )
                                        )
                                    } else {
                                        requestedSettingsPageName = "Debrid"
                                        activateTab(AppScreenTab.Settings)
                                    }
                                },
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingLongPress = onContinueWatchingLongPress,
                                onSwitchProfile = onSwitchProfile,
                                onSettingsPageClick = if (useNativeNavigation && !isTabletLayout) {
                                    { pageName, title ->
                                        navController.navigate(SettingsPageRoute(pageName, title))
                                    }
                                } else {
                                    null
                                },
                                onHomescreenSettingsClick = { navController.navigate(HomescreenSettingsRoute(homescreenSettingsTitle)) },
                                onMetaScreenSettingsClick = { navController.navigate(MetaScreenSettingsRoute(metaScreenSettingsTitle)) },
                                onContinueWatchingSettingsClick = { navController.navigate(ContinueWatchingSettingsRoute(continueWatchingSettingsTitle)) },
                                onDownloadsSettingsClick = { navController.navigate(DownloadsSettingsRoute(downloadsSettingsTitle)) },
                                onAddonsSettingsClick = { navController.navigate(AddonsSettingsRoute(addonsSettingsTitle)) },
                                onPluginsSettingsClick = {
                                    if (AppFeaturePolicy.pluginsEnabled) {
                                        navController.navigate(PluginsSettingsRoute(pluginsSettingsTitle))
                                    }
                                },
                                onAccountSettingsClick = { navController.navigate(AccountSettingsRoute(accountSettingsTitle)) },
                                onSupportersContributorsSettingsClick = {
                                    if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                                        navController.navigate(SupportersContributorsSettingsRoute(supportersSettingsTitle))
                                    }
                                },
                                onLicensesAttributionsSettingsClick = {
                                    navController.navigate(LicensesAttributionsSettingsRoute(licensesSettingsTitle))
                                },
                                onCheckForUpdatesClick = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                                    {
                                        appUpdaterController.checkForUpdates(
                                            force = true,
                                            showNoUpdateFeedback = true,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onTestUpdateBannerClick = if (
                                    AppFeaturePolicy.inAppUpdaterEnabled && AppUpdaterPlatform.isDebugBuild
                                ) {
                                    appUpdaterController::showDebugTestUpdate
                                } else {
                                    null
                                },
                                onCollectionsSettingsClick = { navController.navigate(CollectionsRoute(collectionsTitle)) },
                                onFolderClick = { collectionId, folderId ->
                                    val folderTitle = CollectionRepository.collections.value
                                        .firstOrNull { it.id == collectionId }
                                        ?.folders
                                        ?.firstOrNull { it.id == folderId }
                                        ?.title
                                        .orEmpty()
                                    navController.navigate(
                                        FolderDetailRoute(
                                            collectionId = collectionId,
                                            folderId = folderId,
                                            title = folderTitle.ifBlank { collectionsTitle },
                                        )
                                    )
                                },
                                onRequestedSettingsPageConsumed = {
                                    requestedSettingsPageName = null
                                },
                                onInitialHomeContentRendered = { initialHomeReady = true },
                            )
                        },
                        onBack = {
                            if (selectedTab != AppScreenTab.Home) {
                                activateTab(AppScreenTab.Home)
                            } else {
                                showExitConfirmation = !showExitConfirmation
                            }
                        },
                        onTabSelected = ::handleRootTabClick,
                        onProfileSelected = { profile ->
                            profileSwitchLoading = true
                            NativeTabBridge.publishTabBarVisible(false)
                            activateTab(AppScreenTab.Home)
                            ProfileRepository.selectProfile(profile.profileIndex)
                            SyncManager.pullAllForProfile(profile.profileIndex)
                        },
                        onAddProfileRequested = onSwitchProfile,
                    )
                }
                entry<DetailRoute> { route ->
                    DetailsDestination(
                        route = route,
                        navController = navController,
                        onPlay = onPlay,
                        onPlayManually = onPlayManually,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                    )
                }
                entry<PersonDetailRoute> { route ->
                    PersonDestination(
                        route = route,
                        navController = navController,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                    )
                }
                entry<EntityBrowseRoute> { route ->
                    EntityDestination(route = route, navController = navController)
                }
                entry<StreamRoute> { route ->
                    StreamDestination(
                        route = route,
                        navController = navController,
                        p2pEnabled = p2pSettingsUiState.p2pEnabled,
                        openExternalPlayback = ::openExternalPlayback,
                        openExternalStreamUrl = ::openExternalStreamUrl,
                    )
                }
                entry<PlayerRoute>(
                    metadata = if (isIos) {
                        NavDisplay.transitionSpec {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(220))
                        } + NavDisplay.popTransitionSpec {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(220))
                        }
                    } else {
                        emptyMap()
                    },
                ) { route ->
                    PlayerDestination(
                        route = route,
                        navController = navController,
                        externalPlayerId = playerSettingsUiState.externalPlayerId,
                        externalPlayerNotConfiguredText = externalPlayerNotConfiguredText,
                        externalPlayerFailedText = externalPlayerFailedText,
                        onExternalPlayerLaunch = { launch -> lastExternalPlayerLaunch = launch },
                        launchExternalPlayer = launchExternalPlayer,
                        openExternalStreamUrl = ::openExternalStreamUrl,
                    )
                }
                entry<CatalogRoute> { route ->
                    CatalogDestination(
                        route = route,
                        navController = navController,
                        onPosterLongClick = openPosterActions,
                    )
                }
                entry<HomescreenSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        HomescreenSettingsScreen(onBack = onBack)
                    }
                }
                entry<MetaScreenSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        MetaScreenSettingsScreen(onBack = onBack)
                    }
                }
                entry<ContinueWatchingSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        ContinueWatchingSettingsScreen(onBack = onBack)
                    }
                }
                entry<SettingsPageRoute> { route ->
                    SettingsRootDestination(
                        route = route,
                        navController = navController,
                        useNativeNavigation = useNativeNavigation,
                        downloadsTitle = downloadsSettingsTitle,
                        collectionsTitle = collectionsTitle,
                        onCheckForUpdates = if (AppFeaturePolicy.inAppUpdaterEnabled) {
                            { appUpdaterController.checkForUpdates(force = true, showNoUpdateFeedback = true) }
                        } else null,
                        onTestUpdateBanner = if (
                            AppFeaturePolicy.inAppUpdaterEnabled && AppUpdaterPlatform.isDebugBuild
                        ) appUpdaterController::showDebugTestUpdate else null,
                    )
                }
                entry<DownloadsSettingsRoute> { route ->
                    DownloadsDestination(
                        route = route,
                        navController = navController,
                        useNativeNavigation = useNativeNavigation,
                        onOpenDownload = ::openDownloadedItem,
                    )
                }
                entry<DownloadShowRoute> { route ->
                    DownloadShowDestination(
                        route = route,
                        navController = navController,
                        onOpenDownload = ::openDownloadedItem,
                    )
                }
                entry<AddonsSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        AddonsSettingsScreen(onBack = onBack)
                    }
                }
                if (AppFeaturePolicy.pluginsEnabled) {
                    entry<PluginsSettingsRoute> { route ->
                        SettingsDestination(route, navController) { onBack ->
                            PluginsSettingsScreen(onBack = onBack)
                        }
                    }
                }
                entry<AccountSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        AccountSettingsScreen(onBack = onBack)
                    }
                }
                entry<SupportersContributorsSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                            SupportersContributorsSettingsScreen(onBack = onBack)
                        } else {
                            LaunchedEffect(Unit) { onBack() }
                        }
                    }
                }
                entry<LicensesAttributionsSettingsRoute> { route ->
                    SettingsDestination(route, navController) { onBack ->
                        LicensesAttributionsSettingsScreen(onBack = onBack)
                    }
                }
                entry<CollectionsRoute> { route ->
                    CollectionsDestination(
                        route = route,
                        navController = navController,
                        newCollectionTitle = newCollectionTitle,
                    )
                }
                entry<CollectionEditorRoute> { route ->
                    CollectionEditorDestination(
                        route = route,
                        navController = navController,
                        useNativeNavigation = useNativeNavigation,
                    )
                }
                entry<CollectionEditorPageRoute> { route ->
                    CollectionEditorPageDestination(
                        route = route,
                        navController = navController,
                    )
                }
                entry<FolderDetailRoute> { route ->
                    FolderDestination(
                        route = route,
                        navController = navController,
                        onCatalogClick = onCatalogClick,
                    )
                }
                    }.let { provider ->
                        { key ->
                            routeDisposalDecorator.register(
                                key = key,
                                entry = provider(key),
                            )
                        }
                    },
                )
                }
            }
            }

            selectedPosterActionTarget?.let { posterActionTarget ->
                key(posterActionTarget) {
                    val preview = posterActionTarget.preview
                    val isSaved = LibraryRepository.isSaved(preview.id, preview.type)
                    val isWatched = WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = preview,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    )
                    val removesFromLibrary = isSaved &&
                        (posterActionTarget.libraryItem != null || !isRemoteLibrarySource)
                    NuvioPosterZoomActionOverlay(
                        imageUrl = selectedPosterAnchor?.imageUrl ?: preview.poster,
                        title = preview.name,
                        subtitle = preview.releaseInfo
                            ?.takeIf { it.isNotBlank() }
                            ?.let { formatReleaseDateForDisplay(it) }
                            ?: preview.type.replaceFirstChar { char ->
                                if (char.isLowerCase()) char.titlecase() else char.toString()
                            },
                        isWatched = isWatched,
                        anchor = selectedPosterAnchor,
                        actions = listOf(
                            PosterZoomOverlayAction(
                                icon = if (isSaved) Icons.Default.DeleteOutline else Icons.Default.Add,
                                label = if (isSaved) {
                                    stringResource(Res.string.hero_remove_from_library)
                                } else {
                                    stringResource(Res.string.hero_add_to_library)
                                },
                                isDestructive = removesFromLibrary,
                                exitAnimation = if (removesFromLibrary && !isRemoteLibrarySource) {
                                    PosterZoomOverlayExitAnimation.DISINTEGRATE
                                } else {
                                    PosterZoomOverlayExitAnimation.COLLAPSE
                                },
                                onSelected = {
                                    val libraryItem = posterActionTarget.libraryItem
                                        ?: preview.toLibraryItem(savedAtEpochMs = 0L)
                                    if (posterActionTarget.libraryItem != null) {
                                        val animationKey = posterActionTarget.libraryListKey
                                            ?.let { listKey -> librarySectionItemKey(listKey, libraryItem) }
                                        if (isRemoteLibrarySource) {
                                            coroutineScope.launch {
                                                val listKey = posterActionTarget.libraryListKey
                                                val removeMembership: suspend (Set<TrackingProviderId>) ->
                                                    TrackingMembershipApplyResult = { confirmedProviders ->
                                                    if (listKey.isNullOrBlank()) {
                                                        val currentMembership = LibraryRepository.getMembershipSnapshot(libraryItem)
                                                        LibraryRepository.applyMembershipChanges(
                                                            item = libraryItem,
                                                            desiredMembership = currentMembership.mapValues { false },
                                                            confirmedRemovalProviders = confirmedProviders,
                                                        )
                                                    } else {
                                                        LibraryRepository.removeFromList(
                                                            item = libraryItem,
                                                            listKey = listKey,
                                                            confirmedRemovalProviders = confirmedProviders,
                                                        )
                                                    }
                                                }
                                                val removeMembershipWithAnimation:
                                                    suspend (Set<TrackingProviderId>) -> TrackingMembershipApplyResult =
                                                    { confirmedProviders ->
                                                        val request = if (removesFromLibrary) {
                                                            animationKey?.let(libraryDisintegrationRequests::arm)
                                                        } else {
                                                            null
                                                        }
                                                        try {
                                                            removeMembership(confirmedProviders).also { result ->
                                                                if (result.requiresRemovalConfirmation && request != null) {
                                                                    libraryDisintegrationRequests.cancel(request)
                                                                }
                                                            }
                                                        } catch (error: Throwable) {
                                                            request?.let(libraryDisintegrationRequests::cancel)
                                                            throw error
                                                        }
                                                    }
                                                executeTrackingMembershipOperation(
                                                    operation = { removeMembershipWithAnimation(emptySet()) },
                                                    onSuccess = { result ->
                                                        if (result.requiresRemovalConfirmation) {
                                                            pendingTrackingRemoval = PendingTrackingMembershipRemoval(
                                                                itemTitle = libraryItem.name,
                                                                confirmations = result.requiredRemovalConfirmations,
                                                                retry = removeMembershipWithAnimation,
                                                                onApplied = {},
                                                                onFailure = { error ->
                                                                    NuvioToastController.show(
                                                                        error.message
                                                                            ?: trackingListsUpdateFailedMessage,
                                                                    )
                                                                },
                                                            )
                                                        }
                                                    },
                                                    onFailure = { error ->
                                                        NuvioToastController.show(
                                                            error.message ?: trackingListsUpdateFailedMessage,
                                                        )
                                                    },
                                                )
                                            }
                                        } else {
                                            if (removesFromLibrary) {
                                                animationKey?.let(libraryDisintegrationRequests::arm)
                                            }
                                            LibraryRepository.remove(libraryItem.id)
                                        }
                                    } else {
                                        if (!isRemoteLibrarySource) {
                                            LibraryRepository.toggleLocalSaved(libraryItem)
                                        } else {
                                            pickerItem = libraryItem
                                            pickerTitle = preview.name
                                            pickerTabs = LibraryRepository.libraryListTabs(libraryItem)
                                            pickerMembership = pickerTabs.associate { it.key to false }
                                            pickerPending = true
                                            pickerError = null
                                            showLibraryListPicker = true
                                            coroutineScope.launch {
                                                runCatching {
                                                    val snapshot = LibraryRepository.getMembershipSnapshot(libraryItem)
                                                    val tabs = LibraryRepository.libraryListTabs(libraryItem)
                                                    pickerTabs = tabs
                                                    pickerMembership = tabs.associate { tab ->
                                                        tab.key to (snapshot[tab.key] == true)
                                                    }
                                                }.onFailure { error ->
                                                    pickerError = error.message ?: getString(Res.string.trakt_lists_load_failed)
                                                }
                                                pickerPending = false
                                            }
                                        }
                                    }
                                },
                            ),
                            PosterZoomOverlayAction(
                                icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                                label = if (isWatched) {
                                    stringResource(Res.string.hero_mark_unwatched)
                                } else {
                                    stringResource(Res.string.hero_mark_watched)
                                },
                                onSelected = {
                                    coroutineScope.launch {
                                        WatchingActions.togglePosterWatched(preview)
                                    }
                                },
                            ),
                        ),
                        hazeState = posterOverlayHazeState,
                        onDismissed = {
                            selectedPosterActionTarget = null
                            selectedPosterAnchor = null
                        },
                    )
                }
            }

            selectedContinueWatchingForActions?.let { item ->
                selectedContinueWatchingZoomAnchor?.let { anchor ->
                    key(item.videoId, anchor) {
                        val showManualPlayOption = StreamAutoPlayPolicy.isEffectivelyEnabled(playerSettingsUiState)
                        val showDetailsOption = !item.isCloudLibraryContinueWatchingItem()
                        NuvioPosterZoomActionOverlay(
                            imageUrl = cloudLibraryDisplayArtworkUrl(anchor.imageUrl ?: item.poster ?: item.imageUrl),
                            title = item.title,
                            subtitle = localizedContinueWatchingSubtitle(item),
                            blurred = item.shouldBlurContinueWatchingArtwork(
                                blurUnwatchedEpisodes = continueWatchingPreferencesUiState.blurNextUp,
                                useEpisodeThumbnails = continueWatchingPreferencesUiState.useEpisodeThumbnails,
                                artworkUrl = anchor.imageUrl ?: item.poster ?: item.imageUrl,
                            ),
                            depthSurface = NuvioCardDepthSurface.ContinueWatching,
                            anchor = anchor,
                            actions = buildList {
                                if (showDetailsOption) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.Info,
                                            label = stringResource(Res.string.cw_action_go_to_details),
                                            onSelected = {
                                                navController.navigate(
                                                    DetailRoute(
                                                        type = item.parentMetaType,
                                                        id = item.parentMetaId,
                                                        title = item.title,
                                                    ),
                                                )
                                            },
                                        ),
                                    )
                                }
                                if (showManualPlayOption) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.PlayArrow,
                                            label = stringResource(Res.string.play_manually),
                                            onSelected = { onContinueWatchingPlayManually(item) },
                                        ),
                                    )
                                }
                                if (!item.isNextUp) {
                                    add(
                                        PosterZoomOverlayAction(
                                            icon = Icons.Default.Replay,
                                            label = stringResource(Res.string.cw_action_start_from_beginning),
                                            onSelected = { onContinueWatchingStartFromBeginning(item) },
                                        ),
                                    )
                                }
                                add(
                                    PosterZoomOverlayAction(
                                        icon = Icons.Default.DeleteOutline,
                                        label = stringResource(Res.string.cw_action_remove),
                                        isDestructive = true,
                                        onSelected = { onContinueWatchingRemove(item) },
                                    ),
                                )
                            },
                            hazeState = posterOverlayHazeState,
                            onDismissed = {
                                selectedContinueWatchingForActions = null
                                selectedContinueWatchingZoomAnchor = null
                            },
                        )
                    }
                }
            }

            NuvioContinueWatchingActionSheet(
                item = selectedContinueWatchingForActions.takeIf { selectedContinueWatchingZoomAnchor == null },
                showManualPlayOption = StreamAutoPlayPolicy.isEffectivelyEnabled(playerSettingsUiState),
                showDetailsOption = selectedContinueWatchingForActions?.isCloudLibraryContinueWatchingItem() != true,
                onDismiss = { selectedContinueWatchingForActions = null },
                onOpenDetails = {
                    selectedContinueWatchingForActions?.let { item ->
                        navController.navigate(
                            DetailRoute(
                                type = item.parentMetaType,
                                id = item.parentMetaId,
                                title = item.title,
                            ),
                        )
                    }
                },
                onStartFromBeginning = selectedContinueWatchingForActions
                    ?.takeIf { !it.isNextUp }
                    ?.let { item -> { onContinueWatchingStartFromBeginning(item) } },
                onPlayManually = selectedContinueWatchingForActions
                    ?.let { item -> { onContinueWatchingPlayManually(item) } },
                onRemove = {
                    selectedContinueWatchingForActions?.let(onContinueWatchingRemove)
                },
            )

            TrackingListPickerDialog(
                visible = showLibraryListPicker,
                title = pickerTitle,
                tabs = pickerTabs,
                membership = pickerMembership,
                isPending = pickerPending,
                errorMessage = pickerError,
                onToggle = { listKey ->
                    pickerMembership = toggleTrackingLibraryMembership(
                        tabs = pickerTabs,
                        membership = pickerMembership,
                        key = listKey,
                    )
                },
                onDismiss = {
                    if (!pickerPending) {
                        showLibraryListPicker = false
                        pickerItem = null
                        pickerError = null
                    }
                },
                onSave = {
                    val item = pickerItem ?: return@TrackingListPickerDialog
                    coroutineScope.launch {
                        pickerPending = true
                        pickerError = null
                        val desiredMembership = pickerMembership.toMap()
                        val applyMembership: suspend (Set<TrackingProviderId>) ->
                            TrackingMembershipApplyResult = { confirmedProviders ->
                            LibraryRepository.applyMembershipChanges(
                                item = item,
                                desiredMembership = desiredMembership,
                                confirmedRemovalProviders = confirmedProviders,
                            )
                        }
                        val completeMembershipUpdate: suspend (TrackingMembershipApplyResult) -> Unit = { result ->
                            showTrackingMembershipRewriteFeedback(result)
                            showLibraryListPicker = false
                            pickerItem = null
                            pickerError = null
                        }
                        executeTrackingMembershipOperation(
                            operation = { applyMembership(emptySet()) },
                            onSuccess = { result ->
                                if (result.requiresRemovalConfirmation) {
                                    pendingTrackingRemoval = PendingTrackingMembershipRemoval(
                                        itemTitle = item.name,
                                        confirmations = result.requiredRemovalConfirmations,
                                        retry = applyMembership,
                                        onApplied = completeMembershipUpdate,
                                        onFailure = { error ->
                                            pickerError = error.message ?: trackingListsUpdateFailedMessage
                                        },
                                    )
                                } else {
                                    completeMembershipUpdate(result)
                                }
                            },
                            onFailure = { error ->
                                pickerError = error.message ?: trackingListsUpdateFailedMessage
                            },
                        )
                        pickerPending = false
                    }
                },
            )

            TrackingMembershipRemovalConfirmationHost(
                pending = pendingTrackingRemoval,
                onPendingChange = { pendingTrackingRemoval = it },
            )

            NuvioStatusModal(
                title = stringResource(Res.string.app_exit_title),
                message = stringResource(Res.string.app_exit_message),
                isVisible = showExitConfirmation,
                confirmText = stringResource(Res.string.action_yes),
                dismissText = stringResource(Res.string.action_no),
                onConfirm = {
                    showExitConfirmation = false
                    platformExitApp()
                },
                onDismiss = {
                    showExitConfirmation = false
                },
            )

            androidx.compose.animation.AnimatedVisibility(
                visibleState = launchOverlayState,
                enter = fadeIn(),
                exit = fadeOut(androidx.compose.animation.core.tween(400)),
            ) {
                AppLaunchOverlay(
                    profile = launchOverlayProfile,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (profileSwitchLoading) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1200)
                    profileSwitchLoading = false
                }
            }

            NuvioFloatingPrompt(
                visible = resumePromptItem != null,
                imageUrl = resumePromptItem?.poster ?: resumePromptItem?.imageUrl,
                title = resumePromptItem?.title.orEmpty(),
                subtitle = resumePromptItem?.let { localizedContinueWatchingSubtitle(it) }.orEmpty(),
                progressFraction = resumePromptItem?.progressFraction ?: 0f,
                actionLabel = stringResource(Res.string.resume_prompt_action),
                onAction = {
                    val item = resumePromptItem ?: return@NuvioFloatingPrompt
                    resumePromptItem = null
                    openContinueWatching(item, false, false)
                },
                onDismiss = { resumePromptItem = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(15f),
            )

            NuvioToastHost(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(20f),
            )

            }
        }
}
