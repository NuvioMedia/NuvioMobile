package com.nuvio.app.features.audiosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSyncDiagnosticsTest {
    @Test
    fun timelineCountsUniqueAudioAndReplacesOverlappingSpeechEvidence() {
        val timeline = SpeechTimeline()
        timeline.record(100, 1f)
        timeline.record(101, 0f)
        timeline.record(100, 0f) // Playback and look-ahead can analyse the same media frame.
        timeline.record(101, 1f)
        val diagnostics = timeline.diagnostics()
        assertEquals(0.064, diagnostics.analysedSeconds, 1e-9)
        assertEquals(0.032, diagnostics.speechSeconds, 1e-9)
        assertEquals(3_264L, diagnostics.lastMediaMs)
        timeline.clear()
        assertEquals(0.0, timeline.diagnostics().analysedSeconds)
        assertEquals(0.0, timeline.diagnostics().speechSeconds)
        assertNull(timeline.diagnostics().lastMediaMs)
    }

    @Test
    fun speechSummaryMatchesQuantizedTimelineWithRepeatedWrites() {
        val timeline = SpeechTimeline()
        repeat(500) { frame -> timeline.record(frame, (frame % 100) / 100f) }
        repeat(500) { frame -> timeline.record(frame, (frame % 17) / 17f) }
        val expected = timeline.snapshot(0, 500).sumOf { it.toDouble() } * 0.032
        assertEquals(expected, timeline.diagnostics().speechSeconds, 1e-6)
    }

    @Test
    fun trackerExplainsMissingAndInsufficientAudio() {
        val tracker = AudioSyncTracker(track())
        val timeline = SpeechTimeline()
        assertEquals(AudioSyncTracker.Outcome.NotEnoughEvidence, tracker.update(timeline, 0))
        assertEquals("Waiting for analysed audio", tracker.waitingReason)
        repeat(100) { timeline.record(it, 0.5f) }
        assertEquals(AudioSyncTracker.Outcome.NotEnoughEvidence, tracker.update(timeline, 3_000))
        assertTrue(tracker.waitingReason.startsWith("Collecting audio"))
    }

    @Test
    fun trackerExplainsFlatDetectorOutputRatherThanSilentlyWaiting() {
        for (probability in listOf(0f, 0.5f, 1f)) {
            val tracker = AudioSyncTracker(track())
            val timeline = SpeechTimeline()
            repeat(800) { timeline.record(it, probability) }
            assertEquals(AudioSyncTracker.Outcome.NotEnoughEvidence, tracker.update(timeline, 25_000))
            assertTrue(tracker.waitingReason.startsWith("Speech activity is too constant"))
            assertNull(tracker.lastEstimate)
        }
    }

    @Test
    fun trackerExplainsNoSubtitleOverlap() {
        val tracker = AudioSyncTracker(track(startMs = 600_000L))
        val timeline = SpeechTimeline()
        repeat(800) { timeline.record(it, if (it % 41 < 17) 0.9f else 0.1f) }
        assertEquals(AudioSyncTracker.Outcome.NotEnoughEvidence, tracker.update(timeline, 25_000))
        assertTrue(tracker.waitingReason.startsWith("No usable subtitle overlap"))
    }

    private fun track(startMs: Long = 0L): SubtitleSpeechTrack = SubtitleSpeechTrack.fromCues(
        List(30) { index ->
            val start = startMs + index * 3_000L
            Triple(start, start + 1_500L, "A line of spoken dialogue")
        },
    )
}
