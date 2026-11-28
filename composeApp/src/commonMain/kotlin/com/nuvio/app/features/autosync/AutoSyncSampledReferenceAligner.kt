package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong

/** Conservative constant-delay matcher for sparse embedded subtitle timelines. */
internal object AutoSyncSampledReferenceAligner {
    private const val MAX_OFFSET_MS = 180_000L
    private const val HISTOGRAM_BIN_MS = 250L
    private const val MAX_HISTOGRAM_BIN = (MAX_OFFSET_MS / HISTOGRAM_BIN_MS).toInt()
    private const val HISTOGRAM_SIZE = MAX_HISTOGRAM_BIN * 2 + 1
    private const val MATCH_TOLERANCE_MS = 1_250L
    private const val DISTINCT_OFFSET_MS = 3_000L
    private const val MIN_MATCHED_CUES = 8
    private const val MIN_COVERAGE = 0.68
    private const val MIN_MARGIN = 0.08
    private const val REQUIRED_SEGMENTS = 3
    private const val MAX_HISTOGRAM_CANDIDATES = 12

    fun findDelay(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < MIN_MATCHED_CUES || target.size < MIN_MATCHED_CUES) return null
        val referenceStarts = sortedStarts(reference)
        val targetStarts = sortedStarts(target)
        val histogram = IntArray(HISTOGRAM_SIZE)
        val invBinMs = 1.0 / HISTOGRAM_BIN_MS.toDouble()

        var windowStart = 0
        val targetSize = targetStarts.size
        for (referenceMs in referenceStarts) {
            val minimum = referenceMs - MAX_OFFSET_MS
            val maximum = referenceMs + MAX_OFFSET_MS
            while (windowStart < targetSize && targetStarts[windowStart] < minimum) windowStart++
            var index = windowStart
            while (index < targetSize && targetStarts[index] <= maximum) {
                val bin = ((referenceMs - targetStarts[index]).toDouble() * invBinMs).roundToLong().toInt() + MAX_HISTOGRAM_BIN
                if (bin in 0 until HISTOGRAM_SIZE) histogram[bin]++
                index++
            }
        }

        // Select top distinct histogram peaks by descending count.
        val candidateSeeds = LongArray(MAX_HISTOGRAM_CANDIDATES)
        var seedCount = 0
        while (seedCount < MAX_HISTOGRAM_CANDIDATES) {
            var bestBin = -1
            var bestCount = 0
            for (b in 0 until HISTOGRAM_SIZE) {
                if (histogram[b] > bestCount) {
                    bestCount = histogram[b]
                    bestBin = b
                }
            }
            if (bestBin < 0) break
            histogram[bestBin] = 0
            val offset = (bestBin - MAX_HISTOGRAM_BIN).toLong() * HISTOGRAM_BIN_MS
            var distinct = true
            for (j in 0 until seedCount) {
                if (abs(candidateSeeds[j] - offset) < DISTINCT_OFFSET_MS) {
                    distinct = false
                    break
                }
            }
            if (distinct) candidateSeeds[seedCount++] = offset
        }
        if (seedCount == 0) return null

        val scratchDeltas = LongArray(referenceStarts.size)
        val candidates = Array(seedCount) { idx ->
            evaluate(candidateSeeds[idx], referenceStarts, targetStarts, scratchDeltas)
        }
        candidates.sortByDescending { it.score }
        val best = candidates[0]
        val secondScore = candidates.drop(1)
            .firstOrNull { abs(it.offsetMs - best.offsetMs) >= DISTINCT_OFFSET_MS }
            ?.score
            ?: 0.0
        val margin = best.score - secondScore

