package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity

/**
 * What a native swipe-back on a pushed screen needs to know about its Compose
 * content: areas (in points of the hosting view) that handle horizontal drags
 * themselves, and in-screen back handlers that must run before the screen is popped.
 */
class BackGestureBridge {
    private val zones = mutableMapOf<Any, Rect>()
    private val handlers = mutableListOf<BackGestureHandler>()

    fun isExcluded(x: Double, y: Double): Boolean {
        val point = Offset(x.toFloat(), y.toFloat())
        return zones.values.any { it.contains(point) }
    }

    val hasBackHandler: Boolean
        get() = handlers.any { it.enabled }

    /** Runs the innermost enabled in-screen back handler, like a system back press. */
    fun handleBack(): Boolean {
        val handler = handlers.lastOrNull { it.enabled } ?: return false
        handler.onBack()
        return true
    }

    internal fun updateZone(owner: Any, rect: Rect?) {
        if (rect == null || rect.isEmpty) zones.remove(owner) else zones[owner] = rect
    }

    internal fun addHandler(handler: BackGestureHandler) {
        handlers += handler
    }

    internal fun removeHandler(handler: BackGestureHandler) {
        handlers -= handler
    }
}

internal class BackGestureHandler(
    var enabled: Boolean,
    val onBack: () -> Unit,
)

val LocalBackGestureBridge = staticCompositionLocalOf<BackGestureBridge?> { null }

/** Keeps a native swipe-back from starting on content that drags horizontally. */
fun Modifier.backGestureExclusion(): Modifier = this then BackGestureExclusionElement

private object BackGestureExclusionElement : ModifierNodeElement<BackGestureExclusionNode>() {
    override fun create() = BackGestureExclusionNode()
    override fun update(node: BackGestureExclusionNode) = Unit
    override fun hashCode() = 0
    override fun equals(other: Any?) = other === this
    override fun InspectorInfo.inspectableProperties() {
        name = "backGestureExclusion"
    }
}

private class BackGestureExclusionNode :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bridge = currentValueOf(LocalBackGestureBridge) ?: return
        val scale = currentValueOf(LocalDensity).density
        val bounds = coordinates.boundsInWindow()
        bridge.updateZone(
            this,
            Rect(bounds.left / scale, bounds.top / scale, bounds.right / scale, bounds.bottom / scale),
        )
    }

    override fun onDetach() {
        currentValueOf(LocalBackGestureBridge)?.updateZone(this, null)
    }
}

/**
 * Back handler for UI layered inside a screen (overlays, menus). Unlike a handler that
 * just leaves the screen, it also takes the iOS swipe-back, which would otherwise pop
 * the whole screen from under the overlay.
 */
@Composable
fun InScreenBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    PlatformBackHandler(enabled = enabled, onBack = onBack)
    val bridge = LocalBackGestureBridge.current ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    val handler = remember { BackGestureHandler(enabled) { currentOnBack() } }
    SideEffect { handler.enabled = enabled }
    DisposableEffect(bridge, handler) {
        bridge.addHandler(handler)
        onDispose { bridge.removeHandler(handler) }
    }
}
