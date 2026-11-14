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

    @Test
    fun liftRisesRightAway() {
        assertEquals(
            SubtitleLiftDecision(0.3f, null),
            nextSubtitleLift(0.3f, 0f, cueVersion = 4, cueVisible = true, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false),
        )
    }

    @Test
    fun liftDropsOnlyAfterTheShownLineChanges() {
        val waiting = nextSubtitleLift(0f, 0.3f, cueVersion = 4, cueVisible = true, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false)
        assertEquals(SubtitleLiftDecision(0.3f, 4L), waiting)
        assertEquals(waiting, nextSubtitleLift(0f, 0.3f, 4, true, false, waiting.pendingDropCueVersion, false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 0.3f, 5, true, false, waiting.pendingDropCueVersion, false))
    }

    @Test
    fun liftDropsRightAwayWithoutALinePausedOrAfterTheTimeout() {
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 0.3f, 4, cueVisible = false, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 0.3f, 4, cueVisible = true, applyImmediately = true, pendingDropCueVersion = null, dropTimedOut = false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 0.3f, 4, cueVisible = true, applyImmediately = false, pendingDropCueVersion = 4L, dropTimedOut = true))
    }

    @Test
    fun liftDropsRightAwayOnceTheControlsAreHidden() {
        assertEquals(true, shouldApplySubtitleLiftImmediately(paused = false, controlsVisible = false))
        assertEquals(false, shouldApplySubtitleLiftImmediately(paused = false, controlsVisible = true))
        assertEquals(true, shouldApplySubtitleLiftImmediately(paused = true, controlsVisible = true))
    }
}
