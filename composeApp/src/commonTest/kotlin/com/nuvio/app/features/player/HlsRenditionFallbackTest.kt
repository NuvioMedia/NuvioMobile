package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRenditionFallbackTest {
    @Test
    fun mediaSegment404WithAlternativePrefersAnotherTrack() {
        assertTrue(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                isMediaSegment = true,
                alternativeTrackAvailable = true,
            )
        )
    }

    @Test
    fun manifestErrorsAndMissingAlternativesDoNotTriggerTrackFallback() {
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                isMediaSegment = false,
                alternativeTrackAvailable = true,
            )
        )
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                isMediaSegment = true,
                alternativeTrackAvailable = false,
            )
        )
    }
}
