package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.firstEnabledManifestError
import com.nuvio.app.features.addons.hasPendingEnabledManifests
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.buildAddonCatalogRefreshSignature
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsUiState
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.AndroidLibmpvVideoOutput
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.trakt.TraktAuthUiState
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.TrackingSettingsUiState
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.navigation.LocalUseNativeNavigation
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_root
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val SettingsSearchRevealThreshold = 28.dp
private const val SettingsSearchRevealAnimationMillis = 240L
private const val SettingsSearchRevealHapticDelayMillis = 90L

private fun SettingsPage.isEnabledByPolicy(): Boolean =
    when (this) {
        SettingsPage.SupportersContributors -> AppFeaturePolicy.supportersContributorsPageEnabled
        else -> true
    }

@Composable
private fun settingsPageTitles(): Map<SettingsPage, String> {
    val titles = mutableMapOf<SettingsPage, String>()
    for (page in SettingsPage.entries) {
        titles[page] = stringResource(page.titleRes)
    }
    return titles
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    rootActionRequests: Flow<Unit> = emptyFlow(),
    initialPageName: String = SettingsPage.Root.name,
    requestedPageName: String? = null,
    onRequestedPageConsumed: () -> Unit = {},
    rootActionsEnabled: Boolean = true,
    isSelectedTab: Boolean = true,
    onNavigatePage: ((pageName: String, title: String) -> Unit)? = null,
    onExternalBack: (() -> Unit)? = null,
    showInternalHeader: Boolean = true,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onMetaScreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onTestUpdateBannerClick: (() -> Unit)? = null,
    onCollectionsClick: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val screenActive = LocalScreenActive.current
        val pageStateHolder = rememberSaveableStateHolder()
        val playerSettingsUiState by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        val selectedTheme by remember {
            ThemeSettingsRepository.ensureLoaded()
            ThemeSettingsRepository.selectedTheme
        }.collectAsStateWithLifecycle()
        val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
        val liquidGlassNativeTabBarEnabled by remember {
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled
        }.collectAsStateWithLifecycle()
        val useNativeNavigation = LocalUseNativeNavigation.current
        val liquidGlassNativeTabBarSupported = remember(useNativeNavigation) {
            !useNativeNavigation && isLiquidGlassNativeTabBarSupported()
        }
        val selectedAppLanguage by remember { ThemeSettingsRepository.selectedAppLanguage }.collectAsStateWithLifecycle()
        val navBarStyle by remember { ThemeSettingsRepository.navBarStyle }.collectAsStateWithLifecycle()
        val appIconState by remember {
            AppIconRepository.ensureLoaded()
            AppIconRepository.state
        }.collectAsStateWithLifecycle()
        val appIconScope = rememberCoroutineScope()
        val onAppIconSelected: (AppIconOption) -> Unit = { icon ->
            appIconScope.launch { AppIconRepository.select(icon) }
        }
        val tmdbSettings by remember {
            TmdbSettingsRepository.ensureLoaded()
            TmdbSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val mdbListSettings by remember {
            MdbListSettingsRepository.ensureLoaded()
            MdbListSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val debridSettings by remember {
            DebridSettingsRepository.ensureLoaded()
            DebridSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val traktAuthUiState by remember {
            TraktAuthRepository.ensureLoaded()
            TraktAuthRepository.uiState
        }.collectAsStateWithLifecycle()
        val simklAuthUiState by remember {
            SimklAuthRepository.ensureLoaded()
            SimklAuthRepository.uiState
        }.collectAsStateWithLifecycle()
        val traktCommentsEnabled by remember {
            TraktCommentsSettings.ensureLoaded()
            TraktCommentsSettings.enabled
        }.collectAsStateWithLifecycle()
        val trackingSettingsUiState by remember {
            TrackingSettingsRepository.ensureLoaded()
            TrackingSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val addonsUiState by remember {
            AddonRepository.initialize()
            AddonRepository.uiState
        }.collectAsStateWithLifecycle()
        val homescreenCatalogRefreshKey = remember(addonsUiState.addons) {
            buildAddonCatalogRefreshSignature(addonsUiState.addons)
        }
        val addonManifestsLoading = addonsUiState.addons.hasPendingEnabledManifests()
        val addonManifestErrorMessage = addonsUiState.addons.firstEnabledManifestError()
        val homescreenSettingsUiState by remember {
            HomeCatalogSettingsRepository.snapshot()
            HomeCatalogSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
        val metaScreenSettingsUiState by remember {
            MetaScreenSettingsRepository.ensureLoaded()
            MetaScreenSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val continueWatchingPreferencesUiState by remember {
            ContinueWatchingPreferencesRepository.ensureLoaded()
            ContinueWatchingPreferencesRepository.uiState
        }.collectAsStateWithLifecycle()
        val posterCardStyleUiState by remember {
            PosterCardStyleRepository.ensureLoaded()
            PosterCardStyleRepository.uiState
        }.collectAsStateWithLifecycle()
        val episodeReleaseNotificationsUiState by remember {
            EpisodeReleaseNotificationsRepository.ensureLoaded()
            EpisodeReleaseNotificationsRepository.uiState
        }.collectAsStateWithLifecycle()
        val profileSettingsState by remember {
            ProfileRepository.state
        }.collectAsStateWithLifecycle()

        LaunchedEffect(homescreenCatalogRefreshKey) {
            val enabledAddons = addonsUiState.addons.enabledAddons()
            if (!enabledAddons.isWaitingForFirstEnabledManifest()) {
                HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
            }
        }

        LaunchedEffect(Unit) {
            CollectionRepository.initialize()
        }

        LaunchedEffect(collections) {
            HomeCatalogSettingsRepository.syncCollections(collections)
        }

        val initialPage = remember(initialPageName) {
            runCatching { SettingsPage.valueOf(initialPageName) }
                .getOrDefault(SettingsPage.Root)
                .takeIf { it.isEnabledByPolicy() }
                ?: SettingsPage.Root
        }
        var currentPage by rememberSaveable(initialPageName) { mutableStateOf(initialPage.name) }
        val scrollToTopRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
        val pageTitles = if (onNavigatePage != null) settingsPageTitles() else emptyMap()
        val page = remember(currentPage) {
            runCatching { SettingsPage.valueOf(currentPage) }
                .getOrDefault(SettingsPage.Root)
                .takeIf { it.isEnabledByPolicy() }
                ?: SettingsPage.Root
        }
        val previousPage = page.previousPage()

        fun openPage(targetPage: SettingsPage) {
            if (!targetPage.isEnabledByPolicy()) return
            val externalNavigator = onNavigatePage
            if (externalNavigator == null) {
                currentPage = targetPage.name
                return
            }
            if (targetPage == SettingsPage.Root && onExternalBack != null) {
                onExternalBack()
                return
            }
            externalNavigator(
                targetPage.name,
                pageTitles.getValue(targetPage),
            )
        }

        fun navigateBack() {
            val parentPage = previousPage ?: return
            if (onNavigatePage != null && onExternalBack != null) {
                onExternalBack()
            } else {
                currentPage = parentPage.name
            }
        }

        val openHomescreen = if (onNavigatePage != null) {
            { openPage(SettingsPage.Homescreen) }
        } else {
            onHomescreenClick
        }
        val openMetaScreen = if (onNavigatePage != null) {
            { openPage(SettingsPage.MetaScreen) }
        } else {
            onMetaScreenClick
        }
        val openContinueWatching = if (onNavigatePage != null) {
            { openPage(SettingsPage.ContinueWatching) }
        } else {
            onContinueWatchingClick
        }
        val openAddons = if (onNavigatePage != null) {
            { openPage(SettingsPage.Addons) }
        } else {
            onAddonsClick
        }
        val openPlugins = if (onNavigatePage != null) {
            { openPage(SettingsPage.Plugins) }
        } else {
            onPluginsClick
        }
        val openAccount = if (onNavigatePage != null) {
            { openPage(SettingsPage.Account) }
        } else {
            onAccountClick
        }
        val openSupportersContributors = if (onNavigatePage != null) {
            { openPage(SettingsPage.SupportersContributors) }
        } else {
            onSupportersContributorsClick
        }
        val openLicensesAttributions = if (onNavigatePage != null) {
            { openPage(SettingsPage.LicensesAttributions) }
        } else {
            onLicensesAttributionsClick
        }

        LaunchedEffect(page, currentPage) {
            if (page.name != currentPage) {
                currentPage = page.name
            }
        }

        ScreenActivityEffect(rootActionRequests, rootActionsEnabled, page) { active ->
            if (!active || !rootActionsEnabled) return@ScreenActivityEffect
            rootActionRequests.collect {
                val pageToOpen = page.previousPage()
                if (pageToOpen != null) {
                    navigateBack()
                } else {
                    scrollToTopRequests.tryEmit(Unit)
                }
            }
        }

        ScreenActivityEffect(requestedPageName, rootActionsEnabled) { active ->
            if (!active || !rootActionsEnabled) return@ScreenActivityEffect
            val requestedPage = requestedPageName ?: return@ScreenActivityEffect
            val targetPage = runCatching { SettingsPage.valueOf(requestedPage) }.getOrNull()
            if (targetPage == null || !targetPage.isEnabledByPolicy()) {
                onRequestedPageConsumed()
                return@ScreenActivityEffect
            }
            openPage(targetPage)
            onRequestedPageConsumed()
        }

        PlatformBackHandler(
            enabled = screenActive && previousPage != null && (rootActionsEnabled || onExternalBack != null),
            onBack = ::navigateBack,
        )

        if (isSelectedTab || page == SettingsPage.Root) {
            pageStateHolder.SaveableStateProvider("content") {
                if (maxWidth >= 768.dp) {
                    TabletSettingsScreen(
                        page = page,
                        scrollToTopRequests = scrollToTopRequests,
                        onPageChange = ::openPage,
                        onNavigateBack = ::navigateBack,
                        showInternalHeader = showInternalHeader,
                        showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                        holdToSpeedEnabled = playerSettingsUiState.holdToSpeedEnabled,
                        holdToSpeedValue = playerSettingsUiState.holdToSpeedValue,
                        touchGesturesEnabled = playerSettingsUiState.touchGesturesEnabled,
                        preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                        secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                        preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                        secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                        streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                        streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                        androidPlaybackEngine = playerSettingsUiState.androidPlaybackEngine,
                        androidLibmpvVideoOutput = playerSettingsUiState.androidLibmpvVideoOutput,
                        androidLibmpvHardwareDecodingEnabled = playerSettingsUiState.androidLibmpvHardwareDecodingEnabled,
                        androidLibmpvYuv420pEnabled = playerSettingsUiState.androidLibmpvYuv420pEnabled,
                        decoderPriority = playerSettingsUiState.decoderPriority,
                        mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                        tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                        useLibass = playerSettingsUiState.useLibass,
                        libassRenderType = playerSettingsUiState.libassRenderType,
                        rememberLastProfileEnabled = profileSettingsState.rememberLastProfileEnabled,
                        selectedTheme = selectedTheme,
                        onThemeSelected = ThemeSettingsRepository::setTheme,
                        amoledEnabled = amoledEnabled,
                        onAmoledToggle = ThemeSettingsRepository::setAmoled,
                        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                        liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                        onLiquidGlassNativeTabBarToggle = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                        appIconState = appIconState,
                        onAppIconSelected = onAppIconSelected,
                        onAppIconFailureDismissed = AppIconRepository::clearFailure,
                        selectedAppLanguage = selectedAppLanguage,
                        onAppLanguageSelected = ThemeSettingsRepository::setAppLanguage,
                        navBarStyle = navBarStyle,
                        onNavBarStyleSelected = ThemeSettingsRepository::setNavBarStyle,
                        episodeReleaseNotificationsUiState = episodeReleaseNotificationsUiState,
                        tmdbSettings = tmdbSettings,
                        mdbListSettings = mdbListSettings,
                        debridSettings = debridSettings,
                        traktAuthUiState = traktAuthUiState,
                        simklAuthUiState = simklAuthUiState,
                        traktCommentsEnabled = traktCommentsEnabled,
                        trackingSettingsUiState = trackingSettingsUiState,
                        homescreenHeroEnabled = homescreenSettingsUiState.heroEnabled,
                        homescreenShowCatalogType = homescreenSettingsUiState.showCatalogType,
                        homescreenHideUnreleasedContent = homescreenSettingsUiState.hideUnreleasedContent,
                        homescreenItems = homescreenSettingsUiState.items,
                        homescreenCatalogLoading = addonManifestsLoading,
                        homescreenCatalogErrorMessage = addonManifestErrorMessage,
                        metaScreenSettingsUiState = metaScreenSettingsUiState,
                        continueWatchingPreferencesUiState = continueWatchingPreferencesUiState,
                        posterCardStyleUiState = posterCardStyleUiState,
                        onSwitchProfile = onSwitchProfile,
                        onDownloadsClick = onDownloadsClick,
                        onSupportersContributorsClick = openSupportersContributors,
                        onLicensesAttributionsClick = openLicensesAttributions,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                        onTestUpdateBannerClick = onTestUpdateBannerClick,
                        onCollectionsClick = onCollectionsClick,
                    )
                } else {
                    MobileSettingsScreen(
                        page = page,
                        scrollToTopRequests = scrollToTopRequests,
                        onPageChange = ::openPage,
                        onNavigateBack = ::navigateBack,
                        showInternalHeader = showInternalHeader,
                        showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                        holdToSpeedEnabled = playerSettingsUiState.holdToSpeedEnabled,
                        holdToSpeedValue = playerSettingsUiState.holdToSpeedValue,
                        touchGesturesEnabled = playerSettingsUiState.touchGesturesEnabled,
                        preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                        secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                        preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                        secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                        streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                        streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                        androidPlaybackEngine = playerSettingsUiState.androidPlaybackEngine,
                        androidLibmpvVideoOutput = playerSettingsUiState.androidLibmpvVideoOutput,
                        androidLibmpvHardwareDecodingEnabled = playerSettingsUiState.androidLibmpvHardwareDecodingEnabled,
                        androidLibmpvYuv420pEnabled = playerSettingsUiState.androidLibmpvYuv420pEnabled,
                        decoderPriority = playerSettingsUiState.decoderPriority,
                        mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                        tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                        useLibass = playerSettingsUiState.useLibass,
                        libassRenderType = playerSettingsUiState.libassRenderType,
                        rememberLastProfileEnabled = profileSettingsState.rememberLastProfileEnabled,
                        selectedTheme = selectedTheme,
                        onThemeSelected = ThemeSettingsRepository::setTheme,
                        amoledEnabled = amoledEnabled,
                        onAmoledToggle = ThemeSettingsRepository::setAmoled,
                        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                        liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                        onLiquidGlassNativeTabBarToggle = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                        appIconState = appIconState,
                        onAppIconSelected = onAppIconSelected,
                        onAppIconFailureDismissed = AppIconRepository::clearFailure,
                        selectedAppLanguage = selectedAppLanguage,
                        onAppLanguageSelected = ThemeSettingsRepository::setAppLanguage,
                        navBarStyle = navBarStyle,
                        onNavBarStyleSelected = ThemeSettingsRepository::setNavBarStyle,
                        episodeReleaseNotificationsUiState = episodeReleaseNotificationsUiState,
                        tmdbSettings = tmdbSettings,
                        mdbListSettings = mdbListSettings,
                        debridSettings = debridSettings,
                        traktAuthUiState = traktAuthUiState,
                        simklAuthUiState = simklAuthUiState,
                        traktCommentsEnabled = traktCommentsEnabled,
                        trackingSettingsUiState = trackingSettingsUiState,
                        homescreenHeroEnabled = homescreenSettingsUiState.heroEnabled,
                        homescreenShowCatalogType = homescreenSettingsUiState.showCatalogType,
                        homescreenHideUnreleasedContent = homescreenSettingsUiState.hideUnreleasedContent,
                        homescreenItems = homescreenSettingsUiState.items,
                        homescreenCatalogLoading = addonManifestsLoading,
                        homescreenCatalogErrorMessage = addonManifestErrorMessage,
                        metaScreenSettingsUiState = metaScreenSettingsUiState,
                        continueWatchingPreferencesUiState = continueWatchingPreferencesUiState,
                        posterCardStyleUiState = posterCardStyleUiState,
                        onSwitchProfile = onSwitchProfile,
                        onHomescreenClick = openHomescreen,
                        onMetaScreenClick = openMetaScreen,
                        onContinueWatchingClick = openContinueWatching,
                        onAddonsClick = openAddons,
                        onPluginsClick = openPlugins,
                        onDownloadsClick = onDownloadsClick,
                        onAccountClick = openAccount,
                        onSupportersContributorsClick = openSupportersContributors,
                        onLicensesAttributionsClick = openLicensesAttributions,
                        onCheckForUpdatesClick = onCheckForUpdatesClick,
                        onTestUpdateBannerClick = onTestUpdateBannerClick,
                        onCollectionsClick = onCollectionsClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileSettingsScreen(
    page: SettingsPage,
    scrollToTopRequests: Flow<Unit>,
    onPageChange: (SettingsPage) -> Unit,
    onNavigateBack: () -> Unit,
    showInternalHeader: Boolean,
    showLoadingOverlay: Boolean,
    holdToSpeedEnabled: Boolean,
    holdToSpeedValue: Float,
    touchGesturesEnabled: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    androidPlaybackEngine: AndroidPlaybackEngine,
    androidLibmpvVideoOutput: AndroidLibmpvVideoOutput,
    androidLibmpvHardwareDecodingEnabled: Boolean,
    androidLibmpvYuv420pEnabled: Boolean,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
    rememberLastProfileEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    onLiquidGlassNativeTabBarToggle: (Boolean) -> Unit,
    appIconState: AppIconSettingsState,
    onAppIconSelected: (AppIconOption) -> Unit,
    onAppIconFailureDismissed: () -> Unit,
    selectedAppLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    navBarStyle: NavBarStyle,
    onNavBarStyleSelected: (NavBarStyle) -> Unit,
    episodeReleaseNotificationsUiState: EpisodeReleaseNotificationsUiState,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    debridSettings: DebridSettings,
    traktAuthUiState: TraktAuthUiState,
    simklAuthUiState: SimklAuthUiState,
    traktCommentsEnabled: Boolean,
    trackingSettingsUiState: TrackingSettingsUiState,
    homescreenHeroEnabled: Boolean,
    homescreenShowCatalogType: Boolean,
    homescreenHideUnreleasedContent: Boolean,
    homescreenItems: List<HomeCatalogSettingsItem>,
    homescreenCatalogLoading: Boolean,
    homescreenCatalogErrorMessage: String?,
    metaScreenSettingsUiState: MetaScreenSettingsUiState,
    continueWatchingPreferencesUiState: ContinueWatchingPreferencesUiState,
    posterCardStyleUiState: PosterCardStyleUiState,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onMetaScreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onPluginsClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onTestUpdateBannerClick: (() -> Unit)? = null,
    onCollectionsClick: () -> Unit = {},
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    saveableStateHolder.SaveableStateProvider(page.name) {
        var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
        var rootSearchVisible by rememberSaveable { mutableStateOf(false) }
        var rootSearchRevealAnimating by rememberSaveable { mutableStateOf(false) }
        val listState = rememberLazyListState()
        ScreenActivityEffect(listState) { screenActive ->
            if (!screenActive) listState.stopScroll()
        }
        val hapticFeedback = LocalHapticFeedback.current
        val hapticScope = rememberCoroutineScope()
        val rootSearchRevealConnection = rememberSettingsRootSearchRevealConnection(
            page = page,
            listState = listState,
            query = settingsSearchQuery,
            searchVisible = rootSearchVisible,
        ) {
            rootSearchVisible = true
            rootSearchRevealAnimating = true
            hapticScope.launch {
                delay(SettingsSearchRevealHapticDelayMillis)
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        fun openSearchTarget(target: SettingsSearchTarget) {
            when (target) {
                is SettingsSearchTarget.Page -> when (target.page) {
                    SettingsPage.Account -> onAccountClick()
                    SettingsPage.SupportersContributors -> {
                        if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                            onSupportersContributorsClick()
                        }
                    }
                    SettingsPage.LicensesAttributions -> onLicensesAttributionsClick()
                    SettingsPage.ContinueWatching -> onContinueWatchingClick()
                    SettingsPage.Addons -> onAddonsClick()
                    SettingsPage.Plugins -> {
                        if (AppFeaturePolicy.pluginsEnabled) {
                            onPluginsClick()
                        }
                    }
                    SettingsPage.Homescreen -> onHomescreenClick()
                    SettingsPage.MetaScreen -> onMetaScreenClick()
                    else -> onPageChange(target.page)
                }
                SettingsSearchTarget.Downloads -> onDownloadsClick()
                SettingsSearchTarget.Collections -> onCollectionsClick()
                SettingsSearchTarget.SwitchProfile -> onSwitchProfile?.invoke()
                SettingsSearchTarget.CheckForUpdates -> onCheckForUpdatesClick?.invoke()
            }
        }

        LaunchedEffect(rootSearchRevealAnimating) {
            if (rootSearchRevealAnimating) {
                delay(SettingsSearchRevealAnimationMillis)
                rootSearchRevealAnimating = false
            }
        }

        LaunchedEffect(scrollToTopRequests) {
            scrollToTopRequests.collect {
                listState.animateScrollToItem(0)
            }
        }

        NuvioScreen(
            modifier = Modifier.nestedScroll(rootSearchRevealConnection),
            listState = listState,
        ) {
            if (showInternalHeader) {
                stickyHeader {
                    val previousPage = page.previousPage()
                    NuvioScreenHeader(
                        title = stringResource(page.titleRes),
                        onBack = previousPage?.let { { onNavigateBack() } },
                    )
                }
            } else {
                item { Spacer(modifier = Modifier.height(44.dp)) }
            }

            when (page) {
                SettingsPage.Root -> {
                    settingsSearchRootContent(
                        query = settingsSearchQuery,
                        entries = {
                            settingsSearchEntries(
                                isTablet = false,
                                pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
                                supportersContributorsPageEnabled = AppFeaturePolicy.supportersContributorsPageEnabled,
                                accountDeletionEnabled = AppFeaturePolicy.accountDeletionEnabled,
                                personalMediaAddonCopyEnabled = AppFeaturePolicy.personalMediaAddonCopyEnabled,
                                liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                                switchProfileAvailable = onSwitchProfile != null,
                                checkForUpdatesAvailable = onCheckForUpdatesClick != null,
                            )
                        },
                        isTablet = false,
                        showSearchField = rootSearchVisible,
                        animateSearchField = rootSearchRevealAnimating,
                        onQueryChange = { settingsSearchQuery = it },
                        onTargetClick = { openSearchTarget(it) },
                    )
                    if (settingsSearchQuery.isBlank()) {
                        settingsRootContent(
                            isTablet = false,
                            onPlaybackClick = { onPageChange(SettingsPage.Playback) },
                            onAppearanceClick = { onPageChange(SettingsPage.Appearance) },
                            onAdvancedClick = { onPageChange(SettingsPage.Advanced) },
                            onNotificationsClick = { onPageChange(SettingsPage.Notifications) },
                            onContentDiscoveryClick = { onPageChange(SettingsPage.ContentDiscovery) },
                            onIntegrationsClick = { onPageChange(SettingsPage.Integrations) },
                            onTrackingClick = { onPageChange(SettingsPage.TraktAuthentication) },
                            onSupportersContributorsClick = onSupportersContributorsClick,
                            onLicensesAttributionsClick = onLicensesAttributionsClick,
                            onCheckForUpdatesClick = onCheckForUpdatesClick,
                            onTestUpdateBannerClick = onTestUpdateBannerClick,
                            onDownloadsClick = onDownloadsClick,
                            onAccountClick = onAccountClick,
                            onSwitchProfileClick = onSwitchProfile,
                            showSupportersContributorsPage = AppFeaturePolicy.supportersContributorsPageEnabled,
                        )
                    }
                }
                SettingsPage.Account -> accountSettingsContent(
                    isTablet = false,
                )
                SettingsPage.SupportersContributors -> {
                    if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                        supportersContributorsContent(isTablet = false)
                    }
                }
                SettingsPage.LicensesAttributions -> licensesAttributionsContent(
                    isTablet = false,
                )
                SettingsPage.Playback -> playbackSettingsContent(
                    isTablet = false,
                    showLoadingOverlay = showLoadingOverlay,
                    holdToSpeedEnabled = holdToSpeedEnabled,
                    holdToSpeedValue = holdToSpeedValue,
                    touchGesturesEnabled = touchGesturesEnabled,
                    preferredAudioLanguage = preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                    secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                    streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                    streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                    androidPlaybackEngine = androidPlaybackEngine,
                    androidLibmpvVideoOutput = androidLibmpvVideoOutput,
                    androidLibmpvHardwareDecodingEnabled = androidLibmpvHardwareDecodingEnabled,
                    androidLibmpvYuv420pEnabled = androidLibmpvYuv420pEnabled,
                    decoderPriority = decoderPriority,
                    mapDV7ToHevc = mapDV7ToHevc,
                    tunnelingEnabled = tunnelingEnabled,
                    useLibass = useLibass,
                    libassRenderType = libassRenderType,
                )
                SettingsPage.Streams -> streamsSettingsContent(
                    isTablet = false,
                )
                SettingsPage.Appearance -> appearanceSettingsContent(
                    isTablet = false,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
                    amoledEnabled = amoledEnabled,
                    onAmoledToggle = onAmoledToggle,
                    liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                    liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                    onLiquidGlassNativeTabBarToggle = onLiquidGlassNativeTabBarToggle,
                    appIconState = appIconState,
                    onAppIconSelected = onAppIconSelected,
                    onAppIconFailureDismissed = onAppIconFailureDismissed,
                    selectedAppLanguage = selectedAppLanguage,
                    onAppLanguageSelected = onAppLanguageSelected,
                    selectedNavBarStyle = navBarStyle,
                    onNavBarStyleSelected = onNavBarStyleSelected,
                    onHomescreenClick = onHomescreenClick,
                    onMetaScreenClick = onMetaScreenClick,
                    onStreamsClick = { onPageChange(SettingsPage.Streams) },
                    onCollectionsClick = onCollectionsClick,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onPosterCustomizationClick = { onPageChange(SettingsPage.PosterCustomization) },
                )
                SettingsPage.Advanced -> advancedSettingsContent(
                    isTablet = false,
                    rememberLastProfileEnabled = rememberLastProfileEnabled,
                )
                SettingsPage.Notifications -> notificationsSettingsContent(
                    isTablet = false,
                    uiState = episodeReleaseNotificationsUiState,
                )
                SettingsPage.ContinueWatching -> continueWatchingSettingsContent(
                    isTablet = false,
                    isVisible = continueWatchingPreferencesUiState.isVisible,
                    style = continueWatchingPreferencesUiState.style,
                    upNextFromFurthestEpisode = continueWatchingPreferencesUiState.upNextFromFurthestEpisode,
                    useEpisodeThumbnails = continueWatchingPreferencesUiState.useEpisodeThumbnails,
                    showUnairedNextUp = continueWatchingPreferencesUiState.showUnairedNextUp,
                    blurNextUp = continueWatchingPreferencesUiState.blurNextUp,
                    showResumePromptOnLaunch = continueWatchingPreferencesUiState.showResumePromptOnLaunch,
                    sortMode = continueWatchingPreferencesUiState.sortMode,
                )
                SettingsPage.PosterCustomization -> posterCustomizationSettingsContent(
                    isTablet = false,
                    uiState = posterCardStyleUiState,
                )
                SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                    isTablet = false,
                    showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                    onAddonsClick = onAddonsClick,
                    onPluginsClick = onPluginsClick,
                    onMediaServersClick = { onPageChange(SettingsPage.MediaServers) },
                )
                SettingsPage.Addons -> addonsSettingsContent()
                SettingsPage.MediaServers -> mediaServersSettingsContent(isTablet = false)
                SettingsPage.Plugins -> if (AppFeaturePolicy.pluginsEnabled) pluginsSettingsContent() else addonsSettingsContent()
                SettingsPage.Homescreen -> homescreenSettingsContent(
                    isTablet = false,
                    heroEnabled = homescreenHeroEnabled,
                    showCatalogType = homescreenShowCatalogType,
                    hideUnreleasedContent = homescreenHideUnreleasedContent,
                    items = homescreenItems,
                    isCatalogLoading = homescreenCatalogLoading,
                    catalogErrorMessage = homescreenCatalogErrorMessage,
                )
                SettingsPage.MetaScreen -> metaScreenSettingsContent(
                    isTablet = false,
                    uiState = metaScreenSettingsUiState,
                )
                SettingsPage.Integrations -> integrationsContent(
                    isTablet = false,
                    onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                    onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
                    onDebridClick = { onPageChange(SettingsPage.Debrid) },
                )
                SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                    isTablet = false,
                    settings = tmdbSettings,
                )
                SettingsPage.MdbListRatings -> mdbListSettingsContent(
                    isTablet = false,
                    settings = mdbListSettings,
                )
                SettingsPage.Debrid -> debridSettingsContent(
                    isTablet = false,
                    settings = debridSettings,
                )
                SettingsPage.TraktAuthentication -> trackingSettingsContent(
                    isTablet = false,
                    traktUiState = traktAuthUiState,
                    simklUiState = simklAuthUiState,
                    settingsUiState = trackingSettingsUiState,
                    commentsEnabled = traktCommentsEnabled,
                    onCommentsEnabledChange = TraktCommentsSettings::setEnabled,
                )
            }
        }
    }
}

@Composable
private fun rememberSettingsRootSearchRevealConnection(
    page: SettingsPage,
    listState: LazyListState,
    query: String,
    searchVisible: Boolean,
    onReveal: () -> Unit,
): NestedScrollConnection {
    val revealThresholdPx = with(LocalDensity.current) { SettingsSearchRevealThreshold.toPx() }
    val currentOnReveal by rememberUpdatedState(onReveal)
    var pullDistancePx by remember(page) { mutableStateOf(0f) }
    var revealTriggered by remember(page) { mutableStateOf(false) }

    return remember(page, listState, query, searchVisible, revealThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val isRootAtTop = page == SettingsPage.Root &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val canRevealSearch = isRootAtTop && !searchVisible && !revealTriggered && query.isBlank()

                if (canRevealSearch && available.y > 0f) {
                    pullDistancePx += available.y
                    if (pullDistancePx >= revealThresholdPx) {
                        pullDistancePx = 0f
                        revealTriggered = true
                        currentOnReveal()
                    }
                } else if (!isRootAtTop || available.y < 0f) {
                    pullDistancePx = 0f
                }

                return Offset.Zero
            }
        }
    }
}

@Composable
private fun TabletSettingsScreen(
    page: SettingsPage,
    scrollToTopRequests: Flow<Unit>,
    onPageChange: (SettingsPage) -> Unit,
    onNavigateBack: () -> Unit,
    showInternalHeader: Boolean,
    showLoadingOverlay: Boolean,
    holdToSpeedEnabled: Boolean,
    holdToSpeedValue: Float,
    touchGesturesEnabled: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    androidPlaybackEngine: AndroidPlaybackEngine,
    androidLibmpvVideoOutput: AndroidLibmpvVideoOutput,
    androidLibmpvHardwareDecodingEnabled: Boolean,
    androidLibmpvYuv420pEnabled: Boolean,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    useLibass: Boolean,
    libassRenderType: String,
    rememberLastProfileEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    liquidGlassNativeTabBarSupported: Boolean,
    liquidGlassNativeTabBarEnabled: Boolean,
    onLiquidGlassNativeTabBarToggle: (Boolean) -> Unit,
    appIconState: AppIconSettingsState,
    onAppIconSelected: (AppIconOption) -> Unit,
    onAppIconFailureDismissed: () -> Unit,
    selectedAppLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    navBarStyle: NavBarStyle,
    onNavBarStyleSelected: (NavBarStyle) -> Unit,
    episodeReleaseNotificationsUiState: EpisodeReleaseNotificationsUiState,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    debridSettings: DebridSettings,
    traktAuthUiState: TraktAuthUiState,
    simklAuthUiState: SimklAuthUiState,
    traktCommentsEnabled: Boolean,
    trackingSettingsUiState: TrackingSettingsUiState,
    homescreenHeroEnabled: Boolean,
    homescreenShowCatalogType: Boolean,
    homescreenHideUnreleasedContent: Boolean,
    homescreenItems: List<HomeCatalogSettingsItem>,
    homescreenCatalogLoading: Boolean,
    homescreenCatalogErrorMessage: String?,
    metaScreenSettingsUiState: MetaScreenSettingsUiState,
    continueWatchingPreferencesUiState: ContinueWatchingPreferencesUiState,
    posterCardStyleUiState: PosterCardStyleUiState,
    onSwitchProfile: (() -> Unit)? = null,
    onDownloadsClick: () -> Unit = {},
    onSupportersContributorsClick: () -> Unit = {},
    onLicensesAttributionsClick: () -> Unit = {},
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onTestUpdateBannerClick: (() -> Unit)? = null,
    onCollectionsClick: () -> Unit = {},
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.General.name) }
    val activeCategory = SettingsCategory.valueOf(selectedCategory)
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topOffset = max(statusBarPadding + 24.dp, 48.dp) + 64.dp

    LaunchedEffect(page) {
        if (page.opensInlineOnTablet) {
            selectedCategory = page.category.name
        }
    }

    fun openInlinePage(page: SettingsPage) {
        selectedCategory = page.category.name
        onPageChange(page)
    }

    val saveableStateHolder = rememberSaveableStateHolder()

    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topOffset),
            ) {
                Text(
                    text = stringResource(Res.string.compose_settings_page_root),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 20.dp),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(10.dp))
                SettingsCategory.entries.forEach { category ->
                    SettingsSidebarItem(
                        label = stringResource(category.labelRes),
                        icon = category.icon,
                        selected = category == activeCategory,
                        onClick = {
                            selectedCategory = category.name
                            if (page != SettingsPage.Root) {
                                onPageChange(SettingsPage.Root)
                            }
                        },
                    )
                }
            }
        }

        saveableStateHolder.SaveableStateProvider(page.name) {
            var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
            var rootSearchVisible by rememberSaveable { mutableStateOf(false) }
            var rootSearchRevealAnimating by rememberSaveable { mutableStateOf(false) }
            val hapticFeedback = LocalHapticFeedback.current
            val hapticScope = rememberCoroutineScope()
            fun openSearchTarget(target: SettingsSearchTarget) {
                when (target) {
                    is SettingsSearchTarget.Page -> {
                        if (target.page.isEnabledByPolicy()) {
                            openInlinePage(target.page)
                        }
                    }
                    SettingsSearchTarget.Downloads -> onDownloadsClick()
                    SettingsSearchTarget.Collections -> onCollectionsClick()
                    SettingsSearchTarget.SwitchProfile -> onSwitchProfile?.invoke()
                    SettingsSearchTarget.CheckForUpdates -> onCheckForUpdatesClick?.invoke()
                }
            }

            val listState = rememberLazyListState()
            ScreenActivityEffect(listState) { screenActive ->
                if (!screenActive) listState.stopScroll()
            }
            val bottomOverlayPadding = LocalNuvioBottomNavigationOverlayPadding.current
            val rootSearchRevealConnection = rememberSettingsRootSearchRevealConnection(
                page = page,
                listState = listState,
                query = settingsSearchQuery,
                searchVisible = rootSearchVisible,
            ) {
                rootSearchVisible = true
                rootSearchRevealAnimating = true
                hapticScope.launch {
                    delay(SettingsSearchRevealHapticDelayMillis)
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            LaunchedEffect(rootSearchRevealAnimating) {
                if (rootSearchRevealAnimating) {
                    delay(SettingsSearchRevealAnimationMillis)
                    rootSearchRevealAnimating = false
                }
            }
            LaunchedEffect(scrollToTopRequests) {
                scrollToTopRequests.collect {
                    listState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(rootSearchRevealConnection),
                contentPadding = PaddingValues(
                    start = 40.dp,
                    top = topOffset,
                    end = 40.dp,
                    bottom = 40.dp + bottomOverlayPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (showInternalHeader) {
                    item {
                        val previousPage = page.previousPage()
                        TabletPageHeader(
                            title = if (page == SettingsPage.Root) {
                                if (settingsSearchQuery.isBlank()) {
                                    stringResource(activeCategory.labelRes)
                                } else {
                                    stringResource(Res.string.compose_settings_page_root)
                                }
                            } else {
                                stringResource(page.titleRes)
                            },
                            showBack = previousPage != null,
                            onBack = onNavigateBack,
                        )
                    }
                }
                when (page) {
                    SettingsPage.Root -> {
                        settingsSearchRootContent(
                            query = settingsSearchQuery,
                            entries = {
                                settingsSearchEntries(
                                    isTablet = true,
                                    pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
                                    supportersContributorsPageEnabled = AppFeaturePolicy.supportersContributorsPageEnabled,
                                    accountDeletionEnabled = AppFeaturePolicy.accountDeletionEnabled,
                                    personalMediaAddonCopyEnabled = AppFeaturePolicy.personalMediaAddonCopyEnabled,
                                    liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                                    switchProfileAvailable = onSwitchProfile != null,
                                    checkForUpdatesAvailable = onCheckForUpdatesClick != null,
                                )
                            },
                            isTablet = true,
                            showSearchField = rootSearchVisible,
                            animateSearchField = rootSearchRevealAnimating,
                            onQueryChange = { settingsSearchQuery = it },
                            onTargetClick = { openSearchTarget(it) },
                        )
                        if (settingsSearchQuery.isBlank()) {
                            settingsRootContent(
                                isTablet = true,
                                onPlaybackClick = { openInlinePage(SettingsPage.Playback) },
                                onAppearanceClick = { openInlinePage(SettingsPage.Appearance) },
                                onAdvancedClick = { openInlinePage(SettingsPage.Advanced) },
                                onNotificationsClick = { openInlinePage(SettingsPage.Notifications) },
                                onContentDiscoveryClick = { openInlinePage(SettingsPage.ContentDiscovery) },
                                onIntegrationsClick = { openInlinePage(SettingsPage.Integrations) },
                                onTrackingClick = { openInlinePage(SettingsPage.TraktAuthentication) },
                                onSupportersContributorsClick = { openInlinePage(SettingsPage.SupportersContributors) },
                                onLicensesAttributionsClick = { openInlinePage(SettingsPage.LicensesAttributions) },
                                onCheckForUpdatesClick = onCheckForUpdatesClick,
                                onTestUpdateBannerClick = onTestUpdateBannerClick,
                                onDownloadsClick = onDownloadsClick,
                                onAccountClick = { openInlinePage(SettingsPage.Account) },
                                onSwitchProfileClick = onSwitchProfile,
                                showAccountSection = activeCategory == SettingsCategory.Account,
                                showGeneralSection = activeCategory == SettingsCategory.General,
                                showAboutSection = activeCategory == SettingsCategory.About,
                                showAdvancedSection = activeCategory == SettingsCategory.Advanced,
                                showSupportersContributorsPage = AppFeaturePolicy.supportersContributorsPageEnabled,
                            )
                        }
                    }
                    SettingsPage.Account -> accountSettingsContent(
                        isTablet = true,
                    )
                    SettingsPage.SupportersContributors -> {
                        if (AppFeaturePolicy.supportersContributorsPageEnabled) {
                            supportersContributorsContent(isTablet = true)
                        }
                    }
                    SettingsPage.LicensesAttributions -> licensesAttributionsContent(
                        isTablet = true,
                    )
                    SettingsPage.Playback -> playbackSettingsContent(
                        isTablet = true,
                        showLoadingOverlay = showLoadingOverlay,
                        holdToSpeedEnabled = holdToSpeedEnabled,
                        holdToSpeedValue = holdToSpeedValue,
                        touchGesturesEnabled = touchGesturesEnabled,
                        preferredAudioLanguage = preferredAudioLanguage,
                        secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                        preferredSubtitleLanguage = preferredSubtitleLanguage,
                        secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                        streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                        streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                        androidPlaybackEngine = androidPlaybackEngine,
                        androidLibmpvVideoOutput = androidLibmpvVideoOutput,
                        androidLibmpvHardwareDecodingEnabled = androidLibmpvHardwareDecodingEnabled,
                        androidLibmpvYuv420pEnabled = androidLibmpvYuv420pEnabled,
                        decoderPriority = decoderPriority,
                        mapDV7ToHevc = mapDV7ToHevc,
                        tunnelingEnabled = tunnelingEnabled,
                        useLibass = useLibass,
                        libassRenderType = libassRenderType,
                    )
                    SettingsPage.Streams -> streamsSettingsContent(
                        isTablet = true,
                    )
                    SettingsPage.Appearance -> appearanceSettingsContent(
                        isTablet = true,
                        selectedTheme = selectedTheme,
                        onThemeSelected = onThemeSelected,
                        amoledEnabled = amoledEnabled,
                        onAmoledToggle = onAmoledToggle,
                        liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
                        liquidGlassNativeTabBarEnabled = liquidGlassNativeTabBarEnabled,
                        onLiquidGlassNativeTabBarToggle = onLiquidGlassNativeTabBarToggle,
                        appIconState = appIconState,
                        onAppIconSelected = onAppIconSelected,
                        onAppIconFailureDismissed = onAppIconFailureDismissed,
                        selectedAppLanguage = selectedAppLanguage,
                        onAppLanguageSelected = onAppLanguageSelected,
                        selectedNavBarStyle = navBarStyle,
                        onNavBarStyleSelected = onNavBarStyleSelected,
                        onHomescreenClick = { openInlinePage(SettingsPage.Homescreen) },
                        onMetaScreenClick = { openInlinePage(SettingsPage.MetaScreen) },
                        onStreamsClick = { openInlinePage(SettingsPage.Streams) },
                        onCollectionsClick = onCollectionsClick,
                        onContinueWatchingClick = { openInlinePage(SettingsPage.ContinueWatching) },
                        onPosterCustomizationClick = { openInlinePage(SettingsPage.PosterCustomization) },
                    )
                    SettingsPage.Advanced -> advancedSettingsContent(
                        isTablet = true,
                        rememberLastProfileEnabled = rememberLastProfileEnabled,
                    )
                    SettingsPage.Notifications -> notificationsSettingsContent(
                        isTablet = true,
                        uiState = episodeReleaseNotificationsUiState,
                    )
                    SettingsPage.ContinueWatching -> continueWatchingSettingsContent(
                        isTablet = true,
                        isVisible = continueWatchingPreferencesUiState.isVisible,
                        style = continueWatchingPreferencesUiState.style,
                        upNextFromFurthestEpisode = continueWatchingPreferencesUiState.upNextFromFurthestEpisode,
                        useEpisodeThumbnails = continueWatchingPreferencesUiState.useEpisodeThumbnails,
                        showUnairedNextUp = continueWatchingPreferencesUiState.showUnairedNextUp,
                        blurNextUp = continueWatchingPreferencesUiState.blurNextUp,
                        showResumePromptOnLaunch = continueWatchingPreferencesUiState.showResumePromptOnLaunch,
                        sortMode = continueWatchingPreferencesUiState.sortMode,
                    )
                    SettingsPage.PosterCustomization -> posterCustomizationSettingsContent(
                        isTablet = true,
                        uiState = posterCardStyleUiState,
                    )
                    SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                        isTablet = true,
                        showPluginsEntry = AppFeaturePolicy.pluginsEnabled,
                        onAddonsClick = { openInlinePage(SettingsPage.Addons) },
                        onPluginsClick = { openInlinePage(SettingsPage.Plugins) },
                        onMediaServersClick = { openInlinePage(SettingsPage.MediaServers) },
                    )
                    SettingsPage.Addons -> addonsSettingsContent()
                    SettingsPage.MediaServers -> mediaServersSettingsContent(isTablet = true)
                    SettingsPage.Plugins -> if (AppFeaturePolicy.pluginsEnabled) pluginsSettingsContent() else addonsSettingsContent()
                    SettingsPage.Homescreen -> homescreenSettingsContent(
                        isTablet = true,
                        heroEnabled = homescreenHeroEnabled,
                        showCatalogType = homescreenShowCatalogType,
                        hideUnreleasedContent = homescreenHideUnreleasedContent,
                        items = homescreenItems,
                        isCatalogLoading = homescreenCatalogLoading,
                        catalogErrorMessage = homescreenCatalogErrorMessage,
                    )
                    SettingsPage.MetaScreen -> metaScreenSettingsContent(
                        isTablet = true,
                        uiState = metaScreenSettingsUiState,
                    )
                    SettingsPage.Integrations -> integrationsContent(
                        isTablet = true,
                        onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                        onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
                        onDebridClick = { onPageChange(SettingsPage.Debrid) },
                    )
                    SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                        isTablet = true,
                        settings = tmdbSettings,
                    )
                    SettingsPage.MdbListRatings -> mdbListSettingsContent(
                        isTablet = true,
                        settings = mdbListSettings,
                    )
                    SettingsPage.Debrid -> debridSettingsContent(
                        isTablet = true,
                        settings = debridSettings,
                    )
                    SettingsPage.TraktAuthentication -> trackingSettingsContent(
                        isTablet = true,
                        traktUiState = traktAuthUiState,
                        simklUiState = simklAuthUiState,
                        settingsUiState = trackingSettingsUiState,
                        commentsEnabled = traktCommentsEnabled,
                        onCommentsEnabledChange = TraktCommentsSettings::setEnabled,
                    )
                }
            }
        }
    }
}
