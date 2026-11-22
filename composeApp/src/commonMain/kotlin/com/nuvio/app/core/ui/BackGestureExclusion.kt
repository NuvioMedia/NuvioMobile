package com.nuvio.app.core.ui

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
 * Areas of a screen, in points of the hosting view, where a full-content
 * swipe-back must not start because the content scrolls horizontally there.
 */
class BackGestureExclusionZones {
    private val zones = mutableMapOf<Any, Rect>()

    fun contains(x: Double, y: Double): Boolean {
        val point = Offset(x.toFloat(), y.toFloat())
        return zones.values.any { it.contains(point) }
    }

    internal fun update(owner: Any, rect: Rect?) {
        if (rect == null || rect.isEmpty) zones.remove(owner) else zones[owner] = rect
    }
}

val LocalBackGestureExclusionZones = staticCompositionLocalOf<BackGestureExclusionZones?> { null }

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
        val zones = currentValueOf(LocalBackGestureExclusionZones) ?: return
        val scale = currentValueOf(LocalDensity).density
        val bounds = coordinates.boundsInWindow()
        zones.update(
            this,
            Rect(bounds.left / scale, bounds.top / scale, bounds.right / scale, bounds.bottom / scale),
        )
    }

    override fun onDetach() {
        currentValueOf(LocalBackGestureExclusionZones)?.update(this, null)
    }
}