        if (best.matches < MIN_MATCHED_CUES || best.score < MIN_COVERAGE ||
            best.segments < REQUIRED_SEGMENTS || margin < MIN_MARGIN
        ) {
            return null
        }

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.offsetMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = best.segments,
        )
    }

    private fun sortedStarts(cues: List<SubtitleSyncCue>): LongArray {
        val starts = LongArray(cues.size)
        var sorted = true
        var prev = Long.MIN_VALUE
        for (i in cues.indices) {
            val s = cues[i].startTimeMs
            starts[i] = s
            if (s < prev) sorted = false
            prev = s
        }
        if (!sorted) starts.sort()
        return starts
    }

    private fun evaluate(
        seedOffsetMs: Long,
        reference: LongArray,
        target: LongArray,
        scratchDeltas: LongArray,
    ): Candidate {
        var initialCount = 0
        forEachMatch(seedOffsetMs, reference, target) { rIdx, tIdx ->
            scratchDeltas[initialCount++] = reference[rIdx] - target[tIdx]
        }
        if (initialCount == 0) return Candidate(seedOffsetMs, 0, 0.0, 0)
        scratchDeltas.sort(0, initialCount)
        val refinedOffset = scratchDeltas[initialCount / 2]

        val first = reference[0]
        val spanPlusOne = (reference[reference.lastIndex] - first).coerceAtLeast(1L) + 1L
        var availableTimeMask = 0
        for (rMs in reference) {
            availableTimeMask = availableTimeMask or (1 shl ((rMs - first) * 3L / spanPlusOne).toInt().coerceIn(0, 2))
        }
        var matchCount = 0
        var timeSegmentMask = 0
        var indexSegmentMask = 0
        val refSize = reference.size
        forEachMatch(refinedOffset, reference, target) { rIdx, _ ->
            matchCount++
            timeSegmentMask = timeSegmentMask or
                (1 shl ((reference[rIdx] - first) * 3L / spanPlusOne).toInt().coerceIn(0, 2))
            indexSegmentMask = indexSegmentMask or (1 shl (rIdx * 3 / refSize).coerceIn(0, 2))
        }

        val score = matchCount.toDouble() / refSize.toDouble()
        // If the sparse reference itself has a silent middle third (fewer than 3 populated time
        // thirds), accept when every populated time third AND all 3 cue-index thirds matched.
        val timeSegments = timeSegmentMask.countOneBits()
        val availableTimeSegments = availableTimeMask.countOneBits()
        val segments = if (availableTimeSegments < REQUIRED_SEGMENTS && timeSegments == availableTimeSegments) {
            indexSegmentMask.countOneBits()
        } else {
            timeSegments
        }
        return Candidate(refinedOffset, matchCount, score, segments)
    }

    private inline fun forEachMatch(
        offsetMs: Long,
        reference: LongArray,
        target: LongArray,
        onMatch: (refIndex: Int, targetIndex: Int) -> Unit,
    ) {
        var minimumTargetIndex = 0
        var searchStart = 0
        val targetSize = target.size
        for (rIdx in reference.indices) {
            val referenceMs = reference[rIdx]
            val expectedTargetMs = referenceMs - offsetMs
            val minTarget = expectedTargetMs - MATCH_TOLERANCE_MS
            val maxTarget = expectedTargetMs + MATCH_TOLERANCE_MS
            if (searchStart < minimumTargetIndex) searchStart = minimumTargetIndex
            while (searchStart < targetSize && target[searchStart] < minTarget) searchStart++
            var index = searchStart
            var bestIndex = -1
            var bestResidual = Long.MAX_VALUE
            while (index < targetSize && target[index] <= maxTarget) {
                val residual = abs((target[index] + offsetMs) - referenceMs)
                if (residual < bestResidual) {
                    bestResidual = residual
                    bestIndex = index
                }
                index++
            }
            if (bestIndex >= 0) {
                onMatch(rIdx, bestIndex)
                minimumTargetIndex = bestIndex + 1
            }
        }
    }

    private data class Candidate(
        val offsetMs: Long,
        val matches: Int,
        val score: Double,
        val segments: Int,
    )
}
