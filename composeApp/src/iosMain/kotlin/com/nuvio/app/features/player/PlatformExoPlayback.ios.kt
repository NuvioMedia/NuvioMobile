package com.nuvio.app.features.player

internal actual fun applyExoPlayerNativeMemory(enabled: Boolean) = Unit

internal actual fun isExoNativeMemorySupported(): Boolean = false

internal actual fun exoNativeMemoryInfo(): ExoNativeMemoryInfo? = null

internal actual fun playbackHeapBufferMaxMb(): Int = PlaybackBufferSettings.MIN_TARGET_MB

internal actual fun playbackCacheUsableSpaceBytes(): Long = 0L
