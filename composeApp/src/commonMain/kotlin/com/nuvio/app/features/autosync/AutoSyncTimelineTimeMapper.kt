package com.nuvio.app.features.autosync

import kotlin.math.abs
import kotlin.math.roundToLong

internal interface SubtitleTimeMapping {
    fun mapStartMs(originalMs: Long): Long

    fun mapEndMs(originalMs: Long): Long
}

/**
 * Maps original subtitle times onto a retimed AutoSync timeline: the nearest retimed cue boundary
 * when one is close enough, otherwise the timeline's whole-film transform.
 */
internal class AutoSyncTimelineTimeMapper(private val timeline: AutoSyncTimelineRetimeResult) : SubtitleTimeMapping {
    private val startOriginal: LongArray
    private val startRetimed: LongArray
    private val endOriginal: LongArray
    private val endRetimed: LongArray

    init {
        val cues = timeline.cues
        val n = cues.size
        startOriginal = LongArray(n)
        startRetimed = LongArray(n)
        endOriginal = LongArray(n)
        endRetimed = LongArray(n)

        var startsSorted = true
        var endsSorted = true
        var prevStart = Long.MIN_VALUE
        var prevEnd = Long.MIN_VALUE
        for (i in 0 until n) {
            val cue = cues[i]
            val os = cue.originalStartTimeMs
            val oe = cue.originalEndTimeMs
            startOriginal[i] = os
            startRetimed[i] = cue.startTimeMs
            endOriginal[i] = oe
            endRetimed[i] = cue.endTimeMs
            if (os < prevStart) startsSorted = false
            if (oe < prevEnd) endsSorted = false
            prevStart = os
            prevEnd = oe
        }
        if (!startsSorted) sortPairs(startOriginal, startRetimed)
        if (!endsSorted) sortPairs(endOriginal, endRetimed)
    }

    var boundaryMapped = 0
        private set
    var affineFallback = 0
        private set

    override fun mapStartMs(originalMs: Long): Long = mapTime(originalMs, startOriginal, startRetimed)

    override fun mapEndMs(originalMs: Long): Long = mapTime(originalMs, endOriginal, endRetimed)

    private fun mapTime(originalMs: Long, originals: LongArray, retimed: LongArray): Long {
        val n = originals.size
        if (n > 0) {
            var low = 0
            var high = n
            while (low < high) {
                val mid = (low + high) ushr 1
                if (originals[mid] < originalMs) low = mid + 1 else high = mid
            }

            val first = (low - 2).coerceAtLeast(0)
            val last = (low + 2).coerceAtMost(n - 1)
            var bestIdx = -1
            var bestError = Long.MAX_VALUE
            var index = first
            while (index <= last) {
                val error = abs(originals[index] - originalMs)
                if (error < bestError) {
                    bestIdx = index
                    bestError = error
                }
                index++
            }
            if (bestIdx >= 0 && bestError <= BOUNDARY_TOLERANCE_MS) {
                boundaryMapped++
                if (bestError <= EXACT_BOUNDARY_TOLERANCE_MS) {
                    return retimed[bestIdx].coerceAtLeast(0L)
                }
                // Preserve exact relative timing for filtered events (ASS signs, karaoke syllables,
                // SDH cues) that sit near a dialogue boundary instead of collapsing them onto it.
                val deltaMs =
                    ((originalMs - originals[bestIdx]).toDouble() * timeline.alignmentScale)
                        .roundToLong()
                return (retimed[bestIdx] + deltaMs).coerceAtLeast(0L)
            }
        }

        affineFallback++
        return (
            originalMs.toDouble() * timeline.alignmentScale +
                timeline.alignmentInterceptMs
            ).roundToLong().coerceAtLeast(0L)
    }

    private fun sortPairs(keys: LongArray, values: LongArray) {
        val n = keys.size
        val order = Array(n) { it }
        order.sortWith(compareBy<Int> { keys[it] }.thenBy { it })
        val sortedKeys = LongArray(n)
        val sortedValues = LongArray(n)
        for (i in 0 until n) {
            val idx = order[i]
            sortedKeys[i] = keys[idx]
            sortedValues[i] = values[idx]
        }
        sortedKeys.copyInto(keys)
        sortedValues.copyInto(values)
    }

    private companion object {
        const val EXACT_BOUNDARY_TOLERANCE_MS = 20L
        const val BOUNDARY_TOLERANCE_MS = 500L
    }
}
