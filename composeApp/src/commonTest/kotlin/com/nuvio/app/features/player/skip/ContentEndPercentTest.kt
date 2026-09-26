package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the content ends, read from the credits marker, which the tracker uses instead of a
 * percentage alone.
 */
class ContentEndPercentTest {

    private val thirtyMinutesMs = 1_800_000L

    @Test
    fun `the outro start is where the content ends`() {
        val intervals = listOf(SkipInterval(1_584.0, 1_740.0, "outro", "introdb"))

        assertEquals(88.0, intervals.contentEndPercent(thirtyMinutesMs)!!, 0.001)
    }

    @Test
    fun `film credits count as well`() {
        val intervals = listOf(SkipInterval(1_620.0, 1_800.0, "movie-credits", "introdb"))

        assertEquals(90.0, intervals.contentEndPercent(thirtyMinutesMs)!!, 0.001)
    }

    @Test
    fun `the earliest end marker wins`() {
        val intervals = listOf(
            SkipInterval(1_620.0, 1_750.0, "outro", "introdb"),
            SkipInterval(1_584.0, 1_700.0, "ed", "aniskip"),
        )

        assertEquals(88.0, intervals.contentEndPercent(thirtyMinutesMs)!!, 0.001)
    }

    @Test
    fun `an opening is not an ending`() {
        val intervals = listOf(SkipInterval(90.0, 180.0, "op", "aniskip"))

        assertNull(intervals.contentEndPercent(thirtyMinutesMs))
    }

    @Test
    fun `no marker, no answer`() {
        assertNull(emptyList<SkipInterval>().contentEndPercent(thirtyMinutesMs))
    }

    @Test
    fun `a playback without a duration has nothing to compare against`() {
        val intervals = listOf(SkipInterval(1_584.0, 1_740.0, "outro", "introdb"))

        assertNull(intervals.contentEndPercent(0L))
        assertNull(intervals.contentEndPercent(-1L))
    }

    @Test
    fun `a marker outside the video is not usable`() {
        val pastTheEnd = listOf(SkipInterval(1_900.0, 1_950.0, "outro", "introdb"))
        val atTheStart = listOf(SkipInterval(0.0, 30.0, "outro", "introdb"))

        assertNull(pastTheEnd.contentEndPercent(thirtyMinutesMs))
        assertNull(atTheStart.contentEndPercent(thirtyMinutesMs))
    }
}
