package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

@UnstableApi
internal object NuvioExoPlayerPerformanceHelper {
    const val ALLOCATOR_SEGMENT_SIZE = 64 * 1024
    const val NATIVE_ARENA_CHUNK_SIZE = 64 * 1024
    private const val MIN_BUFFER_MB = 25
    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 45_000
    private const val BUFFER_FOR_PLAYBACK_MS = 3_000
    private const val BACK_BUFFER_TARGET_SHARE_NUM = 1L
    private const val BACK_BUFFER_TARGET_SHARE_DEN = 2L

    @Volatile
    var enabled: Boolean = false
        set(value) {
            val active = value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            field = active
            if (active) {
                NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
            } else {
                NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
            }
        }

    fun getWarningNativeMemoryLimitMb(context: Context): Int {
        val totalMem = deviceRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 260
            totalMem < 1.15 * gb -> 130
            totalMem < 1.45 * gb -> 260
            totalMem < 2.3 * gb -> 260
            totalMem < 3.2 * gb -> 650
            totalMem < 4.8 * gb -> 1200
            totalMem < 6.8 * gb -> 2000
            else -> 2000
        }
    }

    fun getSafeNativeMemoryLimitMb(context: Context): Int {
        val totalMem = deviceRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> 200
            totalMem < 1.15 * gb -> 100
            totalMem < 1.45 * gb -> 200
            totalMem < 2.3 * gb -> 200
            totalMem < 3.2 * gb -> 500
            totalMem < 4.8 * gb -> 1000
            totalMem < 6.8 * gb -> 1600
            else -> 2000
        }
    }

    fun getFriendlyRamLabel(context: Context): String {
        val totalMem = deviceRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            totalMem <= 0L -> "Unknown"
            totalMem < 1.15 * gb -> "1 GB"
            totalMem < 1.45 * gb -> "1.5 GB"
            totalMem < 2.3 * gb -> "2 GB"
            totalMem < 3.2 * gb -> "3 GB"
            totalMem < 4.8 * gb -> "4 GB"
            totalMem < 6.8 * gb -> "6 GB"
            totalMem < 9.6 * gb -> "8 GB"
            totalMem < 13.8 * gb -> "12 GB"
            else -> "16 GB"
        }
    }

    fun buildLoadControl(
        context: Context,
        bufferSettings: PlaybackBufferSettings = PlaybackBufferSettings(),
    ): DefaultLoadControl {
        if (bufferSettings.enabled) {
            return buildCustomLoadControl(context, bufferSettings)
        }
        if (!enabled) {
            return DefaultLoadControl.Builder()
                .setBackBuffer(PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS, true)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    50_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()
        }

        val targetBufferMb = getSafeNativeMemoryLimitMb(context).coerceAtLeast(MIN_BUFFER_MB)
        return nativeLoadControl(
            targetBufferMb = targetBufferMb,
            minBufferMs = MIN_BUFFER_MS,
            maxBufferMs = MAX_BUFFER_MS,
            bufferForPlaybackMs = BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = BUFFER_FOR_PLAYBACK_MS,
            backBufferMs = PlaybackBufferSettings.DEFAULT_BACK_BUFFER_MS,
        )
    }

    private fun buildCustomLoadControl(
        context: Context,
        bufferSettings: PlaybackBufferSettings,
    ): DefaultLoadControl {
        val maxTargetMb = if (enabled) {
            getWarningNativeMemoryLimitMb(context)
        } else {
            playbackHeapBufferMaxMb()
        }
        val settings = bufferSettings.sanitized(enabled, maxTargetMb)
        if (enabled) {
            return nativeLoadControl(
                targetBufferMb = settings.targetBufferSizeMb,
                minBufferMs = settings.minBufferMs,
                maxBufferMs = settings.maxBufferMs,
                bufferForPlaybackMs = settings.bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs = settings.bufferForPlaybackAfterRebufferMs,
                backBufferMs = effectiveBackBufferMs(settings.backBufferDurationMs, settings.minBufferMs),
            )
        }
        val targetBytes = (settings.targetBufferSizeMb.toLong() * 1024L * 1024L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        Log.i(TAG, "heap load control targetMb=${settings.targetBufferSizeMb}")
        return DefaultLoadControl.Builder()
            .setTargetBufferBytes(targetBytes)
            .setBufferDurationsMs(
                settings.minBufferMs,
                settings.maxBufferMs,
                settings.bufferForPlaybackMs,
                settings.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(settings.backBufferDurationMs, true)
            .build()
    }

    private fun nativeLoadControl(
        targetBufferMb: Int,
        minBufferMs: Int,
        maxBufferMs: Int,
        bufferForPlaybackMs: Int,
        bufferForPlaybackAfterRebufferMs: Int,
        backBufferMs: Int,
    ): DefaultLoadControl {
        val targetBufferBytes = (targetBufferMb.toLong() * 1024L * 1024L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (ALLOCATOR_SEGMENT_SIZE != NATIVE_ARENA_CHUNK_SIZE) {
            Log.w(
                TAG,
                "Allocator segment $ALLOCATOR_SEGMENT_SIZE does not match the native arena chunk " +
                    "$NATIVE_ARENA_CHUNK_SIZE; native pooling is disabled",
            )
        }
        Log.i(TAG, "native load control targetMb=$targetBufferMb backBufferMs=$backBufferMs")
        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, ALLOCATOR_SEGMENT_SIZE, 64, true))
            .setTargetBufferBytes(targetBufferBytes)
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(backBufferMs, true)
            .build()
    }

    private fun effectiveBackBufferMs(backBufferMs: Int, minBufferMs: Int): Int {
        if (backBufferMs <= 0) return 0
        val ceiling = (minBufferMs.toLong() * BACK_BUFFER_TARGET_SHARE_NUM / BACK_BUFFER_TARGET_SHARE_DEN).toInt()
        return backBufferMs.coerceAtMost(ceiling)
    }

    private fun deviceRamBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) return memoryInfo.totalMem
        }
        return ramFromMemInfo()
    }

    private fun ramFromMemInfo(): Long {
        return try {
            val firstLine = java.io.File("/proc/meminfo").useLines { it.firstOrNull().orEmpty() }
            val match = Regex("\\d+").find(firstLine) ?: return 0L
            match.value.toLong() * 1024L
        } catch (_: Exception) {
            0L
        }
    }

    private const val TAG = "NuvioExoPerf"
}
