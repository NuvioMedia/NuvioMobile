package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

internal actual val floatingNavigationGlowSupported: Boolean
    get() = false

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
    glowEnabled: Boolean,
) {
    NuvioNavigationBar(modifier, scrollState, hazeState, contentPadding, compactSize) {
        items.forEach { item ->
            when {
                item.icon != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.icon,
                    contentDescription = item.label,
                    label = item.label,
                )
                item.drawable != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.drawable,
                    contentDescription = item.label,
                    label = item.label,
                )
                else -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    label = item.label,
                ) {
                    item.content?.invoke(item.onClick)
                }
            }
        }
    }
}
