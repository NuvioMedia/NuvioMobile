package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Speech probability per 32 ms frame on the media timeline (frame n covers [n * 32 ms, (n + 1) * 32 ms)).
 *
 * Values are stored as one byte on a square-root scale (keeping resolution for low probabilities),
 * so a two hour film needs about 225 KB. Frames that were never
 * analysed stay unknown and are excluded from alignment. Thread-safe: the decoder thread writes while
 * the aligner takes snapshots.
 */
internal class SpeechTimeline {
    // Written under the lock; read without it on the hot paths (the player's playback and loader
    // threads ask per buffer whether a stretch is still unknown). A stale answer there only
    // decides whether a buffer gets analysed, so it is harmless, while waiting on a lock held by a
    // background-priority thread would delay video frames.
    @Volatile private var values = ByteArray(INITIAL_CAPACITY)
    @Volatile private var knownFrames = 0
    /** One past the highest frame ever recorded, so scans stop there rather than at capacity. */
    @Volatile private var knownEnd = 0
    private var probabilitySum = 0.0
    private val lock = SynchronizedObject()

    data class Diagnostics(val analysedSeconds: Double, val speechSeconds: Double, val lastMediaMs: Long?)

    /** Constant-time summary; repeated analysis of a frame replaces its earlier contribution. */
    fun diagnostics(): Diagnostics = synchronized(lock) {
        Diagnostics(
            knownFrames * FRAME_DURATION_MS / 1_000.0,
            probabilitySum * FRAME_DURATION_MS / 1_000.0,
            if (knownFrames == 0) null else (knownEnd * FRAME_DURATION_MS).toLong(),
        )
    }

    @Volatile
    var version: Long = 0L
        private set

    fun record(frame: Int, probability: Float) {
        synchronized(lock) {
            if (frame < 0 || frame >= MAX_FRAMES) return
            ensureCapacity(frame + 1)
            val quantized = 1 + (kotlin.math.sqrt(probability.coerceIn(0f, 1f)) * 254f + 0.5f).toInt()
            val current = values
            if (current[frame].toInt() == 0) knownFrames++
            probabilitySum += probabilityOf(quantized) - probabilityOf(current[frame].toInt() and 0xFF)
            current[frame] = quantized.toByte()
            if (frame >= knownEnd) knownEnd = frame + 1
            version++
        }
    }

    fun clear() {
        synchronized(lock) {
            values.fill(0)
            knownFrames = 0
            knownEnd = 0
            probabilitySum = 0.0
            version++
        }
    }

    fun knownFrameCount(): Int = knownFrames

    /** Even PCM shorter than one VAD frame must be fed while any of its audio is unknown. */
    fun needsAnalysis(timeUs: Long, durationUs: Long): Boolean {
        if (durationUs <= 0) return false
        val from = frameForTimeUs(timeUs)
        val to = frameForTimeUs(timeUs + durationUs - 1) + 1
        return knownFramesIn(from, to) < to - from
    }

    /** Returns probabilities for [fromFrame, toFrame); unknown frames are NaN. */
    fun snapshot(fromFrame: Int, toFrame: Int): FloatArray {
        val out = FloatArray((toFrame - fromFrame).coerceAtLeast(0)) { Float.NaN }
        val start = fromFrame.coerceAtLeast(0)
        val raw = copyRange(start, toFrame)
        for (index in raw.indices) {
            val value = raw[index].toInt() and 0xFF
            if (value != 0) {
                val root = (value - 1) / 254f
                out[start + index - fromFrame] = root * root
            }
        }
        return out
    }

    /** First and last known frame (inclusive), or null when nothing was analysed yet. */
    fun knownRange(): IntRange? {
        if (knownFrames == 0) return null
        val raw = copyRange(0, Int.MAX_VALUE)
        var first = -1
        for (i in raw.indices) {
            if (raw[i].toInt() != 0) {
                first = i
                break
            }
        }
        if (first < 0) return null
        var last = first
        for (i in raw.indices.reversed()) {
            if (raw[i].toInt() != 0) {
                last = i
                break
            }
        }
        return first..last
    }

    /** Lock-free (see [values]): may miss frames being recorded concurrently. */
    fun knownFramesIn(fromFrame: Int, toFrame: Int): Int {
        val current = values
        var count = 0
        for (frame in fromFrame.coerceAtLeast(0) until toFrame.coerceAtMost(minOf(current.size, knownEnd))) {
            if (current[frame].toInt() != 0) count++
        }
        return count
    }

    /**
     * A copy of the raw values for [fromFrame, toFrame), clipped to what was ever recorded. Only
     * the copy happens under the lock; callers scan and convert outside it.
     */
    private fun copyRange(fromFrame: Int, toFrame: Int): ByteArray = synchronized(lock) {
        val start = fromFrame.coerceAtLeast(0)
        val end = toFrame.coerceAtMost(minOf(values.size, knownEnd))
        if (end <= start) EMPTY else values.copyOfRange(start, end)
    }

