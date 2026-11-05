package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.DisintegrationRequest
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.ui.LiveTvScreen
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileBackgroundBackdrop
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.AppBrandWordmark
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.NuvioNavigator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberGuardedPopBackStack(
    navController: NuvioNavigator,
    route: AppRoute,
    beforePop: () -> Unit = {},
): () -> Unit {
    var popHandled by remember(route) { mutableStateOf(false) }

    return remember(navController, route, popHandled, beforePop) {
        {
            if (!popHandled && navController.currentRoute == route) {
                popHandled = true
                beforePop()
                navController.popBackStack(expectedRoute = route)
            }
        }
    }
}

internal data class AppTabState(
    val searchListState: LazyListState,
    val homeContentGeneration: Int = 0,
    val profileId: Int? = null,
    val searchFocusRequestCount: Int = 0,
    val rootActionsEnabled: Boolean = true,
    val animateHomeCollectionGifs: Boolean = true,
    val libraryDisintegrationRequest: DisintegrationRequest<String>? = null,
    val continueWatchingDisintegrationRequest: DisintegrationRequest<String>? = null,
    val requestedSettingsPageName: String? = null,
)

internal data class AppTabRequests(
    val homeScrollToTopRequests: Flow<Unit>,
    val searchScrollToTopRequests: Flow<Unit>,
    val libraryScrollToTopRequests: Flow<Unit>,
    val settingsRootActionRequests: Flow<Unit>,
    val liveTvScrollToTopRequests: Flow<Unit> = emptyFlow(),
)

internal data class AppTabActions(
    val onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    val onPosterClick: ((MetaPreview) -> Unit)? = null,
    val onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    val onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    val onLibraryPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)? = null,
    val onLibrarySectionViewAllClick: ((LibrarySection, LibrarySortOption) -> Unit)? = null,
    val onCloudFilePlay: ((CloudLibraryItem, CloudLibraryFile) -> Unit)? = null,
    val onConnectCloudClick: (() -> Unit)? = null,
    val onDownloadsClick: () -> Unit = {},
    val onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    val onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    val onSwitchProfile: (() -> Unit)? = null,
    val onSettingsPageClick: ((pageName: String, title: String) -> Unit)? = null,
    val onHomescreenSettingsClick: () -> Unit = {},
    val onMetaScreenSettingsClick: () -> Unit = {},
    val onContinueWatchingSettingsClick: () -> Unit = {},
    val onAddonsSettingsClick: () -> Unit = {},
    val onPluginsSettingsClick: () -> Unit = {},
    val onAccountSettingsClick: () -> Unit = {},
    val onSupportersContributorsSettingsClick: () -> Unit = {},
    val onLicensesAttributionsSettingsClick: () -> Unit = {},
    val onCheckForUpdatesClick: (() -> Unit)? = null,
    val onTestUpdateBannerClick: (() -> Unit)? = null,
    val onCollectionsSettingsClick: () -> Unit = {},
    val onWatchPartySettingsClick: () -> Unit = {},
    val onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    val onRequestedSettingsPageConsumed: () -> Unit = {},
    val onInitialHomeContentRendered: () -> Unit = {},
    val onLiveTvPlay: (LiveChannel, String?) -> Unit = { _, _ -> },
    val onLiveTvGuideClick: () -> Unit = {},
    val onLiveTvSettingsClick: () -> Unit = {},
    val onLiveTvReplay: (LiveChannel, EpgProgram) -> Unit = { _, _ -> },
    val onLiveTvOnDemandClick: () -> Unit = {},
    val onLiveTvRecordingsClick: () -> Unit = {},
    val onLiveTvSportsClick: () -> Unit = {},
    val onLiveTvManageClick: () -> Unit = {},
    val onLiveTvCalendarClick: () -> Unit = {},
)

