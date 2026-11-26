package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrLock
import com.nuvio.app.features.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.audiosync.asr.ReferenceSubtitle
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Words heard densely around the playhead plus a few sampled spots across the film, as on a real
 * run; the lock must place subtitles right everywhere, not just where most words were heard.
 */
class AsrRateAndStepTest {
    private val syllables = listOf("ka", "lo", "mi", "ra", "te", "su", "no", "vi", "pe", "do")
    private val vocabulary = List(1_000) { index ->
        syllables[index % 10] + syllables[(index / 10) % 10] + syllables[(index / 100) % 10]
    }

    private fun script(random: Random, untilMs: Long): List<Triple<Long, Long, String>> {
        val out = ArrayList<Triple<Long, Long, String>>()
        var t = 20_000L
        while (t < untilMs) {
            val words = List(3 + random.nextInt(5)) { vocabulary[random.nextInt(vocabulary.size)] }
            val length = 1_200L + random.nextLong(2_500L)
            out += Triple(t, t + length, words.joinToString(" "))
            t += length + 500L + random.nextLong(3_000L)
        }
        return out
    }

    /**
     * Runs recognition with subtitle time mapped to media time by [toMedia], hearing the lines whose
     * media time falls in [heardMediaSec] ([spread] ones as sampled spots). Returns the last lock.
     */
    private fun lock(
        cues: List<Triple<Long, Long, String>>,
        toMedia: (Double) -> Double,
        nearSec: IntRange,
        spotsSec: List<IntRange>,
        seed: Int,
    ): AsrLock {
        val media = cues.map { (a, b, t) -> Triple((toMedia(a / 1_000.0) * 1_000).toLong(), (toMedia(b / 1_000.0) * 1_000).toLong(), t) }
        val locks = java.util.Collections.synchronizedList(ArrayList<AsrLock>())
        val engine = AsrSyncEngine(speech(media), onLock = { locks += it })
        engine.startSession(SubtitleSpeechTrack.fromCues(cues), listOf(ReferenceSubtitle("en", cues, null)))
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        fun offer(range: IntRange, spread: Boolean): Int {
            val lines = media.indices.filter { media[it].first / 1_000 in range }
            lines.forEach { line ->
                engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread)
            }
            return lines.size
        }
        var expected = spotsSec.sumOf { offer(it, spread = true) }
        expected += offer(nearSec, spread = false)
        val random = Random(seed)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.onPlayhead(nearSec.first * 1_000L)
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            text.split(' ').let { words ->
                words.mapIndexedNotNull { k, word ->
                    if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                    ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
                }
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        return locks.last()
    }

    /** Media time where the lock shows the subtitle line at [subSec]. */
    private fun shown(lock: AsrLock, subSec: Double): Double {
        // Media = sub * scale + shift within the segment that contains that media time.
        var result = Double.NaN
        for (segment in lock.segments) {
            val t = subSec * segment.scale + segment.shiftMs / 1_000.0
            if (t * 1_000 >= segment.fromMediaMs) result = t
        }
        return if (result.isNaN()) subSec * lock.segments[0].scale + lock.segments[0].shiftMs / 1_000.0 else result
    }

    private fun assertPlacedRight(lock: AsrLock, toMedia: (Double) -> Double, subSecs: List<Double>) {
        for (sub in subSecs) {
            val error = shown(lock, sub) - toMedia(sub)
            assertTrue(abs(error) < 1.0, "line at ${sub}s shown ${"%.1f".format(error)}s off; lock $lock")
        }
    }

    @Test
    fun rateWithAStepNearTheStartIsPlacedRightEverywhere() {
        // As Scary Movie 5 on 2026-10-02: subtitle made for 23.976 fps on a 25 fps release (×0.959),
        // and the release's opening differs by ~17 s. Many words heard near the start, a few spots.
        val cues = script(Random(21), 5_100_000L)
        val toMedia = { s: Double -> if (s < 200.0) 0.95904 * s - 3.3 else 0.95904 * s + 13.8 }
        val lock = lock(cues, toMedia, nearSec = 0..120, spotsSec = SPOTS, seed = 3)
        assertPlacedRight(lock, toMedia, listOf(60.0, 90.0, 800.0, 1_500.0, 2_300.0, 3_700.0, 4_500.0))
    }

    @Test
    fun rateWithAStepIsFoundFromAShortOpeningToo() {
        // Only the first minute heard around the playhead: there 1x fits as well as any rate, and
        // the opening alone is too little to trust its own offset yet (listening goes on there).
        // The rest of the film must not follow the opening.
        val cues = script(Random(25), 5_100_000L)
        val toMedia = { s: Double -> if (s < 200.0) 0.95904 * s - 3.3 else 0.95904 * s + 13.8 }
        val lock = lock(cues, toMedia, nearSec = 0..70, spotsSec = SPOTS, seed = 7)
        assertPlacedRight(lock, toMedia, listOf(800.0, 1_500.0, 2_300.0, 3_700.0, 4_500.0))
    }

    @Test
    fun plainRateIsPlacedRightEverywhere() {
        val cues = script(Random(22), 5_100_000L)
        val toMedia = { s: Double -> 0.95904 * s + 5.0 }
        val lock = lock(cues, toMedia, nearSec = 0..120, spotsSec = SPOTS, seed = 4)
        assertPlacedRight(lock, toMedia, listOf(60.0, 800.0, 2_300.0, 3_700.0, 4_500.0))
    }

    @Test
    fun normalRateWithManyWordsNearTheStartStaysNormal() {
        val cues = script(Random(23), 5_100_000L)
        val toMedia = { s: Double -> s - 2.5 }
        val lock = lock(cues, toMedia, nearSec = 0..120, spotsSec = SPOTS, seed = 5)
        assertPlacedRight(lock, toMedia, listOf(60.0, 800.0, 2_300.0, 3_700.0, 4_500.0))
    }

    @Test
    fun stepWithoutRateIsPlacedRight() {
        // A scene the release adds at ~30 min, normal rate, with full 30 s spots on both sides.
        val cues = script(Random(24), 5_100_000L)
        val toMedia = { s: Double -> if (s < 1_800.0) s + 1.0 else s + 25.0 }
        val spots = listOf(770..800, 2_190..2_220, 2_610..2_640, 3_590..3_620)
        val lock = lock(cues, toMedia, nearSec = 0..120, spotsSec = spots, seed = 6)
        assertPlacedRight(lock, toMedia, listOf(60.0, 800.0, 2_300.0, 3_700.0))
    }

    private companion object {
        /** Short sampled spots, as on a high-bitrate stream: only a few lines each. */
        val SPOTS = listOf(770..778, 2_190..2_198, 2_610..2_618, 3_590..3_598)
    }

    /** Clean speech exactly where [cues] are. */
    private fun speech(cues: List<Triple<Long, Long, String>>): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val timeline = SpeechTimeline()
        val frames = ((cues.maxOf { it.second } + 30_000) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        for ((a, b, _) in cues) {
            val from = ((a + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            val to = ((b + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            for (f in from.coerceAtLeast(0) until to.coerceAtMost(frames)) speaking[f] = true
        }
        // Only the heard stretches are known, like a real run.
        for (f in 0 until frames) timeline.record(f, if (speaking[f]) 0.9f else 0.05f)
        return timeline
    }
}
