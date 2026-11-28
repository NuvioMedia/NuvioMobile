package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackGestureBridgeTest {
    @Test
    fun pointsInsideRegisteredScrollersAreExcludedUntilRemoved() {
        val bridge = BackGestureBridge()
        val owner = Any()

        bridge.updateZone(owner, Rect(0f, 100f, 400f, 300f))
        assertTrue(bridge.isExcluded(200.0, 150.0))
        assertFalse(bridge.isExcluded(200.0, 350.0))

        bridge.updateZone(owner, null)
        assertFalse(bridge.isExcluded(200.0, 150.0))
    }

    @Test
    fun scrolledOffScreenScrollerExcludesNothing() {
        val bridge = BackGestureBridge()

        bridge.updateZone(Any(), Rect.Zero)

        assertFalse(bridge.isExcluded(0.0, 0.0))
    }

    @Test
    fun backRunsTheInnermostEnabledHandler() {
        val bridge = BackGestureBridge()
        val calls = mutableListOf<String>()
        val screen = BackGestureHandler(enabled = true) { calls += "screen" }
        val overlay = BackGestureHandler(enabled = false) { calls += "overlay" }
        bridge.addHandler(screen)
        bridge.addHandler(overlay)

        assertTrue(bridge.handleBack())
        overlay.enabled = true
        assertTrue(bridge.handleBack())
        bridge.removeHandler(overlay)
        bridge.removeHandler(screen)

        assertEquals(listOf("screen", "overlay"), calls)
        assertFalse(bridge.hasBackHandler)
        assertFalse(bridge.handleBack())
    }
}
