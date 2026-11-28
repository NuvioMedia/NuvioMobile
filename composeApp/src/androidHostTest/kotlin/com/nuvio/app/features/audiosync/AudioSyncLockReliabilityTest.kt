package com.nuvio.app.features.audiosync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lock decisions measured on real releases: dense dialogue, and locks made from far-away spots. */
class AudioSyncLockReliabilityTest {
    @Test
    fun separationIgnoresTheMatchsOwnSlope() {
        // Long lines with short pauses: shifting the subtitle by 2.5 s still overlaps most speech,
        // so the curve falls off slowly around the true offset.
        val cues = denseCues(Random(51), durationMs = 4 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, -1_000.0)
        val timeline = speechFor(cues, truth, Random(52))
        val range = timeline.knownRange()!!
        val estimate = assertNotNull(
            SubtitleAudioAligner.estimate(
                probabilities = timeline.snapshot(range.first, range.last + 1),
                fromFrame = range.first,
                track = SubtitleSpeechTrack.fromCues(cues),
                minShiftMs = -60_000.0,
                maxShiftMs = 60_000.0,
            ),
        )
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 80.0, "shift ${estimate.shiftMs}")
        assertTrue(estimate.separation > estimate.prominence, "separation ${estimate.separation} prominence ${estimate.prominence}")
        assertTrue(estimate.separation >= AudioSyncTracker.ACCEPT_SEPARATION, "separation ${estimate.separation}")
    }

    @Test
    fun lockFromFarSpotsIsReplacedWhenPlaybackHearsAnotherOffset() {
        val cues = syntheticCues(Random(61), durationMs = 30 * 60_000L)
        val spotted = SubtitleSyncSegment(0L, 1.0, -20_000.0)
        val played = SubtitleSyncSegment(0L, 1.0, 1_200.0)
        val spotSpeech = speechFor(cues, spotted, Random(62))
        val playSpeech = speechFor(cues, played, Random(63))
        val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues))
        val live = SpeechTimeline()
        val frameMs = SpeechTimeline.FRAME_DURATION_MS

        // Look-ahead spots far from the playhead that support the wrong offset.
        val spotStarts = listOf(12 * 60_000L, 18 * 60_000L, 24 * 60_000L)
        val spotFrames = (60_000 / frameMs).toInt()
        val spots = spotSpeech.snapshot(0, spotSpeech.knownRange()!!.last + 1)
        for (start in spotStarts) {
            val first = (start / frameMs).toInt()
            for (frame in first until first + spotFrames) if (!spots[frame].isNaN()) live.record(frame, spots[frame])
        }
        tracker.update(live, 0L)
        val early = assertNotNull(tracker.model, "spots should lock")
        assertTrue(abs(early.segments.single().shiftMs - spotted.shiftMs) < 100.0, "spot lock ${early.segments}")

        // Playback from the start hears the other offset, which never agreed with the lock.
        val heard = playSpeech.snapshot(0, playSpeech.knownRange()!!.last + 1)
        var copied = 0
        var positionMs = 0L
        while (positionMs <= 10 * 60_000L) {
            val horizon = minOf(heard.size, ((positionMs + 60_000L) / frameMs).toInt())
            while (copied < horizon) {
                if (!heard[copied].isNaN()) live.record(copied, heard[copied])
                copied++
            }
            tracker.update(live, positionMs)
            positionMs += 8_000L
        }
        val model = assertNotNull(tracker.model)
        // Replaced, not split: the wrong offset must not survive for the start of the film.
        assertEquals(1, model.segments.size, "segments ${model.segments}")
        val delayMs = model.delayUsAt(5 * 60_000_000L) / 1_000.0
        assertTrue(abs(delayMs - played.shiftMs) < 100.0, "delay $delayMs")
    }

    @Test
    fun rateOfAnotherFamilyScoringAlikeWaitsUnlessLineStartsSettleIt() {
        val cues = syntheticCues(Random(71), durationMs = 30 * 60_000L)
        val track = SubtitleSpeechTrack.fromCues(cues)
        val timeline = speechFor(cues, SubtitleSyncSegment(0L, 1.0, 1_200.0), Random(72))
        val segments = timeline.segments(toFrame = (4 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt())
        val known = segments.sumOf { it.knownFrames }
        val speech = assertNotNull(SubtitleAudioAligner.prepare(segments, -60_000.0, 60_000.0))
        val unit = assertNotNull(speech.estimate(track, doubleArrayOf(1.0)))

        // The PAL rates score far lower here: no change to the lock.
        val rival = assertNotNull(AudioSyncTracker.rivalRate(speech, track, unit))
        assertTrue(rival.scale > 1.02 || rival.scale < 0.98, "rival $rival")
        assertTrue(AudioSyncTracker.isLockable(unit, segments, known, track, rival), "unit $unit rival $rival")
        // Another rate scoring as well on overlap, line starts clearly preferring this one: locks.
        val tiedPal = rival.copy(peak = unit.peak)
        assertTrue(AudioSyncTracker.isLockable(unit, segments, known, track, tiedPal))
        // A tie that line starts can't settle either (they score the same at both rates): waits.
        val tiedAlike = unit.copy(peak = unit.peak + 0.01)
        assertTrue(!AudioSyncTracker.isLockable(unit, segments, known, track, tiedAlike))
    }

    private fun syntheticCues(random: Random, durationMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = 5_000L + random.nextLong(20_000L)
        while (t < durationMs) {
            val length = 800L + random.nextLong(3_700L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 9_000L else 2_500L)
        }
        return cues
    }

    /** Back-to-back dialogue: 4-7 s lines with short pauses, as in a busy scene. */
    private fun denseCues(random: Random, durationMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = 5_000L
        while (t < durationMs) {
            val length = 4_000L + random.nextLong(3_000L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + 100L + random.nextLong(if (random.nextInt(8) == 0) 3_000L else 300L)
        }
        return cues
    }

    /** Noisy detector output for speech that happens where [cues] land under [mapping]. */
    private fun speechFor(cues: List<Triple<Long, Long, String>>, mapping: SubtitleSyncSegment, random: Random): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val end = cues.maxOf { it.second } * mapping.scale + mapping.shiftMs + 30_000
        val frames = (end / frameMs).toInt()
        val speaking = BooleanArray(frames)
        val latencyMs = SubtitleAudioAligner.DETECTOR_BIAS_MS
        for ((start, stop, _) in cues) {
            val from = ((start * mapping.scale + mapping.shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            val to = ((stop * mapping.scale + mapping.shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            for (f in from until to) speaking[f] = true
        }
        val timeline = SpeechTimeline()
        for (f in 0 until frames) {
            val p = if (speaking[f]) {
                if (random.nextFloat() < 0.25f) random.nextFloat() * 0.3f else 0.7f + random.nextFloat() * 0.3f
            } else {
                if (random.nextFloat() < 0.08f) 0.4f + random.nextFloat() * 0.5f else random.nextFloat() * 0.1f
            }
            timeline.record(f, p)
        }
        return timeline
    }
}
