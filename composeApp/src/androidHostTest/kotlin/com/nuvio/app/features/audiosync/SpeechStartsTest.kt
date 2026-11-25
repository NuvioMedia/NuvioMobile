package com.nuvio.app.features.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpeechStartsTest {
    @Test
    fun speechStartsMarkWhereSpeechRises() {
        val p = FloatArray(40) { if (it in 10 until 25) 0.9f else 0.05f }
        p[35] = Float.NaN
        val rises = SubtitleAudioAligner.speechStarts(listOf(SpeechSegment(100, p))).single()
        // The 3 frames at either end, with no neighbour on one side, are left out.
        assertEquals(103, rises.fromFrame)
        assertEquals(34, rises.probabilities.size)
        val at = { frame: Int -> rises.probabilities[frame - 3] }
        // Rises around the start of speech, not inside it or where it ends.
        assertTrue(at(10) > 0.8f, "start ${at(10)}")
        assertEquals(0f, at(17))
        assertEquals(0f, at(25))
        // Unknown where either side, or the frame itself, is unknown.
        assertTrue(at(32).isNaN())
        assertTrue(at(35).isNaN())
    }

    @Test
    fun lineStartsAreShortIntervalsAroundEachStart() {
        val track = SubtitleSpeechTrack.fromCues(listOf(Triple(1_000L, 3_000L, "Hello"), Triple(5_000L, 6_500L, "There")))
        val starts = track.starts
        assertEquals(2, starts.size)
        assertEquals(listOf(920L, 4_920L), starts.startsMs.toList())
        assertEquals(listOf(1_080L, 5_080L), starts.endsMs.toList())
    }

    @Test
    fun lineStartsFindTheOffsetWhereDenseDialogueOverlapIsAmbiguous() {
        // Lines of near-constant length with short gaps: overlap with speech scores almost alike a
        // line apart, but the starts of the lines only line up at the true offset.
        val random = Random(4)
        var t = 20_000L
        val cues = List(120) {
            val length = 2_400L + random.nextLong(400L)
            Triple(t, t + length, "line").also { t += length + 300L + random.nextLong(900L) }
        }
        val trueShiftMs = 3_200.0
        val frames = ((t + 10_000L) / SpeechTimeline.FRAME_DURATION_MS).toInt()
        val p = FloatArray(frames) { 0.05f }
        for ((a, b, _) in cues) {
            val from = ((a + trueShiftMs + SubtitleAudioAligner.DETECTOR_BIAS_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt()
            val to = ((b + trueShiftMs + SubtitleAudioAligner.DETECTOR_BIAS_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt()
            for (f in from until minOf(to, frames)) p[f] = 0.9f
        }
        val segments = listOf(SpeechSegment(0, p))
        val track = SubtitleSpeechTrack.fromCues(cues)
        val starts = assertNotNull(
            SubtitleAudioAligner.estimate(
                segments = SubtitleAudioAligner.speechStarts(segments),
                track = track.starts,
                scales = doubleArrayOf(1.0),
                minShiftMs = -60_000.0,
                maxShiftMs = 60_000.0,
            ),
        )
        assertTrue(abs(starts.shiftMs - trueShiftMs) < 150.0, "starts $starts")
        assertTrue(starts.separation > 0.2, "starts stand out: $starts")
    }
}
