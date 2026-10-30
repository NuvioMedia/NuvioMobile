package com.nuvio.app.features.player

enum class VodCacheSizeMode {
    AUTO,
    MANUAL,
}

internal object VodCacheSizing {
    const val MIN_SIZE_MB = 100
    const val MAX_SIZE_MB = 65_536
    const val DEFAULT_SIZE_MB = 500
    const val FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
    const val FREE_SPACE_RESERVE_MB = 1024L
    private const val AUTO_FLOOR_BYTES = 2L * 1024L * 1024L * 1024L

    fun resolveAutoBytes(freeSpaceBytes: Long, minBytes: Long, runtimeMaxBytes: Long): Long =
        maxOf(AUTO_FLOOR_BYTES, freeSpaceBytes / 5L).coerceIn(minBytes, runtimeMaxBytes)

    fun resolveRuntimeUpperBoundBytes(reclaimableBytes: Long, hardMaxBytes: Long): Long {
        val headroomAdjusted = if (reclaimableBytes > FREE_SPACE_RESERVE_BYTES) {
            reclaimableBytes - FREE_SPACE_RESERVE_BYTES
        } else {
            (reclaimableBytes * 8L) / 10L
        }
        return headroomAdjusted.coerceAtLeast(1L * 1024L * 1024L).coerceAtMost(hardMaxBytes)
    }

    fun resolveMaxBytes(
        mode: VodCacheSizeMode,
        manualSizeMb: Int,
        reclaimableBytes: Long,
    ): Long {
        val minBytes = MIN_SIZE_MB.toLong() * 1024L * 1024L
        val maxBytes = MAX_SIZE_MB.toLong() * 1024L * 1024L
        val runtimeMaxBytes = resolveRuntimeUpperBoundBytes(reclaimableBytes, maxBytes)
        if (runtimeMaxBytes < minBytes) return 0L
        val manualBytes = manualSizeMb
            .coerceIn(MIN_SIZE_MB, MAX_SIZE_MB)
            .toLong() * 1024L * 1024L
        val resolvedManualBytes = manualBytes.coerceAtMost(runtimeMaxBytes)
        if (mode == VodCacheSizeMode.MANUAL) return resolvedManualBytes
        if (reclaimableBytes <= 0L) return resolvedManualBytes
        return resolveAutoBytes(reclaimableBytes, minBytes, runtimeMaxBytes)
    }

    fun resolveManualMaxMb(freeDiskBytes: Long): Int {
        val freeDiskMb = freeDiskBytes.coerceAtLeast(0L) / (1024L * 1024L)
        val dynamicMaxMb = when {
            freeDiskMb > FREE_SPACE_RESERVE_MB -> freeDiskMb - FREE_SPACE_RESERVE_MB
            else -> (freeDiskMb * 8L) / 10L
        }
        return minOf(
            MAX_SIZE_MB.toLong(),
            dynamicMaxMb.coerceAtLeast(MIN_SIZE_MB.toLong()),
        ).toInt()
    }
}
