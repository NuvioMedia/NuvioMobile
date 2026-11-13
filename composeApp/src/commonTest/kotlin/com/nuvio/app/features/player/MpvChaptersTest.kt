package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvChaptersTest {
    @Test
    fun `chapters are read from mpv string properties`() {
        val properties = mapOf(
            "chapter-list/count" to "3",
            "chapter-list/0/time" to "0.000000",
            "chapter-list/0/title" to "Opening",
            "chapter-list/1/time" to "90.500000",
            "chapter-list/1/title" to "  Part A  ",
            "chapter-list/2/time" to "1320.000000",
        )

        assertEquals(
            listOf(
                PlayerChapter(startMs = 0L, title = "Opening"),
                PlayerChapter(startMs = 90_500L, title = "Part A"),
                PlayerChapter(startMs = 1_320_000L, title = null),
            ),
            mpvChapters(properties::get),
        )
    }

    @Test
    fun `a chapter without a start time is skipped`() {
        val properties = mapOf(
            "chapter-list/count" to "2",
            "chapter-list/0/time" to "",
            "chapter-list/1/time" to "60",
        )

        assertEquals(listOf(PlayerChapter(startMs = 60_000L, title = null)), mpvChapters(properties::get))
    }

    @Test
    fun `no count means no chapters`() {
        assertEquals(emptyList(), mpvChapters { null })
        assertEquals(emptyList(), mpvChapters { if (it == "chapter-list/count") "0" else null })
    }
}
