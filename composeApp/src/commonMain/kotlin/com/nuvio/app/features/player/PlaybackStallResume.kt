package com.nuvio.app.features.player

internal enum class PlaybackSourceFailure {
    None,
    ClosedStream,
    ReadPositionOutOfRange,
}

/**
 * Remembers where a source that already started playing should be reopened.
 * The prepare position is the value written to the player as startPositionMs.
 */
internal class PlaybackReopenSession(
    private val initialPositionMs: Long?,
) {
    var fallbackStartPositionMs: Long? = null
        private set
    var probeAttempted: Boolean = false
        private set
    var resumeGeneration: Int = 0
        private set
    private var resumeAlreadyAttempted: Boolean = false

    fun onReady() {
        fallbackStartPositionMs = null
        resumeAlreadyAttempted = false
    }

    fun prepareStartPositionMs(decoderFallbackMs: Long? = null): Long? =
        decoderFallbackMs ?: fallbackStartPositionMs ?: initialPositionMs?.takeIf { it > 0L }

    fun isReopening(decoderFallbackMs: Long? = null): Boolean =
        decoderFallbackMs != null || fallbackStartPositionMs != null

    fun onFailure(failure: PlaybackSourceFailure, positionMs: Long, durationMs: Long): Boolean {
        val reopenAtMs = stalledReopenPositionMs(
            failure = failure,
            positionMs = positionMs,
            durationMs = durationMs,
            resumeAlreadyAttempted = resumeAlreadyAttempted,
        ) ?: return false
        resumeAlreadyAttempted = true
        probeAttempted = true
        fallbackStartPositionMs = reopenAtMs
        resumeGeneration += 1
        return true
    }

    fun markMimeProbe() {
        probeAttempted = true
    }
}

internal fun stalledReopenPositionMs(
    failure: PlaybackSourceFailure,
    positionMs: Long,
    durationMs: Long,
    resumeAlreadyAttempted: Boolean,
): Long? {
    val stalled = failure == PlaybackSourceFailure.ClosedStream ||
        failure == PlaybackSourceFailure.ReadPositionOutOfRange
    if (!stalled || resumeAlreadyAttempted || durationMs <= 0L || positionMs < 0L) return null
    return positionMs
}

internal fun useVodCacheForSource(
    vodCacheEnabled: Boolean,
    youtubeChunked: Boolean,
    adaptive: Boolean,
    loopback: Boolean,
): Boolean = vodCacheEnabled && !youtubeChunked && !adaptive && !loopback