    /**
     * The first stretch [segments] would return from [fromFrame] (with no frame limit), as its
     * first..last known frame, without building it: lock-free (see [values]) and stopping where
     * that stretch ends, so it is cheap enough for a once-a-second status line.
     */
    fun firstStretch(fromFrame: Int, joinGapFrames: Int = DEFAULT_JOIN_GAP_FRAMES): IntRange? {
        if (knownFrames == 0) return null
        val current = values
        val end = minOf(current.size, knownEnd)
        var runStart = -1
        var lastKnown = -1
        for (frame in fromFrame.coerceAtLeast(0) until end) {
            if (current[frame].toInt() == 0) {
                // A gap this long splits the stretch whatever follows.
                if (runStart >= 0 && frame - lastKnown >= joinGapFrames) break
                continue
            }
            if (runStart < 0) runStart = frame
            lastKnown = frame
        }
        return if (runStart < 0) null else runStart..lastKnown
    }

    /**
     * Known audio in [fromFrame, toFrame) as separate stretches: runs of known frames, with unknown
     * gaps shorter than [joinGapFrames] kept inside a stretch (as NaN). At most [maxFrames] frames
     * are returned, keeping the latest stretches. Scattered samples of a film stay cheap to align
     * this way, where one array spanning all of them would not.
     */
    fun segments(
        fromFrame: Int = 0,
        toFrame: Int = Int.MAX_VALUE,
        joinGapFrames: Int = DEFAULT_JOIN_GAP_FRAMES,
        maxFrames: Int = Int.MAX_VALUE,
    ): List<SpeechSegment> {
        if (knownFrames == 0) return emptyList()
        val start = fromFrame.coerceAtLeast(0)
        // One consistent copy, then everything below runs without the lock.
        val raw = copyRange(start, toFrame)
        val runs = ArrayList<IntRange>()
        var runStart = -1
        var lastKnown = -1
        for (index in raw.indices) {
            if (raw[index].toInt() == 0) continue
            val frame = start + index
            if (runStart >= 0 && frame - lastKnown - 1 >= joinGapFrames) {
                runs += runStart..lastKnown
                runStart = -1
            }
            if (runStart < 0) runStart = frame
            lastKnown = frame
        }
        if (runStart >= 0) runs += runStart..lastKnown
        val kept = ArrayList<SpeechSegment>()
        var budget = maxFrames
        for (run in runs.asReversed()) {
            if (budget <= 0) break
            val first = maxOf(run.first, run.last + 1 - budget)
            kept += SpeechSegment(first, probabilities(raw, first - start, run.last + 1 - start))
            budget -= run.last + 1 - first
        }
        kept.reverse()
        return kept
    }

    private fun ensureCapacity(required: Int) {
        val current = values
        if (required <= current.size) return
        var size = current.size
        while (size < required) size *= 2
        values = current.copyOf(size.coerceAtMost(MAX_FRAMES))
    }

    /** Probabilities of raw[from until to]; unknown frames are NaN. */
    private fun probabilities(raw: ByteArray, from: Int, to: Int): FloatArray {
        val out = FloatArray(to - from)
        for (index in out.indices) {
            val value = raw[from + index].toInt() and 0xFF
            out[index] = if (value == 0) {
                Float.NaN
            } else {
                val root = (value - 1) / 254f
                root * root
            }
        }
        return out
    }

    companion object {
        const val FRAME_DURATION_US = SileroVad.CHUNK_DURATION_US
        const val FRAME_DURATION_MS = FRAME_DURATION_US / 1_000.0
        private const val INITIAL_CAPACITY = 1 shl 15
        private val EMPTY = ByteArray(0)

        private fun probabilityOf(value: Int): Double {
            if (value == 0) return 0.0
            val root = (value - 1) / 254.0
            return root * root
        }

        /** Two minutes: shorter gaps cost less to carry than a separate transform. */
        val DEFAULT_JOIN_GAP_FRAMES = (120_000 / FRAME_DURATION_MS).toInt()
        /** Six hours; anything beyond is ignored. */
        private const val MAX_FRAMES = (6L * 3_600_000_000L / FRAME_DURATION_US).toInt()

        fun frameForTimeUs(timeUs: Long): Int = timeUs.floorDiv(FRAME_DURATION_US).toInt()
    }
}

/** A stretch of the speech timeline: probabilities from [fromFrame] on, NaN where unknown. */
internal class SpeechSegment(val fromFrame: Int, val probabilities: FloatArray) {
    val toFrame: Int get() = fromFrame + probabilities.size

    val knownFrames: Int get() = probabilities.count { !it.isNaN() }
}
