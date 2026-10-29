package com.nuvio.app.features.player

internal data class PlaybackBufferSettings(
    val enabled: Boolean = false,
    val minBufferMs: Int = DEFAULT_MIN_BUFFER_MS,
    val maxBufferMs: Int = DEFAULT_MAX_BUFFER_MS,
    val bufferForPlaybackMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    val bufferForPlaybackAfterRebufferMs: Int = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val backBufferDurationMs: Int = DEFAULT_BACK_BUFFER_MS,
    val targetBufferSizeMb: Int = DEFAULT_TARGET_BUFFER_MB,
) {
    companion object {
        const val DEFAULT_MIN_BUFFER_MS = 15_000
        const val DEFAULT_MAX_BUFFER_MS = 45_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 5_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val DEFAULT_BACK_BUFFER_MS = 10_000
        const val DEFAULT_TARGET_BUFFER_MB = 100

        const val MIN_DURATION_SEC = 5
        const val MAX_DURATION_SEC_HEAP = 120
        const val MAX_DURATION_SEC_NATIVE = 1_200
        const val DURATION_STEP_HEAP_SEC = 5
        const val DURATION_STEP_NATIVE_SEC = 10
        const val MIN_START_SEC = 1
        const val MAX_START_SEC = 60
        const val MAX_REBUFFER_SEC = 120
        const val MAX_BACK_SEC = 120
        const val BACK_STEP_SEC = 5
        const val MIN_TARGET_MB = 50
        const val TARGET_STEP_MB = 25
        const val MIN_APPLIED_BUFFER_MB = 25
    }

    fun sanitized(nativeMemory: Boolean, maxTargetMb: Int): PlaybackBufferSettings {
        val maxDurationMs = (if (nativeMemory) MAX_DURATION_SEC_NATIVE else MAX_DURATION_SEC_HEAP) * 1_000
        val minMs = minBufferMs.coerceIn(MIN_DURATION_SEC * 1_000, maxDurationMs)
        val maxMs = maxBufferMs.coerceIn(minMs, maxDurationMs)
        val targetMb = targetBufferSizeMb
            .coerceIn(MIN_TARGET_MB, maxTargetMb.coerceAtLeast(MIN_TARGET_MB))
            .coerceAtLeast(MIN_APPLIED_BUFFER_MB)
        return copy(
            minBufferMs = minMs,
            maxBufferMs = maxMs,
            bufferForPlaybackMs = bufferForPlaybackMs.coerceIn(MIN_START_SEC * 1_000, MAX_START_SEC * 1_000),
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs.coerceIn(
                MIN_START_SEC * 1_000,
                MAX_REBUFFER_SEC * 1_000,
            ),
            backBufferDurationMs = backBufferDurationMs.coerceIn(0, MAX_BACK_SEC * 1_000),
            targetBufferSizeMb = if (nativeMemory) targetMb else targetMb,
        )
    }
}

internal expect fun playbackHeapBufferMaxMb(): Int
