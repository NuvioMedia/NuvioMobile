package com.nuvio.app.features.player

import kotlin.math.pow

/** Instant-attack peak limiter with hold and exponential release, applied per audio frame. */
internal class PeakLimiter(
    private val ceiling: Float = 0.891f, // -1 dBFS
    private val holdMs: Float = 10f,
    private val releaseMs: Float = 100f,
) {
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var holdFrames = (holdMs * DEFAULT_SAMPLE_RATE / 1000f).toInt()
    private var gain = 1f
    private var release = 1f
    private var holdCounter = 0

    fun configure(sampleRate: Int) {
        this.sampleRate = if (sampleRate > 0) sampleRate.toFloat() else DEFAULT_SAMPLE_RATE
        holdFrames = (holdMs * this.sampleRate / 1000f).toInt()
    }

    fun reset() {
        gain = 1f
        release = 1f
        holdCounter = 0
    }

    /** Returns the gain for a frame whose loudest sample, before limiting, is [peak]. */
    fun gainFor(peak: Float): Float {
        if (peak * gain > ceiling) {
            gain = ceiling / peak
            holdCounter = holdFrames
            release = (1f / gain).pow(1f / (releaseMs * sampleRate / 1000f))
        }
        val current = gain
        if (holdCounter > 0) {
            holdCounter--
        } else if (gain < 1f) {
            gain = (gain * release).coerceAtMost(1f)
        }
        return current
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48000f
    }
}
