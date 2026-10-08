package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStillWatchingTest {
    @Test
    fun `still watching defaults match TV`() {
        val settings = PlayerSettingsUiState()
        assertFalse(settings.stillWatchingEnabled)
        assertEquals(3, settings.stillWatchingEpisodeThreshold)
    }

    @Test
    fun `gating returns false when still-watching setting disabled`() {
        assertFalse(
            shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = false,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when auto-play next episode disabled`() {
        assertFalse(
            shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = false,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when next episode has not aired`() {
        assertFalse(
            shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = false,
                consecutiveAutoPlayCount = 5,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns false when consecutive count below threshold`() {
        assertFalse(
            shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 2,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `gating returns true when all conditions met`() {
        assertTrue(
            shouldEnterStillWatchingPrompt(
                stillWatchingEnabled = true,
                autoPlayNextEpisodeEnabled = true,
                nextEpisodeHasAired = true,
                consecutiveAutoPlayCount = 3,
                threshold = 3,
            ),
        )
    }

    @Test
    fun `auto-played episodes count up and a manual switch resets the count`() {
        var count = 0
        count = nextConsecutiveAutoPlayCount(count, isAutoPlay = true)
        count = nextConsecutiveAutoPlayCount(count, isAutoPlay = true)
        assertEquals(2, count)
        count = nextConsecutiveAutoPlayCount(count, isAutoPlay = false)
        assertEquals(0, count)
    }
}
