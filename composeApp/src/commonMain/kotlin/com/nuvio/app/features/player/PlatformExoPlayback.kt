package com.nuvio.app.features.player

internal data class ExoNativeMemoryInfo(
    val ramLabel: String,
    val safeLimitMb: Int,
    val warningLimitMb: Int,
)

internal expect fun applyExoPlayerNativeMemory(enabled: Boolean)

internal expect fun isExoNativeMemorySupported(): Boolean

internal expect fun exoNativeMemoryInfo(): ExoNativeMemoryInfo?

internal expect fun playbackCacheUsableSpaceBytes(): Long
