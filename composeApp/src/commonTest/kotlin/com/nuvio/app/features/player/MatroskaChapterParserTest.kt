package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatroskaChapterParserTest {

    private fun idBytes(id: Long): ByteArray {
        var width = 1
        while (width < 4 && id ushr (width * 8) != 0L) width++
        return ByteArray(width) { i -> (id ushr ((width - 1 - i) * 8)).toByte() }
    }

    /** Size as an 8-byte EBML vint, valid for any test payload. */
    private fun size(value: Long): ByteArray =
        ByteArray(8) { i -> if (i == 0) 0x01 else (value ushr ((7 - i) * 8)).toByte() }

    private fun element(id: Long, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { acc, child -> acc + child }
        return idBytes(id) + size(payload.size.toLong()) + payload
    }

    private fun uint(id: Long, value: Long): ByteArray {
        var width = 1
        while (width < 8 && value ushr (width * 8) != 0L) width++
        return element(id, ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() })
    }

    private fun text(id: Long, value: String): ByteArray = element(id, value.encodeToByteArray())

    private fun atom(startMs: Long, title: String?, hidden: Boolean = false, enabled: Boolean = true): ByteArray {
        val parts = mutableListOf(uint(0x91L, startMs * 1_000_000L))
        if (hidden) parts += uint(0x98L, 1L)
        if (!enabled) parts += uint(0x4598L, 0L)
        if (title != null) parts += element(0x80L, text(0x85L, title), text(0x437CL, "eng"))
        return element(0xB6L, *parts.toTypedArray())
    }

    private fun chapters(vararg editions: ByteArray): ByteArray =
        element(MatroskaChapterParser.ID_CHAPTERS, *editions)

    @Test
    fun chaptersOfTheOnlyEditionWithTitlesAndTimes() {
        val parsed = MatroskaChapterParser.parseChapters(
            chapters(element(0x45B9L, atom(0L, "Opening"), atom(90_500L, "Part A"), atom(1_200_000L, null))),
        )
        assertEquals(listOf(0L, 90_500L, 1_200_000L), parsed.map { it.startMs })
        assertEquals(listOf("Opening", "Part A", null), parsed.map { it.title })
    }

    @Test
    fun hiddenAndDisabledChaptersAreLeftOut() {
        val parsed = MatroskaChapterParser.parseChapters(
            chapters(
                element(
                    0x45B9L,
                    atom(0L, "A"),
                    atom(10_000L, "Hidden", hidden = true),
                    atom(20_000L, "Off", enabled = false),
                    atom(30_000L, "B"),
                ),
            ),
        )
        assertEquals(listOf("A", "B"), parsed.map { it.title })
    }

    @Test
    fun theDefaultEditionWinsOverTheFirstOne() {
        val first = element(0x45B9L, atom(0L, "first"))
        val default = element(0x45B9L, uint(0x45DBL, 1L), atom(0L, "default"), atom(5_000L, "default 2"))
        assertEquals("default", MatroskaChapterParser.parseChapters(chapters(first, default)).first().title)
    }

    @Test
    fun aHiddenEditionIsSkipped() {
        val hidden = element(0x45B9L, uint(0x45BDL, 1L), atom(0L, "hidden"))
        val shown = element(0x45B9L, atom(0L, "shown"))
        assertEquals("shown", MatroskaChapterParser.parseChapters(chapters(hidden, shown)).first().title)
    }

    @Test
    fun notAChaptersElementGivesNothing() {
        assertTrue(MatroskaChapterParser.parseChapters(element(0x45B9L, atom(0L, "x"))).isEmpty())
    }

    @Test
    fun layoutFindsChaptersBeforeTheFirstCluster() {
        val ebml = element(0x1A45DFA3L, uint(0x4282L, 0L))
        val info = element(0x1549A966L, uint(0x2AD7B1L, 1_000_000L))
        val chaptersElement = chapters(element(0x45B9L, atom(0L, "A"), atom(1_000L, "B")))
        val payload = info + chaptersElement + element(MatroskaChapterParser.ID_CLUSTER, uint(0xE7L, 0L))
        // Segment of unknown size, as in streamed files.
        val unknownSize = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)
        val file = ebml + idBytes(MatroskaChapterParser.ID_SEGMENT) + unknownSize + payload

        val layout = assertNotNull(MatroskaChapterParser.topLevelLayout(file))
        assertEquals((file.size - payload.size).toLong(), layout.segmentDataStart)
        assertEquals(layout.segmentDataStart + info.size, layout.chaptersPosition)
        assertNull(layout.seekHeadPosition)
    }

    @Test
    fun seekHeadGivesChaptersPositionRelativeToTheSegment() {
        val seek = element(
            MatroskaChapterParser.ID_SEEK,
            element(MatroskaChapterParser.ID_SEEK_ID, idBytes(MatroskaChapterParser.ID_CHAPTERS)),
            uint(MatroskaChapterParser.ID_SEEK_POSITION, 123_456_789L),
        )
        val positions = MatroskaChapterParser.seekHeadPositions(element(MatroskaChapterParser.ID_SEEK_HEAD, seek))
        assertEquals(123_456_789L, positions[MatroskaChapterParser.ID_CHAPTERS])
    }

    @Test
    fun notMatroskaHasNoLayout() {
        assertNull(MatroskaChapterParser.topLevelLayout("....ftypisom".encodeToByteArray() + ByteArray(64)))
    }
}
