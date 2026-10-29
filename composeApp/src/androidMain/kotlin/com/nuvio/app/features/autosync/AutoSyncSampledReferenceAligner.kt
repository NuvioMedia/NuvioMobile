package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong

/** Conservative constant-delay matcher for sparse embedded subtitle timelines. */
internal object AutoSyncSampledReferenceAligner {
    private const val MAX_OFFSET_MS = 180_000L
    private const val HISTOGRAM_BIN_MS = 250L
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
        val referenceStarts = reference.map { it.startTimeMs }.sorted()
        val targetStarts = target.map { it.startTimeMs }.sorted()
        val histogram = HashMap<Long, Int>()

        for (referenceMs in referenceStarts) {
            val minimum = referenceMs - MAX_OFFSET_MS
            val maximum = referenceMs + MAX_OFFSET_MS
            var index = targetStarts.lowerBound(minimum)
            while (index < targetStarts.size && targetStarts[index] <= maximum) {
                val offset = referenceMs - targetStarts[index]
                val bin = (offset.toDouble() / HISTOGRAM_BIN_MS).roundToLong()
                histogram[bin] = (histogram[bin] ?: 0) + 1
                index++
            }
        }

        val candidateSeeds = mutableListOf<Long>()
        histogram.entries
            .sortedByDescending { it.value }
            .map { it.key * HISTOGRAM_BIN_MS }
            .forEach { offset ->
                if (candidateSeeds.none { abs(it - offset) < DISTINCT_OFFSET_MS }) {
                    candidateSeeds += offset
                }
            }
        val candidates = candidateSeeds
            .take(MAX_HISTOGRAM_CANDIDATES)
            .map { evaluate(it, referenceStarts, targetStarts) }
            .sortedByDescending { it.score }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.firstOrNull {
            abs(it.offsetMs - best.offsetMs) >= DISTINCT_OFFSET_MS
        }
        val margin = best.score - (second?.score ?: 0.0)

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

    private fun evaluate(
        seedOffsetMs: Long,
        reference: List<Long>,
        target: List<Long>,
    ): Candidate {
        val initialMatches = match(seedOffsetMs, reference, target)
        if (initialMatches.isEmpty()) return Candidate(seedOffsetMs, 0, 0.0, 0)
        val refinedOffset = initialMatches
            .map { it.referenceMs - it.targetMs }
            .sorted()
            .let { it[it.size / 2] }
        val matches = match(refinedOffset, reference, target)
        val score = matches.size.toDouble() / reference.size.toDouble()
        val first = reference.first()
        val span = (reference.last() - first).coerceAtLeast(1L)
        val segments = matches
            .map { ((it.referenceMs - first) * 3L / (span + 1L)).toInt().coerceIn(0, 2) }
            .distinct()
            .size
        return Candidate(refinedOffset, matches.size, score, segments)
    }

    private fun match(
        offsetMs: Long,
        reference: List<Long>,
        target: List<Long>,
    ): List<Match> {
        val matches = ArrayList<Match>()
        var minimumTargetIndex = 0
        for (referenceMs in reference) {
            val expectedTargetMs = referenceMs - offsetMs
            var index = target.lowerBound(expectedTargetMs - MATCH_TOLERANCE_MS)
                .coerceAtLeast(minimumTargetIndex)
            var bestIndex = -1
            var bestResidual = Long.MAX_VALUE
            while (index < target.size && target[index] <= expectedTargetMs + MATCH_TOLERANCE_MS) {
                val residual = abs((target[index] + offsetMs) - referenceMs)
                if (residual < bestResidual) {
                    bestResidual = residual
                    bestIndex = index
                }
                index++
            }
            if (bestIndex >= 0) {
                matches += Match(referenceMs, target[bestIndex])
                minimumTargetIndex = bestIndex + 1
            }
        }
        return matches
    }

    private fun List<Long>.lowerBound(value: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    private data class Match(val referenceMs: Long, val targetMs: Long)

    private data class Candidate(
        val offsetMs: Long,
        val matches: Int,
        val score: Double,
        val segments: Int,
    )
}
