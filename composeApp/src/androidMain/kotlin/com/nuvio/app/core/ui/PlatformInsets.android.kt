package com.nuvio.app.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal actual val nuvioPlatformExtraTopPadding: Dp = 0.dp
internal actual val nuvioPlatformExtraBottomPadding: Dp = 0.dp
internal actual val nuvioBottomNavigationExtraVerticalPadding: Dp = 6.dp
@Composable
internal actual fun nuvioBottomNavigationBarInsets(): WindowInsets = WindowInsets.navigationBars

@Composable
internal actual fun platformPhysicalTopInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
internal actual fun ResizeForKeyboard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val previous = window?.attributes?.softInputMode
        if (window != null && previous != null) {
            window.setSoftInputMode(
                (previous and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        onDispose {
            if (window != null && previous != null) window.setSoftInputMode(previous)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
