package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerChaptersTest {

    private val chapters = listOf(
        PlayerChapter(0L, "Intro"),
        PlayerChapter(90_000L, "Part 1"),
        PlayerChapter(600_000L, "Credits"),
    )

    @Test
    fun aLoneChapterIsNoChapters() {
        assertTrue(PlayerChapters.normalize(listOf(PlayerChapter(0L, "Chapter 1"))).isEmpty())
    }

    @Test
    fun normalizeSortsAndDropsRepeatedStarts() {
        val normalized = PlayerChapters.normalize(
            listOf(PlayerChapter(600_000L, "C"), PlayerChapter(0L, "A"), PlayerChapter(0L, "dup"), PlayerChapter(90_000L, "B")),
        )
        assertEquals(listOf(0L, 90_000L, 600_000L), normalized.map { it.startMs })
        assertEquals("A", normalized.first().title)
    }

    @Test
    fun indexIsTheChapterPlayingAtThePosition() {
        assertEquals(0, PlayerChapters.indexAt(chapters, 0L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 90_000L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 599_999L))
        assertEquals(2, PlayerChapters.indexAt(chapters, 700_000L))
    }

    @Test
    fun indexIsMinusOneBeforeTheFirstChapter() {
        assertEquals(-1, PlayerChapters.indexAt(listOf(PlayerChapter(5_000L, null), PlayerChapter(9_000L, null)), 1_000L))
    }

    @Test
    fun nextGoesToTheFollowingChapterStart() {
        assertEquals(90_000L, PlayerChapters.nextStartMs(chapters, 0L))
        assertEquals(600_000L, PlayerChapters.nextStartMs(chapters, 90_000L))
        assertNull(PlayerChapters.nextStartMs(chapters, 600_000L))
    }

    @Test
    fun previousRestartsTheChapterWhenWellIntoIt() {
        assertEquals(90_000L, PlayerChapters.previousStartMs(chapters, 200_000L))
    }

    @Test
    fun previousGoesToTheChapterBeforeRightAfterAStart() {
        assertEquals(0L, PlayerChapters.previousStartMs(chapters, 91_000L))
    }

    @Test
    fun previousInTheFirstChapterRestartsIt() {
        assertEquals(0L, PlayerChapters.previousStartMs(chapters, 1_000L))
    }
}