@Composable
internal fun AppTabHost(
    selectedTab: AppScreenTab,
    requests: AppTabRequests,
    state: AppTabState,
    actions: AppTabActions,
    modifier: Modifier = Modifier,
) {
    RootTabHost(
        selectedTab = selectedTab,
        modifier = modifier,
        active = state.rootActionsEnabled,
        profileId = state.profileId,
    ) { tab ->
        when (tab) {
            AppScreenTab.Home -> {
                key(state.homeContentGeneration) {
                    HomeScreen(
                        modifier = Modifier.fillMaxSize(),
                        animateCollectionGifs = state.animateHomeCollectionGifs,
                        scrollToTopRequests = requests.homeScrollToTopRequests,
                        onCatalogClick = actions.onCatalogClick,
                        onPosterClick = actions.onPosterClick,
                        onPosterLongClick = actions.onPosterLongClick,
                        onContinueWatchingClick = actions.onContinueWatchingClick,
                        onContinueWatchingLongPress = actions.onContinueWatchingLongPress,
                        continueWatchingDisintegrationRequest = state.continueWatchingDisintegrationRequest,
                        onFolderClick = actions.onFolderClick,
                        onFirstCatalogRendered = actions.onInitialHomeContentRendered,
                    )
                }
            }

            AppScreenTab.Search -> {
                SearchScreen(
                    modifier = Modifier.fillMaxSize(),
                    listState = state.searchListState,
                    onPosterClick = actions.onPosterClick,
                    onPosterLongClick = actions.onPosterLongClick,
                    searchFocusRequestCount = state.searchFocusRequestCount,
                    scrollToTopRequests = requests.searchScrollToTopRequests,
                )
            }

            AppScreenTab.Library -> {
                LibraryScreen(
                    modifier = Modifier.fillMaxSize(),
                    scrollToTopRequests = requests.libraryScrollToTopRequests,
                    onPosterClick = actions.onLibraryPosterClick,
                    onPosterLongClick = actions.onLibraryPosterLongClick,
                    onSectionViewAllClick = actions.onLibrarySectionViewAllClick,
                    onCloudFilePlay = actions.onCloudFilePlay,
                    onConnectCloudClick = actions.onConnectCloudClick,
                    onCatalogClick = actions.onCatalogClick,
                    onPreviewClick = actions.onPosterClick,
                    onPreviewLongClick = actions.onPosterLongClick,
                    onDownloadsClick = actions.onDownloadsClick,
                    disintegrationRequest = state.libraryDisintegrationRequest,
                )
            }

            AppScreenTab.LiveTv -> {
                LiveTvScreen(
                    modifier = Modifier.fillMaxSize(),
                    scrollToTopRequests = requests.liveTvScrollToTopRequests,
                    onPlayChannel = actions.onLiveTvPlay,
                    onOpenGuide = actions.onLiveTvGuideClick,
                    onOpenSettings = actions.onLiveTvSettingsClick,
                    onPlayCatchup = actions.onLiveTvReplay,
                    onOpenOnDemand = actions.onLiveTvOnDemandClick,
                    onOpenRecordings = actions.onLiveTvRecordingsClick,
                    onOpenSports = actions.onLiveTvSportsClick,
                    onOpenManage = actions.onLiveTvManageClick,
                    onOpenCalendar = actions.onLiveTvCalendarClick,
                )
            }

            AppScreenTab.Settings -> {
                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    rootActionRequests = requests.settingsRootActionRequests,
                    requestedPageName = state.requestedSettingsPageName,
                    onRequestedPageConsumed = actions.onRequestedSettingsPageConsumed,
                    rootActionsEnabled = state.rootActionsEnabled,
                    isSelectedTab = selectedTab == AppScreenTab.Settings,
                    onNavigatePage = actions.onSettingsPageClick,
                    onSwitchProfile = actions.onSwitchProfile,
                    onHomescreenClick = actions.onHomescreenSettingsClick,
                    onMetaScreenClick = actions.onMetaScreenSettingsClick,
                    onContinueWatchingClick = actions.onContinueWatchingSettingsClick,
                    onAddonsClick = actions.onAddonsSettingsClick,
                    onPluginsClick = actions.onPluginsSettingsClick,
                    onAccountClick = actions.onAccountSettingsClick,
                    onSupportersContributorsClick = actions.onSupportersContributorsSettingsClick,
                    onLicensesAttributionsClick = actions.onLicensesAttributionsSettingsClick,
                    onCheckForUpdatesClick = actions.onCheckForUpdatesClick,
                    onTestUpdateBannerClick = actions.onTestUpdateBannerClick,
                    onCollectionsClick = actions.onCollectionsSettingsClick,
                    onWatchPartyClick = actions.onWatchPartySettingsClick,
                )
            }
        }
    }
}

internal fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

@Composable
internal fun AppLoadingContent(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // "Nuvio + IPTV" in the theme's colours, and what the app does (as on the TV app).
            AppBrandWordmark(
                contentDescription = "Nuvio + IPTV",
                iptv = true,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .widthIn(max = 420.dp)
                    .aspectRatio(1700f / 344f),
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s14))
            Text(
                text = "Live TV  ·  On Demand  ·  Streaming",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s40))
            NuvioLoadingIndicator()
        }
    }
}

@Composable
internal fun AppLaunchOverlay(
    profile: NuvioProfile?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.zIndex(NuvioTokens.Z.dialog),
    ) {
        ProfileBackgroundBackdrop(
            profile = profile,
            modifier = Modifier.fillMaxSize(),
        )
        AppLoadingContent(modifier = Modifier.fillMaxSize())
    }
}
