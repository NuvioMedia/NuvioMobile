package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Lightweight, text-independent full-timeline subtitle retiming.
 *
 * The existing AutoSync matcher supplies a coarse affine transform that puts an add-on subtitle
 * near an embedded subtitle timeline. This aligner then walks both ordered cue sequences and
 * resolves local 1:1 / 1:2 / 2:1 / 1:3 / 3:1 / 2:2 groupings plus skips. Matched add-on groups
 * are locally anchored to embedded starts while preserving the add-on cue durations; unmatched
 * add-on cues retain the coarse affine timing. For validated delay-only matches, this DP remains
 * the structural safety gate while final playback keeps the external timeline intact and applies
 * only the single validated offset.
 *
 * This file is deliberately commonMain and player-independent so the algorithm can be unit-tested
 * without Android, Media3, networking, or playback state.
 */
internal object AutoSyncTimelineRetimer {
    private const val MIN_CUES = 4
    private const val BAND_RADIUS_CUES = 28
    private const val DP_MAX_GAP_BRIDGE_CUES = 250

    private const val SKIP_REFERENCE_COST = 1.35
    private const val SKIP_TARGET_COST = 1.85
    private const val GROUP_COMPLEXITY_COST = 0.10

    private const val START_END_TOLERANCE_MS = 1_250.0
    private const val MIDPOINT_TOLERANCE_MS = 1_500.0
    private const val DURATION_TOLERANCE_MS = 2_000.0
    private const val INV_START_END_TOLERANCE_MS = 1.0 / START_END_TOLERANCE_MS
    private const val INV_MIDPOINT_TOLERANCE_MS = 1.0 / MIDPOINT_TOLERANCE_MS
    private const val INV_DURATION_TOLERANCE_MS = 1.0 / DURATION_TOLERANCE_MS
    private const val MAX_GROUP_COST = 8.0

    private const val MIN_TARGET_COVERAGE = 0.84
    private const val MIN_MATCHED_TARGET_CUES = 20
    private const val MAX_AVERAGE_GROUP_COST = 2.35
    private const val MAX_LONGEST_TARGET_SKIP_RUN = 12

    // A target spanning well under the reference (e.g. a CD2 subtitle) can sit anywhere inside
    // it, so its offset search covers the whole reference instead of only ACTIVITY_MAX_OFFSET_MS.
    private const val PARTIAL_TARGET_SPAN_RATIO = 0.75

    // Each matched group is anchored to its reference start unless its shift differs from the
    // median of its neighbours by more than LOCAL_SHIFT_MAX_DEVIATION_MS. Genuine per-line
    // corrections survive; a mis-paired group takes the local median instead of snapping a line
    // up to a full match tolerance away.
    private const val LOCAL_SHIFT_RADIUS_GROUPS = 3
    private const val LOCAL_SHIFT_MAX_DEVIATION_MS = 250L

    // Whole-timeline subtitle activity correlation. This is deliberately global:
    // mid-film cuts/splits are rejected rather than growing another piecewise synchronization layer.
    private const val ACTIVITY_COARSE_BIN_MS = 500L
    private const val ACTIVITY_FINE_BIN_MS = 100L
    private const val ACTIVITY_MAX_OFFSET_MS = 180_000L
    private const val ACTIVITY_WIDE_MAX_OFFSET_MS = 600_000L
    private const val ACTIVITY_FINE_RADIUS_MS = 750L

    // Dense dialogue leaves almost no gaps, so a run of neighbouring delays can overlap equally
    // well. Scores this close count as a tie: the fine search covers the whole run (up to
    // ACTIVITY_TIE_MAX_SPREAD_MS either side of the coarse winner) and a tie goes to the delay
    // where the subtitle's lines start when the reference's lines start.
    private const val ACTIVITY_TIE_SCORE_EPSILON = 0.002
    private const val ACTIVITY_TIE_MAX_SPREAD_MS = 3_000L
    private const val START_AGREEMENT_TOLERANCE_MS = 200L
    private const val ACTIVITY_MAX_CUE_DURATION_MS = 20_000L
    private const val ACTIVITY_MAX_TIMELINE_MS = 8L * 60L * 60L * 1_000L
    private const val ACTIVITY_MIN_SCORE = 0.55
    private const val ACTIVITY_MIN_MARGIN = 0.02
    private const val ACTIVITY_DISTINCT_TRANSFORM_MS = 3_000.0
    private const val ACTIVITY_MIN_SCALE = 0.94
    private const val ACTIVITY_MAX_SCALE = 1.06
    private const val ACTIVITY_SCALE_DEDUP = 0.00035

    // Cheap delay-only fast path. It always APPLIES scale=1.0; the segment drift tolerance
    // merely allows near-1.0 timelines to qualify when one constant delay remains visually valid.
    private const val DELAY_ONLY_MIN_CUES = 4
    private const val DELAY_ONLY_SCALE_TOLERANCE = 0.0015
    private const val DELAY_ONLY_MAX_DRIFT_MS = 600.0
    private const val DELAY_ONLY_CLOSE_SCORE_MAX_DRIFT_MS = 1_000.0
    private const val DELAY_ONLY_CLOSE_SCORE_EPSILON = 0.015
    private const val DELAY_REFINEMENT_MIN_GROUPS = 8
    private const val DELAY_REFINEMENT_MAX_DELTA_MS = 500.0
    private const val DELAY_ONLY_MIN_SCORE = 0.78
    private const val DELAY_ONLY_MIN_MARGIN = 0.02
    private const val DELAY_ONLY_STABLE_OVERRIDE_MIN_MARGIN = 0.01
    private const val DELAY_ONLY_MIN_SEGMENT_SCORE = 0.68
    private const val DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS = 1_000L
    private const val DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS = 500L
    private const val DELAY_ONLY_DISTINCT_OFFSET_MS = 3_000L
    private const val DELAY_ONLY_FAST_PATH_MIN_SCORE = 0.90
    private const val DELAY_ONLY_FAST_PATH_MIN_MARGIN = 0.04
    private const val DELAY_ONLY_FAST_PATH_REQUIRED_SEGMENTS = 3
    private const val SMALL_SAMPLE_CUE_LIMIT = 8
    private const val SMALL_SAMPLE_ACTIVITY_MIN_SCORE = 0.72
    private const val SMALL_SAMPLE_ACTIVITY_MIN_MARGIN = 0.03
    private const val SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS = 2

    // Activity correlation only finds the global corridor. The existing cue/group DP remains
    // the final authority before embedded timestamps can replace external timing.
    private const val DISCOVERED_MIN_TARGET_COVERAGE = 0.90
    private const val DISCOVERED_MAX_AVERAGE_GROUP_COST = 1.10
    private const val DISCOVERED_MIN_SIMPLE_GROUP_RATIO = 0.55
    private const val SEGMENTATION_IMBALANCE_RATIO = 1.60
    private const val SEGMENTATION_MIN_TIMELINE_COVERAGE = 0.80
    private const val COVERAGE_SEGMENT_MIN_COVERAGE = 0.72
    private const val COVERAGE_SEGMENT_MAX_AVERAGE_COST = 1.35
    private const val COVERAGE_SEGMENT_MIN_GROUPS = 4

    // Symmetric gate for targets with substantial extra cues absent from the reference (unbracketed
    // SDH, signs/lyrics, TV recaps): when nearly the entire reference aligns tightly across all
    // three thirds, lower target coverage alone does not indicate a bad lock.
    private const val ASYMMETRIC_EXTRA_TARGET_MIN_CUES = 20
    private const val ASYMMETRIC_EXTRA_TARGET_MIN_REF_COVERAGE = 0.90
    private const val ASYMMETRIC_EXTRA_TARGET_MIN_TARGET_COVERAGE = 0.65
    private const val ASYMMETRIC_EXTRA_TARGET_MAX_AVERAGE_GROUP_COST = 0.85
    private const val REF_COVERAGE_SEGMENT_MIN_COVERAGE = 0.78
    private const val REF_COVERAGE_SEGMENT_MAX_AVERAGE_COST = 1.15

    // Single-pass post-DP affine refinement when coarse scale discovery left notable residual.
    private const val AFFINE_REFINE_MIN_GROUPS = 30
    private const val AFFINE_REFINE_TRIGGER_RESIDUAL_MS = 250.0
    private const val AFFINE_REFINE_MIN_SCALE_DELTA = 0.00015
    private const val AFFINE_REFINE_MIN_INTERCEPT_DELTA_MS = 150.0

    // Conservative fallback for sparse external subtitles (e.g. forced tracks) aligned directly
    // against a full-dialogue embedded reference when no forced embedded reference exists.
    private const val SPARSE_TARGET_MIN_CUES = 8
    private const val SPARSE_TARGET_SMALL_LIMIT = 15
    private const val SPARSE_TARGET_MIN_REF_RATIO = 3
    private const val SPARSE_TARGET_BIN_MS = 250L
    private const val SPARSE_TARGET_MATCH_TOLERANCE_MS = 450L
    private const val SPARSE_TARGET_MIN_MATCH_RATIO = 0.78
    private const val SPARSE_TARGET_SMALL_MIN_MATCH_RATIO = 0.88
    private const val SPARSE_TARGET_MIN_ACTIVITY_COVERAGE = 0.68
    private const val SPARSE_TARGET_SMALL_MIN_ACTIVITY_COVERAGE = 0.76
    private const val SPARSE_TARGET_MIN_MARGIN = 0.15
    private const val SPARSE_TARGET_SMALL_MIN_MARGIN = 0.20
    private const val SPARSE_TARGET_DISTINCT_OFFSET_MS = 3_000L
    private const val SPARSE_TARGET_MAX_MEDIAN_RESIDUAL_MS = 220.0
    private const val SPARSE_TARGET_SMALL_MAX_MEDIAN_RESIDUAL_MS = 160.0
    private const val SPARSE_TARGET_SEGMENT_MIN_MATCH_RATIO = 0.65
    private const val SPARSE_TARGET_SEGMENT_MAX_OFFSET_DELTA_MS = 400.0

    // Conservative post-confidence repair for a supported 2:2 reply boundary. These checks are
    // intentionally output-only: they never feed back into DP scoring, confidence, or selection.
    private const val GROUPED_REPLY_MIN_REFERENCE_GAP_MS = 500L
    private const val GROUPED_REPLY_MIN_EARLY_START_MS = 250L
    private const val GROUPED_REPLY_MAX_START_ADJUSTMENT_MS = 1_250L
    private const val GROUPED_REPLY_ENDPOINT_TOLERANCE_MS = 200L
    private const val GROUPED_REPLY_CONTEXT_SHIFT_TOLERANCE_MS = 500L
    private const val GROUPED_REPLY_MIN_RESULT_DURATION_MS = 800L
    private const val GROUPED_REPLY_CONTEXT_GROUP_RADIUS = 2
    private const val GROUPED_REPLY_REFERENCE_NEIGHBOR_RADIUS = 2

    internal fun normalizeExternalTimeline(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        var alreadySorted = true
        for (i in 1 until cues.size) {
            if (cues[i].startTimeMs < cues[i - 1].startTimeMs) {
                alreadySorted = false
                break
            }
        }
        val sorted = if (alreadySorted) cues else cues.sortedBy { it.startTimeMs }
        val normalized = ArrayList<SubtitleSyncCue>(sorted.size)
        var groupStartIdx = 0
        var prevStartMs = Long.MIN_VALUE
        for (i in sorted.indices) {
            val cue = sorted[i]
            if (cue.startTimeMs != prevStartMs) {
                groupStartIdx = normalized.size
                prevStartMs = cue.startTimeMs
                normalized += cue
            } else {
                var duplicate = false
                for (j in groupStartIdx until normalized.size) {
                    val existing = normalized[j]
                    if (existing.endTimeMs == cue.endTimeMs && existing.text == cue.text) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) normalized += cue
            }
        }
        return normalized
    }

    /**
     * SDH and over-segmented references (1.5x the target's cues or more) legitimately produce
     * ambiguous delay-only margins, so the margin gate is relaxed for them.
     */
    internal fun shouldRelaxDelayOnlyMargin(
        sdhReference: Boolean,
        referenceSize: Int,
        targetSize: Int,
    ): Boolean = sdhReference || referenceSize.toLong() * 2L >= targetSize.toLong() * 3L

    internal fun prepareUnitActivity(
        cues: List<SubtitleSyncCue>,
    ): PreparedActivity? {
        if (cues.size < MIN_CUES) return null
        val coarse = buildActivityTimeline(cues, 1.0, ACTIVITY_COARSE_BIN_MS) ?: return null
        val fine = buildActivityTimeline(cues, 1.0, ACTIVITY_FINE_BIN_MS) ?: return null
        val starts = LongArray(cues.size)
        var sorted = true
        var prev = Long.MIN_VALUE
        for (i in cues.indices) {
            val s = cues[i].startTimeMs.coerceAtLeast(0L)
            starts[i] = s
            if (s < prev) sorted = false
            prev = s
        }
        if (!sorted) starts.sort()
        return PreparedActivity(
            coarse = coarse,
            fine = fine,
            starts = starts,
        )
    }

