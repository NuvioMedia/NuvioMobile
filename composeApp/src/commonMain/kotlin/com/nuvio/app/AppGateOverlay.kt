package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.navigation.TabsRoute

@Composable
internal fun AppGateOverlay(
    onActivate: (AppScreenTab) -> Unit,
    onAppReady: (Boolean) -> Unit,
    onMainContentMountChanged: (Boolean) -> Unit,
    onMainContentVisibleChanged: (Boolean) -> Unit,
    nativeProfileSwitcherController: NativeProfileSwitcherController,
    appGateController: AppGateController,
) {
    val currentOnActivate by rememberUpdatedState(onActivate)
    val currentOnAppReady by rememberUpdatedState(onAppReady)
    val currentOnMainContentMountChanged by rememberUpdatedState(onMainContentMountChanged)
    val currentOnMainContentVisibleChanged by rememberUpdatedState(onMainContentVisibleChanged)

    AppEnvironment {
        AppGate(
            initialTab = AppScreenTab.Home,
            initialRoute = TabsRoute,
            useNativeNavigation = true,
            useNativeTabBar = true,
            useTabletFloatingTabBar = false,
            ownsAppRuntime = true,
            bypassAppGate = false,
            renderMainContent = false,
            onNavigate = null,
            onGoBack = null,
            onReplace = null,
            onActivate = currentOnActivate,
            onAppReady = currentOnAppReady,
            onColdStartUiReady = null,
            onMainContentMountChanged = currentOnMainContentMountChanged,
            onMainContentVisibleChanged = currentOnMainContentVisibleChanged,
            onTabTitles = null,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
            appGateController = appGateController,
        )
    }
}
