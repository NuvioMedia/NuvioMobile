package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.audiosync.asr.SpeechSegmenter
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpotSamplingTest {
    private val filmMs = 100 * 60_000L

    @Test
    fun shortPlaybackBuffersBuildEvidenceWhenCaptureHasNone() {
        val weights = SileroVadWeights.read(File("src/commonMain/composeResources/files/silero_vad_v5_16k.bin").readBytes())
        for ((rate, frames) in listOf(48_000 to 960, 16_000 to 320, 48_000 to 320)) {
            val timeline = SpeechTimeline()
            val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
            val samples = FloatArray(frames)
            val durationUs = frames * 1_000_000L / rate
            val blocks = 6 * rate / frames
            repeat(blocks) { index ->
                val timeUs = 240_000_000L + index * frames * 1_000_000L / rate
                // The playback fallback must retain short buffers until VAD produces full frames.
                if (timeline.needsAnalysis(timeUs, durationUs)) {
                    analyzer.accept(samples, frames, rate, timeUs)
                }
            }
            assertEquals(1L, analyzer.starts, "continuous $frames-frame buffers at $rate Hz")
            assertTrue(timeline.diagnostics().analysedSeconds > 5.7, "short PCM never got past warm-up at $rate Hz")
            val known = timeline.knownRange()!!
            assertFalse(timeline.needsAnalysis(known.first * SpeechTimeline.FRAME_DURATION_US, 20_000L))
            assertTrue(timeline.needsAnalysis(known.last * SpeechTimeline.FRAME_DURATION_US, 40_000L))
        }
    }

    @Test
    fun sampledPcmStillBuildsSpeechActivityWithRecognitionDisabled() {
        val timeline = SpeechTimeline()
        val engine = AsrSyncEngine(timeline, onLock = { error("Recognition must remain idle") })
        engine.setListening(false) // Japanese audio, or no usable English reference.
        engine.setRecognizer { error("Sampled speech must not invoke English recognition") }
        val weights = SileroVadWeights.read(File("src/commonMain/composeResources/files/silero_vad_v5_16k.bin").readBytes())
        val segmenter = SpeechSegmenter { frame, samples -> engine.offerSegment(frame, samples, spread = true) }
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline).apply { chunkListener = segmenter }
        val random = Random(721)
        try {
            // Two disconnected sampled spots, neither at the playhead. Each must warm up its VAD.
            for (startUs in listOf(60_000_000L, 420_000_000L)) {
                repeat(100) { index ->
                    val samples = FloatArray(SileroVad.CHUNK_SAMPLES) { random.nextFloat() * 0.4f - 0.2f }
                    analyzer.accept(samples, samples.size, SileroVad.SAMPLE_RATE, startUs + index * SileroVad.CHUNK_DURATION_US)
                }
                segmenter.flush()
            }
            assertEquals(2L, analyzer.starts)
            assertEquals(192, timeline.knownFrameCount())
            assertEquals(2, timeline.segments().size)
            assertEquals(6.144, timeline.diagnostics().analysedSeconds, 1e-9)
            assertTrue(timeline.diagnostics().speechSeconds > 0.0)
            assertEquals(0, engine.heardWordCount)
        } finally {
            engine.release()
        }
    }

    @Test
    fun spotsLandOnDialogueEvenWithOffTiming() {
        // Dialogue only in a few scenes; the subtitle used for planning is 8 s late.
        val scenes = listOf(12L, 31L, 47L, 66L, 83L).map { it * 60_000L }
        val spoken = scenes.flatMap { scene -> cues(Random(scene), scene, scene + 90_000L) }
        val lateSubtitle = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a + 8_000L, b + 8_000L, t) })
        val spots = DialogueSpotPlanner.plan(listOf(lateSubtitle), filmMs, count = 4, spotMs = 30_000L)
        assertEquals(4, spots.size)
        for (spot in spots) {
            val speech = spoken.sumOf { (a, b, _) -> overlap(a, b, spot, spot + 30_000L) }
            assertTrue(speech > 10_000L, "spot at ${spot / 1_000}s has only ${speech / 1_000}s of dialogue")
        }
        // Spread over the film, widest first.
        assertTrue(abs(spots[0] - spots[1]) > 20 * 60_000L, "spots $spots")
    }

    @Test
    fun spotOpeningsFindSpeechWhenTheSubtitleIsOff() {
        // Each part of the film has a short dense exchange and a long conversation; the subtitle
        // used for planning is 15 s early. On a high-bitrate stream only a spot's first 8 s are
        // read, so those must be speech, which only the long conversations guarantee.
        val spoken = (0 until 4).flatMap { part ->
            val base = (8 + part * 22) * 60_000L
            cues(Random(70L + part), base, base + 25_000L) +
                cues(Random(80L + part), base + 9 * 60_000L, base + 12 * 60_000L)
        }
        val earlySubtitle = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 15_000L, b - 15_000L, t) })
        val spots = DialogueSpotPlanner.plan(listOf(earlySubtitle), filmMs, count = 4, spotMs = 30_000L)
        assertEquals(4, spots.size)
        for (spot in spots) {
            val speech = spoken.sumOf { (a, b, _) -> overlap(a, b, spot, spot + 8_000L) }
            assertTrue(speech > 4_000L, "spot at ${spot / 1_000}s opens with only ${speech / 1_000}s of dialogue")
        }
    }

    @Test
    fun spotsAreEvenlySpreadWithoutSubtitles() {
        val spots = DialogueSpotPlanner.plan(emptyList(), filmMs, count = 4, spotMs = 30_000L, notBeforeMs = 5 * 60_000L)
        assertEquals(4, spots.size)
        assertTrue(spots.all { it >= 5 * 60_000L && it <= filmMs - 6 * 60_000L }, "spots $spots")
        val sorted = spots.sorted()
        for (i in 1 until sorted.size) assertTrue(sorted[i] - sorted[i - 1] > 15 * 60_000L, "spots $spots")
    }

    @Test
    fun scatteredSpotsLockQuicklyAndCatchFrameRate() {
        val spoken = cues(Random(40), 20_000L, filmMs - 5 * 60_000L)
        // Subtitle made for 25 fps, video at 23.976 fps, plus a 1.2 s offset.
        val truth = SubtitleSyncSegment(0L, 25.0 / 23.976, -1_200.0)
        val subtitle = spoken.map { (a, b, t) ->
            Triple(((a - truth.shiftMs) / truth.scale).toLong(), ((b - truth.shiftMs) / truth.scale).toLong(), t)
        }
        val track = SubtitleSpeechTrack.fromCues(subtitle)
        val full = speechFor(spoken, Random(41))
        // Only the first 90 s near the playhead plus four sampled spots are known.
        val live = SpeechTimeline()
        val spots = DialogueSpotPlanner.plan(listOf(track), filmMs, count = 4, spotMs = 30_000L, notBeforeMs = 90_000L)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val known = listOf(0L to 90_000L) + spots.map { it to it + 30_000L }
        val all = full.snapshot(0, (filmMs / frameMs).toInt())
        for ((from, to) in known) {
            for (f in (from / frameMs).toInt() until (to / frameMs).toInt()) if (!all[f].isNaN()) live.record(f, all[f])
        }
        val tracker = AudioSyncTracker(track)
        tracker.update(live, 10_000L)
        val model = assertNotNull(tracker.model, "no lock from ${known.size} stretches")
        val segment = model.segments.single()
        assertEquals(truth.scale, segment.scale, 1e-9)
        val delayMs = model.delayUsAt(3_000_000_000L) / 1_000.0
        val expectedMs = SubtitleSyncModel(listOf(truth)).delayUsAt(3_000_000_000L) / 1_000.0
        assertTrue(abs(delayMs - expectedMs) < 80.0, "delay $delayMs vs $expectedMs")
    }

    @Test
    fun stretchesMatchOneArrayExactly() {
        val spoken = cues(Random(50), 20_000L, 30 * 60_000L)
        val track = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 2_000L, b - 2_000L, t) })
        val timeline = speechFor(spoken, Random(51))
        val frames = (25 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        val whole = timeline.snapshot(0, frames)
        // Blank out a 5 minute hole so the timeline splits into two stretches.
        val holed = SpeechTimeline()
        val holeFrom = frames / 3
        val holeTo = holeFrom + (5 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        for (f in 0 until frames) if (f !in holeFrom until holeTo) holed.record(f, whole[f])
        val segments = holed.segments()
        assertEquals(2, segments.size)
        val single = SubtitleAudioAligner.estimate(
            probabilities = holed.snapshot(0, frames), fromFrame = 0, track = track,
            minShiftMs = -60_000.0, maxShiftMs = 60_000.0,
        )!!
        val split = SubtitleAudioAligner.estimate(segments, track, minShiftMs = -60_000.0, maxShiftMs = 60_000.0)!!
        assertEquals(single.scale, split.scale)
        assertEquals(single.shiftMs, split.shiftMs, 1e-6)
        assertEquals(single.peak, split.peak, 1e-9)
        // Only lines where audio is known count as evidence; the single array also counted the hole.
        assertTrue(split.cueCount < single.cueCount, "${split.cueCount} vs ${single.cueCount}")
    }

    @Test
    fun correlationMatchesDirectComputationWithAndWithoutGaps() {
        val spoken = cues(Random(60), 5_000L, 90_000L)
        val track = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 1_300L, b - 1_300L, t) })
        val base = speechFor(spoken, Random(61)).snapshot(0, 2_500)
        val withGaps = base.copyOf().also { for (f in 900 until 960) it[f] = Float.NaN }
        for (probabilities in listOf(base, withGaps)) {
            val fromFrame = 40
            val slice = probabilities.copyOfRange(fromFrame, probabilities.size)
            val estimate = SubtitleAudioAligner.estimate(
                probabilities = slice, fromFrame = fromFrame, track = track, scales = doubleArrayOf(1.0),
                minShiftMs = -10_000.0, maxShiftMs = 10_000.0,
            )!!
            // Direct masked Pearson correlation per lag.
            val frameMs = SpeechTimeline.FRAME_DURATION_MS
            val bias = SubtitleAudioAligner.DETECTOR_BIAS_MS
            val minLag = Math.floorDiv((-10_000.0 + bias).roundToInt(), frameMs.toInt())
            val maxLag = Math.floorDiv((10_000.0 + bias).roundToInt(), frameMs.toInt()) + 1
            val known = slice.indices.filter { !slice[it].isNaN() }
            val mean = known.sumOf { slice[it].toDouble() } / known.size
            val norm = kotlin.math.sqrt(known.sumOf { (slice[it] - mean) * (slice[it] - mean) })
            var bestPeak = Double.NEGATIVE_INFINITY
            for (lag in minLag..maxLag) {
                val g = track.render(fromFrame - lag, fromFrame - lag + slice.size, 1.0)
                var c1 = 0.0; var cm = 0.0; var cm2 = 0.0
                for (i in known) {
                    c1 += (slice[i] - mean) * g[i]; cm += g[i]; cm2 += g[i] * g[i]
                }
                val variance = cm2 - cm * cm / known.size
                if (variance > 1e-6 * known.size && cm > 0.5) bestPeak = maxOf(bestPeak, c1 / (norm * kotlin.math.sqrt(variance)))
            }
            assertEquals(bestPeak, estimate.peak, 1e-9)
            assertTrue(abs(estimate.shiftMs - 1_300.0) < 64.0, "shift ${estimate.shiftMs}")
        }
    }

    private fun overlap(a: Long, b: Long, from: Long, to: Long): Long = (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L)

    private fun cues(random: Random, fromMs: Long, toMs: Long): List<Triple<Long, Long, String>> {
        val cues = mutableListOf<Triple<Long, Long, String>>()
        var t = fromMs
        while (t < toMs) {
            val length = 800L + random.nextLong(3_700L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 9_000L else 2_500L)
        }
        return cues
    }

    private fun speechFor(cues: List<Triple<Long, Long, String>>, random: Random): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val frames = ((cues.maxOf { it.second } + 30_000) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        val latencyMs = SubtitleAudioAligner.DETECTOR_BIAS_MS
        for ((start, stop, _) in cues) {
            val from = ((start + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            val to = ((stop + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
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
