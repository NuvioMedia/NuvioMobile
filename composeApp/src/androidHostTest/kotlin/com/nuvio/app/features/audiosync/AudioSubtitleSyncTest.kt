package com.nuvio.app.features.audiosync

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSubtitleSyncTest {
    @Test
    fun retimedLinesPlayWhereTheDelayWouldShowThem() {
        // Frame-rate stretch plus a scene the release adds (+2.4 s from 5:00) and one it cuts (-3 s from 20:00).
        val model = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(0L, 1.001, 1_500.0),
                SubtitleSyncSegment(300_000L, 1.001, 3_900.0),
                SubtitleSyncSegment(1_200_000L, 1.001, 900.0),
            ),
        )
        var dropped = 0
        for (subtitleMs in 0L..1_800_000L step 700L) {
            val mediaUs = model.mediaTimeUs(subtitleMs * 1_000L)
            if (mediaUs == null) {
                dropped++
                continue
            }
            // A subtitle delay shows subtitle time (media - delay) at that media time: the same line.
            val shownMs = (mediaUs - model.delayUsAt(mediaUs)) / 1_000.0
            assertTrue(abs(shownMs - subtitleMs) < 1.0, "line at ${subtitleMs}ms plays at ${mediaUs / 1_000}ms showing ${shownMs}ms")
        }
        // Only lines of the cut 3 s are dropped.
        assertTrue(dropped in 1..6, "dropped $dropped")
    }

    @Test
    fun sileroPortMatchesReferenceModel() {
        val vad = SileroVad(loadWeights())
        val signal = referenceSignal(REFERENCE_PROBABILITIES.size * SileroVad.CHUNK_SAMPLES)
        REFERENCE_PROBABILITIES.forEachIndexed { index, expected ->
            val actual = vad.process(signal, index * SileroVad.CHUNK_SAMPLES)
            assertTrue(abs(actual - expected) < 0.01f, "chunk $index: expected $expected, got $actual")
        }
    }

    @Test
    fun analyzerAlignsFramesToMediaTime() {
        val timeline = SpeechTimeline()
        val analyzer = SpeechAnalyzer(SileroVad(loadWeights()), timeline)
        val rate = 48_000
        val block = FloatArray(1_024) { (0.1 * sin(2 * PI * 220 * it / rate)).toFloat() }
        var timeUs = 10_000_000L
        repeat(200) {
            analyzer.accept(block, block.size, rate, timeUs)
            timeUs += block.size * 1_000_000L / rate
        }
        val range = assertNotNull(timeline.knownRange())
        // First chunk starts on the next 32 ms boundary; four warm-up chunks are not recorded.
        assertEquals(313 + 4, range.first)
        val expectedLast = ((timeUs - 1) / SpeechTimeline.FRAME_DURATION_US).toInt() - 1
        assertTrue(abs(range.last - expectedLast) <= 1, "last frame ${range.last} vs $expectedLast")
    }

    @Test
    fun soundEffectAndLyricCuesAreIgnored() {
        assertTrue(SubtitleSpeechTrack.isDialogue("Where were you?"))
        assertTrue(SubtitleSpeechTrack.isDialogue("JOHN: Where were you?"))
        assertTrue(SubtitleSpeechTrack.isDialogue("<i>JOHN:</i> Where were you?"))
        assertTrue(SubtitleSpeechTrack.isDialogue("Hello!\n♪ la la la ♪"))
        assertTrue(SubtitleSpeechTrack.isDialogue("Hello!\nla la la ♪"))
        assertFalse(SubtitleSpeechTrack.isDialogue("[door slams]"))
        assertFalse(SubtitleSpeechTrack.isDialogue("(thunder rumbling)"))
        assertFalse(SubtitleSpeechTrack.isDialogue("♪ la la la ♪"))
        assertFalse(SubtitleSpeechTrack.isDialogue("<i>[door slams]</i>"))
        assertFalse(SubtitleSpeechTrack.isDialogue("<i> ♪ Vampirina ♪</i>"))
        assertFalse(SubtitleSpeechTrack.isDialogue("<i> ♪ We were normal vampires</i>\n<i> in Transylvania ♪</i>"))
        assertFalse(SubtitleSpeechTrack.isDialogue("♪ Line 1\nLine 2\nLine 3 ♪"))
    }

    @Test
    fun alignerRecoversConstantShift() {
        val cues = syntheticCues(Random(7), durationMs = 8 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, 2_370.0)
        val timeline = speechFor(cues, truth, Random(11))
        val estimate = assertNotNull(estimateAll(timeline, cues))
        assertEquals(1.0, estimate.scale)
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 50.0, "shift ${estimate.shiftMs}")
    }

    @Test
    fun narrowSearchNeedsTheEdgeMarginAddedToUseItsWholeRange() {
        val cues = syntheticCues(Random(7), durationMs = 8 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, 10_684.0)
        val timeline = speechFor(cues, truth, Random(11))
        val range = timeline.knownRange()!!
        val speech = assertNotNull(
            SubtitleAudioAligner.prepare(timeline.snapshot(range.first, range.last + 1), range.first, -60_000.0, 60_000.0),
        )
        val track = SubtitleSpeechTrack.fromCues(cues)
        // A ±1.5 s search around 10 s finds 10,684 ms but is all edge margin.
        assertTrue(assertNotNull(speech.estimate(track, doubleArrayOf(1.0), 8_500.0, 11_500.0)).atSearchEdge)
        val reach = 1_500.0 + SubtitleAudioAligner.EDGE_MARGIN_MS
        val widened = assertNotNull(speech.estimate(track, doubleArrayOf(1.0), 10_000.0 - reach, 10_000.0 + reach))
        assertFalse(widened.atSearchEdge)
        assertTrue(abs(widened.shiftMs - truth.shiftMs) < 50.0, "shift ${widened.shiftMs}")
    }

    @Test
    fun narrowSearchScoresMatchTheTransformedSearch() {
        val cues = syntheticCues(Random(41), durationMs = 10 * 60_000L)
        val timeline = speechFor(cues, SubtitleSyncSegment(0L, 25.0 / 24.0, 3_210.0), Random(42))
        val range = timeline.knownRange()!!
        val probabilities = timeline.snapshot(range.first, range.last + 1)
        // Unknown audio inside the stretch, as from a dropped decoder buffer.
        for (i in 4_000 until 4_300) probabilities[i] = Float.NaN
        val segments = listOf(
            SpeechSegment(range.first, probabilities.copyOfRange(0, 9_000)),
            SpeechSegment(range.first + 11_000, probabilities.copyOfRange(11_000, probabilities.size)),
        )
        val track = SubtitleSpeechTrack.fromCues(cues)
        val wide = assertNotNull(SubtitleAudioAligner.prepare(segments, -60_000.0, 60_000.0))
        for (centre in listOf(3_210.0, 2_000.0, -700.0)) {
            for (scale in SubtitleAudioAligner.CANDIDATE_SCALES) {
                val narrow = assertNotNull(SubtitleAudioAligner.prepare(segments, centre - 1_500.0, centre + 1_500.0))
                val direct = assertNotNull(narrow.estimate(track, doubleArrayOf(scale)))
                val transformed = assertNotNull(wide.estimate(track, doubleArrayOf(scale), centre - 1_500.0, centre + 1_500.0))
                assertEquals(transformed.shiftMs, direct.shiftMs, 1e-6)
                assertEquals(transformed.peak, direct.peak, 1e-9)
                assertEquals(transformed.prominence, direct.prominence, 1e-9)
                assertEquals(transformed.atSearchEdge, direct.atSearchEdge)
            }
        }
    }

    @Test
    fun alignerDetectsFrameRateMismatch() {
        val cues = syntheticCues(Random(3), durationMs = 12 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 25.0 / 23.976, -1_200.0)
        val timeline = speechFor(cues, truth, Random(5))
        val estimate = assertNotNull(estimateAll(timeline, cues))
        assertEquals(truth.scale, estimate.scale, 1e-9)
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 80.0, "shift ${estimate.shiftMs}")
    }

    @Test
    fun trackerLocksOntoMatchingSubtitles() {
        val cues = syntheticCues(Random(21), durationMs = 20 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, -4_500.0)
        val model = assertNotNull(replay(speechFor(cues, truth, Random(22)), cues))
        val delayMs = model.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
    }

    @Test
    fun trackerFollowsJumpInSubtitleTiming() {
        val cues = syntheticCues(Random(31), durationMs = 30 * 60_000L)
        // Subtitles made for a cut that lacks 6 seconds after the 12 minute mark.
        val speech = SpeechTimeline()
        val early = speechFor(cues.filter { it.first < 720_000L }, SubtitleSyncSegment(0L, 1.0, 1_000.0), Random(32))
        val late = speechFor(cues.filter { it.first >= 720_000L }, SubtitleSyncSegment(0L, 1.0, 7_000.0), Random(33))
        val known = maxOf(early.knownRange()!!.last, late.knownRange()!!.last)
        val a = early.snapshot(0, known + 1)
        val b = late.snapshot(0, known + 1)
        val splitFrame = (721_000.0 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        for (frame in 0..known) speech.record(frame, if (frame < splitFrame) a[frame] else b[frame])
        val model = assertNotNull(replay(speech, cues))
        val before = model.delayUsAt(300_000_000L) / 1_000.0
        val after = model.delayUsAt(1_500_000_000L) / 1_000.0
        assertTrue(abs(before - 1_000.0) < 80.0, "before $before")
        assertTrue(abs(after - 7_000.0) < 80.0, "after $after")
    }

    @Test
    fun trackerAppliesEarlyEstimateWithinFirstMinute() {
        val cues = syntheticCues(Random(41), durationMs = 10 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, 2_600.0)
        val full = speechFor(cues, truth, Random(42))
        val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues))
        val all = full.snapshot(0, full.knownRange()!!.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        var positionMs = 0L
        var firstMoveMs: Long? = null
        while (positionMs <= 120_000L && firstMoveMs == null) {
            val horizon = minOf(all.size, ((positionMs + 60_000L) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            tracker.update(live, positionMs)
            val applied = tracker.model ?: tracker.provisionalModel
            if (applied != null) {
                firstMoveMs = positionMs
                val delayMs = applied.delayUsAt(positionMs * 1_000L) / 1_000.0
                assertTrue(abs(delayMs - truth.shiftMs) < 150.0, "early delay $delayMs")
            }
            positionMs += 3_000L
        }
        val moved = assertNotNull(firstMoveMs, "no early estimate within two minutes")
        assertTrue(moved <= 60_000L, "first estimate only at ${moved}ms")
    }

    @Test
    fun trackerNeverLocksOntoUnrelatedSubtitles() {
        repeat(6) { seed ->
            val spoken = syntheticCues(Random(100 + seed), durationMs = 20 * 60_000L)
            val unrelated = syntheticCues(Random(200 + seed), durationMs = 20 * 60_000L)
            val timeline = speechFor(spoken, SubtitleSyncSegment(0L, 1.0, 0.0), Random(300 + seed))
            val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(unrelated))
            assertNull(replay(timeline, unrelated, tracker), "false lock with seed $seed")
            assertNull(tracker.provisionalModel, "false early estimate with seed $seed")
        }
    }

    private fun loadWeights(): SileroVadWeights =
        SileroVadWeights.read(File("src/commonMain/composeResources/files/silero_vad_v5_16k.bin").readBytes())

    private fun referenceSignal(length: Int): FloatArray {
        var state = 12_345L
        return FloatArray(length) { n ->
            state = (state * 1_103_515_245L + 12_345L) % (1L shl 31)
            val noise = state.toDouble() / (1L shl 31) - 0.5
            val envelope = 0.5 + 0.5 * sin(2 * PI * 3 * n / 16_000)
            val t = 2 * PI * n / 16_000
            (0.25 * envelope * (sin(180 * t) + 0.5 * sin(360 * t) + 0.25 * sin(540 * t)) + 0.02 * noise).toFloat()
        }
    }

    /** Dialogue-like cues: 0.8-4.5 s lines separated by 0.2-9 s gaps. */
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

    /** Noisy detector output for speech that happens where [cues] land under [mapping]. */
    private fun speechFor(
        cues: List<Triple<Long, Long, String>>,
        mapping: SubtitleSyncSegment,
        random: Random,
    ): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val end = cues.maxOf { it.second } * mapping.scale + mapping.shiftMs + 30_000
        val frames = (end / frameMs).toInt()
        val speaking = BooleanArray(frames)
        // The real detector reports speech slightly late; the aligner compensates for that.
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

    private fun estimateAll(timeline: SpeechTimeline, cues: List<Triple<Long, Long, String>>): SubtitleAudioAligner.Estimate? {
        val range = timeline.knownRange()!!
        return SubtitleAudioAligner.estimate(
            probabilities = timeline.snapshot(range.first, range.last + 1),
            fromFrame = range.first,
            track = SubtitleSpeechTrack.fromCues(cues),
            minShiftMs = -60_000.0,
            maxShiftMs = 60_000.0,
        )
    }

    /** Plays [full] back with a 60 s look-ahead, updating the tracker every 10 s. */
    private fun replay(
        full: SpeechTimeline,
        cues: List<Triple<Long, Long, String>>,
        tracker: AudioSyncTracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues)),
    ): SubtitleSyncModel? {
        val range = full.knownRange()!!
        val all = full.snapshot(0, range.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        val endMs = ((range.last + 1) * SpeechTimeline.FRAME_DURATION_MS).toLong()
        var positionMs = 0L
        while (positionMs <= endMs) {
            val horizon = minOf(all.size, ((positionMs + 60_000L) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            tracker.update(live, positionMs)
            positionMs += 10_000L
        }
        return tracker.model
    }

    private companion object {
        /** Silero VAD v5 (official ONNX export, onnxruntime) on [referenceSignal], one value per 512 samples. */
        val REFERENCE_PROBABILITIES = floatArrayOf(
            0.5301f, 0.7079f, 0.7212f, 0.6686f, 0.5150f, 0.5388f, 0.9100f, 0.9640f, 0.8622f, 0.8879f,
            0.8539f, 0.7242f, 0.6466f, 0.6541f, 0.5937f, 0.5775f, 0.6165f, 0.8755f, 0.8050f, 0.7650f,
            0.7258f, 0.6130f, 0.5258f, 0.4998f, 0.4177f, 0.3685f, 0.2827f, 0.3774f, 0.2654f, 0.3670f,
            0.4804f, 0.3676f, 0.1679f, 0.0845f, 0.0941f, 0.0768f, 0.0639f, 0.0839f, 0.0938f, 0.1370f,
        )
    }
}
