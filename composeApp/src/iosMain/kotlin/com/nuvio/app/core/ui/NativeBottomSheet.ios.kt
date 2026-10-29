package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIColor
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UISheetPresentationController
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UISheetPresentationControllerDetentIdentifierMedium
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.darwin.NSObject

internal actual val usesNativeNuvioBottomSheet: Boolean = true

private var activeNativeBottomSheet: UIViewController? = null
private var activeNativeBottomSheetDelegate: NuvioNativeBottomSheetDelegate? = null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun NuvioNativeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    showDragHandle: Boolean,
    fullHeight: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val parentViewController = LocalUIViewController.current
    val latestContent = rememberUpdatedState(content)
    val latestOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val latestContainerColor = rememberUpdatedState(containerColor)
    val latestContentColor = rememberUpdatedState(contentColor)
    val latestModifier = rememberUpdatedState(modifier)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val themeTokens = LocalNuvioThemeTokens.current
    val typeScale = LocalNuvioTypeScale.current
    val appTheme = LocalAppTheme.current
    val themePalette = LocalThemePalette.current
    val density = LocalDensity.current
    val rippleConfiguration = LocalRippleConfiguration.current

    val latestColorScheme = rememberUpdatedState(colorScheme)
    val latestTypography = rememberUpdatedState(typography)
    val latestShapes = rememberUpdatedState(shapes)
    val latestThemeTokens = rememberUpdatedState(themeTokens)
    val latestTypeScale = rememberUpdatedState(typeScale)
    val latestAppTheme = rememberUpdatedState(appTheme)
    val latestThemePalette = rememberUpdatedState(themePalette)
    val latestDensity = rememberUpdatedState(density)
    val latestRippleConfiguration = rememberUpdatedState(rippleConfiguration)

    DisposableEffect(parentViewController) {
        val contentController = ComposeUIViewController {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = latestDensity.value.density,
                    fontScale = latestDensity.value.fontScale,
                ),
                LocalNuvioThemeTokens provides latestThemeTokens.value,
                LocalNuvioTypeScale provides latestTypeScale.value,
                LocalAppTheme provides latestAppTheme.value,
                LocalThemePalette provides latestThemePalette.value,
                LocalRippleConfiguration provides latestRippleConfiguration.value,
            ) {
                MaterialTheme(
                    colorScheme = latestColorScheme.value,
                    typography = latestTypography.value,
                    shapes = latestShapes.value,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides latestContentColor.value,
                    ) {
                        Column(
                            modifier = latestModifier.value
                                .fillMaxSize()
                                .background(latestContainerColor.value)
                                .padding(top = NuvioTokens.Space.s20),
                        ) {
                            latestContent.value(this)
                        }
                    }
                }
            }
        }
        val sheetController = contentController
        sheetController.modalPresentationStyle = UIModalPresentationPageSheet
        sheetController.view.backgroundColor = latestContainerColor.value.toUIColor()
        (sheetController.presentationController() as? UISheetPresentationController)?.apply {
            if (fullHeight) {
                detents = listOf(UISheetPresentationControllerDetent.largeDetent())
            } else {
                detents = listOf(
                    UISheetPresentationControllerDetent.mediumDetent(),
                    UISheetPresentationControllerDetent.largeDetent(),
                )
                selectedDetentIdentifier = UISheetPresentationControllerDetentIdentifierMedium
            }
            prefersGrabberVisible = showDragHandle
            prefersScrollingExpandsWhenScrolledToEdge = false
        }

        val dismissalDelegate = NuvioNativeBottomSheetDelegate {
            if (activeNativeBottomSheet === sheetController) {
                activeNativeBottomSheet = null
                activeNativeBottomSheetDelegate = null
            }
            latestOnDismissRequest.value()
        }
        sheetController.presentationController()?.delegate = dismissalDelegate

        activeNativeBottomSheet?.dismissViewControllerAnimated(
            flag = false,
            completion = null,
        )
        activeNativeBottomSheet = sheetController
        activeNativeBottomSheetDelegate = dismissalDelegate
        parentViewController.presentViewController(
            viewControllerToPresent = sheetController,
            animated = true,
            completion = null,
        )

        onDispose {
            if (activeNativeBottomSheet === sheetController) {
                activeNativeBottomSheet = null
                activeNativeBottomSheetDelegate = null
            }
            if (sheetController.presentingViewController != null) {
                sheetController.dismissViewControllerAnimated(
                    flag = true,
                    completion = null,
                )
            }
        }
    }
}

internal actual fun dismissNativeNuvioBottomSheet() {
    activeNativeBottomSheet?.dismissViewControllerAnimated(
        flag = true,
        completion = null,
    )
}

private class NuvioNativeBottomSheetDelegate(
    private val onDismissRequest: () -> Unit,
) : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
    private var didNotifyDismissal = false

    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        notifyDismissed()
    }

    private fun notifyDismissed() {
        if (didNotifyDismissal) return
        didNotifyDismissal = true
        onDismissRequest()
    }
}

private fun Color.toUIColor(): UIColor = UIColor(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble(),
)
