package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming front-end: mono PCM at any rate -> 16 kHz -> 32 ms chunks aligned to the media
 * timeline -> Silero VAD -> [SpeechTimeline].
 *
 * Blocks must arrive in presentation order. A timestamp jump, a rate change or [reset] restarts the
 * resampler and the VAD state, so seeks and dropped input never smear speech across the gap.
 */
internal class SpeechAnalyzer(
    private val vad: SileroVad,
    private val timeline: SpeechTimeline,
) {
    /** Receives every analysed 16 kHz chunk (after warm-up) with its frame and speech probability. */
    interface ChunkListener {
        fun onChunk(frame: Int, chunk: FloatArray, probability: Float)

        /** Audio continuity was lost (seek, dropped input); partial state must be discarded. */
        fun onReset()
    }

    @Volatile
    var chunkListener: ChunkListener? = null

    private var sourceRate = 0
    private var kernel: ResampleKernel? = null

    /** Source samples kept for the resampler; [historyStart] is the absolute index of history[0]. */
    private var history = FloatArray(0)
    private var historyLength = 0
    private var historyStart = 0L

    /** Media time of absolute source sample 0 of the current run. */
    private var runStartTimeUs = 0L
    private var receivedSamples = 0L

    /** Absolute source position of the next 16 kHz output sample. */
    private var nextOutputPosition = 0.0
    private var outputStep = 1.0

    private val chunk = FloatArray(SileroVad.CHUNK_SAMPLES)
    private var chunkFill = 0
    private var nextFrame = 0
    private var warmupChunks = 0
    private var running = false
    @Volatile
    var starts: Long = 0L
        private set
    @Volatile
    var lastStartReason: String = "not started"
        private set

    fun reset() {
        running = false
        chunkFill = 0
        historyLength = 0
        vad.reset()
        chunkListener?.onReset()
    }

    /** Feeds [count] mono samples starting at media time [timeUs]. */
    fun accept(samples: FloatArray, count: Int, sampleRate: Int, timeUs: Long) {
        if (count <= 0 || sampleRate <= 0) return
        val deltaUs = if (running) timeUs - expectedNextTimeUs() else 0L
        val reason = when {
            !running -> "initial or reset"
            sampleRate != sourceRate -> "rate $sourceRate->$sampleRate"
            abs(deltaUs) > CONTINUITY_TOLERANCE_US -> "timestamp delta=${deltaUs}us block=$count@$sampleRate timeUs=$timeUs"
            else -> null
        }
        if (reason != null) {
            lastStartReason = reason
            start(sampleRate, timeUs)
        }
        appendHistory(samples, count)
        receivedSamples += count
        produceOutput()
    }

    private fun expectedNextTimeUs(): Long =
        runStartTimeUs + receivedSamples * 1_000_000L / sourceRate.coerceAtLeast(1)

    private fun start(sampleRate: Int, timeUs: Long) {
        starts++
        if (sampleRate != sourceRate || kernel == null) {
            sourceRate = sampleRate
            kernel = ResampleKernel.create(sampleRate, SileroVad.SAMPLE_RATE)
        }
        running = true
        vad.reset()
        chunkListener?.onReset()
        chunkFill = 0
        historyLength = 0
        historyStart = 0L
        receivedSamples = 0L
        runStartTimeUs = timeUs
        outputStep = sampleRate.toDouble() / SileroVad.SAMPLE_RATE
        // Start on the next frame boundary so chunks line up with the timeline grid.
        val frameUs = SpeechTimeline.FRAME_DURATION_US
        val firstFrame = (timeUs + frameUs - 1).floorDiv(frameUs)
        nextFrame = firstFrame.toInt()
        val firstOutputTimeUs = firstFrame * frameUs
        nextOutputPosition = (firstOutputTimeUs - timeUs) * sampleRate / 1_000_000.0
        warmupChunks = WARMUP_CHUNKS
    }

    private fun appendHistory(samples: FloatArray, count: Int) {
        val required = historyLength + count
        if (history.size < required) {
            history = history.copyOf(maxOf(required, history.size * 2, 4_096))
        }
        samples.copyInto(history, historyLength, 0, count)
        historyLength += count
    }

    private fun produceOutput() {
        val kernel = kernel ?: return
        val available = historyStart + historyLength
        while (true) {
            val center = nextOutputPosition
            if (floor(center).toLong() + kernel.halfWidth >= available) break
            val sample = kernel.interpolate(history, historyStart, historyLength, center)
            nextOutputPosition += outputStep
            chunk[chunkFill++] = sample
            if (chunkFill == SileroVad.CHUNK_SAMPLES) {
                chunkFill = 0
                val probability = vad.process(chunk)
                val frame = nextFrame++
                if (warmupChunks > 0) {
                    warmupChunks--
                } else {
                    timeline.record(frame, probability)
                    chunkListener?.onChunk(frame, chunk, probability)
                }
            }
        }
        // Drop history the kernel can no longer reach.
        val keepFrom = floor(nextOutputPosition).toLong() - kernel.halfWidth - 1
        val drop = (keepFrom - historyStart).coerceIn(0L, historyLength.toLong()).toInt()
        if (drop > 0) {
            history.copyInto(history, 0, drop, historyLength)
            historyLength -= drop
            historyStart += drop
        }
    }

    /**
     * Windowed-sinc low-pass resampler as a polyphase bank: the fractional source position is
     * rounded to one of [PHASES] precomputed tap sets, so each output sample is one dot product.
     */
    private class ResampleKernel(
        val halfWidth: Int,
        private val bank: FloatArray,
    ) {
        private val taps = 2 * halfWidth

        fun interpolate(history: FloatArray, historyStart: Long, historyLength: Int, position: Double): Float {
            if (halfWidth == 0) {
                val index = (position.toLong() - historyStart).toInt()
                return if (index in 0 until historyLength) history[index] else 0f
            }
            val base = floor(position).toLong()
            var phase = ((position - base) * PHASES + 0.5).toInt()
            var first = base - halfWidth + 1
            if (phase == PHASES) {
                phase = 0
                first++
            }
            val start = (first - historyStart).toInt()
            val offset = phase * taps
            var acc = 0f
            if (start >= 0 && start + taps <= historyLength) {
                for (k in 0 until taps) acc += history[start + k] * bank[offset + k]
            } else {
                for (k in 0 until taps) {
                    val index = start + k
                    if (index in 0 until historyLength) acc += history[index] * bank[offset + k]
                }
            }
            return acc
        }

        companion object {
            private const val ZERO_CROSSINGS = 6
            private const val PHASES = 64

            fun create(sourceRate: Int, targetRate: Int): ResampleKernel {
                if (sourceRate == targetRate) return ResampleKernel(0, FloatArray(0))
                // Cutoff relative to the source Nyquist; keep a small guard band below 8 kHz.
                val cutoff = minOf(1.0, targetRate.toDouble() / sourceRate) * 0.92
                val halfWidth = ceil(ZERO_CROSSINGS / cutoff).toInt()
                val taps = 2 * halfWidth
                val bank = FloatArray(PHASES * taps)
                for (phase in 0 until PHASES) {
                    val fraction = phase.toDouble() / PHASES
                    for (k in 0 until taps) {
                        // Tap k reads source sample (base - halfWidth + 1 + k); distance from the output point:
                        val x = (k - halfWidth + 1) - fraction
                        val ax = abs(x)
                        val sinc = if (ax < 1e-9) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                        val window = if (ax >= halfWidth) 0.0 else 0.5 + 0.5 * cos(PI * x / halfWidth)
                        bank[phase * taps + k] = (cutoff * sinc * window).toFloat()
                    }
                }
                return ResampleKernel(halfWidth, bank)
            }
        }
    }

    companion object {
        private const val CONTINUITY_TOLERANCE_US = 40_000L
        /** The LSTM needs a few chunks after a reset before its output is meaningful. */
        private const val WARMUP_CHUNKS = 4
    }
}
