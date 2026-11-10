package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDurationGrowthTest {
    private fun growth(vararg readings: Pair<Long, Boolean>, mediaKey: String = "a"): LiveDurationGrowth =
        readings.fold(LiveDurationGrowth()) { state, (durationMs, fromRequestedMedia) ->
            state.next(mediaKey, durationMs, fromRequestedMedia)
        }

    @Test
    fun `fixed vod duration does not count as growing`() {
        assertFalse(growth(1_320_000L to true, 1_320_000L to true, 1_325_000L to true).grew)
    }

    @Test
    fun `growing mpv duration marks live`() {
        assertTrue(growth(8_000L to true, 18_000L to true, 32_000L to true).grew)
    }

    @Test
    fun `readings before the requested media are skipped`() {
        // The previous file's 20 min is reported before mpv switches to a 45 min episode
        assertFalse(growth(1_200_000L to false, 2_700_000L to true, 2_700_000L to true).grew)
    }

    @Test
    fun `a new media key starts over`() {
        val live = growth(8_000L to true, 46_000L to true)
        assertTrue(live.grew)
        val next = live.next("b", 2_700_000L, true)
        assertFalse(next.grew)
        assertEquals(2_700_000L, next.firstDurationMs)
    }
}
