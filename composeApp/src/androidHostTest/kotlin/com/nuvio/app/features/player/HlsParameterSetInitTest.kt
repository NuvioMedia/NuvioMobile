package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.Util
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric because TsExtractor keeps its readers in android.util collections.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HlsParameterSetInitTest {

    @Test
    fun segmentOpeningOnAnIdrFrameHasNoParameterSets() {
        val head = assertNotNull(HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = false)))

        assertEquals(VIDEO_PID, head.videoPid)
        assertTrue(head.reachedFirstSlice)
        assertFalse(head.hasParameterSets)
    }

    @Test
    fun parameterSetsAheadOfTheFirstSliceAreReadWithoutStartCodeBytes() {
        val head = assertNotNull(HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = true)))

        assertTrue(head.reachedFirstSlice)
        assertContentEquals(SPS, head.sps)
        assertContentEquals(PPS, head.pps)
    }

    @Test
    fun streamWithoutH264VideoIsNotScanned() {
        assertNull(HlsParameterSetInit.scanSegmentHead(ByteArray(188 * 4) { 0x47 }))
        assertNull(HlsParameterSetInit.scanSegmentHead(ByteArray(4096)))
    }

    @Test
    fun segmentHeadIsSettledOnceTheFirstSliceOrANonH264StreamShows() {
        val segment = segment(withParameterSets = true)
        // The tables alone, then the first video packet with the SPS, PPS and the start of the IDR slice.
        assertFalse(HlsParameterSetInit.isSegmentHeadConclusive(segment.copyOf(376)))
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(segment.copyOf(564)))

        // AAC only: settled as soon as the PMT is read, once enough packets show it is a transport stream.
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(tables(streamType = 0x0F) + tsPacket(0x1FFF, ByteArray(0))))
        assertFalse(HlsParameterSetInit.isSegmentHeadConclusive(ByteArray(100)))
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(ByteArray(4096)))
    }

    @Test
    fun initSegmentCarriesTheTablesAndParameterSetsInAtLeastFivePackets() {
        val head = assertNotNull(HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = true)))
        val init = assertNotNull(HlsParameterSetInit.buildInitSegment(head))

        assertEquals(0, init.size % 188)
        assertTrue(init.size / 188 >= 5)
        assertContentEquals(head.patPacket, init.copyOfRange(0, 188))
        assertContentEquals(head.pmtPacket, init.copyOfRange(188, 376))
        assertTrue(init.toByteString().indexOf((START + SPS + START + PPS).toByteString()) > 376)
    }

    @Test
    fun media3OutputsVideoFromASegmentWithoutParameterSetsOnceTheInitSegmentIsFed() {
        val head = assertNotNull(HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = true)))
        val init = assertNotNull(HlsParameterSetInit.buildInitSegment(head))

        val primed = extract(init, segment(withParameterSets = false))
        assertEquals(1920, primed.format?.width)
        assertEquals(1080, primed.format?.height)
        assertTrue(primed.sampleFlags.isNotEmpty())
        assertTrue(primed.sampleFlags.first() and C.BUFFER_FLAG_KEY_FRAME != 0)

        // Without it Media3 never gets a video format or sample: the reported stall.
        val unprimed = extract(segment(withParameterSets = false))
        assertNull(unprimed.format)
        assertTrue(unprimed.sampleFlags.isEmpty())
    }

    @Test
    fun playlistGetsAnInitSegmentOnlyWhenTheSecondSegmentLacksParameterSets() {
        val playlist = mediaPlaylist()
        val first = segment(withParameterSets = true)
        val fetched = ArrayList<String>()

        val rewritten = assertNotNull(
            HlsParameterSetInit.withParameterSetInit(playlist, "https://host/hls/index.m3u8?t=1") { uri ->
                fetched += uri
                if (uri.endsWith("seg_000.ts")) first else segment(withParameterSets = false)
            }
        )

        assertEquals(listOf("https://host/hls/seg_001.ts", "https://host/hls/seg_000.ts"), fetched)
        val mapLine = rewritten.lines().single { it.startsWith("#EXT-X-MAP") }
        assertTrue(rewritten.indexOf(mapLine) < rewritten.indexOf("#EXTINF"))
        val init = assertNotNull(mapLine.substringAfter("base64,").removeSuffix("\"").decodeBase64()).toByteArray()
        val firstHead = assertNotNull(HlsParameterSetInit.scanSegmentHead(first))
        assertContentEquals(HlsParameterSetInit.buildInitSegment(firstHead), init)

        val healthy = HlsParameterSetInit.withParameterSetInit(playlist, "https://host/hls/index.m3u8") {
            segment(withParameterSets = true)
        }
        assertNull(healthy)
    }

    @Test
    fun onlyFinishedClearMediaPlaylistsWithoutTheirOwnInitSegmentQualify() {
        assertEquals(listOf("seg_000.ts", "seg_001.ts", "seg_002.ts"), HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist()))
        assertNotNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-DISCONTINUITY-SEQUENCE:0")))
        assertNotNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-KEY:METHOD=NONE")))

        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(ended = false)))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-MAP:URI=\"init.mp4\"")))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-KEY:METHOD=AES-128,URI=\"k\"")))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-DISCONTINUITY")))
        assertNull(
            HlsParameterSetInit.eligibleSegmentUris(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.640028\"\nindex.m3u8\n"
            )
        )
    }

    @Test
    fun segmentCheckGivesUpAtItsTimeLimit() {
        val started = System.nanoTime()
        assertNull(resultWithin(100) { Thread.sleep(5_000); "late" })
        assertTrue(System.nanoTime() - started < 2_000_000_000L)

        assertEquals("done", resultWithin(1_000) { "done" })
        assertNull(resultWithin<String>(1_000) { throw IllegalStateException("host gone") })
    }

    private fun mediaPlaylist(ended: Boolean = true, extraHeader: String? = null): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        extraHeader?.let { append(it).append('\n') }
        for (i in 0..2) append("#EXTINF:6.0,\nseg_00$i.ts\n")
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    private class RecordingTrack(val type: Int) : TrackOutput {
        var format: Format? = null
        val sampleFlags = ArrayList<Int>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(ByteArray(length), 0, length)
            if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            sampleFlags += flags
        }
    }

    /** Feeds [inputs] through one HLS-mode TsExtractor, as ExoPlayer does with an init segment and a segment. */
    private fun extract(vararg inputs: ByteArray): RecordingTrack {
        val tracks = HashMap<Int, RecordingTrack>()
        val extractor = TsExtractor(
            TsExtractor.MODE_HLS,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
        )
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { RecordingTrack(type) }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        for (bytes in inputs) {
            var position = 0
            val reader = object : DataReader {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (position >= bytes.size) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
            }
            val input = DefaultExtractorInput(reader, 0, bytes.size.toLong())
            while (extractor.read(input, PositionHolder()) == Extractor.RESULT_CONTINUE) Unit
        }
        return tracks.values.single { it.type == C.TRACK_TYPE_VIDEO }
    }

    private companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100

        // Parameter sets of the 1080p stream the stall was reported on.
        val SPS = hex("67640034acd940780227e5c05a808080a00032e9e009896811e30632c0")
        val PPS = hex("68ebe72c8b")
        val START = byteArrayOf(0, 0, 0, 1)
        val AUD = START + byteArrayOf(0x09, 0xF0.toByte())

        fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** A segment of three frames, opening on an IDR frame with or without the SPS and PPS before it. */
        fun segment(withParameterSets: Boolean): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tables())
            val idr = AUD + (if (withParameterSets) START + SPS + START + PPS else ByteArray(0)) +
                START + byteArrayOf(0x65, 0x88.toByte()) + ByteArray(400) { 0x11 }
            val frames = listOf(idr, AUD + START + byteArrayOf(0x41, 0x9A.toByte()) + ByteArray(200) { 0x22 })
            frames.plus(frames[1]).forEachIndexed { index, frame ->
                out.write(videoPes(frame, pts = 900_000L + index * 3_000L, continuityStart = index * 4))
            }
            return out.toByteArray()
        }

        /** A PAT and a PMT listing one stream of [streamType] on [VIDEO_PID]. */
        fun tables(streamType: Int = 0x1B): ByteArray =
            tsPacket(0, section(0x00, byteArrayOf(0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00))) +
                tsPacket(
                    PMT_PID,
                    section(
                        0x02,
                        byteArrayOf(
                            0, 1, 0xC1.toByte(), 0, 0,
                            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                            streamType.toByte(), 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00
                        )
                    )
                )

        fun section(tableId: Int, body: ByteArray): ByteArray {
            val length = body.size + 4
            val header = byteArrayOf(tableId.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte())
            val withoutCrc = header + body
            val crc = Util.crc32(withoutCrc, 0, withoutCrc.size, -1)
            return withoutCrc + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }

        fun tsPacket(pid: Int, section: ByteArray): ByteArray {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (0x40 or (pid shr 8)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = 0x10
            packet[4] = 0 // pointer field
            section.copyInto(packet, 5)
            return packet
        }

        fun videoPes(es: ByteArray, pts: Long, continuityStart: Int): ByteArray {
            val ptsBytes = byteArrayOf(
                (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
                (pts shr 22).toByte(),
                (0x01 or ((pts shr 14) and 0xFE).toInt()).toByte(),
                (pts shr 7).toByte(),
                (0x01 or ((pts shl 1) and 0xFE).toInt()).toByte()
            )
            val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5) + ptsBytes + es
            val out = ByteArrayOutputStream()
            var offset = 0
            var continuity = continuityStart
            while (offset < pes.size) {
                val chunk = minOf(184, pes.size - offset)
                val packet = ByteArray(188)
                packet[0] = 0x47
                packet[1] = ((if (offset == 0) 0x40 else 0) or (VIDEO_PID shr 8)).toByte()
                packet[2] = (VIDEO_PID and 0xFF).toByte()
                var write = 4
                if (chunk < 184) {
                    val adaptationLength = 183 - chunk
                    packet[3] = (0x30 or (continuity and 0x0F)).toByte()
                    packet[4] = adaptationLength.toByte()
                    if (adaptationLength > 0) {
                        packet[5] = 0
                        packet.fill(0xFF.toByte(), 6, 5 + adaptationLength)
                    }
                    write = 5 + adaptationLength
                } else {
                    packet[3] = (0x10 or (continuity and 0x0F)).toByte()
                }
                pes.copyInto(packet, write, offset, offset + chunk)
                out.write(packet)
                offset += chunk
                continuity++
            }
            return out.toByteArray()
        }
    }
}
