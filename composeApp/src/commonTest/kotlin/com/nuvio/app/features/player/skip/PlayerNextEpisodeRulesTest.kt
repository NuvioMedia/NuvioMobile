package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerNextEpisodeRulesTest {

    @Test
    fun streamStartIsAwayFromEnd() {
        assertTrue(awayFromEnd(positionMs = 0L, durationMs = 22 * 60_000L))
    }

    @Test
    fun middleOfEpisodeIsAwayFromEnd() {
        assertTrue(awayFromEnd(positionMs = 11 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun stalePreviousFileEndDoesNotArmEndDetection() {
        assertFalse(awayFromEnd(positionMs = 22 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun stalePositionInsideNextEpisodeWindowDoesNotArmEndDetection() {
        assertFalse(
            awayFromEnd(
                positionMs = 22 * 60_000L,
                durationMs = 22 * 60_000L + 20_000L,
            ),
        )
    }

    @Test
    fun unknownDurationIsNotAwayFromEnd() {
        assertFalse(awayFromEnd(positionMs = 0L, durationMs = 0L))
    }

    private fun awayFromEnd(positionMs: Long, durationMs: Long): Boolean =
        PlayerNextEpisodeRules.isAwayFromEnd(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 97f,
            thresholdMinutesBeforeEnd = 2f,
        )
}
