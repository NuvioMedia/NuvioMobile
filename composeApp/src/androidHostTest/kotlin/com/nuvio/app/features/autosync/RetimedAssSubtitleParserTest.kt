package com.nuvio.app.features.autosync

import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.Test

class RetimedAssSubtitleParserTest {
    private class RecordingParser : SubtitleParser {
        var parsed: ByteArray? = null
        var resets = 0

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            parsed = data.copyOfRange(offset, offset + length)
        }

        override fun reset() {
            resets++
        }

        override fun getCueReplacementBehavior(): Int = 2
    }

    private val original = "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi".toByteArray()
    private val retimed = "[Events]\nDialogue: 0,0:00:02.50,0:00:03.50,Default,,0,0,0,,Hi".toByteArray()

    @Test
    fun withoutSyncedCopyTheOriginalBytesPassThrough() {
        val delegate = RecordingParser()
        val wrapped = RetimedAssSubtitleParser(delegate) { null }

        val framed = byteArrayOf(9, 9) + original + byteArrayOf(9)
        wrapped.parse(framed, 2, original.size, SubtitleParser.OutputOptions.allCues()) {}

        assertContentEquals(original, delegate.parsed)
    }

    @Test
    fun syncedCopyReplacesTheLoadedBytes() {
        val delegate = RecordingParser()
        val wrapped = RetimedAssSubtitleParser(delegate) { retimed }

        wrapped.parse(original, 0, original.size, SubtitleParser.OutputOptions.allCues()) {}

        assertContentEquals(retimed, delegate.parsed)
    }

    @Test
    fun behaviourAndResetFollowTheLibassParser() {
        val delegate = RecordingParser()
        val wrapped = RetimedAssSubtitleParser(delegate) { null }

        wrapped.reset()

        assertEquals(1, delegate.resets)
        assertEquals(2, wrapped.cueReplacementBehavior)
    }

    @Test
    fun libassTrackIdMatchesTheConfigurationIdWithOrWithoutAPrefix() {
        val id = "nuvio_addon_subtitle:1a2b3c"

        assertEquals(id, AutoSyncLibassSubtitles.libassTrackId(id, setOf("other", id)))
        assertEquals("1:$id", AutoSyncLibassSubtitles.libassTrackId(id, setOf("other", "1:$id")))
        assertEquals(id, AutoSyncLibassSubtitles.libassTrackId(id, emptySet()))
    }
}
