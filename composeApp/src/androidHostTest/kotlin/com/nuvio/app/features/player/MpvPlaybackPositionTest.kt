package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvPlaybackPositionTest {

    @Test
    fun zeroVideoTimestampWhileAudioPlaysUsesTheAudioClock() {
        // Logged on an HLS stream: time-pos=0.000 frame=0 seeking=false audio-pts=123.785
        assertEquals(123.785, mpvPlaybackPositionSec(0.0, 123.785, seeking = false, audioDelaySec = 0.0))
    }

    @Test
    fun normalPlaybackKeepsTheVideoPosition() {
        assertEquals(124.333, mpvPlaybackPositionSec(124.333, 124.291, seeking = false, audioDelaySec = 0.0))
    }

    @Test
    fun seekTargetIsTrustedWhileSeeking() {
        // A backward seek: time-pos already holds the target, audio may still report the old spot.
        assertEquals(0.0, mpvPlaybackPositionSec(0.0, 158.194, seeking = true, audioDelaySec = 0.0))
    }

    @Test
    fun missingAudioClockKeepsTheVideoPosition() {
        assertEquals(116.418, mpvPlaybackPositionSec(116.418, null, seeking = false, audioDelaySec = 0.0))
    }

    @Test
    fun videoAheadOfAudioIsNeverReplaced() {
        // Audio track ending before the video must not pull the position back.
        assertEquals(1400.0, mpvPlaybackPositionSec(1400.0, 1300.0, seeking = false, audioDelaySec = 0.0))
    }

    @Test
    fun audioDelayWidensTheTolerance() {
        assertEquals(100.0, mpvPlaybackPositionSec(100.0, 125.0, seeking = false, audioDelaySec = 20.0))
        assertEquals(125.0, mpvPlaybackPositionSec(100.0, 125.0, seeking = false, audioDelaySec = 5.0))
    }
}