    fun retime(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        discoverAlignment: Boolean = false,
        allowAmbiguousDelayOnlyMargin: Boolean = false,
        referenceEstimatedEndStartsMs: Set<Long> = emptySet(),
        preparedReferenceActivity: PreparedActivity? = null,
        preparedTargetActivity: PreparedActivity? = null,
        precomputedDelayOnly: AutoSyncDelayOnlyAlignment? = null,
        precomputedDelayOnlyEvidence: DelayOnlySearchEvidence? = null,
        allowPrecomputedDelayFastPath: Boolean = true,
        cancellationCheck: (() -> Unit)? = null,
        timingObserver: ((AutoSyncRetimePhaseTimings) -> Unit)? = null,
    ): AutoSyncTimelineRetimeResult? {
        val timingEnabled = timingObserver != null
        val totalMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        var prepareActivityMs = 0L
        var delayValidationMs = 0L
        var activitySearchMs = 0L
        var dpMs = 0L
        var validationMs = 0L

        fun elapsedMs(mark: TimeMark?): Long =
            mark?.elapsedNow()?.inWholeMilliseconds ?: 0L

        fun reportTimings(path: String) {
            timingObserver?.invoke(
                AutoSyncRetimePhaseTimings(
                    path = path,
                    prepareActivityMs = prepareActivityMs,
                    delayValidationMs = delayValidationMs,
                    activitySearchMs = activitySearchMs,
                    dpMs = dpMs,
                    validationMs = validationMs,
                    totalMs = elapsedMs(totalMark),
                ),
            )
        }

        if (!discoverAlignment) {
            val dpMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
            val result = retimeWithSeed(
                reference = reference,
                target = target,
                coarseScale = coarseScale,
                coarseInterceptMs = coarseInterceptMs,
                referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                cancellationCheck = cancellationCheck,
            ) ?: return null
            dpMs += elapsedMs(dpMark)

            val validationMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
            var finalized = result.copy(
                alignmentSource = "provided",
                alignmentScale = coarseScale,
                alignmentInterceptMs = coarseInterceptMs,
                coverageSegmentsPassed = coverageSegmentsPassed(result, target.size),
                simpleGroupRatio = structuralGroupRatio(
                    result = result,
                    referenceSize = reference.size,
                    targetSize = target.size,
                ),
            )
            if (finalized.confident) {
                finalized = refineGroupedReplyTiming(
                    reference = reference,
                    target = target,
                    result = finalized,
                    referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                    cancellationCheck = cancellationCheck,
                )
            }
            validationMs += elapsedMs(validationMark)
            reportTimings("provided")
            return finalized
        }

        val prepareMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        val referenceActivity =
            preparedReferenceActivity ?: prepareUnitActivity(reference) ?: return null
        val targetActivity =
            preparedTargetActivity ?: prepareUnitActivity(target) ?: return null
        prepareActivityMs += elapsedMs(prepareMark)

        // A precomputed delay can skip affine discovery only when the existing V2
        // delay validator already proved a strong, unambiguous, three-segment constant offset.
        // Structural DP and all final confidence checks still run unchanged. Any failure falls
        // through to the normal affine/FPS discovery path.
        val fastDelayOnly = precomputedDelayOnly?.takeIf { alignment ->
            allowPrecomputedDelayFastPath &&
                !allowAmbiguousDelayOnlyMargin &&
                alignment.score >= DELAY_ONLY_FAST_PATH_MIN_SCORE &&
                alignment.margin >= DELAY_ONLY_FAST_PATH_MIN_MARGIN &&
                alignment.segmentsPassed >= DELAY_ONLY_FAST_PATH_REQUIRED_SEGMENTS
        }
        if (fastDelayOnly != null) {
            val fastDpMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
            val fastResult = retimeWithSeed(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = fastDelayOnly.offsetMs,
                referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                skipCueMaterialization = true,
                cancellationCheck = cancellationCheck,
            )
            dpMs += elapsedMs(fastDpMark)
            if (fastResult != null) {
                val fastValidationMark =
                    if (timingEnabled) TimeSource.Monotonic.markNow() else null
                val validatedFast = finalizeDiscoveredResult(
                    result = fastResult,
                    referenceSize = reference.size,
                    targetSize = target.size,
                    candidateScale = 1.0,
                    candidateInterceptMs = fastDelayOnly.offsetMs,
                    candidateActivityScore = fastDelayOnly.score,
                    candidateActivityMargin = fastDelayOnly.margin,
                    delayOnly = true,
                    allowAmbiguousDelayOnlyMargin = false,
                )
                validationMs += elapsedMs(fastValidationMark)
                if (validatedFast.confident) {
                    val refinedOffsetMs = refineValidatedDelayOffset(
                        reference = reference,
                        target = target,
                        groups = validatedFast.groups,
                        currentOffsetMs = fastDelayOnly.offsetMs,
                    )
                    reportTimings("fast-delay")
                    return validatedFast.withValidatedDelayOnlyCues(
                        reference = reference,
                        target = target,
                        offsetMs = refinedOffsetMs,
                    )
                }
            }
        }

        // Full V2 fallback remains authoritative whenever the proven-delay fast path is absent
        // or fails structural validation.
        val targetSpanMs = (target.last().startTimeMs - target.first().startTimeMs).coerceAtLeast(0L)

        fun resolveDelayOnlyFor(
            candidateAlignment: ActivityAlignment,
            reusablePrecomputed: AutoSyncDelayOnlyAlignment?,
        ): AutoSyncDelayOnlyAlignment? {
            if (abs(candidateAlignment.scale - 1.0) > DELAY_ONLY_SCALE_TOLERANCE) return null
            val totalScaleDriftMs = abs(candidateAlignment.scale - 1.0) * targetSpanMs.toDouble()
            if (candidateAlignment.scale != 1.0 && totalScaleDriftMs > DELAY_ONLY_CLOSE_SCORE_MAX_DRIFT_MS) {
                return null
            }
            val found = reusablePrecomputed ?: findDelayOnlyAlignmentPrepared(
                referenceActivity = referenceActivity,
                targetActivity = targetActivity,
                targetSize = target.size,
                allowAmbiguousMargin = allowAmbiguousDelayOnlyMargin,
                seed = candidateAlignment.delayOnlySeed,
                allowStableSegmentMarginOverride = !allowAmbiguousDelayOnlyMargin,
                cancellationCheck = cancellationCheck,
            ) ?: return null
            if (
                candidateAlignment.scale != 1.0 &&
                totalScaleDriftMs > DELAY_ONLY_MAX_DRIFT_MS &&
                found.score < candidateAlignment.score - DELAY_ONLY_CLOSE_SCORE_EPSILON
            ) {
                return null
            }
            return found
        }

        fun trySparseFallback(activeValidationMark: TimeMark? = null): AutoSyncTimelineRetimeResult? {
            if (!canTrySparseTarget(reference, target)) return null
            val sparse = findSparseTargetAlignment(reference, target, referenceActivity, cancellationCheck)
                ?: return null
            if (activeValidationMark != null) validationMs += elapsedMs(activeValidationMark)
            reportTimings("sparse-target")
            return sparse
        }

        val activityMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        var alignment = discoverActivityAlignment(
            reference = reference,
            target = target,
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            precomputedUnitEvidence = precomputedDelayOnlyEvidence,
            maxOffsetMs = ACTIVITY_MAX_OFFSET_MS,
            cancellationCheck = cancellationCheck,
        )
        activitySearchMs += elapsedMs(activityMark)
        if (alignment == null) return trySparseFallback()

        val delayMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        var delayOnly = resolveDelayOnlyFor(alignment, precomputedDelayOnly)
        delayValidationMs += elapsedMs(delayMark)

        var candidateScale = if (delayOnly != null) 1.0 else alignment.scale
        var candidateInterceptMs = delayOnly?.offsetMs ?: alignment.interceptMs
        var candidateActivityScore = delayOnly?.score ?: alignment.score
        var candidateActivityMargin = delayOnly?.margin ?: alignment.margin

        val smallSample = target.size < SMALL_SAMPLE_CUE_LIMIT
        val requiredActivityScore =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_SCORE else ACTIVITY_MIN_SCORE
        val requiredActivityMargin =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_MARGIN else ACTIVITY_MIN_MARGIN
        var effectiveAllowAmbiguousMargin =
            allowAmbiguousDelayOnlyMargin || (delayOnly?.stableSegmentMarginOverride == true)
        var activityMarginAccepted =
            candidateActivityMargin >= requiredActivityMargin ||
                (delayOnly != null && effectiveAllowAmbiguousMargin)

        // Two-tier wide-offset fallback (±180s -> ±600s) for long recaps or extended intros,
        // paid only when the normal ±180s corridor fails AND a cheap stride-2 outer-wing probe
        // finds a plausible peak beyond ±180s (keeping wrong-subtitle rejection fast on TV).
        if (
            (candidateActivityScore < requiredActivityScore || !activityMarginAccepted) &&
            canTryWideOffsetFallback(referenceActivity.coarse, targetActivity.coarse) &&
            hasWideOuterWingCandidate(
                referenceCoarse = referenceActivity.coarse,
                targetActivity = targetActivity,
                target = target,
                requiredScore = requiredActivityScore,
                innerBestScore = candidateActivityScore,
                cancellationCheck = cancellationCheck,
            )
        ) {
            val wideActivityMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
            val wideAlignment = discoverActivityAlignment(
                reference = reference,
                target = target,
                referenceActivity = referenceActivity,
                targetActivity = targetActivity,
                precomputedUnitEvidence = null,
                maxOffsetMs = ACTIVITY_WIDE_MAX_OFFSET_MS,
                cancellationCheck = cancellationCheck,
            )
            activitySearchMs += elapsedMs(wideActivityMark)
            if (wideAlignment != null) {
                val wideDelayMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
                val wideDelayOnly = resolveDelayOnlyFor(wideAlignment, reusablePrecomputed = null)
                delayValidationMs += elapsedMs(wideDelayMark)
                val wideScore = wideDelayOnly?.score ?: wideAlignment.score
                val wideMargin = wideDelayOnly?.margin ?: wideAlignment.margin
                val wideEffectiveAmbiguous =
                    allowAmbiguousDelayOnlyMargin || (wideDelayOnly?.stableSegmentMarginOverride == true)
                val wideMarginAccepted =
                    wideMargin >= requiredActivityMargin ||
                        (wideDelayOnly != null && wideEffectiveAmbiguous)
                val widePassesGate = wideScore >= requiredActivityScore && wideMarginAccepted
                if (widePassesGate || wideScore > candidateActivityScore) {
                    alignment = wideAlignment
                    delayOnly = wideDelayOnly
                    candidateScale = if (wideDelayOnly != null) 1.0 else wideAlignment.scale
                    candidateInterceptMs = wideDelayOnly?.offsetMs ?: wideAlignment.interceptMs
                    candidateActivityScore = wideScore
                    candidateActivityMargin = wideMargin
                    effectiveAllowAmbiguousMargin = wideEffectiveAmbiguous
                    activityMarginAccepted = wideMarginAccepted
                }
            }
        }

        // Activity score and margin are already known before DP. If they cannot possibly pass
        // finalizeDiscoveredResult, skip the expensive banded DP (and try sparse-target alignment
        // if eligible) before returning the rejected result directly.
        if (candidateActivityScore < requiredActivityScore || !activityMarginAccepted) {
            trySparseFallback()?.let { return it }
            val rejectReasons = buildList {
                if (candidateActivityScore < requiredActivityScore) {
                    add("activityScore=${round3(candidateActivityScore)}<$requiredActivityScore")
                }
                if (!activityMarginAccepted) {
                    add("activityMargin=${round3(candidateActivityMargin)}<$requiredActivityMargin")
                }
            }
            reportTimings(if (delayOnly != null) "delay-only-rejected" else "activity-rejected")
            return AutoSyncTimelineRetimeResult(
                cues = emptyList(),
                groups = emptyList(),
                targetCoverage = 0.0,
                referenceCoverage = 0.0,
                skippedTargetCues = target.size,
                skippedReferenceCues = reference.size,
                longestTargetSkipRun = target.size,
                averageGroupCost = Double.POSITIVE_INFINITY,
                oneToOneGroups = 0,
                oneToTwoGroups = 0,
                twoToOneGroups = 0,
                oneToThreeGroups = 0,
                threeToOneGroups = 0,
                twoToTwoGroups = 0,
                confident = false,
                alignmentSource = if (delayOnly != null) "delay-only-validated" else "activity-correlation",
                alignmentScale = candidateScale,
                alignmentInterceptMs = candidateInterceptMs,
                activityScore = candidateActivityScore,
                activityMargin = candidateActivityMargin,
                coverageSegmentsPassed = 0,
                simpleGroupRatio = 0.0,
                rejectReason = rejectReasons.joinToString(","),
            )
        }

        val dpMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        val result = retimeWithSeed(
            reference = reference,
            target = target,
            coarseScale = candidateScale,
            coarseInterceptMs = candidateInterceptMs,
            referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
            skipCueMaterialization = delayOnly != null,
            cancellationCheck = cancellationCheck,
        )
        dpMs += elapsedMs(dpMark)
        if (result == null) return trySparseFallback()

        val validationMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        var finalized = finalizeDiscoveredResult(
            result = result,
            referenceSize = reference.size,
            targetSize = target.size,
            candidateScale = candidateScale,
            candidateInterceptMs = candidateInterceptMs,
            candidateActivityScore = candidateActivityScore,
            candidateActivityMargin = candidateActivityMargin,
            delayOnly = delayOnly != null,
            allowAmbiguousDelayOnlyMargin = effectiveAllowAmbiguousMargin,
        )
        // Single-pass post-DP affine slope/intercept refinement when non-standard FPS drift or
        // intro/outro differences left residual error after coarse scale discovery.
        if (
            delayOnly == null &&
            (!finalized.confident || finalized.medianGroupResidualMs > AFFINE_REFINE_TRIGGER_RESIDUAL_MS) &&
            result.groups.size >= AFFINE_REFINE_MIN_GROUPS
        ) {
            val refined = estimateRefinedAffineTransform(reference, target, result.groups)
            if (
                refined != null &&
                (
                    abs(refined.first - candidateScale) >= AFFINE_REFINE_MIN_SCALE_DELTA ||
                        abs(refined.second - candidateInterceptMs) >= AFFINE_REFINE_MIN_INTERCEPT_DELTA_MS
                    )
            ) {
                val refinedDpMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
                val refinedRaw = retimeWithSeed(
                    reference = reference,
                    target = target,
                    coarseScale = refined.first,
                    coarseInterceptMs = refined.second,
                    referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                    skipCueMaterialization = false,
                    cancellationCheck = cancellationCheck,
                )
                dpMs += elapsedMs(refinedDpMark)
                if (refinedRaw != null) {
                    val refinedFinalized = finalizeDiscoveredResult(
                        result = refinedRaw,
                        referenceSize = reference.size,
                        targetSize = target.size,
                        candidateScale = refined.first,
                        candidateInterceptMs = refined.second,
                        candidateActivityScore = candidateActivityScore,
                        candidateActivityMargin = candidateActivityMargin,
                        delayOnly = false,
                        allowAmbiguousDelayOnlyMargin = effectiveAllowAmbiguousMargin,
                    )
                    val upgradesConfidence = refinedFinalized.confident && !finalized.confident
                    val improvesConfidentFit =
                        refinedFinalized.confident && finalized.confident &&
                            (
                                refinedFinalized.averageGroupCost < finalized.averageGroupCost - 0.02 ||
                                    refinedFinalized.medianGroupResidualMs < finalized.medianGroupResidualMs - 20.0
                                )
                    if (upgradesConfidence || improvesConfidentFit) {
                        finalized = refinedFinalized
                    }
                }
            }
        }
        if (!finalized.confident) trySparseFallback(validationMark)?.let { return it }
        if (finalized.confident && delayOnly == null) {
            finalized = refineGroupedReplyTiming(
                reference = reference,
                target = target,
                result = finalized,
                referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
                cancellationCheck = cancellationCheck,
            )
        }
        validationMs += elapsedMs(validationMark)
        reportTimings(if (delayOnly != null) "delay-only" else "activity")
        return if (finalized.confident && delayOnly != null) {
            val refinedOffsetMs = refineValidatedDelayOffset(
                reference = reference,
                target = target,
                groups = finalized.groups,
                currentOffsetMs = delayOnly.offsetMs,
            )
            finalized.withValidatedDelayOnlyCues(
                reference = reference,
                target = target,
                offsetMs = refinedOffsetMs,
            )
        } else {
            finalized
        }
    }

    private fun finalizeDiscoveredResult(
        result: AutoSyncTimelineRetimeResult,
        referenceSize: Int,
        targetSize: Int,
        candidateScale: Double,
        candidateInterceptMs: Double,
        candidateActivityScore: Double,
        candidateActivityMargin: Double,
        delayOnly: Boolean,
        allowAmbiguousDelayOnlyMargin: Boolean,
    ): AutoSyncTimelineRetimeResult {
        val coverageSegments = coverageSegmentsPassed(result, targetSize)
        val simpleRatio = structuralGroupRatio(
            result = result,
            referenceSize = referenceSize,
            targetSize = targetSize,
        )
        val smallSample = targetSize < SMALL_SAMPLE_CUE_LIMIT
        val requiredActivityScore =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_SCORE else ACTIVITY_MIN_SCORE
        val requiredActivityMargin =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_MARGIN else ACTIVITY_MIN_MARGIN
        val requiredCoverageSegments =
            if (smallSample) SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS else 3
        val activityMarginAccepted =
            candidateActivityMargin >= requiredActivityMargin ||
                (delayOnly && allowAmbiguousDelayOnlyMargin)

        // A long unmatched target run alone is tolerated when every other gate passes: it is
        // typically localized content the reference lacks (lyrics, credits, sign translations).
        val matchedTargetCount = targetSize - result.skippedTargetCues
        val skipRunOnlyFailure =
            !result.confident &&
                result.longestTargetSkipRun > MAX_LONGEST_TARGET_SKIP_RUN &&
                matchedTargetCount >= min(MIN_MATCHED_TARGET_CUES, targetSize) &&
                result.targetCoverage >= MIN_TARGET_COVERAGE &&
                result.averageGroupCost <= MAX_AVERAGE_GROUP_COST

        // Symmetric acceptance for targets with substantial extra cues absent from the reference
        // (unbracketed SDH, extra signs/lyrics, TV recaps): accept only when virtually the entire
        // reference matches tightly across all three reference thirds and activity gates pass.
        val needsAsymmetricExtraTargetCheck =
            result.targetCoverage < DISCOVERED_MIN_TARGET_COVERAGE ||
                coverageSegments < requiredCoverageSegments ||
                !result.confident
        val asymmetricExtraTargetAccepted =
            needsAsymmetricExtraTargetCheck &&
                referenceSize >= ASYMMETRIC_EXTRA_TARGET_MIN_CUES &&
                targetSize >= ASYMMETRIC_EXTRA_TARGET_MIN_CUES &&
                matchedTargetCount >= min(MIN_MATCHED_TARGET_CUES, targetSize) &&
                result.referenceCoverage >= ASYMMETRIC_EXTRA_TARGET_MIN_REF_COVERAGE &&
                result.targetCoverage >= ASYMMETRIC_EXTRA_TARGET_MIN_TARGET_COVERAGE &&
                result.averageGroupCost <= ASYMMETRIC_EXTRA_TARGET_MAX_AVERAGE_GROUP_COST &&
                simpleRatio >= DISCOVERED_MIN_SIMPLE_GROUP_RATIO &&
                candidateActivityScore >= requiredActivityScore &&
                activityMarginAccepted &&
                referenceCoverageSegmentsPassed(result, referenceSize) >= 3

        val rejectReasons = buildList {
            if (!skipRunOnlyFailure && !asymmetricExtraTargetAccepted) result.rejectReason?.let(::add)
            if (candidateActivityScore < requiredActivityScore) {
                add("activityScore=${round3(candidateActivityScore)}<$requiredActivityScore")
            }
            if (!activityMarginAccepted) {
                add("activityMargin=${round3(candidateActivityMargin)}<$requiredActivityMargin")
            }
            if (!asymmetricExtraTargetAccepted && coverageSegments < requiredCoverageSegments) {
                add("coverageSegments=$coverageSegments<$requiredCoverageSegments")
            }
            if (!asymmetricExtraTargetAccepted && result.targetCoverage < DISCOVERED_MIN_TARGET_COVERAGE) {
                add(
                    "targetCoverage=${round3(result.targetCoverage)}" +
                        "<$DISCOVERED_MIN_TARGET_COVERAGE",
                )
            }
            if (result.averageGroupCost > DISCOVERED_MAX_AVERAGE_GROUP_COST) {
                add(
                    "avgGroupCost=${round3(result.averageGroupCost)}" +
                        ">$DISCOVERED_MAX_AVERAGE_GROUP_COST",
                )
            }
            if (simpleRatio < DISCOVERED_MIN_SIMPLE_GROUP_RATIO) {
                add("simpleRatio=${round3(simpleRatio)}<$DISCOVERED_MIN_SIMPLE_GROUP_RATIO")
            }
        }.distinct()
        val confirmed = rejectReasons.isEmpty()

        return result.copy(
            confident = confirmed,
            localizedMismatchIgnored = confirmed && (skipRunOnlyFailure || asymmetricExtraTargetAccepted),
            rejectReason = rejectReasons.joinToString(",").ifEmpty { null },
            alignmentSource = if (delayOnly) "delay-only-validated" else "activity-correlation",
            alignmentScale = candidateScale,
            alignmentInterceptMs = candidateInterceptMs,
            activityScore = candidateActivityScore,
            activityMargin = candidateActivityMargin,
            coverageSegmentsPassed = if (asymmetricExtraTargetAccepted) max(coverageSegments, 3) else coverageSegments,
            simpleGroupRatio = simpleRatio,
        )
    }

