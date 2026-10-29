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
    fun `the marker is read five seconds early, whatever the runtime`() {
        // A 20 minute episode: the credits at 18:20 are read from 18:15.
        val episode = listOf(SkipInterval(1_100.0, 1_140.0, "outro", "introdb"))
            .contentEndPercent(1_200_000L)!!
            .contentEndWithTolerance(1_200_000L)!!
        assertEquals(1_095.0 / 1_200.0 * 100.0, episode, 0.001)

        // A two hour film: the credits at 1:58:20 are read from 1:58:15. The same five seconds, not a
        // share of the runtime.
        val film = listOf(SkipInterval(7_100.0, 7_200.0, "outro", "introdb"))
            .contentEndPercent(7_200_000L)!!
            .contentEndWithTolerance(7_200_000L)!!
        assertEquals(7_095.0 / 7_200.0 * 100.0, film, 0.001)
    }

    @Test
    fun `a margin that reaches past the start of the video reads as the start`() {
        assertEquals(
            0.0,
            listOf(SkipInterval(3.0, 30.0, "outro", "introdb"))
                .contentEndPercent(1_200_000L)!!
                .contentEndWithTolerance(1_200_000L)!!,
            0.001,
        )
    }

    @Test
    fun `a marker that was not read gets no margin either`() {
        val none: Double? = null

        assertNull(none.contentEndWithTolerance(1_200_000L))
        assertNull(none.contentEndWithTolerance(0L))
    }

    @Test
    fun `a marker outside the video is not usable`() {
        val pastTheEnd = listOf(SkipInterval(1_900.0, 1_950.0, "outro", "introdb"))
        val atTheStart = listOf(SkipInterval(0.0, 30.0, "outro", "introdb"))

        assertNull(pastTheEnd.contentEndPercent(thirtyMinutesMs))
        assertNull(atTheStart.contentEndPercent(thirtyMinutesMs))
    }
}
