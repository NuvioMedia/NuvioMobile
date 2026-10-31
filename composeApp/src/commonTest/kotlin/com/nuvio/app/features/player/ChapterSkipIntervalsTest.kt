package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterSkipIntervalsTest {

    private val durationMs = 24 * 60_000L

    private fun chapters(vararg entries: Pair<Long, String?>): List<PlayerChapter> =
        PlayerChapters.normalize(entries.map { (ms, title) -> PlayerChapter(ms, title) })

    @Test
    fun opChapterProducesIntroInterval() {
        val chapters = chapters(0L to "Prologue", 90_000L to "OP", 180_000L to "Episode", 1_320_000L to "ED")
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val opening = intervals.find { it.type == "intro" }
        assertEquals(90.0, opening!!.startTime)
        assertEquals(180.0, opening.endTime)
        assertEquals("chapters", opening.provider)
    }

    @Test
    fun openingChapterProducesIntroInterval() {
        val chapters = chapters(0L to "Cold Open", 120_000L to "Opening", 210_000L to "Part A")
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertEquals(120.0, intervals.find { it.type == "intro" }!!.startTime)
    }

    @Test
    fun edChapterProducesOutroInterval() {
        val chapters = chapters(0L to "Episode", 1_300_000L to "ED", 1_390_000L to "Preview")
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertEquals(1_300.0, intervals.find { it.type == "outro" }!!.startTime)
    }

    @Test
    fun recapChapterProducesRecapInterval() {
        val chapters = chapters(0L to "Previously on...", 60_000L to "OP", 150_000L to "Episode")
        assertEquals(0.0, ChapterSkipIntervals.fromChapters(chapters, durationMs).find { it.type == "recap" }!!.startTime)
    }

    @Test
    fun introAloneTreatedAsOpeningWhenNoOpExists() {
        val chapters = chapters(0L to "Intro", 90_000L to "Episode", 1_320_000L to "Credits")
        assertEquals(0.0, ChapterSkipIntervals.fromChapters(chapters, durationMs).find { it.type == "intro" }!!.startTime)
    }

    @Test
    fun introDemotedWhenOpAlsoExists() {
        val chapters = chapters(0L to "Intro", 60_000L to "OP", 150_000L to "Episode", 1_320_000L to "ED")
        val intros = ChapterSkipIntervals.fromChapters(chapters, durationMs).filter { it.type == "intro" }
        assertEquals(1, intros.size)
        assertEquals(60.0, intros[0].startTime)
    }

    @Test
    fun chaptersWithoutKnownTitlesProduceNoIntervals() {
        val chapters = chapters(0L to "Chapter 1", 300_000L to "Chapter 2", 600_000L to "Chapter 3")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).isEmpty())
    }

    @Test
    fun fewerThanTwoChaptersProduceNoIntervals() {
        assertTrue(ChapterSkipIntervals.fromChapters(listOf(PlayerChapter(0L, "OP")), durationMs).isEmpty())
    }

    @Test
    fun caseInsensitiveMatching() {
        val chapters = chapters(0L to "RECAP", 60_000L to "opening", 150_000L to "episode", 1_300_000L to "Ending Credits")
        assertEquals(3, ChapterSkipIntervals.fromChapters(chapters, durationMs).size)
    }

    @Test
    fun veryShortChaptersAreFilteredOut() {
        val chapters = chapters(0L to "Episode", 1_300_000L to "OP", 1_302_000L to "Part B")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).none { it.type == "intro" })
    }

    @Test
    fun veryLongChaptersAreFilteredOut() {
        val chapters = chapters(0L to "OP", 300_000L to "Episode")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).none { it.type == "intro" })
    }

    @Test
    fun postCreditsSceneChapterIsExcluded() {
        val chapters = chapters(0L to "Movie", 5_400_000L to "Credits", 5_700_000L to "Post-Credits Scene")
        val intervals = ChapterSkipIntervals.fromChapters(chapters, 6_000_000L)
        assertTrue(intervals.none { it.startTime == 5_700.0 })
        assertTrue(intervals.any { it.type == "outro" && it.startTime == 5_400.0 })
    }

    @Test
    fun midCreditsChapterIsExcluded() {
        val chapters = chapters(0L to "Movie", 5_400_000L to "Credits", 5_600_000L to "Mid-Credits Scene", 5_700_000L to "More Credits")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, 6_000_000L).none { it.startTime == 5_600.0 })
    }

    @Test
    fun sceneNamedChaptersDoNotFalseMatch() {
        val chapters = chapters(0L to "The Opening", 600_000L to "The Journey", 1_200_000L to "The Ending", 1_500_000L to "Alternate Ending")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, 1_800_000L).isEmpty())
    }

    @Test
    fun standaloneEdDoesNotMatch() {
        val chapters = chapters(0L to "Ed arrives", 300_000L to "The battle")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, 600_000L).isEmpty())
    }

    @Test
    fun opWithNumberSuffixMatches() {
        val chapters = chapters(0L to "Prologue", 90_000L to "OP 2", 180_000L to "Episode")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).any { it.type == "intro" })
    }

    @Test
    fun openingThemeMatches() {
        val chapters = chapters(0L to "Cold Open", 90_000L to "Opening Theme", 180_000L to "Episode")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).any { it.type == "intro" })
    }

    @Test
    fun introductionDoesNotMatch() {
        val chapters = chapters(0L to "Introduction", 300_000L to "Chapter 2")
        assertTrue(ChapterSkipIntervals.fromChapters(chapters, durationMs).isEmpty())
    }

    @Test
    fun lastChapterUsesDurationAsEndTime() {
        val chapters = chapters(0L to "Episode", 1_200_000L to "ED")
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val ending = intervals.find { it.type == "outro" }
        // (1440 - 1200) = 240s = exactly at MAX_DURATION limit
        assertEquals(durationMs / 1000.0, ending!!.endTime)
    }
}
