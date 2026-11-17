package com.nuvio.app.features.autosync

import kotlin.math.abs
import kotlin.math.max

/**
 * Embedded subtitle tracks in one file are all timed to the same video, so every independent
 * track should align to the others at a near-zero constant delay. A track whose timing no other
 * independent source confirms, while at least two other sources confirm each other, is itself
 * mistimed and must not be used as a sync reference.
 *
 * Tracks that are near-identical in timing (e.g. CHS / CHT / bilingual variants from one source)
 * are grouped first, so correlated copies cannot outvote an independent track.
 */
internal object AutoSyncReferenceConsistency {
    private const val MAX_CHECKED_REFERENCES = 8
    private const val MAX_CHECKED_SOURCES = 6
    private const val SAME_SOURCE_MIN_OVERLAP = 0.90
    private const val MAX_AGREEING_OFFSET_MS = 1_000.0

    internal data class Reference(
        val key: String,
        val activity: AutoSyncTimelineRetimer.PreparedActivity,
        val cueCount: Int,
    )

    /** Keys of references to drop. Empty when there are fewer than three independent sources. */
    fun findOutliers(
        references: List<Reference>,
        cancellationCheck: (() -> Unit)? = null,
    ): Set<String> {
        val sources = mutableListOf<MutableList<Reference>>()
        for (reference in references.take(MAX_CHECKED_REFERENCES)) {
            val sameSource = sources.firstOrNull { source ->
                isSameSource(source.first().activity, reference.activity)
            }
            if (sameSource != null) sameSource += reference else sources += mutableListOf(reference)
        }
        val checked = sources.take(MAX_CHECKED_SOURCES)
        if (checked.size < 3) return emptySet()

        val agrees = Array(checked.size) { BooleanArray(checked.size) }
        for (first in checked.indices) {
            for (second in first + 1 until checked.size) {
                cancellationCheck?.invoke()
                val agreement = agreeAtNearZeroDelay(checked[first].first(), checked[second].first())
                agrees[first][second] = agreement
                agrees[second][first] = agreement
            }
        }

        return buildSet {
            for (candidate in checked.indices) {
                if (agrees[candidate].any { it }) continue
                val othersConfirmEachOther = checked.indices.any { first ->
                    first != candidate && checked.indices.any { second ->
                        second != candidate && second != first && agrees[first][second]
                    }
                }
                if (othersConfirmEachOther) checked[candidate].forEach { add(it.key) }
            }
        }
    }

    private fun agreeAtNearZeroDelay(reference: Reference, target: Reference): Boolean {
        val alignment = AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
            referenceActivity = reference.activity,
            targetActivity = target.activity,
            targetSize = target.cueCount,
        ) ?: return false
        return abs(alignment.offsetMs) <= MAX_AGREEING_OFFSET_MS
    }

    /** Near-identical timing (e.g. CHS / CHT / bilingual variants of one source). */
    internal fun isSameSource(
        first: AutoSyncTimelineRetimer.PreparedActivity,
        second: AutoSyncTimelineRetimer.PreparedActivity,
    ): Boolean = fineActivityOverlap(first, second) >= SAME_SOURCE_MIN_OVERLAP

    /** Jaccard overlap of the 100 ms activity bins at zero offset. */
    private fun fineActivityOverlap(
        first: AutoSyncTimelineRetimer.PreparedActivity,
        second: AutoSyncTimelineRetimer.PreparedActivity,
    ): Double {
        val a = first.fine.packed
        val b = second.fine.packed
        var intersection = 0
        var union = 0
        for (word in 0 until max(a.size, b.size)) {
            val left = a.getOrElse(word) { 0L }
            val right = b.getOrElse(word) { 0L }
            intersection += (left and right).countOneBits()
            union += (left or right).countOneBits()
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}