    private fun retimeWithSeed(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        referenceEstimatedEndStartsMs: Set<Long>,
        skipCueMaterialization: Boolean = false,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncTimelineRetimeResult? {
        val refSize = reference.size
        val tgtSize = target.size
        if (refSize < MIN_CUES || tgtSize < MIN_CUES) return null
        if (!coarseScale.isFinite() || coarseScale !in 0.85..1.15) return null
        if (!coarseInterceptMs.isFinite()) return null

        val hasEstimatedEnds = referenceEstimatedEndStartsMs.isNotEmpty()
        val referenceStarts = DoubleArray(refSize)
        val referenceEnds = DoubleArray(refSize)
        val estimatedEndPrefix = if (hasEstimatedEnds) IntArray(refSize + 1) else EMPTY_INT_ARRAY
        for (index in 0 until refSize) {
            if ((index and 0xFF) == 0) cancellationCheck?.invoke()
            val cue = reference[index]
            referenceStarts[index] = cue.startTimeMs.toDouble()
            referenceEnds[index] = cue.endTimeMs.toDouble()
            if (hasEstimatedEnds) {
                estimatedEndPrefix[index + 1] =
                    estimatedEndPrefix[index] +
                        if (cue.startTimeMs in referenceEstimatedEndStartsMs) 1 else 0
            }
        }

        val transformedTargetStarts = DoubleArray(tgtSize)
        val transformedTargetEnds = DoubleArray(tgtSize)
        for (index in 0 until tgtSize) {
            if ((index and 0xFF) == 0) cancellationCheck?.invoke()
            val cue = target[index]
            transformedTargetStarts[index] =
                transformTimeDouble(cue.startTimeMs, coarseScale, coarseInterceptMs)
            transformedTargetEnds[index] =
                transformTimeDouble(cue.endTimeMs, coarseScale, coarseInterceptMs)
        }

        val centers = computeDpCenters(
            reference = reference,
            transformedTargetStarts = transformedTargetStarts,
            transformedTargetEnds = transformedTargetEnds,
        )
        val rowCount = tgtSize + 1
        val rowStarts = IntArray(rowCount)
        val rowEnds = IntArray(rowCount)
        val rowOffsets = IntArray(rowCount + 1)
        var totalCells = 0
        for (t in 0..tgtSize) {
            var start = if (t == 0) 0 else max(0, centers[t] - BAND_RADIUS_CUES)
            val end = min(refSize, centers[t] + BAND_RADIUS_CUES)
            // Bridge unsubtitled reference blocks (songs, credits, SDH bursts) up to
            // DP_MAX_GAP_BRIDGE_CUES cues without disconnecting the banded DP.
            if (t > 0 && centers[t] - centers[t - 1] in (BAND_RADIUS_CUES + 1)..DP_MAX_GAP_BRIDGE_CUES) {
                start = min(start, max(0, centers[t - 1] - BAND_RADIUS_CUES))
            }
            rowStarts[t] = start
            rowEnds[t] = end
            rowOffsets[t] = totalCells
            totalCells += end - start + 1
        }
        rowOffsets[rowCount] = totalCells

        val transitionCodes = ByteArray(totalCells)
        val bestFinalRef = fillBandedDp(
            refSize = refSize,
            tgtSize = tgtSize,
            referenceStarts = referenceStarts,
            referenceEnds = referenceEnds,
            estimatedEndPrefix = estimatedEndPrefix,
            hasEstimatedEnds = hasEstimatedEnds,
            transformedTargetStarts = transformedTargetStarts,
            transformedTargetEnds = transformedTargetEnds,
            rowStarts = rowStarts,
            rowEnds = rowEnds,
            rowOffsets = rowOffsets,
            transitionCodes = transitionCodes,
            cancellationCheck = cancellationCheck,
        )
        if (bestFinalRef < 0) return null

        val backtracked = backtrackDpGroups(
            bestFinalRef = bestFinalRef,
            tgtSize = tgtSize,
            referenceStarts = referenceStarts,
            referenceEnds = referenceEnds,
            estimatedEndPrefix = estimatedEndPrefix,
            hasEstimatedEnds = hasEstimatedEnds,
            transformedTargetStarts = transformedTargetStarts,
            transformedTargetEnds = transformedTargetEnds,
            rowStarts = rowStarts,
            rowEnds = rowEnds,
            rowOffsets = rowOffsets,
            transitionCodes = transitionCodes,
        ) ?: return null

        return buildRetimeResultFromGroups(
            reference = reference,
            target = target,
            referenceStarts = referenceStarts,
            transformedTargetStarts = transformedTargetStarts,
            transformedTargetEnds = transformedTargetEnds,
            backtracked = backtracked,
            skipCueMaterialization = skipCueMaterialization,
        )
    }

    private fun computeDpCenters(
        reference: List<SubtitleSyncCue>,
        transformedTargetStarts: DoubleArray,
        transformedTargetEnds: DoubleArray,
    ): IntArray {
        val refSize = reference.size
        val tgtSize = transformedTargetStarts.size
        val centers = IntArray(tgtSize + 1)
        var centerScan = 0
        for (targetIndex in 0 until tgtSize) {
            val predictedStart =
                transformedTargetStarts[targetIndex].roundToLong().coerceAtLeast(0L)
            if (centerScan > 0 && reference[centerScan - 1].startTimeMs >= predictedStart) {
                centerScan = lowerBoundReference(reference, predictedStart)
            } else {
                while (centerScan < refSize && reference[centerScan].startTimeMs < predictedStart) {
                    centerScan++
                }
            }
            centers[targetIndex] = centerScan
        }
        val predictedEnd =
            transformedTargetEnds[tgtSize - 1].roundToLong().coerceAtLeast(0L)
        centers[tgtSize] = lowerBoundReference(reference, predictedEnd)
        return centers
    }

    private fun fillBandedDp(
        refSize: Int,
        tgtSize: Int,
        referenceStarts: DoubleArray,
        referenceEnds: DoubleArray,
        estimatedEndPrefix: IntArray,
        hasEstimatedEnds: Boolean,
        transformedTargetStarts: DoubleArray,
        transformedTargetEnds: DoubleArray,
        rowStarts: IntArray,
        rowEnds: IntArray,
        rowOffsets: IntArray,
        transitionCodes: ByteArray,
        cancellationCheck: (() -> Unit)?,
    ): Int {
        // Rolling 4-row cost arrays keep active DP costs inside L1 cache (~1.8 KB).
        val bandWidth = BAND_RADIUS_CUES * 2 + 1
        fun rowAllocWidth(row: Int): Int =
            if (row <= tgtSize) max(bandWidth, rowEnds[row] - rowStarts[row] + 1) else bandWidth
        var costs0 = DoubleArray(rowAllocWidth(0)) { Double.POSITIVE_INFINITY }
        var costs1 = DoubleArray(rowAllocWidth(1)) { Double.POSITIVE_INFINITY }
        var costs2 = DoubleArray(rowAllocWidth(2)) { Double.POSITIVE_INFINITY }
        var costs3 = DoubleArray(rowAllocWidth(3)) { Double.POSITIVE_INFINITY }
        var minReach0 = 0
        var minReach1 = Int.MAX_VALUE
        var minReach2 = Int.MAX_VALUE
        var minReach3 = Int.MAX_VALUE

        costs0[0] = 0.0
        transitionCodes[0] = DP_CODE_START

        for (t in 0..tgtSize) {
            if ((t and 15) == 0) cancellationCheck?.invoke()
            val curStart = rowStarts[t]
            val curEnd = rowEnds[t]
            val curOff = rowOffsets[t]
            var r = minReach0

            if (r <= curEnd) {
                val hasT1 = t < tgtSize
                val hasT2 = t + 1 < tgtSize
                val hasT3 = t + 2 < tgtSize
                val s1 = if (hasT1) rowStarts[t + 1] else 0
                val e1 = if (hasT1) rowEnds[t + 1] else -1
                val o1 = if (hasT1) rowOffsets[t + 1] else 0
                val s2 = if (hasT2) rowStarts[t + 2] else 0
                val e2 = if (hasT2) rowEnds[t + 2] else -1
                val o2 = if (hasT2) rowOffsets[t + 2] else 0
                val s3 = if (hasT3) rowStarts[t + 3] else 0
                val e3 = if (hasT3) rowEnds[t + 3] else -1
                val o3 = if (hasT3) rowOffsets[t + 3] else 0

                val tgtStart = if (hasT1) transformedTargetStarts[t] else 0.0
                val tgtEnd1 = if (hasT1) transformedTargetEnds[t] else 0.0
                val tgtDur1 = if (hasT1) max(1.0, tgtEnd1 - tgtStart) else 1.0
                val tgtMid1 = if (hasT1) (tgtStart + tgtEnd1) * 0.5 else 0.0
                val tgtEnd2 = if (hasT2) transformedTargetEnds[t + 1] else 0.0
                val tgtDur2 = if (hasT2) max(1.0, tgtEnd2 - tgtStart) else 1.0
                val tgtMid2 = if (hasT2) (tgtStart + tgtEnd2) * 0.5 else 0.0
                val tgtEnd3 = if (hasT3) transformedTargetEnds[t + 2] else 0.0
                val tgtDur3 = if (hasT3) max(1.0, tgtEnd3 - tgtStart) else 1.0
                val tgtMid3 = if (hasT3) (tgtStart + tgtEnd3) * 0.5 else 0.0

                while (r <= curEnd) {
                    val cellCost = costs0[r - curStart]
                    if (cellCost != Double.POSITIVE_INFINITY) {
                        if (r < curEnd) {
                            val nextIdx = r + 1 - curStart
                            val cand = cellCost + SKIP_REFERENCE_COST
                            if (cand + 1e-9 < costs0[nextIdx]) {
                                costs0[nextIdx] = cand
                                transitionCodes[curOff + nextIdx] = DP_CODE_SKIP_REF
                            }
                        }

                        if (hasT1 && r in s1..e1) {
                            val nextIdx = r - s1
                            val cand = cellCost + SKIP_TARGET_COST
                            if (cand + 1e-9 < costs1[nextIdx]) {
                                costs1[nextIdx] = cand
                                transitionCodes[o1 + nextIdx] = DP_CODE_SKIP_TGT
                                if (r < minReach1) minReach1 = r
                            }
                        }

                        if (hasT1 && r < refSize) {
                            val refStart = referenceStarts[r]
                            val startError = abs(refStart - tgtStart) * INV_START_END_TOLERANCE_MS
                            if (startError * 0.34 <= MAX_GROUP_COST) {
                                val estBase = if (hasEstimatedEnds) estimatedEndPrefix[r] else 0

                                val r1 = r + 1
                                val in11 = r1 in s1..e1
                                val in12 = hasT2 && r1 in s2..e2
                                val in13 = hasT3 && r1 in s3..e3
                                if (in11 || in12 || in13) {
                                    val refEnd = referenceEnds[r]
                                    val refDur = max(1.0, refEnd - refStart)
                                    val refMid = (refStart + refEnd) * 0.5
                                    val est = hasEstimatedEnds && estimatedEndPrefix[r1] != estBase
                                    val minStart = startError * if (est) 0.58 else 0.34
                                    if (in11 && relaxGroup(
                                            cellCost, minStart, 0.0, startError,
                                            refEnd, refDur, refMid, tgtEnd1, tgtDur1, tgtMid1, est,
                                            costs1, r1 - s1, transitionCodes, o1 + r1 - s1, DP_CODE_GROUP_1_1,
                                        ) && r1 < minReach1
                                    ) minReach1 = r1
                                    if (in12 && relaxGroup(
                                            cellCost, minStart, GROUP_COMPLEXITY_COST, startError,
                                            refEnd, refDur, refMid, tgtEnd2, tgtDur2, tgtMid2, est,
                                            costs2, r1 - s2, transitionCodes, o2 + r1 - s2, DP_CODE_GROUP_1_2,
                                        ) && r1 < minReach2
                                    ) minReach2 = r1
                                    if (in13 && relaxGroup(
                                            cellCost, minStart, GROUP_COMPLEXITY_COST * 2.0, startError,
                                            refEnd, refDur, refMid, tgtEnd3, tgtDur3, tgtMid3, est,
                                            costs3, r1 - s3, transitionCodes, o3 + r1 - s3, DP_CODE_GROUP_1_3,
                                        ) && r1 < minReach3
                                    ) minReach3 = r1
                                }

                                val r2 = r + 2
                                if (r2 <= refSize) {
                                    val in21 = r2 in s1..e1
                                    val in22 = hasT2 && r2 in s2..e2
                                    if (in21 || in22) {
                                        val refEnd = referenceEnds[r + 1]
                                        val refDur = max(1.0, refEnd - refStart)
                                        val refMid = (refStart + refEnd) * 0.5
                                        val est = hasEstimatedEnds && estimatedEndPrefix[r2] != estBase
                                        val minStart = startError * if (est) 0.58 else 0.34
                                        if (in21 && relaxGroup(
                                                cellCost, minStart, GROUP_COMPLEXITY_COST, startError,
                                                refEnd, refDur, refMid, tgtEnd1, tgtDur1, tgtMid1, est,
                                                costs1, r2 - s1, transitionCodes, o1 + r2 - s1, DP_CODE_GROUP_2_1,
                                            ) && r2 < minReach1
                                        ) minReach1 = r2
                                        if (in22 && relaxGroup(
                                                cellCost, minStart, GROUP_COMPLEXITY_COST * 2.0, startError,
                                                refEnd, refDur, refMid, tgtEnd2, tgtDur2, tgtMid2, est,
                                                costs2, r2 - s2, transitionCodes, o2 + r2 - s2, DP_CODE_GROUP_2_2,
                                            ) && r2 < minReach2
                                        ) minReach2 = r2
                                    }
                                }

                                val r3 = r + 3
                                if (r3 <= refSize && r3 in s1..e1) {
                                    val refEnd = referenceEnds[r + 2]
                                    val refDur = max(1.0, refEnd - refStart)
                                    val refMid = (refStart + refEnd) * 0.5
                                    val est = hasEstimatedEnds && estimatedEndPrefix[r3] != estBase
                                    val minStart = startError * if (est) 0.58 else 0.34
                                    if (relaxGroup(
                                            cellCost, minStart, GROUP_COMPLEXITY_COST * 2.0, startError,
                                            refEnd, refDur, refMid, tgtEnd1, tgtDur1, tgtMid1, est,
                                            costs1, r3 - s1, transitionCodes, o1 + r3 - s1, DP_CODE_GROUP_3_1,
                                        ) && r3 < minReach1
                                    ) minReach1 = r3
                                }
                            }
                        }
                    }
                    r++
                }
            }

            if (t < tgtSize) {
                val nextRowWidth = rowAllocWidth(t + 4)
                val recycled = if (costs0.size == nextRowWidth) costs0 else DoubleArray(nextRowWidth)
                recycled.fill(Double.POSITIVE_INFINITY)
                costs0 = costs1
                costs1 = costs2
                costs2 = costs3
                costs3 = recycled
                minReach0 = minReach1
                minReach1 = minReach2
                minReach2 = minReach3
                minReach3 = Int.MAX_VALUE
            }
        }

        val finalStart = rowStarts[tgtSize]
        val finalEnd = rowEnds[tgtSize]
        var bestFinalRef = -1
        var bestFinalCost = Double.POSITIVE_INFINITY
        for (refIdx in finalStart..finalEnd) {
            val cost = costs0[refIdx - finalStart]
            if (cost < bestFinalCost) {
                bestFinalCost = cost
                bestFinalRef = refIdx
            }
        }
        return bestFinalRef
    }

    private fun relaxGroup(
        cellCost: Double,
        minStartTerm: Double,
        complexityCost: Double,
        startError: Double,
        refEnd: Double,
        refDur: Double,
        refMid: Double,
        tgtEnd: Double,
        tgtDur: Double,
        tgtMid: Double,
        referenceEndEstimated: Boolean,
        destCosts: DoubleArray,
        destIdx: Int,
        transitionCodes: ByteArray,
        codeIdx: Int,
        code: Byte,
    ): Boolean {
        val currentBest = destCosts[destIdx]
        val lowerBound = cellCost + minStartTerm + complexityCost
        if (lowerBound + 1e-9 >= currentBest) return false
        val gc = computeGroupCost(
            startError, refEnd, refDur, refMid,
            tgtEnd, tgtDur, tgtMid, referenceEndEstimated,
        )
        if (gc > MAX_GROUP_COST) return false
        val cand = cellCost + gc + complexityCost
        if (cand + 1e-9 < currentBest) {
            destCosts[destIdx] = cand
            transitionCodes[codeIdx] = code
            return true
        }
        return false
    }

    private class BacktrackedGroups(
        val groups: List<AutoSyncCueGroup>,
        val groupCostSum: Double,
        val oneToOne: Int,
        val oneToTwo: Int,
        val twoToOne: Int,
        val oneToThree: Int,
        val threeToOne: Int,
        val twoToTwo: Int,
    )

    private fun backtrackDpGroups(
        bestFinalRef: Int,
        tgtSize: Int,
        referenceStarts: DoubleArray,
        referenceEnds: DoubleArray,
        estimatedEndPrefix: IntArray,
        hasEstimatedEnds: Boolean,
        transformedTargetStarts: DoubleArray,
        transformedTargetEnds: DoubleArray,
        rowStarts: IntArray,
        rowEnds: IntArray,
        rowOffsets: IntArray,
        transitionCodes: ByteArray,
    ): BacktrackedGroups? {
        val groups = ArrayList<AutoSyncCueGroup>(tgtSize)
        var btRef = bestFinalRef
        var btTgt = tgtSize
        var guard = 0
        val guardLimit = (tgtSize + 1) * 8 + bestFinalRef * 2 + 32
        var oneToOne = 0
        var oneToTwo = 0
        var twoToOne = 0
        var oneToThree = 0
        var threeToOne = 0
        var twoToTwo = 0
        var groupCostSum = 0.0

        while (btRef != 0 || btTgt != 0) {
            if (++guard > guardLimit) return null
            if (btTgt !in 0..tgtSize) return null
            val rs = rowStarts[btTgt]
            val re = rowEnds[btTgt]
            if (btRef < rs || btRef > re) return null
            val code = transitionCodes[rowOffsets[btTgt] + (btRef - rs)]
            when (code) {
                DP_CODE_SKIP_REF -> btRef -= 1
                DP_CODE_SKIP_TGT -> btTgt -= 1
                DP_CODE_GROUP_1_1,
                DP_CODE_GROUP_1_2,
                DP_CODE_GROUP_2_1,
                DP_CODE_GROUP_1_3,
                DP_CODE_GROUP_3_1,
                DP_CODE_GROUP_2_2,
                -> {
                    val rc: Int
                    val tc: Int
                    when (code) {
                        DP_CODE_GROUP_1_1 -> { rc = 1; tc = 1; oneToOne++ }
                        DP_CODE_GROUP_1_2 -> { rc = 1; tc = 2; oneToTwo++ }
                        DP_CODE_GROUP_2_1 -> { rc = 2; tc = 1; twoToOne++ }
                        DP_CODE_GROUP_1_3 -> { rc = 1; tc = 3; oneToThree++ }
                        DP_CODE_GROUP_3_1 -> { rc = 3; tc = 1; threeToOne++ }
                        else -> { rc = 2; tc = 2; twoToTwo++ }
                    }
                    val prevR = btRef - rc
                    val prevT = btTgt - tc
                    val gc = groupCost(
                        referenceStarts = referenceStarts,
                        referenceEnds = referenceEnds,
                        estimatedEndPrefix = estimatedEndPrefix,
                        hasEstimatedEnds = hasEstimatedEnds,
                        referenceIndex = prevR,
                        referenceCount = rc,
                        transformedTargetStarts = transformedTargetStarts,
                        transformedTargetEnds = transformedTargetEnds,
                        targetIndex = prevT,
                        targetCount = tc,
                    )
                    groupCostSum += gc
                    groups += AutoSyncCueGroup(
                        referenceStartIndex = prevR,
                        referenceCount = rc,
                        targetStartIndex = prevT,
                        targetCount = tc,
                        cost = gc,
                    )
                    btRef = prevR
                    btTgt = prevT
                }
                else -> return null
            }
        }

        if (groups.isEmpty()) return null
        groups.reverse()
        return BacktrackedGroups(
            groups = groups,
            groupCostSum = groupCostSum,
            oneToOne = oneToOne,
            oneToTwo = oneToTwo,
            twoToOne = twoToOne,
            oneToThree = oneToThree,
            threeToOne = threeToOne,
            twoToTwo = twoToTwo,
        )
    }

    private fun buildRetimeResultFromGroups(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceStarts: DoubleArray,
        transformedTargetStarts: DoubleArray,
        transformedTargetEnds: DoubleArray,
        backtracked: BacktrackedGroups,
        skipCueMaterialization: Boolean,
    ): AutoSyncTimelineRetimeResult {
        val refSize = reference.size
        val tgtSize = target.size
        val groups = backtracked.groups
        val matchedTarget = BooleanArray(tgtSize)
        val matchedReference = BooleanArray(refSize)
        var matchedTargetCount = 0
        var matchedReferenceCount = 0
        for (group in groups) {
            for (index in group.targetStartIndex until group.targetStartIndex + group.targetCount) {
                if (!matchedTarget[index]) { matchedTarget[index] = true; matchedTargetCount++ }
            }
            for (index in group.referenceStartIndex until group.referenceStartIndex + group.referenceCount) {
                if (!matchedReference[index]) { matchedReference[index] = true; matchedReferenceCount++ }
            }
        }

        val retimed: List<AutoSyncRetimedCue> = if (skipCueMaterialization) {
            emptyList()
        } else {
            val mutableRetimed = ArrayList<AutoSyncRetimedCue>(tgtSize)
            for (index in 0 until tgtSize) {
                val cue = target[index]
                val start = transformedTargetStarts[index].roundToLong().coerceAtLeast(0L)
                val end = transformedTargetEnds[index].roundToLong().coerceAtLeast(start + 1L)
                mutableRetimed += AutoSyncRetimedCue(cue.startTimeMs, cue.endTimeMs, start, end)
            }
            // Target ranges of groups are disjoint, so every shift can be read before any is applied.
            val rawShifts = LongArray(groups.size) { groupIndex ->
                val group = groups[groupIndex]
                reference[group.referenceStartIndex].startTimeMs -
                    mutableRetimed[group.targetStartIndex].startTimeMs
            }
            val groupShifts = rejectLocalShiftOutliers(rawShifts)
            for (groupIndex in groups.indices) {
                transplantGroupTiming(groups[groupIndex], mutableRetimed, groupShifts[groupIndex])
            }
            // Keep output monotonic even when malformed source cues overlap backwards.
            var previousStart = Long.MIN_VALUE
            for (index in mutableRetimed.indices) {
                val cue = mutableRetimed[index]
                val start = max(cue.startTimeMs, previousStart)
                val end = max(cue.endTimeMs, start + 1L)
                if (start != cue.startTimeMs || end != cue.endTimeMs) {
                    mutableRetimed[index] = cue.copy(startTimeMs = start, endTimeMs = end)
                }
                previousStart = start
            }
            // Preserve pre-existing overlaps, but never create new overlaps between sequential cues.
            for (index in 0 until mutableRetimed.lastIndex) {
                if (target[index].endTimeMs > target[index + 1].startTimeMs) continue
                val current = mutableRetimed[index]
                val next = mutableRetimed[index + 1]
                if (current.endTimeMs > next.startTimeMs && next.startTimeMs > current.startTimeMs) {
                    mutableRetimed[index] = current.copy(endTimeMs = next.startTimeMs)
                }
            }
            mutableRetimed
        }

        val targetCoverage = matchedTargetCount.toDouble() / tgtSize
        val referenceCoverage = matchedReferenceCount.toDouble() / refSize
        val skippedTarget = tgtSize - matchedTargetCount
        val skippedReference = refSize - matchedReferenceCount
        val longestTargetSkipRun = longestFalseRun(matchedTarget)
        val averageGroupCost = backtracked.groupCostSum / groups.size

        // Offset each matched group still needs after the seed transform.
        val groupResiduals = DoubleArray(groups.size) { groupIndex ->
            val group = groups[groupIndex]
            abs(
                referenceStarts[group.referenceStartIndex] -
                    transformedTargetStarts[group.targetStartIndex],
            )
        }
        groupResiduals.sort()

        val minMatchedTarget = min(MIN_MATCHED_TARGET_CUES, tgtSize)
        val rejectReasons = buildList {
            if (matchedTargetCount < minMatchedTarget) {
                add("matchedTarget=$matchedTargetCount<$minMatchedTarget")
            }
            if (targetCoverage < MIN_TARGET_COVERAGE) {
                add("targetCoverage=${round3(targetCoverage)}<$MIN_TARGET_COVERAGE")
            }
            if (averageGroupCost > MAX_AVERAGE_GROUP_COST) {
                add("avgGroupCost=${round3(averageGroupCost)}>$MAX_AVERAGE_GROUP_COST")
            }
            if (longestTargetSkipRun > MAX_LONGEST_TARGET_SKIP_RUN) {
                add("targetSkipRun=$longestTargetSkipRun>$MAX_LONGEST_TARGET_SKIP_RUN")
            }
        }
        val confident = rejectReasons.isEmpty()

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = groups,
            targetCoverage = targetCoverage,
            referenceCoverage = referenceCoverage,
            skippedTargetCues = skippedTarget,
            skippedReferenceCues = skippedReference,
            longestTargetSkipRun = longestTargetSkipRun,
            averageGroupCost = averageGroupCost,
            oneToOneGroups = backtracked.oneToOne,
            oneToTwoGroups = backtracked.oneToTwo,
            twoToOneGroups = backtracked.twoToOne,
            oneToThreeGroups = backtracked.oneToThree,
            threeToOneGroups = backtracked.threeToOne,
            twoToTwoGroups = backtracked.twoToTwo,
            confident = confident,
            medianGroupResidualMs = groupResiduals[groupResiduals.size / 2],
            rejectReason = rejectReasons.joinToString(",").ifEmpty { null },
        )
    }

