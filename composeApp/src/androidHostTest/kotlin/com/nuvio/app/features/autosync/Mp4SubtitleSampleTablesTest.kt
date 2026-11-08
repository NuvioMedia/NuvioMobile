package com.nuvio.app.features.autosync

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.TrackSampleTable
import android.app.Application
import java.io.ByteArrayOutputStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the shared MP4 subtitle parser against Media3's BoxParser, which AutoSync used before,
 * on real files and on hand-built `moov` boxes covering edit lists and sample table variants.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Mp4SubtitleSampleTablesTest {

    @Test
    fun `real ffmpeg files match Media3`() {
        for (name in listOf("tx3g.mp4", "video_tx3g.mp4", "video_tx3g.mov")) {
            val moov = extractMoov(resource("autosync/$name"))
            assertMatchesMedia3(moov, name)
        }
        val tx3g = Mp4SubtitleSampleTables.parse(extractMoov(resource("autosync/tx3g.mp4")))
        assertNotNull(tx3g)
        assertEquals(1, tx3g.tables.size)
        assertEquals("pol", tx3g.tables.single().language)
        assertTrue(tx3g.tables.single().sampleCount >= 40)
    }

    @Test
    fun `QuickTime text entry is a text track that cannot be indexed`() {
        val index = Mp4SubtitleSampleTables.parse(extractMoov(resource("autosync/video_tx3g.mov")))
        assertNotNull(index)
        assertTrue(index.hasTextTracks)
        assertTrue(index.tables.isEmpty())
    }

    @Test
    fun `plain sample tables match Media3`() {
        val deltas = listOf(1_000 to 3, 2_500 to 4, 700 to 5)
        assertMatchesMedia3(
            movie(
                trak(id = 3, sampleEntry = "tx3g", stts = deltas, sizes = sizesFor(12)),
                trak(id = 4, sampleEntry = "wvtt", timescale = 90_000, stts = listOf(4_500 to 12), sizes = sizesFor(12)),
            ),
            "plain",
        )
    }

    @Test
    fun `ctts stss stz2 co64 and version 1 boxes match Media3`() {
        val sizes = sizesFor(10)
        for (fieldSize in listOf(4, 8, 16)) {
            assertMatchesMedia3(
                movie(
                    trak(
                        id = 1,
                        sampleEntry = "tx3g",
                        stts = listOf(1_000 to 10),
                        ctts = listOf(5 to 200, 5 to -100),
                        stss = listOf(1, 4, 7),
                        sizes = sizes.map { it % 15 },
                        stz2FieldSize = fieldSize,
                        co64 = true,
                        version = 1,
                        edits = listOf(Edit(duration = 9_000, mediaTime = 2_000)),
                    ),
                ),
                "variants $fieldSize",
            )
        }
    }

    @Test
    fun `edit lists match Media3`() {
        val editCases = listOf(
            listOf(Edit(duration = 3_000, mediaTime = 1_500)),
            listOf(Edit(duration = 1_200, mediaTime = -1), Edit(duration = 6_000, mediaTime = 0)),
            listOf(Edit(duration = 0, mediaTime = 2_000)),
            listOf(Edit(duration = 2_000, mediaTime = 0), Edit(duration = 3_000, mediaTime = 6_000)),
            listOf(Edit(duration = 50_000, mediaTime = 0)),
        )
        for ((index, edits) in editCases.withIndex()) {
            for (version in 0..1) {
                assertMatchesMedia3(
                    movie(
                        trak(
                            id = 2,
                            sampleEntry = "tx3g",
                            stts = listOf(1_000 to 6, 1_500 to 6),
                            sizes = sizesFor(12),
                            edits = edits,
                            version = version,
                        ),
                        movieTimescale = 600,
                    ),
                    "edits $index v$version",
                )
            }
        }
    }

    @Test
    fun `truncated chunk data keeps the samples read so far like Media3`() {
        val moov = movie(trak(id = 1, sampleEntry = "tx3g", stts = listOf(1_000 to 12), sizes = sizesFor(12), chunks = 3, samplesPerChunk = 2))
        assertMatchesMedia3(moov, "truncated")
        assertEquals(6, Mp4SubtitleSampleTables.parse(moov)!!.tables.single().sampleCount)
    }

    @Test
    fun `unsupported text entries and other tracks are skipped like Media3`() {
        val moov = movie(
            trak(id = 1, sampleEntry = "avc1", handler = "vide", stts = listOf(40 to 5), sizes = sizesFor(5)),
            trak(id = 2, sampleEntry = "stpp", stts = listOf(1_000 to 5), sizes = sizesFor(5)),
            trak(id = 3, sampleEntry = "wvtt", handler = "subt", stts = listOf(1_000 to 9), sizes = sizesFor(9)),
        )
        assertMatchesMedia3(moov, "mixed")
        val index = Mp4SubtitleSampleTables.parse(moov)!!
        assertTrue(index.hasTextTracks)
        assertEquals(listOf(3), index.tables.map { it.trackId })
    }

    @Test
    fun `no text track and fragmented files`() {
        val noText = Mp4SubtitleSampleTables.parse(
            movie(trak(id = 1, sampleEntry = "avc1", handler = "vide", stts = listOf(40 to 5), sizes = sizesFor(5))),
        )
        assertNotNull(noText)
        assertFalse(noText.hasTextTracks)

        val fragmented = movie(
            trak(id = 1, sampleEntry = "tx3g", stts = listOf(1_000 to 5), sizes = sizesFor(5)),
            extra = listOf(box("mvex", ByteArray(0))),
        )
        assertNull(Mp4SubtitleSampleTables.parse(fragmented))
    }

    @Test
    fun `dwell edits are rejected like Media3`() {
        val moov = movie(
            trak(id = 1, sampleEntry = "tx3g", stts = listOf(1_000 to 5), sizes = sizesFor(5), edits = listOf(Edit(1_000, 0, rate = 0))),
        )
        assertNull(Mp4SubtitleSampleTables.parse(moov))
        assertTrue(runCatching { media3Tables(moov) }.isFailure)
    }

    @Test
    fun `scaling matches Media3 rounding`() {
        val values = listOf(0L, 1L, 7L, 999L, 1_001L, 123_456_789L, -1_500L, Long.MAX_VALUE / 3)
        val factors = listOf(1_000_000L to 1_000L, 1_000_000L to 90_000L, 1_000_000L to 600L, 1_000_000L to 48_000L, 600L to 1_000L, 7L to 3L)
        for (value in values) {
            for ((multiplier, divisor) in factors) {
                assertEquals(
                    Util.scaleLargeTimestamp(value, multiplier, divisor),
                    Mp4SubtitleSampleTables.scaleLargeTimestamp(value, multiplier, divisor),
                    "scale $value * $multiplier / $divisor",
                )
            }
        }
    }

    @Test
    fun `track flags match Media3`() {
        assertEquals(C.SELECTION_FLAG_DEFAULT, AutoSyncTrackFlags.SELECTION_FLAG_DEFAULT)
        assertEquals(C.SELECTION_FLAG_FORCED, AutoSyncTrackFlags.SELECTION_FLAG_FORCED)
        assertEquals(C.ROLE_FLAG_COMMENTARY, AutoSyncTrackFlags.ROLE_FLAG_COMMENTARY)
        assertEquals(C.ROLE_FLAG_SUBTITLE, AutoSyncTrackFlags.ROLE_FLAG_SUBTITLE)
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, AutoSyncTrackFlags.ROLE_FLAG_DESCRIBES_VIDEO)
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, AutoSyncTrackFlags.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
        assertEquals(C.ROLE_FLAG_TRANSCRIBES_DIALOG, AutoSyncTrackFlags.ROLE_FLAG_TRANSCRIBES_DIALOG)
    }

    // --- Comparison -------------------------------------------------------------------------

    private fun assertMatchesMedia3(moov: ByteArray, label: String) {
        val expected = media3Tables(moov)
        val actual = Mp4SubtitleSampleTables.parse(moov)
        assertNotNull(actual, "$label: shared parser failed")
        assertEquals(expected.map { it.track.id }, actual.tables.map { it.trackId }, "$label: tracks")
        for ((media3, shared) in expected.zip(actual.tables)) {
            val trackLabel = "$label track ${shared.trackId}"
            val expectedCodec = when (media3.track.format.sampleMimeType) {
                MimeTypes.APPLICATION_TX3G -> Mp4SubtitleCodec.TX3G
                MimeTypes.APPLICATION_MP4VTT -> Mp4SubtitleCodec.WVTT
                else -> null
            }
            assertEquals(expectedCodec, shared.codec, "$trackLabel: codec")
            assertEquals(media3.track.format.language, shared.language?.let { Util.normalizeLanguageCode(it) }, "$trackLabel: language")
            assertContentEquals(media3.timestampsUs, shared.timestampsUs, "$trackLabel: timestamps")
            assertContentEquals(media3.sizes, shared.sizes, "$trackLabel: sizes")
            assertEquals(media3.durationUs, shared.durationUs, "$trackLabel: duration")
        }
    }

    /** What the loader used to do: Media3's moov tree with only its text traks, then BoxParser. */
    private fun media3Tables(moovBytes: ByteArray): List<TrackSampleTable> {
        val moov = toMedia3Container(moovBytes, 0, moovBytes.size)
        return BoxParser.parseTraks(moov, GaplessInfoHolder(), C.TIME_UNSET, null, false, false) { track ->
            track?.takeIf {
                it.type == C.TRACK_TYPE_TEXT &&
                    (it.format.sampleMimeType == MimeTypes.APPLICATION_TX3G ||
                        it.format.sampleMimeType == MimeTypes.APPLICATION_MP4VTT)
            }
        }
    }

    private val containerTypes = setOf("moov", "trak", "mdia", "minf", "stbl", "edts").map(::fourCc).toSet()

    private fun toMedia3Container(bytes: ByteArray, start: Int, end: Int): Mp4Box.ContainerBox {
        val type = readInt(bytes, start + 4)
        val container = Mp4Box.ContainerBox(type, end.toLong())
        var position = start + 8
        while (position < end) {
            val size = readInt(bytes, position)
            val childType = readInt(bytes, position + 4)
            val childEnd = position + size
            if (childType == fourCc("trak") && !isTextTrak(bytes, position, childEnd)) {
                // The loader never handed non-text traks to Media3.
            } else if (childType in containerTypes) {
                container.add(toMedia3Container(bytes, position, childEnd))
            } else {
                container.add(Mp4Box.LeafBox(childType, ParsableByteArray(bytes.copyOfRange(position, childEnd))))
            }
            position = childEnd
        }
        return container
    }

    private fun isTextTrak(bytes: ByteArray, start: Int, end: Int): Boolean {
        val mdia = findChild(bytes, start + 8, end, "mdia") ?: return false
        val hdlr = findChild(bytes, mdia.first + 8, mdia.second, "hdlr") ?: return false
        return readInt(bytes, hdlr.first + 16) in setOf("text", "sbtl", "subt", "clcp", "subp").map(::fourCc)
    }

    private fun findChild(bytes: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var position = start
        while (position + 8 <= end) {
            val size = readInt(bytes, position)
            if (size < 8) return null
            if (readInt(bytes, position + 4) == fourCc(type)) return position to position + size
            position += size
        }
        return null
    }

    // --- Fixtures ---------------------------------------------------------------------------

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing $path" }.use { it.readBytes() }

    private fun extractMoov(file: ByteArray): ByteArray {
        var position = 0
        while (position + 8 <= file.size) {
            val size = readInt(file, position)
            if (readInt(file, position + 4) == fourCc("moov")) return file.copyOfRange(position, position + size)
            position += size
        }
        error("no moov")
    }

    private data class Edit(val duration: Long, val mediaTime: Long, val rate: Int = 1)

    private fun sizesFor(count: Int): List<Int> = List(count) { index -> if (index % 4 == 3) 2 else 10 + index * 3 }

    private fun movie(vararg traks: ByteArray, movieTimescale: Int = 1_000, extra: List<ByteArray> = emptyList()): ByteArray {
        val mvhd = fullBox("mvhd", 0) { int(0); int(0); int(movieTimescale); int(0); bytes(ByteArray(80)) }
        return box("moov", concat(listOf(mvhd) + extra + traks.toList()))
    }

    private fun trak(
        id: Int,
        sampleEntry: String,
        handler: String = "text",
        timescale: Int = 1_000,
        stts: List<Pair<Int, Int>>,
        sizes: List<Int>,
        ctts: List<Pair<Int, Int>>? = null,
        stss: List<Int>? = null,
        stz2FieldSize: Int? = null,
        co64: Boolean = false,
        edits: List<Edit>? = null,
        version: Int = 0,
        language: String = "eng",
        chunks: Int = 1,
        samplesPerChunk: Int = sizes.size,
    ): ByteArray {
        val tkhd = fullBox("tkhd", version) {
            if (version == 0) { int(0); int(0) } else { long(0); long(0) }
            int(id); int(0)
            if (version == 0) int(1_000) else long(1_000)
            bytes(ByteArray(60))
        }
        val edts = edits?.let { list ->
            box("edts", fullBox("elst", version) {
                int(list.size)
                for (edit in list) {
                    if (version == 1) { long(edit.duration); long(edit.mediaTime) } else { int(edit.duration.toInt()); int(edit.mediaTime.toInt()) }
                    short(edit.rate); short(0)
                }
            })
        }
        val packedLanguage = language.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }
        val mdhd = fullBox("mdhd", version) {
            if (version == 0) { int(0); int(0) } else { long(0); long(0) }
            int(timescale)
            if (version == 0) int(0) else long(0)
            short(packedLanguage); short(0)
        }
        val hdlr = fullBox("hdlr", 0) { int(0); int(fourCc(handler)); bytes(ByteArray(12)); bytes(byteArrayOf(0)) }
        val stsd = fullBox("stsd", 0) { int(1); bytes(box(sampleEntry, ByteArray(16))) }
        val sttsBox = fullBox("stts", 0) { int(stts.size); stts.forEach { (delta, count) -> int(count); int(delta) } }
        val cttsBox = ctts?.let { list -> fullBox("ctts", 0) { int(list.size); list.forEach { (count, offset) -> int(count); int(offset) } } }
        val stssBox = stss?.let { list -> fullBox("stss", 0) { int(list.size); list.forEach { int(it) } } }
        val stscBox = fullBox("stsc", 0) { int(1); int(1); int(samplesPerChunk); int(1) }
        val sizeBox = if (stz2FieldSize == null) {
            fullBox("stsz", 0) { int(0); int(sizes.size); sizes.forEach { int(it) } }
        } else {
            fullBox("stz2", 0) {
                int(stz2FieldSize); int(sizes.size)
                when (stz2FieldSize) {
                    16 -> sizes.forEach { short(it) }
                    8 -> sizes.forEach { bytes(byteArrayOf(it.toByte())) }
                    else -> sizes.chunked(2).forEach { pair -> bytes(byteArrayOf(((pair[0] shl 4) or (pair.getOrElse(1) { 0 })).toByte())) }
                }
            }
        }
        val offsetBox = if (co64) fullBox("co64", 0) { int(chunks); repeat(chunks) { long(1_000L * (it + 1)) } }
        else fullBox("stco", 0) { int(chunks); repeat(chunks) { int(1_000 * (it + 1)) } }
        val stbl = box("stbl", concat(listOfNotNull(stsd, sttsBox, cttsBox, stssBox, stscBox, sizeBox, offsetBox)))
        val minf = box("minf", stbl)
        val mdia = box("mdia", concat(listOf(mdhd, hdlr, minf)))
        return box("trak", concat(listOfNotNull(tkhd, edts, mdia)))
    }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun int(value: Int) { repeat(4) { out.write((value ushr (24 - 8 * it)) and 0xFF) } }
        fun long(value: Long) { int((value ushr 32).toInt()); int(value.toInt()) }
        fun short(value: Int) { out.write((value ushr 8) and 0xFF); out.write(value and 0xFF) }
        fun bytes(value: ByteArray) { out.write(value) }
    }

    private fun fullBox(type: String, version: Int, body: Writer.() -> Unit): ByteArray =
        box(type, Writer().apply { int(version shl 24) }.apply(body).out.toByteArray())

    private fun box(type: String, payload: ByteArray): ByteArray =
        Writer().apply { int(payload.size + 8); int(fourCc(type)); bytes(payload) }.out.toByteArray()

    private fun concat(parts: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun fourCc(type: String): Int = type.fold(0) { acc, c -> (acc shl 8) or c.code }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
}
