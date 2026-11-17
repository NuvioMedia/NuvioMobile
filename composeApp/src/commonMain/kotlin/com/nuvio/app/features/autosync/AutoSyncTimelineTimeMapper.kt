package com.nuvio.app.features.autosync

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maps original subtitle times onto a retimed AutoSync timeline: the nearest retimed cue boundary
 * when one is close enough, otherwise the timeline's whole-film transform.
 */
internal class AutoSyncTimelineTimeMapper(private val timeline: AutoSyncTimelineRetimeResult) {
    private val startBoundaries = timeline.cues
        .map { TimingBoundary(it.originalStartTimeMs, it.startTimeMs) }
        .sortedBy { it.originalMs }
    private val endBoundaries = timeline.cues
        .map { TimingBoundary(it.originalEndTimeMs, it.endTimeMs) }
        .sortedBy { it.originalMs }

    fun mapStartMs(originalMs: Long): Long = mapTime(originalMs, startBoundaries)

    fun mapEndMs(originalMs: Long): Long = mapTime(originalMs, endBoundaries)

    private fun mapTime(originalMs: Long, boundaries: List<TimingBoundary>): Long {
        if (boundaries.isNotEmpty()) {
            var low = 0
            var high = boundaries.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (boundaries[mid].originalMs < originalMs) low = mid + 1 else high = mid
            }

            val first = (low - 2).coerceAtLeast(0)
            val last = (low + 2).coerceAtMost(boundaries.lastIndex)
            var best: TimingBoundary? = null
            var bestError = Long.MAX_VALUE
            if (first <= last) {
                for (index in first..last) {
                    val candidate = boundaries[index]
                    val error = abs(candidate.originalMs - originalMs)
                    if (error < bestError) {
                        best = candidate
                        bestError = error
                    }
                }
            }
            if (best != null && bestError <= BOUNDARY_TOLERANCE_MS) {
                return best.retimedMs.coerceAtLeast(0L)
            }
        }

        return (
            originalMs.toDouble() * timeline.alignmentScale +
                timeline.alignmentInterceptMs
            ).roundToLong().coerceAtLeast(0L)
    }

    private data class TimingBoundary(
        val originalMs: Long,
        val retimedMs: Long,
    )

    private companion object {
        const val BOUNDARY_TOLERANCE_MS = 500L
    }
}
