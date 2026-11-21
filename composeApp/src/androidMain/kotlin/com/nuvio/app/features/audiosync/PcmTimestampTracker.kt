package com.nuvio.app.features.audiosync

import kotlin.math.abs

/**
 * Timestamps can identify a whole group of compressed packets, so several decoded PCM buffers
 * may have the same timestamp. Their samples still follow one another. Advance repeated stamps
 * by the actual PCM frame count; a distinct timestamp outside the normal jitter window reanchors
 * the clock, leaving real seeks and missing audio for SpeechAnalyzer to detect as discontinuities.
 * Used only by sync analysis; the player's audio and timestamps are never changed.
 */
internal class PcmTimestampTracker {
    private var sourceRate = 0
    private var anchorTimeUs = 0L
    private var receivedFrames = 0L
    private var lastReportedTimeUs = Long.MIN_VALUE

    @Volatile
    var repeatedTimestamps = 0L
        private set

    fun reset() {
        sourceRate = 0
        receivedFrames = 0L
        lastReportedTimeUs = Long.MIN_VALUE
    }

    fun resolve(timeUs: Long, frames: Int, sampleRate: Int): Long {
        require(frames > 0 && sampleRate > 0)
        val expected = if (sourceRate == sampleRate) {
            anchorTimeUs + receivedFrames * 1_000_000L / sampleRate
        } else null
        val repeated = expected != null && timeUs == lastReportedTimeUs
        if (repeated) repeatedTimestamps++
        val resolved = when {
            expected == null -> timeUs
            repeated || abs(timeUs - expected) <= JITTER_TOLERANCE_US -> expected
            else -> timeUs
        }
        if (expected == null || resolved != expected) {
            sourceRate = sampleRate
            anchorTimeUs = resolved
            receivedFrames = 0L
        }
        receivedFrames += frames
        lastReportedTimeUs = timeUs
        return resolved
    }

    private companion object {
        const val JITTER_TOLERANCE_US = 40_000L
    }
}
