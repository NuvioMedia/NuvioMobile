package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackGestureExclusionZonesTest {
    @Test
    fun pointsInsideRegisteredScrollersAreExcludedUntilRemoved() {
        val zones = BackGestureExclusionZones()
        val owner = Any()

        zones.update(owner, Rect(0f, 100f, 400f, 300f))
        assertTrue(zones.contains(200.0, 150.0))
        assertFalse(zones.contains(200.0, 350.0))

        zones.update(owner, null)
        assertFalse(zones.contains(200.0, 150.0))
    }

    @Test
    fun scrolledOffScreenScrollerExcludesNothing() {
        val zones = BackGestureExclusionZones()

        zones.update(Any(), Rect.Zero)

        assertFalse(zones.contains(0.0, 0.0))
    }
}
