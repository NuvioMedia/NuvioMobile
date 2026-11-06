package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimeSkipIntervalsTest {

    private fun ts(at: Double, name: String) = AnimeSkipTimestamp(at, AnimeSkipTimestampType(name))

    @Test
    fun loneIntroMarkerWithoutEndIsDropped() {
        assertTrue(listOf(ts(22.0, "Intro")).toAnimeSkipIntervals(baseDuration = 1430.0).isEmpty())
    }

    @Test
    fun loneRecapMarkerWithoutEndIsDropped() {
        assertTrue(listOf(ts(0.0, "Recap")).toAnimeSkipIntervals(baseDuration = 1430.0).isEmpty())
    }

    @Test
    fun trailingCreditsMarkerRunsToTheEndOfTheEpisode() {
        val result = listOf(
            ts(0.0, "Intro"),
            ts(90.0, "Canon"),
            ts(1300.0, "Credits"),
        ).toAnimeSkipIntervals(baseDuration = 1430.0)

        assertEquals(
            listOf(
                SkipInterval(0.0, 90.0, "op", "animeskip"),
                SkipInterval(1300.0, Double.MAX_VALUE, "ed", "animeskip"),
            ),
            result,
        )
    }

    @Test
    fun trailingMixedCreditsMarkerRunsToTheEndOfTheEpisode() {
        assertEquals(
            listOf(SkipInterval(1300.0, Double.MAX_VALUE, "mixed-ed", "animeskip")),
            listOf(ts(1300.0, "Mixed Credits")).toAnimeSkipIntervals(baseDuration = 1430.0),
        )
    }

    @Test
    fun trailingCreditsMarkerInTheFirstHalfOfTheEpisodeIsDropped() {
        assertTrue(listOf(ts(22.0, "Credits")).toAnimeSkipIntervals(baseDuration = 1430.0).isEmpty())
    }

    @Test
    fun trailingCreditsMarkerIsKeptWhenTheDurationIsUnknown() {
        assertEquals(
            listOf(SkipInterval(1300.0, Double.MAX_VALUE, "ed", "animeskip")),
            listOf(ts(1300.0, "Credits")).toAnimeSkipIntervals(),
        )
    }

    @Test
    fun segmentsEndWhereTheNextMarkerStartsRegardlessOfInputOrder() {
        val result = listOf(
            ts(1300.0, "Credits"),
            ts(90.0, "Canon"),
            ts(1390.0, "Preview"),
            ts(0.0, "Recap"),
            ts(30.0, "New Intro"),
        ).toAnimeSkipIntervals(baseDuration = 1430.0)

        assertEquals(
            listOf(
                SkipInterval(0.0, 30.0, "recap", "animeskip"),
                SkipInterval(30.0, 90.0, "op", "animeskip"),
                SkipInterval(1300.0, 1390.0, "ed", "animeskip"),
            ),
            result,
        )
    }

    @Test
    fun zeroLengthSegmentFromDuplicateTimestampsIsDropped() {
        val result = listOf(
            ts(10.0, "Intro"),
            ts(10.0, "Canon"),
            ts(100.0, "Credits"),
            ts(200.0, "Canon"),
        ).toAnimeSkipIntervals(baseDuration = 1430.0)

        assertEquals(listOf(SkipInterval(100.0, 200.0, "ed", "animeskip")), result)
    }
}
