package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSubtitleLiftTest {
    @Test
    fun liftUsesTheHighestVisibleClearance() {
        assertEquals(0.3f, subtitleLiftFraction(0.3f, 0.1f, 0f))
        assertEquals(0f, subtitleLiftFraction(0f, 0f, 0f))
        assertEquals(PLAYER_SUBTITLE_MAX_LIFT, subtitleLiftFraction(0.9f))
    }

    @Test
    fun mpvPositionMovesUpOnlyWhenTheLiftIsHigher() {
        assertEquals(98, mpvSubtitlePositionWithLift(98, 0f))
        assertEquals(70, mpvSubtitlePositionWithLift(98, 0.3f))
        assertEquals(60, mpvSubtitlePositionWithLift(60, 0.3f))
        assertEquals(70, mpvSubtitlePositionWithLift(140, 0.3f))
    }
}
