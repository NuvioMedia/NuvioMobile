package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the player reports a playback to the trackers: what a pause and a stop stand on, and the one
 * start a playback that begins above the completion bar still sends.
 */
class PlayerScrobblePolicyTest {

    @Test
    fun `a start above the bar is sent once per stretch`() {
        assertTrue(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = false, progressPercent = 80f))
        assertFalse(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = true, progressPercent = 85f))
        assertFalse(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = false, progressPercent = 79.9f))
    }

    @Test
    fun `a pause needs a session or a start above the bar`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 50f))
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = false,
                progressPercent = 50f,
                hasStartAboveCompletionBar = true,
            ),
        )
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 50f))
        // A percentage that is not a position in the video is not reported at all.
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 120f))
    }

    @Test
    fun `a stop above the bar stands on the bar alone`() {
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 80f))
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 50f))
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                progressPercent = 50f,
                hasStartAboveCompletionBar = true,
            ),
        )
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 50f))
    }
}
