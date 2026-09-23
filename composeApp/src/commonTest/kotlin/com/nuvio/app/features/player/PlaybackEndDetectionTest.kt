package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEndDetectionTest {

    @Test
    fun endedSnapshotIsIgnoredUntilTheStreamIsArmed() {
        assertFalse(
            shouldTreatPlaybackAsNaturalEnd(
                isEnded = true,
                endDetectionArmed = false,
                mpvEofSeenClear = true,
            ),
        )
    }

    @Test
    fun endedSnapshotIsIgnoredUntilEofWasClear() {
        assertFalse(
            shouldTreatPlaybackAsNaturalEnd(
                isEnded = true,
                endDetectionArmed = true,
                mpvEofSeenClear = false,
            ),
        )
    }

    @Test
    fun endedSnapshotIsAcceptedAfterBothGuardsPass() {
        assertTrue(
            shouldTreatPlaybackAsNaturalEnd(
                isEnded = true,
                endDetectionArmed = true,
                mpvEofSeenClear = true,
            ),
        )
    }
}
