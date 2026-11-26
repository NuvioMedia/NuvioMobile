package com.nuvio.app.features.audiosync

import kotlin.math.abs
import kotlin.math.round
import kotlin.time.TimeSource

internal fun Double.toFixed(digits: Int): String {
    if (isNaN() || isInfinite()) return toString()
    var scale = 1L
    repeat(digits) { scale *= 10L }
    val scaled = round(this * scale).toLong()
    val magnitude = abs(scaled)
    val whole = magnitude / scale
    val sign = if (scaled < 0) "-" else ""
    if (digits <= 0) return "$sign$whole"
    val fraction = (magnitude % scale).toString().padStart(digits, '0')
    return "$sign$whole.$fraction"
}

internal fun LongArray.binarySearchCompat(key: Long): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = this[mid]
        when {
            value < key -> low = mid + 1
            value > key -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}

private val monotonicOrigin = TimeSource.Monotonic.markNow()

internal fun audioSyncNanoTime(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

internal expect fun startAudioSyncThread(name: String, body: () -> Unit)

internal expect fun audioSyncIso3Language(value: String, code: String): String?