    private fun computeGroupCost(
        startError: Double,
        refEnd: Double,
        refDur: Double,
        refMid: Double,
        tgtEnd: Double,
        tgtDur: Double,
        tgtMid: Double,
        referenceEndEstimated: Boolean,
    ): Double {
        val endError = abs(refEnd - tgtEnd) * INV_START_END_TOLERANCE_MS
        val midpointError = abs(refMid - tgtMid) * INV_MIDPOINT_TOLERANCE_MS
        val durationError = abs(refDur - tgtDur) * INV_DURATION_TOLERANCE_MS
        return if (referenceEndEstimated) {
            startError * 0.58 +
                endError * 0.16 +
                midpointError * 0.16 +
                durationError * 0.10
        } else {
            startError * 0.34 +
                endError * 0.34 +
                midpointError * 0.18 +
                durationError * 0.14
        }
    }

    internal fun findDelayOnlyAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        allowAmbiguousMargin: Boolean = false,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < DELAY_ONLY_MIN_CUES || target.size < DELAY_ONLY_MIN_CUES) return null
        val referenceActivity = prepareUnitActivity(reference) ?: return null
        val targetActivity = prepareUnitActivity(target) ?: return null
        return findDelayOnlyAlignmentPrepared(
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            targetSize = target.size,
            allowAmbiguousMargin = allowAmbiguousMargin,
            seed = null,
        )
    }

    internal fun prepareDelayOnlySearchEvidence(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        cancellationCheck: (() -> Unit)? = null,
    ): DelayOnlySearchEvidence? {
        val offsetRange = coarseOffsetRange(referenceActivity.coarse, targetActivity.coarse)
        val firstOffset = offsetRange.first
        val count = offsetRange.last - firstOffset + 1
        val scores = DoubleArray(count) { Double.NaN }
        val bestIndex = scoreCoarseRangeInto(
            reference = referenceActivity.coarse,
            target = targetActivity.coarse,
            firstOffset = firstOffset,
            scores = scores,
            cancellationCheck = cancellationCheck,
        )
        if (bestIndex < 0) return null

        val seed = buildDelayOnlySearchSeedFromScores(
            scores = scores,
            firstOffsetBins = firstOffset,
            bestIndex = bestIndex,
            bestScore = scores[bestIndex],
        )
        // Fine refinement is deferred lazily until findDelayOnlyAlignmentPrepared or
        // discoverActivityAlignment actually needs it, avoiding wasted fine searches on
        // failing preflights (wrong release / FPS drift).
        return DelayOnlySearchEvidence(
            coarseScores = scores,
            firstOffsetBins = firstOffset,
            seed = seed,
        )
    }

    private fun scoreCoarseRangeInto(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        firstOffset: Int,
        scores: DoubleArray,
        cancellationCheck: (() -> Unit)?,
    ): Int {
        val count = scores.size
        var bestIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until count) {
            if ((i and 63) == 0) cancellationCheck?.invoke()
            val score = scoreActivityOffset(reference, target, firstOffset + i) ?: continue
            scores[i] = score
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun findCoarseTieRangeMs(
        scores: DoubleArray,
        firstOffsetBins: Int,
        bestIndex: Int,
        bestScore: Double,
    ): Pair<Long, Long> {
        val maxSpreadBins = (ACTIVITY_TIE_MAX_SPREAD_MS / ACTIVITY_COARSE_BIN_MS).toInt()
        val tieThreshold = bestScore - ACTIVITY_TIE_SCORE_EPSILON
        var startIdx = bestIndex
        while (startIdx > 0 && bestIndex - (startIdx - 1) <= maxSpreadBins && scores[startIdx - 1] >= tieThreshold) {
            startIdx--
        }
        var endIdx = bestIndex
        while (endIdx < scores.lastIndex && (endIdx + 1) - bestIndex <= maxSpreadBins && scores[endIdx + 1] >= tieThreshold) {
            endIdx++
        }
        return ((firstOffsetBins + startIdx).toLong() * ACTIVITY_COARSE_BIN_MS) to
            ((firstOffsetBins + endIdx).toLong() * ACTIVITY_COARSE_BIN_MS)
    }

    private fun buildDelayOnlySearchSeedFromScores(
        scores: DoubleArray,
        firstOffsetBins: Int,
        bestIndex: Int,
        bestScore: Double,
    ): DelayOnlySearchSeed {
        val bestInterceptMs = (firstOffsetBins + bestIndex).toLong() * ACTIVITY_COARSE_BIN_MS
        val distinctBins =
            ((DELAY_ONLY_DISTINCT_OFFSET_MS + ACTIVITY_COARSE_BIN_MS - 1L) / ACTIVITY_COARSE_BIN_MS).toInt()
        var secondScore = 0.0
        var hasSecond = false
        for (i in scores.indices) {
            val s = scores[i]
            if (!s.isNaN() && abs(i - bestIndex) >= distinctBins && (!hasSecond || s > secondScore)) {
                secondScore = s
                hasSecond = true
            }
        }
        val (tieStartMs, tieEndMs) = findCoarseTieRangeMs(scores, firstOffsetBins, bestIndex, bestScore)
        return DelayOnlySearchSeed(
            coarse = ActivityCandidate(scale = 1.0, interceptMs = bestInterceptMs, score = bestScore),
            margin = bestScore - if (hasSecond) secondScore else 0.0,
            fine = null,
            tieStartMs = tieStartMs,
            tieEndMs = tieEndMs,
        )
    }

    internal fun findDelayOnlyAlignmentPrepared(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        targetSize: Int,
        allowAmbiguousMargin: Boolean = false,
        precomputedEvidence: DelayOnlySearchEvidence? = null,
        maxAbsOffsetMs: Double = Double.POSITIVE_INFINITY,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncDelayOnlyAlignment? =
        findDelayOnlyAlignmentPrepared(
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            targetSize = targetSize,
            allowAmbiguousMargin = allowAmbiguousMargin,
            seed = precomputedEvidence?.seed,
            maxAbsOffsetMs = maxAbsOffsetMs,
            cancellationCheck = cancellationCheck,
        )

    private fun findDelayOnlyAlignmentPrepared(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        targetSize: Int,
        allowAmbiguousMargin: Boolean,
        seed: DelayOnlySearchSeed?,
        allowStableSegmentMarginOverride: Boolean = false,
        maxAbsOffsetMs: Double = Double.POSITIVE_INFINITY,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncDelayOnlyAlignment? {
        val coarseSeed = seed
            ?: prepareDelayOnlySearchEvidence(
                referenceActivity = referenceActivity,
                targetActivity = targetActivity,
                cancellationCheck = cancellationCheck,
            )?.seed
            ?: return null

        val coarse = coarseSeed.coarse
        // Fine search stays within ACTIVITY_TIE_MAX_SPREAD_MS of coarse.interceptMs, so a coarse
        // peak further than maxAbsOffsetMs + ACTIVITY_TIE_MAX_SPREAD_MS can never satisfy the cap.
        if (
            maxAbsOffsetMs.isFinite() &&
            abs(coarse.interceptMs).toDouble() > maxAbsOffsetMs + ACTIVITY_TIE_MAX_SPREAD_MS
        ) {
            return null
        }

        val margin = coarseSeed.margin
        val marginAccepted =
            allowAmbiguousMargin || margin >= DELAY_ONLY_MIN_MARGIN
        val canUseStableMarginOverride =
            allowStableSegmentMarginOverride &&
                !allowAmbiguousMargin &&
                margin >= DELAY_ONLY_STABLE_OVERRIDE_MIN_MARGIN
        if (!marginAccepted && !canUseStableMarginOverride) return null

        val referenceFine = referenceActivity.fine
        val targetFine = targetActivity.fine
        val best = coarseSeed.fine
            ?: fineActivitySearch(
                referenceFine = referenceFine,
                targetFine = targetFine,
                scale = 1.0,
                coarseInterceptMs = coarse.interceptMs,
                tieStartMs = coarseSeed.tieStartMs,
                tieEndMs = coarseSeed.tieEndMs,
                referenceStarts = referenceActivity.starts,
                targetStarts = targetActivity.starts,
                cancellationCheck = cancellationCheck,
            )?.also { coarseSeed.cachedFine = it }
            ?: coarse

        if (best.score < DELAY_ONLY_MIN_SCORE) return null
        if (maxAbsOffsetMs.isFinite() && abs(best.interceptMs).toDouble() > maxAbsOffsetMs) {
            return null
        }

        val globalOffsetBins =
            (best.interceptMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
        val localRadiusBins =
            (DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS / ACTIVITY_FINE_BIN_MS).toInt()
        val maxDeltaBins =
            (DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS / ACTIVITY_FINE_BIN_MS).toInt()

        var availableSegments = 0
        var passedSegments = 0
        for (segment in 0..2) {
            cancellationCheck?.invoke()
            if (allowAmbiguousMargin) {
                val targetCoverage = targetActivityCoverageAtOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = globalOffsetBins,
                    segment = segment,
                ) ?: continue
                availableSegments++
                if (targetCoverage >= DELAY_ONLY_MIN_SEGMENT_SCORE) passedSegments++
                continue
            }

            val segmentScores = DoubleArray(2 * localRadiusBins + 1) { Double.NaN }
            var segmentBestScore = Double.NEGATIVE_INFINITY
            for (delta in -localRadiusBins..localRadiusBins) {
                val score = scoreActivityOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = globalOffsetBins + delta,
                    segment = segment,
                ) ?: continue
                segmentScores[delta + localRadiusBins] = score
                if (score > segmentBestScore) segmentBestScore = score
            }
            if (segmentBestScore == Double.NEGATIVE_INFINITY) continue

            // Among delays that tie with this section's best, the one closest to the global
            // delay decides: a tie is agreement, not drift.
            var segmentBestOffsetBins = globalOffsetBins
            var closestDistance = Int.MAX_VALUE
            for (delta in -localRadiusBins..localRadiusBins) {
                val score = segmentScores[delta + localRadiusBins]
                if (score.isNaN() || score < segmentBestScore - ACTIVITY_TIE_SCORE_EPSILON) continue
                if (abs(delta) < closestDistance) {
                    closestDistance = abs(delta)
                    segmentBestOffsetBins = globalOffsetBins + delta
                }
            }
            availableSegments++
            if (
                segmentBestScore >= DELAY_ONLY_MIN_SEGMENT_SCORE &&
                abs(segmentBestOffsetBins - globalOffsetBins) <= maxDeltaBins
            ) {
                passedSegments++
            }
        }

        val requiredSegments = if (targetSize < SMALL_SAMPLE_CUE_LIMIT) 2 else 3
        if (availableSegments < requiredSegments || passedSegments < requiredSegments) return null

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = passedSegments,
            stableSegmentMarginOverride = !marginAccepted && canUseStableMarginOverride,
        )
    }

    private fun buildUniformDelayCues(
        target: List<SubtitleSyncCue>,
        offsetMs: Long,
    ): List<AutoSyncRetimedCue> = target.map { cue ->
        val start = (cue.startTimeMs + offsetMs).coerceAtLeast(0L)
        val end = (cue.endTimeMs + offsetMs).coerceAtLeast(start + 1L)
        AutoSyncRetimedCue(
            originalStartTimeMs = cue.startTimeMs,
            originalEndTimeMs = cue.endTimeMs,
            startTimeMs = start,
            endTimeMs = end,
        )
    }

    private fun AutoSyncTimelineRetimeResult.withValidatedDelayOnlyCues(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        offsetMs: Double,
    ): AutoSyncTimelineRetimeResult {
        val refinedResidual = if (groups.isNotEmpty()) {
            val residuals = DoubleArray(groups.size) { index ->
                val group = groups[index]
                abs(
                    reference[group.referenceStartIndex].startTimeMs.toDouble() -
                        (target[group.targetStartIndex].startTimeMs.toDouble() + offsetMs),
                )
            }
            residuals.sort()
            residuals[residuals.size / 2]
        } else {
            medianGroupResidualMs
        }
        return copy(
            cues = buildUniformDelayCues(
                target = target,
                offsetMs = offsetMs.roundToLong(),
            ),
            alignmentInterceptMs = offsetMs,
            medianGroupResidualMs = refinedResidual,
        )
    }

    private fun medianOf(values: DoubleArray, count: Int = values.size): Double {
        values.sort(0, count)
        return if ((count and 1) == 1) values[count / 2] else (values[count / 2 - 1] + values[count / 2]) * 0.5
    }

    private fun medianOf(values: LongArray, count: Int = values.size): Double {
        values.sort(0, count)
        return if ((count and 1) == 1) values[count / 2].toDouble() else (values[count / 2 - 1] + values[count / 2]) * 0.5
    }

    /**
     * Snaps a validated delay-only offset from the 100 ms fine-activity bin grid (and any
     * estimated-end plateau bias) to the exact median matched cue-start offset.
     */
    private fun refineValidatedDelayOffset(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        groups: List<AutoSyncCueGroup>,
        currentOffsetMs: Double,
    ): Double {
        if (groups.size < DELAY_REFINEMENT_MIN_GROUPS) return currentOffsetMs
        val oneToOneCount = groups.count { it.referenceCount == 1 && it.targetCount == 1 }
        val useOnlyOneToOne = oneToOneCount >= DELAY_REFINEMENT_MIN_GROUPS
        val diffs = DoubleArray(if (useOnlyOneToOne) oneToOneCount else groups.size)
        var outIdx = 0
        for (g in groups) {
            if (useOnlyOneToOne && (g.referenceCount != 1 || g.targetCount != 1)) continue
            diffs[outIdx++] = (reference[g.referenceStartIndex].startTimeMs - target[g.targetStartIndex].startTimeMs).toDouble()
        }
        val median = medianOf(diffs, outIdx)
        return if (median.isFinite() && abs(median - currentOffsetMs) <= DELAY_REFINEMENT_MAX_DELTA_MS) median else currentOffsetMs
    }

    /**
     * Robust outer-tertile paired slope and median intercept estimator from matched DP groups.
     * Used once when coarse scale discovery leaves noticeable affine residual.
     */
    private fun estimateRefinedAffineTransform(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        groups: List<AutoSyncCueGroup>,
    ): Pair<Double, Double>? {
        val oneToOne = groups.filter { it.referenceCount == 1 && it.targetCount == 1 }
        val selected = if (oneToOne.size >= 24) oneToOne else groups
        val n = selected.size
        if (n < 18) return null
        val third = n / 3
        val slopes = DoubleArray(third)
        var slopeCount = 0
        for (k in 0 until third) {
            val lowGroup = selected[k]
            val highGroup = selected[n - third + k]
            val dt = (target[highGroup.targetStartIndex].startTimeMs - target[lowGroup.targetStartIndex].startTimeMs).toDouble()
            if (dt < 60_000.0) continue
            val slope = (reference[highGroup.referenceStartIndex].startTimeMs - reference[lowGroup.referenceStartIndex].startTimeMs).toDouble() / dt
            if (slope.isFinite() && slope in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) slopes[slopeCount++] = slope
        }
        if (slopeCount < 6) return null
        val refinedScale = medianOf(slopes, slopeCount)
        val intercepts = DoubleArray(n) { i ->
            val g = selected[i]
            reference[g.referenceStartIndex].startTimeMs.toDouble() - target[g.targetStartIndex].startTimeMs.toDouble() * refinedScale
        }
        val refinedIntercept = medianOf(intercepts, n)
        return if (refinedScale.isFinite() && refinedIntercept.isFinite()) refinedScale to refinedIntercept else null
    }

    private fun canTryWideOffsetFallback(
        referenceCoarse: ActivityTimeline,
        targetCoarse: ActivityTimeline,
    ): Boolean {
        val referenceSpanBins = referenceCoarse.lastActive - referenceCoarse.firstActive
        val targetSpanBins = targetCoarse.lastActive - targetCoarse.firstActive
        // Skip if already widened for a partial target (CD1/CD2).
        if (targetSpanBins < referenceSpanBins * PARTIAL_TARGET_SPAN_RATIO) return false
        val referenceSpanMs = referenceSpanBins.toLong() * ACTIVITY_COARSE_BIN_MS
        return referenceSpanMs >= ACTIVITY_WIDE_MAX_OFFSET_MS
    }

    /**
     * Fast stride-2 (1,000 ms) probe over only the outer ±180s..±600s wings across three
     * representative scales (1.0, PAL->NTSC, NTSC->PAL, already cached on [targetActivity]).
     * Avoids paying a full 7-scale ±600s scan on every unrelated subtitle during multi-candidate
     * searches while still triggering the exact full wide search for every genuine recap offset.
     */
    private fun hasWideOuterWingCandidate(
        referenceCoarse: ActivityTimeline,
        targetActivity: PreparedActivity,
        target: List<SubtitleSyncCue>,
        requiredScore: Double,
        innerBestScore: Double,
        cancellationCheck: (() -> Unit)?,
    ): Boolean {
        val innerBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()
        val outerBins = (ACTIVITY_WIDE_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()
        // An outer-wing candidate can only pass margin against the already-evaluated ±180s
        // corridor if it beats both the required score (allowing slight stride-2 attenuation)
        // and the ±180s noise floor by at least ACTIVITY_MIN_MARGIN.
        val triggerScore = max(requiredScore - 0.05, innerBestScore + ACTIVITY_MIN_MARGIN)
        for (scale in WIDE_PROBE_SCALES) {
            cancellationCheck?.invoke()
            val targetCoarse = if (scale == 1.0) {
                targetActivity.coarse
            } else {
                targetActivity.scaledCoarse(scale, target) ?: continue
            }
            var offset = -outerBins
            while (offset < -innerBins) {
                val score = scoreActivityOffset(referenceCoarse, targetCoarse, offset)
                if (score != null && score >= triggerScore) return true
                offset += 2
            }
            offset = innerBins + 2
            while (offset <= outerBins) {
                val score = scoreActivityOffset(referenceCoarse, targetCoarse, offset)
                if (score != null && score >= triggerScore) return true
                offset += 2
            }
        }
        return false
    }

    private val WIDE_PROBE_SCALES = doubleArrayOf(
        1.0,
        25.0 / 23.976,
        23.976 / 25.0,
    )

    private fun canTrySparseTarget(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): Boolean =
        target.size >= SPARSE_TARGET_MIN_CUES &&
            reference.size >= target.size * SPARSE_TARGET_MIN_REF_RATIO

    private class SparsePeakCandidate(
        val scale: Double,
        val offsetMs: Double,
        val score: Double,
        val matchRatio: Double,
        val activityCoverage: Double,
        val matchedCount: Int,
        val medianAbsResidualMs: Double,
        val segmentsPassed: Int,
        val longestSkipRun: Int,
    )

    /**
     * Conservative fallback for sparse external subtitles (such as forced tracks) aligned
     * directly against a much denser full-dialogue embedded reference. Combines a 250 ms
     * cue-start vote histogram with exact monotonic 1:1 start matching, continuous cue-overlap
     * coverage, global multi-scale distinct-peak margin, and temporal third stability.
     */
    private fun findSparseTargetAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceActivity: PreparedActivity,
        cancellationCheck: (() -> Unit)?,
    ): AutoSyncTimelineRetimeResult? {
        if (!canTrySparseTarget(reference, target)) return null
        searchSparseTargetAtMaxOffset(reference, target, referenceActivity.starts, ACTIVITY_MAX_OFFSET_MS, cancellationCheck)
            ?.let { return it }
        val refSpanMs = (reference.last().startTimeMs - reference.first().startTimeMs).coerceAtLeast(0L)
        if (refSpanMs < ACTIVITY_WIDE_MAX_OFFSET_MS) return null
        return searchSparseTargetAtMaxOffset(reference, target, referenceActivity.starts, ACTIVITY_WIDE_MAX_OFFSET_MS, cancellationCheck)
    }

    private fun searchSparseTargetAtMaxOffset(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        refStarts: LongArray,
        maxOffsetMs: Long,
        cancellationCheck: (() -> Unit)?,
    ): AutoSyncTimelineRetimeResult? {
        val refSize = refStarts.size
        val tgtSize = target.size
        if (refSize < tgtSize * SPARSE_TARGET_MIN_REF_RATIO || tgtSize < SPARSE_TARGET_MIN_CUES) return null

        val binMs = SPARSE_TARGET_BIN_MS
        val halfBins = (maxOffsetMs / binMs).toInt()
        val binCount = halfBins * 2 + 1
        val distinctBins = ((SPARSE_TARGET_DISTINCT_OFFSET_MS + binMs - 1L) / binMs).toInt()
        val votes = IntArray(binCount)
        val scaledStarts = LongArray(tgtSize)
        val peakBins = IntArray(8)
        val candidates = ArrayList<SparsePeakCandidate>(SPARSE_TARGET_SCALES.size * 8)

        for (scale in SPARSE_TARGET_SCALES) {
            cancellationCheck?.invoke()
            votes.fill(0)
            for (t in 0 until tgtSize) {
                val ts = if (scale == 1.0) target[t].startTimeMs else (target[t].startTimeMs.toDouble() * scale).roundToLong()
                scaledStarts[t] = ts
                var r = lowerBoundLong(refStarts, ts - maxOffsetMs)
                var lastBin = -1
                while (r < refSize && refStarts[r] <= ts + maxOffsetMs) {
                    val bin = ((refStarts[r] - ts + maxOffsetMs) / binMs).toInt().coerceIn(0, binCount - 1)
                    if (bin != lastBin) { votes[bin]++; lastBin = bin }
                    r++
                }
            }
            var peakCount = 0
            while (peakCount < peakBins.size) {
                var bestBin = -1
                var bestSum = 3
                for (b in 0 until binCount) {
                    val sum = votes[b] + (if (b > 0) votes[b - 1] else 0) + (if (b + 1 < binCount) votes[b + 1] else 0)
                    if (sum <= bestSum) continue
                    if ((0 until peakCount).none { abs(b - peakBins[it]) < distinctBins }) {
                        bestSum = sum
                        bestBin = b
                    }
                }
                if (bestBin < 0) break
                peakBins[peakCount++] = bestBin
            }
            for (p in 0 until peakCount) {
                evaluateSparsePeakCandidate(
                    reference, target, refStarts, scaledStarts, scale,
                    (peakBins[p] - halfBins).toLong() * binMs,
                )?.let { candidates += it }
            }
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        val targetEndMs = target.maxOf { it.endTimeMs }.toDouble()
        val bestEndPos = targetEndMs * best.scale + best.offsetMs
        var secondScore = 0.0
        for (other in candidates) {
            if (other === best) continue
            val diff = max(abs(best.offsetMs - other.offsetMs), abs(bestEndPos - (targetEndMs * other.scale + other.offsetMs)))
            if (diff >= SPARSE_TARGET_DISTINCT_OFFSET_MS.toDouble() && other.score > secondScore) secondScore = other.score
        }
        val margin = best.score - secondScore
        val isSmall = tgtSize < SPARSE_TARGET_SMALL_LIMIT
        val requiredSegments = if (tgtSize < 12) 2 else 3
        if (
            best.matchedCount < SPARSE_TARGET_MIN_CUES ||
            best.matchRatio < (if (isSmall) SPARSE_TARGET_SMALL_MIN_MATCH_RATIO else SPARSE_TARGET_MIN_MATCH_RATIO) ||
            best.activityCoverage < (if (isSmall) SPARSE_TARGET_SMALL_MIN_ACTIVITY_COVERAGE else SPARSE_TARGET_MIN_ACTIVITY_COVERAGE) ||
            margin < (if (isSmall) SPARSE_TARGET_SMALL_MIN_MARGIN else SPARSE_TARGET_MIN_MARGIN) ||
            best.medianAbsResidualMs > (if (isSmall) SPARSE_TARGET_SMALL_MAX_MEDIAN_RESIDUAL_MS else SPARSE_TARGET_MAX_MEDIAN_RESIDUAL_MS) ||
            best.segmentsPassed < requiredSegments
        ) {
            return null
        }

        val retimedCues = if (best.scale == 1.0) {
            buildUniformDelayCues(target, best.offsetMs.roundToLong())
        } else {
            target.map { cue ->
                val start = transformTime(cue.startTimeMs, best.scale, best.offsetMs)
                val end = transformTime(cue.endTimeMs, best.scale, best.offsetMs).coerceAtLeast(start + 1L)
                AutoSyncRetimedCue(cue.startTimeMs, cue.endTimeMs, start, end)
            }
        }
        return AutoSyncTimelineRetimeResult(
            cues = retimedCues,
            groups = emptyList(),
            targetCoverage = best.matchRatio,
            referenceCoverage = best.matchedCount.toDouble() / refSize.toDouble(),
            skippedTargetCues = tgtSize - best.matchedCount,
            skippedReferenceCues = refSize - best.matchedCount,
            longestTargetSkipRun = best.longestSkipRun,
            averageGroupCost = best.medianAbsResidualMs / START_END_TOLERANCE_MS,
            oneToOneGroups = best.matchedCount,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentSource = if (best.scale == 1.0) "sparse-target-delay" else "sparse-target-affine",
            alignmentScale = best.scale,
            alignmentInterceptMs = best.offsetMs,
            activityScore = best.score,
            activityMargin = margin,
            coverageSegmentsPassed = best.segmentsPassed,
            simpleGroupRatio = 1.0,
            medianGroupResidualMs = best.medianAbsResidualMs,
        )
    }

    private fun nearestRefIndex(refStarts: LongArray, expected: Long, minExclusive: Int, maxDist: Long): Int {
        val ins = lowerBoundLong(refStarts, expected)
        var bestIdx = -1
        var bestDist = maxDist + 1L
        if (ins < refStarts.size && ins > minExclusive) {
            val d = abs(refStarts[ins] - expected)
            if (d < bestDist) { bestDist = d; bestIdx = ins }
        }
        if (ins > 0 && ins - 1 > minExclusive) {
            val d = abs(refStarts[ins - 1] - expected)
            if (d < bestDist) { bestIdx = ins - 1 }
        }
        return bestIdx
    }

    private fun evaluateSparsePeakCandidate(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        refStarts: LongArray,
        scaledStarts: LongArray,
        scale: Double,
        approxOffsetMs: Long,
    ): SparsePeakCandidate? {
        val refSize = refStarts.size
        val tgtSize = scaledStarts.size
        val coarseDiffs = LongArray(tgtSize)
        var coarseCount = 0
        for (t in 0 until tgtSize) {
            val idx = nearestRefIndex(refStarts, scaledStarts[t] + approxOffsetMs, -1, 1_000L)
            if (idx >= 0) coarseDiffs[coarseCount++] = refStarts[idx] - scaledStarts[t]
        }
        if (coarseCount < 4) return null
        val refinedOffsetMs = medianOf(coarseDiffs, coarseCount)
        val roundedOffsetMs = refinedOffsetMs.roundToLong()

        val matched = BooleanArray(tgtSize)
        val absResiduals = DoubleArray(tgtSize)
        val signedOffsets = DoubleArray(tgtSize)
        var matchedCount = 0
        var lastRefIdx = -1
        for (t in 0 until tgtSize) {
            val chosenIdx = nearestRefIndex(refStarts, scaledStarts[t] + roundedOffsetMs, lastRefIdx, SPARSE_TARGET_MATCH_TOLERANCE_MS)
            if (chosenIdx >= 0) {
                lastRefIdx = chosenIdx
                matched[t] = true
                val signedOffset = (refStarts[chosenIdx] - scaledStarts[t]).toDouble()
                signedOffsets[t] = signedOffset
                absResiduals[matchedCount++] = abs(signedOffset - refinedOffsetMs)
            }
        }
        if (matchedCount == 0) return null
        val medianAbsResidualMs = medianOf(absResiduals, matchedCount)

        var totalTargetDur = 0L
        var overlappedDur = 0L
        for (t in 0 until tgtSize) {
            val ts = scaledStarts[t] + roundedOffsetMs
            val teRaw = if (scale == 1.0) target[t].endTimeMs + roundedOffsetMs
                else (target[t].endTimeMs.toDouble() * scale).roundToLong() + roundedOffsetMs
            val te = teRaw.coerceAtLeast(ts + 1L).coerceAtMost(ts + ACTIVITY_MAX_CUE_DURATION_MS)
            totalTargetDur += te - ts
            var r = lowerBoundLong(refStarts, ts - ACTIVITY_MAX_CUE_DURATION_MS)
            var coveredUntil = ts
            while (r < refSize && refStarts[r] < te) {
                val rStart = reference[r].startTimeMs
                val rEnd = reference[r].endTimeMs.coerceAtLeast(rStart + 1L).coerceAtMost(rStart + ACTIVITY_MAX_CUE_DURATION_MS)
                if (rEnd > coveredUntil) {
                    val segStart = max(coveredUntil, rStart)
                    val segEnd = min(te, rEnd)
                    if (segEnd > segStart) {
                        overlappedDur += segEnd - segStart
                        coveredUntil = segEnd
                        if (coveredUntil >= te) break
                    }
                }
                r++
            }
        }
        val activityCoverage = if (totalTargetDur <= 0L) 0.0 else overlappedDur.toDouble() / totalTargetDur.toDouble()

        val segmentCount = if (tgtSize < 12) 2 else 3
        val segOffsets = DoubleArray(tgtSize)
        var segmentsPassed = 0
        for (seg in 0 until segmentCount) {
            val from = seg * tgtSize / segmentCount
            val to = if (seg == segmentCount - 1) tgtSize else (seg + 1) * tgtSize / segmentCount
            var segMatched = 0
            for (i in from until to) if (matched[i]) segOffsets[segMatched++] = signedOffsets[i]
            if (segMatched >= 2 && segMatched.toDouble() / (to - from).coerceAtLeast(1) >= SPARSE_TARGET_SEGMENT_MIN_MATCH_RATIO) {
                segOffsets.sort(0, segMatched)
                if (abs(segOffsets[segMatched / 2] - refinedOffsetMs) <= SPARSE_TARGET_SEGMENT_MAX_OFFSET_DELTA_MS) {
                    segmentsPassed++
                }
            }
        }
        val matchRatio = matchedCount.toDouble() / tgtSize.toDouble()
        return SparsePeakCandidate(
            scale = scale,
            offsetMs = refinedOffsetMs,
            score = matchRatio * 0.65 + activityCoverage * 0.35,
            matchRatio = matchRatio,
            activityCoverage = activityCoverage,
            matchedCount = matchedCount,
            medianAbsResidualMs = medianAbsResidualMs,
            segmentsPassed = segmentsPassed,
            longestSkipRun = longestFalseRun(matched),
        )
    }

    private fun lowerBoundLong(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    private val SPARSE_TARGET_SCALES = doubleArrayOf(
        1.0,
        25.0 / 23.976,
        23.976 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
        24.0 / 23.976,
        23.976 / 24.0,
    )

    internal fun buildDelayOnlyTimeline(
        target: List<SubtitleSyncCue>,
        alignment: AutoSyncDelayOnlyAlignment,
    ): AutoSyncTimelineRetimeResult {
        val retimed = buildUniformDelayCues(
            target = target,
            offsetMs = alignment.offsetMs.roundToLong(),
        )

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 0.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 0,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentSource = "delay-only",
            alignmentScale = 1.0,
            alignmentInterceptMs = alignment.offsetMs,
            activityScore = alignment.score,
            activityMargin = alignment.margin,
            coverageSegmentsPassed = alignment.segmentsPassed,
            simpleGroupRatio = 1.0,
        )
    }

    private fun targetActivityCoverageAtOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? = scoreOrCoverageActivityOffsetSegment(reference, target, offsetBins, segment, coverageOnly = true)

    private fun scoreActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? = scoreOrCoverageActivityOffsetSegment(reference, target, offsetBins, segment, coverageOnly = false)

    private fun scoreOrCoverageActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
        coverageOnly: Boolean,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null
        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) target.lastActive else target.firstActive + activeSpan * (segment + 1) / 3 - 1
        if (segmentEnd < segmentStart) return null
        val visibleTarget = target.prefixCount(segmentEnd + 1) - target.prefixCount(segmentStart)
        if (visibleTarget <= 0) return null
        val intersection = countShiftedActivityIntersection(reference, target, offsetBins, segmentStart, segmentEnd)
        val precision = intersection.toDouble() / visibleTarget.toDouble()
        if (coverageOnly) return precision

        val refWinStart = max(0, segmentStart + offsetBins)
        val refWinEnd = min(reference.lastIndex, segmentEnd + offsetBins)
        if (refWinEnd < refWinStart) return null
        val refInWindow = reference.prefixCount(refWinEnd + 1) - reference.prefixCount(refWinStart)
        if (refInWindow <= 0) return null
        return precision * 0.72 + (intersection.toDouble() / refInWindow.toDouble()) * 0.28
    }

    private fun discoverActivityAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        precomputedUnitEvidence: DelayOnlySearchEvidence? = null,
        maxOffsetMs: Long = ACTIVITY_MAX_OFFSET_MS,
        cancellationCheck: (() -> Unit)? = null,
    ): ActivityAlignment? {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return null

        val referenceCoarse = referenceActivity.coarse
        val scales = activityScaleCandidates(reference, target)
        val scaleCount = scales.size
        val scoresPerScale = arrayOfNulls<DoubleArray>(scaleCount)
        val firstOffsetPerScale = IntArray(scaleCount)

        var bestScaleIndex = -1
        var bestOffsetIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var unitScaleIndex = -1
        var unitBestOffsetIndex = -1
        var unitBestScore = Double.NEGATIVE_INFINITY

        for (scaleIdx in 0 until scaleCount) {
            cancellationCheck?.invoke()
            val scale = scales[scaleIdx]

            if (scale == 1.0) {
                unitScaleIndex = scaleIdx
                if (precomputedUnitEvidence != null) {
                    val scores = precomputedUnitEvidence.coarseScores
                    scoresPerScale[scaleIdx] = scores
                    firstOffsetPerScale[scaleIdx] = precomputedUnitEvidence.firstOffsetBins
                    for (i in scores.indices) {
                        val score = scores[i]
                        if (score.isNaN()) continue
                        if (score > unitBestScore) {
                            unitBestScore = score
                            unitBestOffsetIndex = i
                        }
                        if (score > bestScore) {
                            bestScore = score
                            bestScaleIndex = scaleIdx
                            bestOffsetIndex = i
                        }
                    }
                    continue
                }
            }

            val targetCoarse = if (scale == 1.0) {
                targetActivity.coarse
            } else {
                targetActivity.scaledCoarse(scale, target) ?: continue
            }
            val offsetRange = coarseOffsetRange(referenceCoarse, targetCoarse, maxOffsetMs)
            val firstOffset = offsetRange.first
            val count = offsetRange.last - firstOffset + 1
            val scores = DoubleArray(count) { Double.NaN }
            scoresPerScale[scaleIdx] = scores
            firstOffsetPerScale[scaleIdx] = firstOffset

            val scaleBestIdx = scoreCoarseRangeInto(
                reference = referenceCoarse,
                target = targetCoarse,
                firstOffset = firstOffset,
                scores = scores,
                cancellationCheck = cancellationCheck,
            )
            if (scaleBestIdx >= 0) {
                val scaleBestScore = scores[scaleBestIdx]
                if (scale == 1.0 && scaleBestScore > unitBestScore) {
                    unitBestScore = scaleBestScore
                    unitBestOffsetIndex = scaleBestIdx
                }
                if (scaleBestScore > bestScore) {
                    bestScore = scaleBestScore
                    bestScaleIndex = scaleIdx
                    bestOffsetIndex = scaleBestIdx
                }
            }
        }

        if (bestScaleIndex < 0) return null
        val bestScale = scales[bestScaleIndex]
        val bestInterceptMs =
            (firstOffsetPerScale[bestScaleIndex] + bestOffsetIndex).toLong() * ACTIVITY_COARSE_BIN_MS
        val coarseBest = ActivityCandidate(
            scale = bestScale,
            interceptMs = bestInterceptMs,
            score = bestScore,
        )

        val targetEndMs = target.maxOf { it.endTimeMs }.toDouble()
        val bestEndPos = targetEndMs * bestScale + bestInterceptMs
        var secondScore = 0.0
        var hasSecond = false
        for (scaleIdx in 0 until scaleCount) {
            val scores = scoresPerScale[scaleIdx] ?: continue
            val scale = scales[scaleIdx]
            val firstOffset = firstOffsetPerScale[scaleIdx]
            val scaledEnd = targetEndMs * scale
            for (i in scores.indices) {
                if (scaleIdx == bestScaleIndex && i == bestOffsetIndex) continue
                val score = scores[i]
                if (score.isNaN()) continue
                if (hasSecond && score <= secondScore) continue
                val interceptMs = (firstOffset + i).toLong() * ACTIVITY_COARSE_BIN_MS
                val startDiff = abs(bestInterceptMs - interceptMs).toDouble()
                val endDiff = abs(bestEndPos - (scaledEnd + interceptMs))
                if (max(startDiff, endDiff) >= ACTIVITY_DISTINCT_TRANSFORM_MS) {
                    secondScore = score
                    hasSecond = true
                }
            }
        }
        val margin = bestScore - if (hasSecond) secondScore else 0.0

        val referenceFine = referenceActivity.fine
        val targetFine = if (bestScale == 1.0) {
            targetActivity.fine
        } else {
            buildActivityTimeline(target, bestScale, ACTIVITY_FINE_BIN_MS)
                ?: return null
        }

        // Compute contiguous coarse tie run directly from scoresPerScale[bestScaleIndex].
        val (tieStartMs, tieEndMs) = findCoarseTieRangeMs(
            scores = scoresPerScale[bestScaleIndex]!!,
            firstOffsetBins = firstOffsetPerScale[bestScaleIndex],
            bestIndex = bestOffsetIndex,
            bestScore = bestScore,
        )

        val reusedPrecomputedFine =
            if (
                bestScale == 1.0 &&
                precomputedUnitEvidence != null &&
                precomputedUnitEvidence.seed.coarse.interceptMs == bestInterceptMs &&
                precomputedUnitEvidence.seed.tieStartMs == tieStartMs &&
                precomputedUnitEvidence.seed.tieEndMs == tieEndMs
            ) {
                precomputedUnitEvidence.seed.fine
            } else {
                null
            }

        val fineBest = reusedPrecomputedFine ?: fineActivitySearch(
            referenceFine = referenceFine,
            targetFine = targetFine,
            scale = bestScale,
            coarseInterceptMs = bestInterceptMs,
            tieStartMs = tieStartMs,
            tieEndMs = tieEndMs,
            referenceStarts = referenceActivity.starts,
            targetStarts = targetActivity.starts,
            cancellationCheck = cancellationCheck,
        )

        val best = fineBest ?: coarseBest
        val unitSeed = precomputedUnitEvidence?.seed?.let { seed ->
            if (bestScale == 1.0) {
                if (seed.fine == null && fineBest != null) seed.copy(fine = fineBest) else seed
            } else {
                seed.copy(fine = null)
            }
        } ?: if (unitScaleIndex >= 0 && unitBestOffsetIndex >= 0) {
            val uScores = scoresPerScale[unitScaleIndex]!!
            val uSeed = buildDelayOnlySearchSeedFromScores(
                scores = uScores,
                firstOffsetBins = firstOffsetPerScale[unitScaleIndex],
                bestIndex = unitBestOffsetIndex,
                bestScore = unitBestScore,
            )
            if (bestScale == 1.0) uSeed.copy(fine = fineBest) else uSeed
        } else {
            null
        }

        return ActivityAlignment(
            scale = best.scale,
            interceptMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
            delayOnlySeed = unitSeed,
        )
    }

    /**
     * Fine search over the coarse tie run widened by [ACTIVITY_FINE_RADIUS_MS] (capped at
     * [ACTIVITY_TIE_MAX_SPREAD_MS] from [coarseInterceptMs]). The best overlap wins; delays that
     * tie on overlap are separated by how many subtitle lines start with a reference line.
     */
    private fun fineActivitySearch(
        referenceFine: ActivityTimeline,
        targetFine: ActivityTimeline,
        scale: Double,
        coarseInterceptMs: Long,
        tieStartMs: Long,
        tieEndMs: Long,
        referenceStarts: LongArray,
        targetStarts: LongArray,
        cancellationCheck: (() -> Unit)?,
    ): ActivityCandidate? {
        val fromMs = max(
            tieStartMs - ACTIVITY_FINE_RADIUS_MS,
            coarseInterceptMs - ACTIVITY_TIE_MAX_SPREAD_MS,
        )
        val toMs = min(
            tieEndMs + ACTIVITY_FINE_RADIUS_MS,
            coarseInterceptMs + ACTIVITY_TIE_MAX_SPREAD_MS,
        )
        val scored = ArrayList<ActivityCandidate>()
        var offsetMs = fromMs
        while (offsetMs <= toMs) {
            cancellationCheck?.invoke()
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                scored += ActivityCandidate(
                    scale = scale,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }
        // First highest score, as before, unless other delays tie with it.
        var best: ActivityCandidate? = null
        for (candidate in scored) {
            if (best == null || candidate.score > best.score) best = candidate
        }
        best ?: return null
        if (referenceStarts.isEmpty() || targetStarts.isEmpty()) return best
        val tied = scored.filter { it.score >= best.score - ACTIVITY_TIE_SCORE_EPSILON }
        if (tied.size < 2) return best
        var chosen = best
        var chosenAgreement = startAgreement(referenceStarts, targetStarts, scale, best.interceptMs)
        for (candidate in tied) {
            if (candidate === best) continue
            val agreement =
                startAgreement(referenceStarts, targetStarts, scale, candidate.interceptMs)
            if (agreement > chosenAgreement) {
                chosen = candidate
                chosenAgreement = agreement
            }
        }
        return chosen
    }

    /**
     * How well [targetStarts] land on reference starts: each start within
     * [START_AGREEMENT_TOLERANCE_MS] of one counts, closer ones more, averaged over all starts.
     */
    private fun startAgreement(
        referenceStarts: LongArray,
        targetStarts: LongArray,
        scale: Double,
        interceptMs: Long,
    ): Double {
        var agreement = 0.0
        var low = 0
        val refSize = referenceStarts.size
        val invTolerance = 1.0 / (START_AGREEMENT_TOLERANCE_MS + 1).toDouble()
        for (i in targetStarts.indices) {
            val mapped = (targetStarts[i] * scale).roundToLong() + interceptMs
            while (low < refSize && referenceStarts[low] < mapped) {
                low++
            }
            var nearest = Long.MAX_VALUE
            if (low < refSize) {
                nearest = referenceStarts[low] - mapped
            }
            if (low > 0) {
                val leftDist = mapped - referenceStarts[low - 1]
                if (leftDist < nearest) nearest = leftDist
            }
            if (nearest <= START_AGREEMENT_TOLERANCE_MS) {
                agreement += 1.0 - nearest.toDouble() * invTolerance
            }
        }
        return agreement / targetStarts.size.toDouble()
    }

    private fun buildActivityTimeline(
        cues: List<SubtitleSyncCue>,
        scale: Double,
        binMs: Long,
    ): ActivityTimeline? {
        if (!scale.isFinite() || scale !in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) return null
        if (cues.isEmpty() || binMs <= 0L) return null

        val isUnitScale = scale == 1.0
        var maxEndMs = 0L
        for (i in cues.indices) {
            val cue = cues[i]
            val safeStart = cue.startTimeMs.coerceAtLeast(0L)
            val safeEnd = cue.endTimeMs
                .coerceAtLeast(safeStart + 1L)
                .coerceAtMost(safeStart + ACTIVITY_MAX_CUE_DURATION_MS)
            val end = if (isUnitScale) {
                safeEnd
            } else {
                val start = (safeStart * scale).roundToLong().coerceAtLeast(0L)
                (safeEnd * scale).roundToLong().coerceAtLeast(start + 1L)
            }
            if (end > ACTIVITY_MAX_TIMELINE_MS) return null
            if (end > maxEndMs) maxEndMs = end
        }

        val binCount = max(1, ((maxEndMs + binMs) / binMs).toInt())
        val wordCount = (binCount + 63) ushr 6
        val packed = LongArray(wordCount)
        val lastBin = binCount - 1

        for (i in cues.indices) {
            val cue = cues[i]
            val safeStart = cue.startTimeMs.coerceAtLeast(0L)
            val safeEnd = cue.endTimeMs
                .coerceAtLeast(safeStart + 1L)
                .coerceAtMost(safeStart + ACTIVITY_MAX_CUE_DURATION_MS)
            val start: Long
            val end: Long
            if (isUnitScale) {
                start = safeStart
                end = safeEnd
            } else {
                start = (safeStart * scale).roundToLong().coerceAtLeast(0L)
                end = (safeEnd * scale).roundToLong().coerceAtLeast(start + 1L)
            }
            val first = (start / binMs).toInt().coerceIn(0, lastBin)
            val lastExclusive = ((end + binMs - 1L) / binMs).toInt().coerceIn(first + 1, binCount)
            val lastInclusive = lastExclusive - 1
            val firstWord = first ushr 6
            val lastWord = lastInclusive ushr 6
            if (firstWord == lastWord) {
                val lower = -1L shl (first and 63)
                val upperBit = lastInclusive and 63
                val upper = if (upperBit == 63) -1L else (1L shl (upperBit + 1)) - 1L
                packed[firstWord] = packed[firstWord] or (lower and upper)
            } else {
                packed[firstWord] = packed[firstWord] or (-1L shl (first and 63))
                for (w in firstWord + 1 until lastWord) {
                    packed[w] = -1L
                }
                val upperBit = lastInclusive and 63
                val upper = if (upperBit == 63) -1L else (1L shl (upperBit + 1)) - 1L
                packed[lastWord] = packed[lastWord] or upper
            }
        }

        val wordPrefix = IntArray(wordCount + 1)
        var activeCount = 0
        var firstActive = -1
        var lastActive = -1
        for (w in 0 until wordCount) {
            val word = packed[w]
            if (word != 0L) {
                if (firstActive < 0) {
                    firstActive = (w shl 6) + word.countTrailingZeroBits()
                }
                lastActive = (w shl 6) + (63 - word.countLeadingZeroBits())
                activeCount += word.countOneBits()
            }
            wordPrefix[w + 1] = activeCount
        }
        if (activeCount == 0) return null

        return ActivityTimeline(
            binCount = binCount,
            packed = packed,
            wordPrefix = wordPrefix,
            activeCount = activeCount,
            firstActive = firstActive,
            lastActive = lastActive,
        )
    }

    private fun countShiftedActivityIntersection(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        sourceStart: Int,
        sourceEnd: Int,
    ): Int {
        val clampedStart = max(
            max(sourceStart, target.firstActive),
            reference.firstActive - offsetBins,
        )
        val clampedEnd = min(
            min(sourceEnd, target.lastActive),
            reference.lastActive - offsetBins,
        )
        if (clampedEnd < clampedStart) return 0

        val firstWord = clampedStart ushr 6
        val lastWord = clampedEnd ushr 6
        val tPacked = target.packed
        val rPacked = reference.packed
        val wordShift = offsetBins shr 6
        val bitOffset = offsetBins and 63

        if (firstWord == lastWord) {
            val aligned = packedActivityWindow(rPacked, (firstWord shl 6) + offsetBins)
            val mask = activityRangeMask(clampedStart and 63, clampedEnd and 63)
            return (tPacked[firstWord] and aligned and mask).countOneBits()
        }

        val firstMask = -1L shl (clampedStart and 63)
        val lastBit = clampedEnd and 63
        val lastMask = if (lastBit == 63) -1L else (1L shl (lastBit + 1)) - 1L

        if (bitOffset == 0) {
            var intersection =
                (tPacked[firstWord] and rPacked[firstWord + wordShift] and firstMask).countOneBits()
            var w = firstWord + 1
            var rIdx = w + wordShift
            while (w < lastWord) {
                intersection += (tPacked[w] and rPacked[rIdx]).countOneBits()
                w++
                rIdx++
            }
            intersection +=
                (tPacked[lastWord] and rPacked[lastWord + wordShift] and lastMask).countOneBits()
            return intersection
        }

        val invBitOffset = 64 - bitOffset
        var intersection = (
            tPacked[firstWord] and
                packedActivityWindow(rPacked, (firstWord shl 6) + offsetBins) and
                firstMask
            ).countOneBits()

        val interiorStart = firstWord + 1
        if (interiorStart < lastWord) {
            var rIdx = interiorStart + wordShift
            var prev = rPacked[rIdx]
            var w = interiorStart
            while (w < lastWord) {
                val next = rPacked[++rIdx]
                val aligned = (prev ushr bitOffset) or (next shl invBitOffset)
                intersection += (tPacked[w] and aligned).countOneBits()
                prev = next
                w++
            }
        }

        intersection += (
            tPacked[lastWord] and
                packedActivityWindow(rPacked, (lastWord shl 6) + offsetBins) and
                lastMask
            ).countOneBits()
        return intersection
    }

    private fun packedActivityWindow(
        packed: LongArray,
        startBit: Int,
    ): Long {
        val wordIndex = startBit shr 6
        val bitOffset = startBit and 63
        val low = if (wordIndex in packed.indices) packed[wordIndex] else 0L
        if (bitOffset == 0) return low

        val highIndex = wordIndex + 1
        val high = if (highIndex in packed.indices) packed[highIndex] else 0L
        return (low ushr bitOffset) or (high shl (64 - bitOffset))
    }

    private fun activityRangeMask(
        firstBit: Int,
        lastBit: Int,
    ): Long {
        val lower = -1L shl firstBit
        val upper = if (lastBit == 63) -1L else (1L shl (lastBit + 1)) - 1L
        return lower and upper
    }

    private fun scoreActivityOffset(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
    ): Double? {
        val sourceStart = max(0, -offsetBins)
        val sourceEnd = min(target.lastIndex, reference.lastIndex - offsetBins)
        if (sourceEnd < sourceStart) return null
        val fullTargetVisible =
            sourceStart <= target.firstActive && sourceEnd >= target.lastActive
        val visibleTarget = if (fullTargetVisible) {
            target.activeCount
        } else {
            target.prefixCount(sourceEnd + 1) - target.prefixCount(sourceStart)
        }
        if (visibleTarget <= 0) return null

        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
        )

        val referenceWindowStart = max(0, target.firstActive + offsetBins)
        val referenceWindowEnd = min(reference.lastIndex, target.lastActive + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefixCount(referenceWindowEnd + 1) - reference.prefixCount(referenceWindowStart)
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        val baseScore = precision * 0.72 + recall * 0.28
        if (fullTargetVisible) return baseScore
        val visibility = visibleTarget.toDouble() / target.activeCount.toDouble()
        return baseScore * (0.85 + 0.15 * visibility.coerceIn(0.0, 1.0))
    }

    private fun activityScaleCandidates(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<Double> {
        val candidates = mutableListOf(
            1.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
            24.0 / 23.976,
            23.976 / 24.0,
        )
        val referenceSpan = reference.last().startTimeMs - reference.first().startTimeMs
        val targetSpan = target.last().startTimeMs - target.first().startTimeMs
        if (referenceSpan > 0L && targetSpan > 0L) {
            val spanRatio = referenceSpan.toDouble() / targetSpan.toDouble()
            if (spanRatio in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) candidates += spanRatio
        }
        // Trimmed p10..p90 span ratio is immune to extra intro recaps or outro credits.
        // Only add it when genuinely distinct from standard scales so a near-duplicate slope
        // cannot cannibalize margin on long 2.5–3h movies.
        if (reference.size >= 20 && target.size >= 20) {
            val refTrimSpan =
                reference[reference.size * 9 / 10].startTimeMs -
                    reference[reference.size / 10].startTimeMs
            val tgtTrimSpan =
                target[target.size * 9 / 10].startTimeMs -
                    target[target.size / 10].startTimeMs
            if (refTrimSpan > 0L && tgtTrimSpan > 0L) {
                val trimRatio = refTrimSpan.toDouble() / tgtTrimSpan.toDouble()
                if (
                    trimRatio in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE &&
                    candidates.none { abs(it - trimRatio) < 0.0012 }
                ) {
                    candidates += trimRatio
                }
            }
        }

        val unique = mutableListOf<Double>()
        candidates
            .filter { it.isFinite() && it in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE }
            .sorted()
            .forEach { candidate ->
                if (unique.none { abs(it - candidate) < ACTIVITY_SCALE_DEDUP }) unique += candidate
            }
        return unique
    }

    /**
     * Coarse offsets searched for one pair: ±[ACTIVITY_MAX_OFFSET_MS] normally, widened to every
     * placement inside the reference when the target covers only part of it (CD1/CD2 subtitles).
     */
    private fun coarseOffsetRange(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        maxOffsetMs: Long = ACTIVITY_MAX_OFFSET_MS,
    ): IntRange {
        val baseBins = (maxOffsetMs / ACTIVITY_COARSE_BIN_MS).toInt()
        val referenceSpan = reference.lastActive - reference.firstActive
        val targetSpan = target.lastActive - target.firstActive
        if (targetSpan >= referenceSpan * PARTIAL_TARGET_SPAN_RATIO) return -baseBins..baseBins

        return min(-baseBins, reference.firstActive - target.firstActive - baseBins)..
            max(baseBins, reference.lastActive - target.lastActive + baseBins)
    }

    private fun referenceCoverageSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        referenceSize: Int,
    ): Int = computeCoverageSegmentsPassed(
        result = result,
        size = referenceSize,
        useReference = true,
        minCoverage = REF_COVERAGE_SEGMENT_MIN_COVERAGE,
        maxAvgCost = REF_COVERAGE_SEGMENT_MAX_AVERAGE_COST,
    )

    private fun coverageSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        targetSize: Int,
    ): Int = computeCoverageSegmentsPassed(
        result = result,
        size = targetSize,
        useReference = false,
        minCoverage = COVERAGE_SEGMENT_MIN_COVERAGE,
        maxAvgCost = COVERAGE_SEGMENT_MAX_AVERAGE_COST,
    )

    private fun computeCoverageSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        size: Int,
        useReference: Boolean,
        minCoverage: Double,
        maxAvgCost: Double,
    ): Int {
        if (size < MIN_CUES || result.groups.isEmpty()) return 0
        val matched = BooleanArray(size)
        val costSums = DoubleArray(3)
        val costCounts = IntArray(3)
        for (group in result.groups) {
            val gStart = if (useReference) group.referenceStartIndex else group.targetStartIndex
            val gCount = if (useReference) group.referenceCount else group.targetCount
            val gEnd = (gStart + gCount).coerceAtMost(size)
            for (index in gStart until gEnd) matched[index] = true
            val segment = (((gStart + (gCount - 1) / 2).toLong() * 3L) / size.coerceAtLeast(1)).toInt().coerceIn(0, 2)
            costSums[segment] += group.cost
            costCounts[segment]++
        }
        var passed = 0
        for (segment in 0..2) {
            val start = segment * size / 3
            val end = if (segment == 2) size else (segment + 1) * size / 3
            val length = (end - start).coerceAtLeast(1)
            var matchedCount = 0
            for (index in start until end) if (matched[index]) matchedCount++
            val count = costCounts[segment]
            val avgCost = if (count == 0) Double.POSITIVE_INFINITY else costSums[segment] / count.toDouble()
            if (
                matchedCount.toDouble() / length >= minCoverage &&
                avgCost <= maxAvgCost &&
                count >= min(COVERAGE_SEGMENT_MIN_GROUPS, length)
            ) {
                passed++
            }
        }
        return passed
    }

    private fun structuralGroupRatio(
        result: AutoSyncTimelineRetimeResult,
        referenceSize: Int,
        targetSize: Int,
    ): Double {
        if (result.groups.isEmpty()) return 0.0

        var compatible =
            result.oneToOneGroups +
                result.oneToTwoGroups +
                result.twoToOneGroups +
                result.twoToTwoGroups

        val enoughTimelineCoverage =
            result.targetCoverage >= SEGMENTATION_MIN_TIMELINE_COVERAGE &&
                result.referenceCoverage >= SEGMENTATION_MIN_TIMELINE_COVERAGE

        if (enoughTimelineCoverage) {
            val referencePerTarget =
                referenceSize.toDouble() / targetSize.coerceAtLeast(1).toDouble()
            val targetPerReference =
                targetSize.toDouble() / referenceSize.coerceAtLeast(1).toDouble()

            if (referencePerTarget >= SEGMENTATION_IMBALANCE_RATIO) {
                compatible += result.threeToOneGroups
            }
            if (targetPerReference >= SEGMENTATION_IMBALANCE_RATIO) {
                compatible += result.oneToThreeGroups
            }
        }

        return compatible.toDouble() / result.groups.size
    }

    internal class ActivityTimeline(
        val binCount: Int,
        val packed: LongArray,
        val wordPrefix: IntArray,
        val activeCount: Int,
        val firstActive: Int,
        val lastActive: Int,
    ) {
        val lastIndex: Int get() = binCount - 1

        fun prefixCount(exclusiveBin: Int): Int {
            val wordIndex = exclusiveBin ushr 6
            val bitIndex = exclusiveBin and 63
            val base = wordPrefix[wordIndex]
            if (bitIndex == 0) return base
            return base + (packed[wordIndex] and ((1L shl bitIndex) - 1L)).countOneBits()
        }
    }

    internal class PreparedActivity(
        val coarse: ActivityTimeline,
        val fine: ActivityTimeline,
        /** Sorted cue start times, for breaking ties between equally overlapping delays. */
        val starts: LongArray = LongArray(0),
    ) {
        private val scaledCoarseLock = SynchronizedObject()
        private var scaledCoarseCache: MutableMap<Double, ActivityTimeline?>? = null

        fun scaledCoarse(scale: Double, cues: List<SubtitleSyncCue>): ActivityTimeline? {
            if (scale == 1.0) return coarse
            synchronized(scaledCoarseLock) {
                val map = scaledCoarseCache
                if (map != null && map.containsKey(scale)) {
                    return map[scale]
                }
            }
            val built = buildActivityTimeline(cues, scale, ACTIVITY_COARSE_BIN_MS)
            synchronized(scaledCoarseLock) {
                val map = scaledCoarseCache ?: HashMap<Double, ActivityTimeline?>(8).also {
                    scaledCoarseCache = it
                }
                map[scale] = built
            }
            return built
        }
    }

    internal data class ActivityCandidate(
        val scale: Double,
        val interceptMs: Long,
        val score: Double,
    )

    internal class DelayOnlySearchSeed(
        val coarse: ActivityCandidate,
        val margin: Double,
        fine: ActivityCandidate?,
        /** Ends of the run of coarse delays that tie with [coarse]. */
        val tieStartMs: Long = coarse.interceptMs,
        val tieEndMs: Long = coarse.interceptMs,
    ) {
        @Volatile
        internal var cachedFine: ActivityCandidate? = fine

        val fine: ActivityCandidate? get() = cachedFine

        fun copy(fine: ActivityCandidate? = this.cachedFine): DelayOnlySearchSeed =
            DelayOnlySearchSeed(
                coarse = coarse,
                margin = margin,
                fine = fine,
                tieStartMs = tieStartMs,
                tieEndMs = tieEndMs,
            )
    }

    internal data class DelayOnlySearchEvidence(
        /** Coarse score per offset, where index 0 is [firstOffsetBins]; NaN when unscorable. */
        val coarseScores: DoubleArray,
        val firstOffsetBins: Int,
        val seed: DelayOnlySearchSeed,
    )

    private data class ActivityAlignment(
        val scale: Double,
        val interceptMs: Double,
        val score: Double,
        val margin: Double,
        val delayOnlySeed: DelayOnlySearchSeed?,
    )

    private fun refineGroupedReplyTiming(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        result: AutoSyncTimelineRetimeResult,
        referenceEstimatedEndStartsMs: Set<Long>,
        cancellationCheck: (() -> Unit)?,
    ): AutoSyncTimelineRetimeResult {
        if (!result.confident || result.twoToTwoGroups == 0 || result.cues.size != target.size) {
            return result
        }

        var refinedCues: MutableList<AutoSyncRetimedCue>? = null

        for (groupIndex in result.groups.indices) {
            if ((groupIndex and 0xFF) == 0) cancellationCheck?.invoke()

            val group = result.groups[groupIndex]
            if (group.referenceCount != 2 || group.targetCount != 2) continue

            val firstReferenceIndex = group.referenceStartIndex
            val secondReferenceIndex = firstReferenceIndex + 1
            val firstTargetIndex = group.targetStartIndex
            val secondTargetIndex = firstTargetIndex + 1
            if (secondReferenceIndex !in reference.indices || secondTargetIndex !in target.indices) {
                continue
            }

            val firstReference = reference[firstReferenceIndex]
            val secondReference = reference[secondReferenceIndex]
            val firstTarget = target[firstTargetIndex]
            val secondTarget = target[secondTargetIndex]

            if (
                !isValidCueInterval(firstReference) ||
                !isValidCueInterval(secondReference) ||
                !isValidCueInterval(firstTarget) ||
                !isValidCueInterval(secondTarget)
            ) {
                continue
            }
            if (
                firstReference.startTimeMs in referenceEstimatedEndStartsMs ||
                secondReference.startTimeMs in referenceEstimatedEndStartsMs
            ) {
                continue
            }

            val referenceGapMs = secondReference.startTimeMs - firstReference.endTimeMs
            if (
                firstReference.startTimeMs >= secondReference.startTimeMs ||
                referenceGapMs < GROUPED_REPLY_MIN_REFERENCE_GAP_MS ||
                hasAmbiguousReferenceGap(
                    reference = reference,
                    firstReferenceIndex = firstReferenceIndex,
                    secondReferenceIndex = secondReferenceIndex,
                )
            ) {
                continue
            }

            if (
                firstTarget.startTimeMs >= secondTarget.startTimeMs ||
                firstTarget.endTimeMs > secondTarget.startTimeMs ||
                (firstTargetIndex > 0 &&
                    target[firstTargetIndex - 1].endTimeMs > firstTarget.startTimeMs) ||
                (secondTargetIndex < target.lastIndex &&
                    secondTarget.endTimeMs > target[secondTargetIndex + 1].startTimeMs)
            ) {
                continue
            }

            val currentCues = refinedCues ?: result.cues
            val firstOutput = currentCues[firstTargetIndex]
            val secondOutput = currentCues[secondTargetIndex]
            if (
                !isValidRetimedCue(firstOutput) ||
                !isValidRetimedCue(secondOutput) ||
                firstOutput.originalStartTimeMs != firstTarget.startTimeMs ||
                firstOutput.originalEndTimeMs != firstTarget.endTimeMs ||
                secondOutput.originalStartTimeMs != secondTarget.startTimeMs ||
                secondOutput.originalEndTimeMs != secondTarget.endTimeMs ||
                firstOutput.endTimeMs > secondOutput.startTimeMs
            ) {
                continue
            }

            if (
                abs(firstOutput.startTimeMs - firstReference.startTimeMs) >
                    GROUPED_REPLY_ENDPOINT_TOLERANCE_MS ||
                abs(secondOutput.endTimeMs - secondReference.endTimeMs) >
                    GROUPED_REPLY_ENDPOINT_TOLERANCE_MS
            ) {
                continue
            }

            val startAdjustmentMs = secondReference.startTimeMs - secondOutput.startTimeMs
            if (
                startAdjustmentMs < GROUPED_REPLY_MIN_EARLY_START_MS ||
                startAdjustmentMs > GROUPED_REPLY_MAX_START_ADJUSTMENT_MS
            ) {
                continue
            }

            val localGroupShiftMs =
                firstReference.startTimeMs -
                    transformTime(
                        firstTarget.startTimeMs,
                        result.alignmentScale,
                        result.alignmentInterceptMs,
                    )
            if (
                !hasSupportingSimpleGroup(
                    reference = reference,
                    target = target,
                    groups = result.groups,
                    groupIndex = groupIndex,
                    direction = -1,
                    expectedShiftMs = localGroupShiftMs,
                    scale = result.alignmentScale,
                    interceptMs = result.alignmentInterceptMs,
                ) ||
                !hasSupportingSimpleGroup(
                    reference = reference,
                    target = target,
                    groups = result.groups,
                    groupIndex = groupIndex,
                    direction = 1,
                    expectedShiftMs = localGroupShiftMs,
                    scale = result.alignmentScale,
                    interceptMs = result.alignmentInterceptMs,
                )
            ) {
                continue
            }

            val proposedStartMs = secondReference.startTimeMs
            if (
                proposedStartMs < firstOutput.endTimeMs ||
                proposedStartMs >= secondOutput.endTimeMs ||
                (secondTargetIndex < currentCues.lastIndex &&
                    proposedStartMs >= currentCues[secondTargetIndex + 1].startTimeMs)
            ) {
                continue
            }

            val currentDurationMs = secondOutput.endTimeMs - secondOutput.startTimeMs
            val proposedDurationMs = secondOutput.endTimeMs - proposedStartMs
            if (
                proposedDurationMs < GROUPED_REPLY_MIN_RESULT_DURATION_MS ||
                proposedDurationMs * 2L < currentDurationMs
            ) {
                continue
            }

            val output = refinedCues ?: result.cues.toMutableList().also { refinedCues = it }
            output[secondTargetIndex] = secondOutput.copy(startTimeMs = proposedStartMs)
        }

        val output = refinedCues ?: return result
        return result.copy(cues = output)
    }

    private fun hasSupportingSimpleGroup(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        groups: List<AutoSyncCueGroup>,
        groupIndex: Int,
        direction: Int,
        expectedShiftMs: Long,
        scale: Double,
        interceptMs: Double,
    ): Boolean {
        for (distance in 1..GROUPED_REPLY_CONTEXT_GROUP_RADIUS) {
            val neighborIndex = groupIndex + direction * distance
            if (neighborIndex !in groups.indices) break

            val neighbor = groups[neighborIndex]
            if (neighbor.referenceCount != 1 || neighbor.targetCount != 1) continue
            val referenceIndex = neighbor.referenceStartIndex
            val targetIndex = neighbor.targetStartIndex
            if (referenceIndex !in reference.indices || targetIndex !in target.indices) continue

            val referenceCue = reference[referenceIndex]
            val targetCue = target[targetIndex]
            if (!isValidCueInterval(referenceCue) || !isValidCueInterval(targetCue)) continue

            val neighborShiftMs =
                referenceCue.startTimeMs -
                    transformTime(targetCue.startTimeMs, scale, interceptMs)
            return abs(neighborShiftMs - expectedShiftMs) <=
                GROUPED_REPLY_CONTEXT_SHIFT_TOLERANCE_MS
        }
        return false
    }

    private fun hasAmbiguousReferenceGap(
        reference: List<SubtitleSyncCue>,
        firstReferenceIndex: Int,
        secondReferenceIndex: Int,
    ): Boolean {
        val first = reference[firstReferenceIndex]
        val second = reference[secondReferenceIndex]
        val gapStartMs = first.endTimeMs
        val gapEndMs = second.startTimeMs
        val startIndex = max(0, firstReferenceIndex - GROUPED_REPLY_REFERENCE_NEIGHBOR_RADIUS)
        val endIndex = min(
            reference.lastIndex,
            secondReferenceIndex + GROUPED_REPLY_REFERENCE_NEIGHBOR_RADIUS,
        )

        for (index in startIndex..endIndex) {
            if (index == firstReferenceIndex || index == secondReferenceIndex) continue
            val cue = reference[index]
            if (!isValidCueInterval(cue)) return true
            if (cue.startTimeMs < gapEndMs && cue.endTimeMs > gapStartMs) return true
            if (
                abs(cue.startTimeMs - second.startTimeMs) <=
                    GROUPED_REPLY_ENDPOINT_TOLERANCE_MS
            ) {
                return true
            }
        }
        return false
    }

    private fun isValidCueInterval(cue: SubtitleSyncCue): Boolean =
        cue.startTimeMs >= 0L && cue.endTimeMs > cue.startTimeMs

    private fun isValidRetimedCue(cue: AutoSyncRetimedCue): Boolean =
        cue.startTimeMs >= 0L && cue.endTimeMs > cue.startTimeMs

    /**
     * Replaces each group shift further than [LOCAL_SHIFT_MAX_DEVIATION_MS] from the median shift
     * of the groups within [LOCAL_SHIFT_RADIUS_GROUPS] on either side with that median.
     * O(groups) with a tiny window.
     */
    private fun rejectLocalShiftOutliers(shifts: LongArray): LongArray {
        val windowSize = LOCAL_SHIFT_RADIUS_GROUPS * 2 + 1
        if (shifts.size < windowSize) return shifts

        val window = LongArray(windowSize)
        return LongArray(shifts.size) { index ->
            val from = max(0, index - LOCAL_SHIFT_RADIUS_GROUPS)
            val to = min(shifts.lastIndex, index + LOCAL_SHIFT_RADIUS_GROUPS)
            val count = to - from + 1
            shifts.copyInto(window, 0, from, to + 1)
            window.sort(0, count)
            val median = window[count / 2]
            if (abs(shifts[index] - median) <= LOCAL_SHIFT_MAX_DEVIATION_MS) shifts[index] else median
        }
    }

    private fun transplantGroupTiming(
        group: AutoSyncCueGroup,
        output: MutableList<AutoSyncRetimedCue>,
        groupShiftMs: Long,
    ) {
        val targetStartIndex = group.targetStartIndex
        val targetEndIndex = group.targetStartIndex + group.targetCount - 1

        // The affine pass already preserves each external cue's duration (including FPS scaling).
        // Move the matched group as one unit toward its embedded reference start, but never
        // inherit a foreign-language or estimated reference end time.
        for (index in targetStartIndex..targetEndIndex) {
            val cue = output[index]
            val durationMs = (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L)
            val newStart = (cue.startTimeMs + groupShiftMs).coerceAtLeast(0L)
            output[index] = cue.copy(
                startTimeMs = newStart,
                endTimeMs = newStart + durationMs,
            )
        }
    }

    private fun groupCost(
        referenceStarts: DoubleArray,
        referenceEnds: DoubleArray,
        estimatedEndPrefix: IntArray,
        hasEstimatedEnds: Boolean,
        referenceIndex: Int,
        referenceCount: Int,
        transformedTargetStarts: DoubleArray,
        transformedTargetEnds: DoubleArray,
        targetIndex: Int,
        targetCount: Int,
    ): Double {
        val referenceStart = referenceStarts[referenceIndex]
        val referenceEnd = referenceEnds[referenceIndex + referenceCount - 1]
        val targetStart = transformedTargetStarts[targetIndex]
        val targetEnd = transformedTargetEnds[targetIndex + targetCount - 1]

        val referenceDuration = max(1.0, referenceEnd - referenceStart)
        val targetDuration = max(1.0, targetEnd - targetStart)
        val referenceMid = (referenceStart + referenceEnd) * 0.5
        val targetMid = (targetStart + targetEnd) * 0.5

        val startError = abs(referenceStart - targetStart) * INV_START_END_TOLERANCE_MS
        val referenceEndEstimated =
            hasEstimatedEnds &&
                estimatedEndPrefix[referenceIndex + referenceCount] !=
                estimatedEndPrefix[referenceIndex]
        return computeGroupCost(
            startError = startError,
            refEnd = referenceEnd,
            refDur = referenceDuration,
            refMid = referenceMid,
            tgtEnd = targetEnd,
            tgtDur = targetDuration,
            tgtMid = targetMid,
            referenceEndEstimated = referenceEndEstimated,
        )
    }

    private fun lowerBoundReference(reference: List<SubtitleSyncCue>, timeMs: Long): Int {
        var low = 0
        var high = reference.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (reference[mid].startTimeMs < timeMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low.coerceIn(0, reference.size)
    }

    private fun transformTime(timeMs: Long, scale: Double, interceptMs: Double): Long =
        transformTimeDouble(timeMs, scale, interceptMs).roundToLong().coerceAtLeast(0L)

    private fun transformTimeDouble(timeMs: Long, scale: Double, interceptMs: Double): Double =
        timeMs.toDouble() * scale + interceptMs

    private fun round3(value: Double): Double = (value * 1_000.0).roundToLong() / 1_000.0

    private fun longestFalseRun(values: BooleanArray): Int {
        var longest = 0
        var current = 0
        for (value in values) {
            if (value) {
                current = 0
            } else {
                current++
                longest = max(longest, current)
            }
        }
        return longest
    }

    private val EMPTY_INT_ARRAY = IntArray(0)

    private const val DP_CODE_UNREACHABLE: Byte = 0
    private const val DP_CODE_START: Byte = 1
    private const val DP_CODE_SKIP_REF: Byte = 2
    private const val DP_CODE_SKIP_TGT: Byte = 3
    private const val DP_CODE_GROUP_1_1: Byte = 4
    private const val DP_CODE_GROUP_1_2: Byte = 5
    private const val DP_CODE_GROUP_2_1: Byte = 6
    private const val DP_CODE_GROUP_1_3: Byte = 7
    private const val DP_CODE_GROUP_3_1: Byte = 8
    private const val DP_CODE_GROUP_2_2: Byte = 9
}


