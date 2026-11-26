package com.nuvio.app.features.audiosync

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Test

/** The SubRip copy of retimed cues that mpv loads in place of the original subtitle. */
class AudioSyncMpvCopyTest {
    private fun cue(text: String, startMs: Long, durationMs: Long) =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), startMs * 1_000L, durationMs * 1_000L)

    @Test
    fun writesNumberedSubRipCues() {
        val srt = AudioSyncFallback.toSrt(
            listOf(
                cue("Hello there", startMs = 1_500, durationMs = 2_000),
                cue("General Kenobi", startMs = 3_723_004, durationMs = 1_000),
            ),
        )
        assertEquals(
            "1\n00:00:01,500 --> 00:00:03,500\nHello there\n\n" +
                "2\n01:02:03,004 --> 01:02:04,004\nGeneral Kenobi\n\n",
            srt,
        )
    }

    @Test
    fun skipsUntimedAndEmptyCuesAndGivesOpenEndedOnesTwoSeconds() {
        val srt = AudioSyncFallback.toSrt(
            listOf(
                CuesWithTiming(listOf(Cue.Builder().setText("untimed").build()), C.TIME_UNSET, C.TIME_UNSET),
                cue("   ", startMs = 0, durationMs = 1_000),
                CuesWithTiming(listOf(Cue.Builder().setText("open").build()), 10_000_000L, C.TIME_UNSET),
            ),
        )
        assertEquals("1\n00:00:10,000 --> 00:00:12,000\nopen\n\n", srt)
    }
}
