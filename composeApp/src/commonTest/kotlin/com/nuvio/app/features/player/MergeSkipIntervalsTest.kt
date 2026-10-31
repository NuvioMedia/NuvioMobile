package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeSkipIntervalsTest {

    private fun interval(type: String, start: Double, end: Double, provider: String) =
        SkipInterval(startTime = start, endTime = end, type = type, provider = provider)

    @Test
    fun chaptersTakePriorityOverApiForSameCategory() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(1, merged.size)
        assertEquals("chapters", merged[0].provider)
        assertEquals(90.0, merged[0].startTime)
    }

    @Test
    fun apiFillsCategoriesMissingFromChapters() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("recap", 0.0, 60.0, "introdb")
        )
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        assertEquals("chapters", merged.find { it.type == "intro" }!!.provider)
        assertEquals("introdb", merged.find { it.type == "recap" }!!.provider)
    }

    @Test
    fun chaptersFillCategoriesMissingFromApi() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        val chapters = listOf(
            interval("intro", 90.0, 180.0, "chapters"),
            interval("outro", 1300.0, 1400.0, "chapters")
        )
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        assertEquals("chapters", merged.find { it.type == "intro" }!!.provider)
        assertEquals("chapters", merged.find { it.type == "outro" }!!.provider)
    }

    @Test
    fun emptyChaptersReturnsApiAsIs() {
        val api = listOf(interval("intro", 85.0, 175.0, "introdb"))
        assertEquals(api, mergeSkipIntervals(api, emptyList()))
    }

    @Test
    fun emptyApiReturnsChaptersAsIs() {
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        assertEquals(chapters, mergeSkipIntervals(emptyList(), chapters))
    }

    @Test
    fun uncategorizedApiIntervalsArePreserved() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("post-credits", 1350.0, 1400.0, "introdb")
        )
        val chapters = listOf(interval("intro", 90.0, 180.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(2, merged.size)
        assertEquals("introdb", merged.find { it.type == "post-credits" }!!.provider)
    }

    @Test
    fun bothEmptyReturnsEmpty() {
        assertEquals(emptyList(), mergeSkipIntervals(emptyList(), emptyList()))
    }

    @Test
    fun movieModePrefersApiEntirelyWhenItHasData() {
        val api = listOf(
            interval("movie-credits", 5400.0, 5700.0, "introdb"),
            interval("post-credits", 5700.0, 5800.0, "introdb")
        )
        val chapters = listOf(interval("outro", 5400.0, 6000.0, "chapters"))
        val merged = mergeSkipIntervals(api, chapters, isMovie = true)
        assertEquals(api, merged)
    }

    @Test
    fun movieModeFallsBackToChaptersWhenApiIsEmpty() {
        val chapters = listOf(interval("outro", 5400.0, 6000.0, "chapters"))
        val merged = mergeSkipIntervals(emptyList(), chapters, isMovie = true)
        assertEquals(chapters, merged)
    }

    @Test
    fun fullMergeWithAllCategories() {
        val api = listOf(
            interval("intro", 85.0, 175.0, "introdb"),
            interval("recap", 0.0, 60.0, "introdb"),
            interval("outro", 1280.0, 1380.0, "introdb"),
            interval("post-credits", 1380.0, 1440.0, "introdb")
        )
        val chapters = listOf(
            interval("recap", 0.0, 55.0, "chapters"),
            interval("intro", 90.0, 180.0, "chapters"),
            interval("outro", 1300.0, 1400.0, "chapters")
        )
        val merged = mergeSkipIntervals(api, chapters)
        assertEquals(4, merged.size)
        assertEquals("chapters", merged.find { it.type == "recap" }!!.provider)
        assertEquals("chapters", merged.find { it.type == "intro" }!!.provider)
        assertEquals("chapters", merged.find { it.type == "outro" }!!.provider)
        assertEquals("introdb", merged.find { it.type == "post-credits" }!!.provider)
    }
}