internal data class AutoSyncRetimePhaseTimings(
    val path: String,
    val prepareActivityMs: Long,
    val delayValidationMs: Long,
    val activitySearchMs: Long,
    val dpMs: Long,
    val validationMs: Long,
    val totalMs: Long,
)
internal data class AutoSyncCueGroup(
    val referenceStartIndex: Int,
    val referenceCount: Int,
    val targetStartIndex: Int,
    val targetCount: Int,
    val cost: Double,
)

internal data class AutoSyncRetimedCue(
    val originalStartTimeMs: Long,
    val originalEndTimeMs: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

internal data class AutoSyncDelayOnlyAlignment(
    val offsetMs: Double,
    val score: Double,
    val margin: Double,
    val segmentsPassed: Int,
    val stableSegmentMarginOverride: Boolean = false,
)

internal data class AutoSyncTimelineRetimeResult(
    val cues: List<AutoSyncRetimedCue>,
    val groups: List<AutoSyncCueGroup>,
    val targetCoverage: Double,
    val referenceCoverage: Double,
    val skippedTargetCues: Int,
    val skippedReferenceCues: Int,
    val longestTargetSkipRun: Int,
    val averageGroupCost: Double,
    val oneToOneGroups: Int,
    val oneToTwoGroups: Int,
    val twoToOneGroups: Int,
    val oneToThreeGroups: Int,
    val threeToOneGroups: Int,
    val twoToTwoGroups: Int,
    val confident: Boolean,
    val alignmentSource: String = "provided",
    val alignmentScale: Double = 1.0,
    val alignmentInterceptMs: Double = 0.0,
    val activityScore: Double = 0.0,
    val activityMargin: Double = 0.0,
    val coverageSegmentsPassed: Int = 0,
    val simpleGroupRatio: Double = 0.0,
    val localizedMismatchIgnored: Boolean = false,
    /** Median |offset| matched groups still needed after the alignment transform. */
    val medianGroupResidualMs: Double = 0.0,
    /** Failed confidence gates; null when confident. */
    val rejectReason: String? = null,
)

/**
 * Largest correction the alignment transform applies anywhere in the subtitle's span. The
 * transform is linear, so the extremes are at the first and last cue.
 */
internal fun AutoSyncTimelineRetimeResult.maxAlignmentShiftMs(): Double {
    fun shiftAt(timeMs: Long): Double =
        abs((alignmentScale - 1.0) * timeMs + alignmentInterceptMs)
    val first = cues.firstOrNull()?.originalStartTimeMs ?: return abs(alignmentInterceptMs)
    return max(shiftAt(first), shiftAt(cues.last().originalStartTimeMs))
}

private const val TRANSFORM_DISAGREEMENT_MS = 300.0

/**
 * Arbitrates two confident alignments of the same target made against different references.
 * When their transforms disagree by more than [TRANSFORM_DISAGREEMENT_MS] anywhere on the
 * target, at most one is right, and the one whose matched groups fit tighter wins. Returns null
 * when the transforms agree (or fit equally), leaving the normal quality ranking in charge.
 */
internal fun preferTighterFitOnDisagreement(
    candidate: AutoSyncTimelineRetimeResult,
    current: AutoSyncTimelineRetimeResult,
): Boolean? {
    if (!candidate.confident || !current.confident) return null
    val firstStartMs = candidate.cues.firstOrNull()?.originalStartTimeMs ?: return null
    val lastStartMs = candidate.cues.last().originalStartTimeMs

    fun gapAt(timeMs: Long): Double = abs(
        (candidate.alignmentScale - current.alignmentScale) * timeMs +
            candidate.alignmentInterceptMs - current.alignmentInterceptMs,
    )
    if (max(gapAt(firstStartMs), gapAt(lastStartMs)) <= TRANSFORM_DISAGREEMENT_MS) return null
    if (candidate.medianGroupResidualMs == current.medianGroupResidualMs) return null
    return candidate.medianGroupResidualMs < current.medianGroupResidualMs
}
