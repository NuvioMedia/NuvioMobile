package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerResizeModeTest {
    @Test
    fun exposesOfficialModesAndPreservesCustomModes() {
        assertEquals(
            listOf(
                PlayerResizeMode.BestFit,
                PlayerResizeMode.Fit,
                PlayerResizeMode.Fill,
                PlayerResizeMode.Zoom,
                PlayerResizeMode.Ratio16x9,
            ),
            PlayerResizeMode.entries,
        )
    }

    @Test
    fun migratesLegacyFullSelectionToOfficialFill() {
        assertEquals(PlayerResizeMode.Fill, PlayerResizeMode.fromStoredName("Full"))
        assertEquals(PlayerResizeMode.Fill, PlayerResizeMode.fromStoredName("Fill"))
    }

    @Test
    fun restoresCustomModesFromStorage() {
        assertEquals(PlayerResizeMode.BestFit, PlayerResizeMode.fromStoredName("BestFit"))
        assertEquals(PlayerResizeMode.Ratio16x9, PlayerResizeMode.fromStoredName("Ratio16x9"))
    }
}
