package com.nuvio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.core.ui.LocalFold
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.navigation.AppRoute
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

private val nuvioBackgroundColor = UIColor(red = 0.051, green = 0.051, blue = 0.051, alpha = 1.0)

@Suppress("unused")
fun MainViewController(): UIViewController = nuvioComposeViewController {
    App()
}

@Suppress("unused")
fun MainViewController(
    initialTabName: String,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
    onTabTitles: (String, String, String, String, String, String) -> Unit,
    appGateController: AppGateController,
    verticalBar: VerticalBar,
): UIViewController {
    val initialTab = AppScreenTab.fromName(initialTabName)
    return nuvioComposeViewController(verticalBar) {
        App(
            initialTab = initialTab,
            useNativeNavigation = true,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = initialTab == AppScreenTab.Home,
            bypassAppGate = true,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = { tab -> onActivate(tab.name) },
            onTabTitles = onTabTitles,
            appGateController = appGateController,
        )
    }
}

@Suppress("unused")
fun ScreenViewController(
    route: AppRoute,
    onNavigate: (AppRoute, Boolean) -> Unit,
    onGoBack: () -> Unit,
    onReplace: (AppRoute) -> Unit,
    onActivate: (String) -> Unit,
    appGateController: AppGateController,
    verticalBar: VerticalBar,
    hinge: Hinge,
): UIViewController = nuvioComposeViewController(verticalBar, hinge) {
    App(
        initialRoute = route,
        useNativeNavigation = true,
        ownsAppRuntime = false,
        bypassAppGate = true,
        onNavigate = onNavigate,
        onGoBack = onGoBack,
        onReplace = onReplace,
        onActivate = { tab -> onActivate(tab.name) },
        appGateController = appGateController,
    )
}

@Suppress("unused")
@OptIn(ExperimentalComposeUiApi::class)
fun AppGateViewController(
    appGateController: AppGateController,
    nativeProfileSwitcherController: NativeProfileSwitcherController,
    onActivate: (String) -> Unit,
    onAppReady: (Boolean) -> Unit,
    onMainContentMountChanged: (Boolean) -> Unit,
    onMainContentVisibleChanged: (Boolean) -> Unit,
    verticalBar: VerticalBar,
): UIViewController = ComposeUIViewController(
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
        opaque = false
    },
    content = {
        CompositionLocalProvider(LocalVerticalBarPadding provides verticalBar.padding) {
            AppGateOverlay(
                onActivate = { tab -> onActivate(tab.name) },
                onAppReady = onAppReady,
                onMainContentMountChanged = onMainContentMountChanged,
                onMainContentVisibleChanged = onMainContentVisibleChanged,
                nativeProfileSwitcherController = nativeProfileSwitcherController,
                appGateController = appGateController,
            )
        }
    },
).apply {
    view.backgroundColor = UIColor.clearColor
}

private fun nuvioComposeViewController(
    verticalBar: VerticalBar = VerticalBar(),
    hinge: Hinge = Hinge(),
    content: @androidx.compose.runtime.Composable () -> Unit,
): UIViewController = ComposeUIViewController(
    configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
    content = {
        CompositionLocalProvider(
            LocalVerticalBarPadding provides verticalBar.padding,
            LocalFold provides hinge.fold,
            content = content,
        )
    },
).apply {
    view.backgroundColor = nuvioBackgroundColor
}
