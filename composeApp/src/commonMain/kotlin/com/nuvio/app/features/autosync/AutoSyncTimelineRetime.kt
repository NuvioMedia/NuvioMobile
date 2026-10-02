package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.ceil
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

    private const val SKIP_REFERENCE_COST = 1.35
    private const val SKIP_TARGET_COST = 1.85
    private const val GROUP_COMPLEXITY_COST = 0.10

    private const val START_END_TOLERANCE_MS = 1_250.0
    private const val MIDPOINT_TOLERANCE_MS = 1_500.0
    private const val DURATION_TOLERANCE_MS = 2_000.0
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
    private const val ACTIVITY_FINE_RADIUS_MS = 750L
    // Dense dialogue saturates 500 ms coarse bins, so neighbouring offsets can score within a
    // hair of each other and the coarse argmax lands on a plateau edge. Fine refinement then
    // spans the whole contiguous plateau (capped) instead of only the argmax's own radius.
    private const val ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON = 0.01
    private const val ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS = 3_000L
    // Coverage scoring stays flat across such a plateau even at 100 ms bins. Fine offsets within
    // this score of the fine best are separated by how closely target cue starts land on
    // reference cue starts, which is sharp to a single bin.
    private const val ACTIVITY_FINE_TIE_SCORE_EPSILON = 0.005
    private const val ACTIVITY_ONSET_MATCH_TOLERANCE_MS = 250L
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
    private const val DELAY_ONLY_MIN_SCORE = 0.78
    private const val DELAY_ONLY_MIN_MARGIN = 0.02
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

    private val groupShapes = arrayOf(
        GroupShape(referenceCount = 1, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 2),
        GroupShape(referenceCount = 2, targetCount = 1),
        GroupShape(referenceCount = 1, targetCount = 3),
        GroupShape(referenceCount = 3, targetCount = 1),
        GroupShape(referenceCount = 2, targetCount = 2),
    )

    internal fun normalizeExternalTimeline(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        val sorted = cues.sortedBy { it.startTimeMs }
        val seen = HashSet<ExternalCueKey>()
        val normalized = ArrayList<SubtitleSyncCue>(sorted.size)
        for (cue in sorted) {
            val key = ExternalCueKey(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.endTimeMs,
                text = cue.text,
            )
            if (seen.add(key)) normalized += cue
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
        val starts = LongArray(cues.size) { index -> cues[index].startTimeMs.coerceAtLeast(0L) }
        starts.sort()
        return PreparedActivity(coarse = coarse, fine = fine, starts = starts)
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
                    reportTimings("fast-delay")
                    return validatedFast.withValidatedDelayOnlyCues(
                        target = target,
                        alignment = fastDelayOnly,
                    )
                }
            }
        }

        // Full V2 fallback remains authoritative whenever the proven-delay fast path is absent
        // or fails structural validation.
        val activityMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        val alignment = discoverActivityAlignment(
            reference = reference,
            target = target,
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            precomputedUnitEvidence = precomputedDelayOnlyEvidence,
            cancellationCheck = cancellationCheck,
        ) ?: return null
        activitySearchMs += elapsedMs(activityMark)

        val delayMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        val delayOnly = if (abs(alignment.scale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE) {
            precomputedDelayOnly ?: findDelayOnlyAlignmentPrepared(
                referenceActivity = referenceActivity,
                targetActivity = targetActivity,
                targetSize = target.size,
                allowAmbiguousMargin = allowAmbiguousDelayOnlyMargin,
                seed = alignment.delayOnlySeed,
                allowStableSegmentMarginOverride = !allowAmbiguousDelayOnlyMargin,
                cancellationCheck = cancellationCheck,
            )
        } else {
            null
        }
        delayValidationMs += elapsedMs(delayMark)

        val candidateScale = if (delayOnly != null) 1.0 else alignment.scale
        val candidateInterceptMs = delayOnly?.offsetMs ?: alignment.interceptMs
        val candidateActivityScore = delayOnly?.score ?: alignment.score
        val candidateActivityMargin = delayOnly?.margin ?: alignment.margin

        val dpMark = if (timingEnabled) TimeSource.Monotonic.markNow() else null
        val result = retimeWithSeed(
            reference = reference,
            target = target,
            coarseScale = candidateScale,
            coarseInterceptMs = candidateInterceptMs,
            referenceEstimatedEndStartsMs = referenceEstimatedEndStartsMs,
            cancellationCheck = cancellationCheck,
        ) ?: return null
        dpMs += elapsedMs(dpMark)

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
            allowAmbiguousDelayOnlyMargin =
                allowAmbiguousDelayOnlyMargin ||
                    (delayOnly?.stableSegmentMarginOverride == true),
        )
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
            finalized.withValidatedDelayOnlyCues(
                target = target,
                alignment = delayOnly,
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

        val rejectReasons = buildList {
            if (!skipRunOnlyFailure) result.rejectReason?.let(::add)
            if (candidateActivityScore < requiredActivityScore) {
                add("activityScore=${round3(candidateActivityScore)}<$requiredActivityScore")
            }
            if (!activityMarginAccepted) {
                add("activityMargin=${round3(candidateActivityMargin)}<$requiredActivityMargin")
            }
            if (coverageSegments < requiredCoverageSegments) {
                add("coverageSegments=$coverageSegments<$requiredCoverageSegments")
            }
            if (result.targetCoverage < DISCOVERED_MIN_TARGET_COVERAGE) {
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
            localizedMismatchIgnored = confirmed && skipRunOnlyFailure,
            rejectReason = rejectReasons.joinToString(",").ifEmpty { null },
            alignmentSource = if (delayOnly) "delay-only-validated" else "activity-correlation",
            alignmentScale = candidateScale,
            alignmentInterceptMs = candidateInterceptMs,
            activityScore = candidateActivityScore,
            activityMargin = candidateActivityMargin,
            coverageSegmentsPassed = coverageSegments,
            simpleGroupRatio = simpleRatio,
        )
    }

    private fun retimeWithSeed(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        coarseScale: Double,
        coarseInterceptMs: Double,
        referenceEstimatedEndStartsMs: Set<Long>,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncTimelineRetimeResult? {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return null
        if (!coarseScale.isFinite() || coarseScale !in 0.85..1.15) return null
        if (!coarseInterceptMs.isFinite()) return null

        // These values are immutable for one seeded DP. Preparing them once removes repeated
        // affine transforms, Long->Double conversions and boxed Set lookups from the hot
        // group-shape loop without changing any scoring arithmetic or iteration order.
        val referenceStarts = DoubleArray(reference.size)
        val referenceEnds = DoubleArray(reference.size)
        val estimatedEndPrefix = IntArray(reference.size + 1)
        for (index in reference.indices) {
            if ((index and 0xFF) == 0) cancellationCheck?.invoke()
            val cue = reference[index]
            referenceStarts[index] = cue.startTimeMs.toDouble()
            referenceEnds[index] = cue.endTimeMs.toDouble()
            estimatedEndPrefix[index + 1] =
                estimatedEndPrefix[index] +
                    if (
                        referenceEstimatedEndStartsMs.isNotEmpty() &&
                        cue.startTimeMs in referenceEstimatedEndStartsMs
                    ) {
                        1
                    } else {
                        0
                    }
        }

        val transformedTargetStarts = DoubleArray(target.size)
        val transformedTargetEnds = DoubleArray(target.size)
        for (index in target.indices) {
            if ((index and 0xFF) == 0) cancellationCheck?.invoke()
            val cue = target[index]
            transformedTargetStarts[index] =
                transformTimeDouble(cue.startTimeMs, coarseScale, coarseInterceptMs)
            transformedTargetEnds[index] =
                transformTimeDouble(cue.endTimeMs, coarseScale, coarseInterceptMs)
        }

        val centers = IntArray(target.size + 1)
        for (targetIndex in target.indices) {
            val predictedStart =
                transformedTargetStarts[targetIndex].roundToLong().coerceAtLeast(0L)
            centers[targetIndex] = lowerBoundReference(reference, predictedStart)
        }
        val predictedEnd =
            transformedTargetEnds[target.lastIndex].roundToLong().coerceAtLeast(0L)
        centers[target.size] = lowerBoundReference(reference, predictedEnd)

        val rows = BandedDpRows(
            targetSize = target.size,
            referenceSize = reference.size,
            centers = centers,
        )
        // Preserve the legacy HashMap final-row iteration/tie behavior exactly while moving
        // the expensive full DP matrix to compact primitive storage.
        val finalRowCosts = HashMap<Int, Double>()
        rows.setStart()

        for (targetIndex in 0..target.size) {
            cancellationCheck?.invoke()
            var referenceIndex = rows.minReachableReferenceIndex(targetIndex)
            if (referenceIndex < 0) continue

            val rowMax = rowMaxReferenceIndex(
                targetIndex = targetIndex,
                targetSize = target.size,
                referenceSize = reference.size,
                center = centers[targetIndex],
            )

            while (referenceIndex <= rowMax) {
                val cellIndex = rows.indexOf(targetIndex, referenceIndex)
                if (cellIndex >= 0 && rows.isReachable(cellIndex)) {
                    val cellCost = rows.costs[cellIndex]

                    if (referenceIndex < reference.size && referenceIndex + 1 <= rowMax) {
                        relax(
                            rows = rows,
                            finalRowCosts = finalRowCosts,
                            finalTargetIndex = target.size,
                            toTargetIndex = targetIndex,
                            toReferenceIndex = referenceIndex + 1,
                            candidateCost = cellCost + SKIP_REFERENCE_COST,
                            previousReferenceIndex = referenceIndex,
                            previousTargetIndex = targetIndex,
                            referenceCount = 1,
                            targetCount = 0,
                            step = Step.SKIP_REFERENCE,
                        )
                    }

                    if (targetIndex < target.size &&
                        isWithinBand(referenceIndex, targetIndex + 1, centers, reference.size)
                    ) {
                        relax(
                            rows = rows,
                            finalRowCosts = finalRowCosts,
                            finalTargetIndex = target.size,
                            toTargetIndex = targetIndex + 1,
                            toReferenceIndex = referenceIndex,
                            candidateCost = cellCost + SKIP_TARGET_COST,
                            previousReferenceIndex = referenceIndex,
                            previousTargetIndex = targetIndex,
                            referenceCount = 0,
                            targetCount = 1,
                            step = Step.SKIP_TARGET,
                        )
                    }

                    for (shape in groupShapes) {
                        val nextReferenceIndex = referenceIndex + shape.referenceCount
                        val nextTargetIndex = targetIndex + shape.targetCount
                        if (nextReferenceIndex > reference.size || nextTargetIndex > target.size) continue
                        if (!isWithinBand(nextReferenceIndex, nextTargetIndex, centers, reference.size)) continue

                        val groupCost = groupCost(
                            referenceStarts = referenceStarts,
                            referenceEnds = referenceEnds,
                            estimatedEndPrefix = estimatedEndPrefix,
                            referenceIndex = referenceIndex,
                            referenceCount = shape.referenceCount,
                            transformedTargetStarts = transformedTargetStarts,
                            transformedTargetEnds = transformedTargetEnds,
                            targetIndex = targetIndex,
                            targetCount = shape.targetCount,
                        )
                        if (!groupCost.isFinite() || groupCost > MAX_GROUP_COST) continue

                        relax(
                            rows = rows,
                            finalRowCosts = finalRowCosts,
                            finalTargetIndex = target.size,
                            toTargetIndex = nextTargetIndex,
                            toReferenceIndex = nextReferenceIndex,
                            candidateCost = cellCost + groupCost +
                                GROUP_COMPLEXITY_COST *
                                (shape.referenceCount + shape.targetCount - 2),
                            previousReferenceIndex = referenceIndex,
                            previousTargetIndex = targetIndex,
                            referenceCount = shape.referenceCount,
                            targetCount = shape.targetCount,
                            step = Step.GROUP,
                            localGroupCost = groupCost,
                        )
                    }
                }
                referenceIndex++
            }
        }

        val finalEntry = finalRowCosts
            .entries
            .minByOrNull { it.value }
            ?: return null

        val steps = backtrack(
            rows = rows,
            finalReferenceIndex = finalEntry.key,
            finalTargetIndex = target.size,
        ) ?: return null

        val groups = steps
            .filter { it.step == Step.GROUP }
            .map { step ->
                AutoSyncCueGroup(
                    referenceStartIndex = step.previousReferenceIndex,
                    referenceCount = step.referenceCount,
                    targetStartIndex = step.previousTargetIndex,
                    targetCount = step.targetCount,
                    cost = step.localGroupCost,
                )
            }

        if (groups.isEmpty()) return null

        val matchedTarget = BooleanArray(target.size)
        val matchedReference = BooleanArray(reference.size)
        val retimed = target.map { cue ->
            AutoSyncRetimedCue(
                originalStartTimeMs = cue.startTimeMs,
                originalEndTimeMs = cue.endTimeMs,
                startTimeMs = transformTime(cue.startTimeMs, coarseScale, coarseInterceptMs),
                endTimeMs = transformTime(cue.endTimeMs, coarseScale, coarseInterceptMs)
                    .coerceAtLeast(transformTime(cue.startTimeMs, coarseScale, coarseInterceptMs) + 1L),
            )
        }.toMutableList()

        // Target ranges of groups are disjoint, so every shift can be read before any is applied.
        val groupShifts = rejectLocalShiftOutliers(
            LongArray(groups.size) { groupIndex ->
                val group = groups[groupIndex]
                reference[group.referenceStartIndex].startTimeMs -
                    retimed[group.targetStartIndex].startTimeMs
            },
        )
        groups.forEachIndexed { groupIndex, group ->
            for (index in group.targetStartIndex until group.targetStartIndex + group.targetCount) {
                matchedTarget[index] = true
            }
            for (index in group.referenceStartIndex until group.referenceStartIndex + group.referenceCount) {
                matchedReference[index] = true
            }
            transplantGroupTiming(group, retimed, groupShifts[groupIndex])
        }

        // Keep the output monotonic even when malformed source cues overlap backwards.
        for (index in retimed.indices) {
            val previousStart = retimed.getOrNull(index - 1)?.startTimeMs ?: Long.MIN_VALUE
            val cue = retimed[index]
            val start = max(cue.startTimeMs, previousStart)
            val end = max(cue.endTimeMs, start + 1L)
            if (start != cue.startTimeMs || end != cue.endTimeMs) {
                retimed[index] = cue.copy(startTimeMs = start, endTimeMs = end)
            }
        }

        // Local group anchoring can move neighbouring groups by slightly different amounts.
        // Preserve overlaps that already existed in the external subtitle, but never create a
        // new overlap between two cues that were sequential before AutoSync.
        for (index in 0 until retimed.lastIndex) {
            val original = target[index]
            val originalNext = target[index + 1]
            if (original.endTimeMs > originalNext.startTimeMs) continue

            val current = retimed[index]
            val next = retimed[index + 1]
            if (current.endTimeMs > next.startTimeMs && next.startTimeMs > current.startTimeMs) {
                retimed[index] = current.copy(endTimeMs = next.startTimeMs)
            }
        }

        val matchedTargetCount = matchedTarget.count { it }
        val matchedReferenceCount = matchedReference.count { it }
        val targetCoverage = matchedTargetCount.toDouble() / target.size
        val referenceCoverage = matchedReferenceCount.toDouble() / reference.size
        val skippedTarget = target.size - matchedTargetCount
        val skippedReference = reference.size - matchedReferenceCount
        val longestTargetSkipRun = longestFalseRun(matchedTarget)
        val averageGroupCost = groups.map { it.cost }.average()

        // Offset each matched group still needs after the seed transform.
        val groupResiduals = DoubleArray(groups.size) { groupIndex ->
            val group = groups[groupIndex]
            abs(
                referenceStarts[group.referenceStartIndex] -
                    transformedTargetStarts[group.targetStartIndex],
            )
        }
        groupResiduals.sort()

        val minMatchedTarget = min(MIN_MATCHED_TARGET_CUES, target.size)
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

        val shapeCounts = groups.groupingBy { "${it.referenceCount}:${it.targetCount}" }.eachCount()

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = groups,
            targetCoverage = targetCoverage,
            referenceCoverage = referenceCoverage,
            skippedTargetCues = skippedTarget,
            skippedReferenceCues = skippedReference,
            longestTargetSkipRun = longestTargetSkipRun,
            averageGroupCost = averageGroupCost,
            oneToOneGroups = shapeCounts["1:1"] ?: 0,
            oneToTwoGroups = shapeCounts["1:2"] ?: 0,
            twoToOneGroups = shapeCounts["2:1"] ?: 0,
            oneToThreeGroups = shapeCounts["1:3"] ?: 0,
            threeToOneGroups = shapeCounts["3:1"] ?: 0,
            twoToTwoGroups = shapeCounts["2:2"] ?: 0,
            confident = confident,
            medianGroupResidualMs = groupResiduals[groupResiduals.size / 2],
            rejectReason = rejectReasons.joinToString(",").ifEmpty { null },
        )
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
        val scores = DoubleArray(offsetRange.last - offsetRange.first + 1) { Double.NaN }
        val candidates = ArrayList<ActivityCandidate>(scores.size)

        for (offsetBins in offsetRange) {
            if ((offsetBins - offsetRange.first) % 64 == 0) cancellationCheck?.invoke()
            val score = scoreActivityOffset(
                referenceActivity.coarse,
                targetActivity.coarse,
                offsetBins,
            ) ?: continue
            scores[offsetBins - offsetRange.first] = score
            candidates += ActivityCandidate(
                scale = 1.0,
                interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                score = score,
            )
        }

        val seed = buildDelayOnlySearchSeed(candidates) ?: return null
        val refined = refineDelayOnlySearchSeed(
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            seed = seed,
            cancellationCheck = cancellationCheck,
        )
        return DelayOnlySearchEvidence(
            coarseScores = scores,
            firstOffsetBins = offsetRange.first,
            seed = refined,
        )
    }

    private fun refineDelayOnlySearchSeed(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        seed: DelayOnlySearchSeed,
        cancellationCheck: (() -> Unit)? = null,
    ): DelayOnlySearchSeed {
        if (seed.fine != null) return seed
        return seed.copy(
            fine = fineActivityBest(
                referenceFine = referenceActivity.fine,
                targetFine = targetActivity.fine,
                referenceStarts = referenceActivity.starts,
                targetStarts = targetActivity.starts,
                scale = 1.0,
                plateauStartMs = seed.plateauStartMs,
                plateauEndMs = seed.plateauEndMs,
                cancellationCheck = cancellationCheck,
            ),
        )
    }

    internal fun findDelayOnlyAlignmentPrepared(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        targetSize: Int,
        allowAmbiguousMargin: Boolean = false,
        precomputedEvidence: DelayOnlySearchEvidence? = null,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncDelayOnlyAlignment? =
        findDelayOnlyAlignmentPrepared(
            referenceActivity = referenceActivity,
            targetActivity = targetActivity,
            targetSize = targetSize,
            allowAmbiguousMargin = allowAmbiguousMargin,
            seed = precomputedEvidence?.seed,
            cancellationCheck = cancellationCheck,
        )

    private fun findDelayOnlyAlignmentPrepared(
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        targetSize: Int,
        allowAmbiguousMargin: Boolean,
        seed: DelayOnlySearchSeed?,
        allowStableSegmentMarginOverride: Boolean = false,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncDelayOnlyAlignment? {
        val referenceCoarse = referenceActivity.coarse
        val targetCoarse = targetActivity.coarse

        val coarseSeed = seed
            ?: prepareDelayOnlySearchEvidence(
                referenceActivity = referenceActivity,
                targetActivity = targetActivity,
                cancellationCheck = cancellationCheck,
            )?.seed
            ?: return null

        val coarse = coarseSeed.coarse
        val margin = coarseSeed.margin
        val marginAccepted =
            allowAmbiguousMargin || margin >= DELAY_ONLY_MIN_MARGIN
        if (!marginAccepted && !allowStableSegmentMarginOverride) return null

        val referenceFine = referenceActivity.fine
        val targetFine = targetActivity.fine
        val best = coarseSeed.fine
            ?: fineActivityBest(
                referenceFine = referenceFine,
                targetFine = targetFine,
                referenceStarts = referenceActivity.starts,
                targetStarts = targetActivity.starts,
                scale = 1.0,
                plateauStartMs = coarseSeed.plateauStartMs,
                plateauEndMs = coarseSeed.plateauEndMs,
                cancellationCheck = cancellationCheck,
            )
            ?: coarse

        if (best.score < DELAY_ONLY_MIN_SCORE) return null

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

            // The segment agrees when an offset within maxDelta of the global one scores as
            // well as the segment's best, up to the coverage tie epsilon: on a flat coverage
            // plateau the segment argmax is an arbitrary edge, not evidence of drift.
            var segmentBestScore = Double.NEGATIVE_INFINITY
            var segmentNearScore = Double.NEGATIVE_INFINITY
            var hasScore = false
            for (delta in -localRadiusBins..localRadiusBins) {
                val candidateOffsetBins = globalOffsetBins + delta
                val score = scoreActivityOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = candidateOffsetBins,
                    segment = segment,
                ) ?: continue
                hasScore = true
                segmentBestScore = max(segmentBestScore, score)
                if (abs(delta) <= maxDeltaBins) segmentNearScore = max(segmentNearScore, score)
            }

            if (!hasScore) continue
            availableSegments++
            if (
                segmentBestScore >= DELAY_ONLY_MIN_SEGMENT_SCORE &&
                segmentNearScore >= segmentBestScore - ACTIVITY_FINE_TIE_SCORE_EPSILON
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
            stableSegmentMarginOverride =
                !marginAccepted &&
                    allowStableSegmentMarginOverride &&
                    !allowAmbiguousMargin,
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
        target: List<SubtitleSyncCue>,
        alignment: AutoSyncDelayOnlyAlignment,
    ): AutoSyncTimelineRetimeResult =
        copy(
            cues = buildUniformDelayCues(
                target = target,
                offsetMs = alignment.offsetMs.roundToLong(),
            ),
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
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        val visibleTarget =
            target.prefix[segmentEnd + 1] - target.prefix[segmentStart]
        if (visibleTarget <= 0) return null
        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = segmentStart,
            sourceEnd = segmentEnd,
        )
        return intersection.toDouble() / visibleTarget.toDouble()
    }

    private fun scoreActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        val visibleTarget =
            target.prefix[segmentEnd + 1] - target.prefix[segmentStart]
        if (visibleTarget <= 0) return null
        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = segmentStart,
            sourceEnd = segmentEnd,
        )

        val referenceWindowStart = max(0, segmentStart + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, segmentEnd + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        return precision * 0.72 + recall * 0.28
    }

    private fun discoverActivityAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceActivity: PreparedActivity,
        targetActivity: PreparedActivity,
        precomputedUnitEvidence: DelayOnlySearchEvidence? = null,
        cancellationCheck: (() -> Unit)? = null,
    ): ActivityAlignment? {
        if (reference.size < MIN_CUES || target.size < MIN_CUES) return null

        val referenceCoarse = referenceActivity.coarse
        val coarseCandidates = mutableListOf<ActivityCandidate>()
        val unitScaleCandidates = mutableListOf<ActivityCandidate>()

        for (scale in activityScaleCandidates(reference, target)) {
            cancellationCheck?.invoke()

            if (scale == 1.0 && precomputedUnitEvidence != null) {
                val evidence = precomputedUnitEvidence
                for (evidenceIndex in evidence.coarseScores.indices) {
                    if (evidenceIndex % 64 == 0) cancellationCheck?.invoke()
                    val score = evidence.coarseScores[evidenceIndex]
                    if (score.isNaN()) continue
                    val offsetBins = evidence.firstOffsetBins + evidenceIndex
                    val candidate = ActivityCandidate(
                        scale = 1.0,
                        interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                        score = score,
                    )
                    coarseCandidates += candidate
                    unitScaleCandidates += candidate
                }
                continue
            }

            val targetCoarse = if (scale == 1.0) {
                targetActivity.coarse
            } else {
                buildActivityTimeline(target, scale, ACTIVITY_COARSE_BIN_MS) ?: continue
            }
            val offsetRange = coarseOffsetRange(referenceCoarse, targetCoarse)
            for (offsetBins in offsetRange) {
                if ((offsetBins - offsetRange.first) % 64 == 0) cancellationCheck?.invoke()
                val score = scoreActivityOffset(referenceCoarse, targetCoarse, offsetBins)
                    ?: continue
                val candidate = ActivityCandidate(
                    scale = scale,
                    interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                    score = score,
                )
                coarseCandidates += candidate
                if (scale == 1.0) unitScaleCandidates += candidate
            }
        }

        val coarseBest = coarseCandidates.maxByOrNull { it.score } ?: return null
        val targetEndMs = target.maxOf { it.endTimeMs }.toDouble()
        val secondDistinct = coarseCandidates.asSequence()
            .filter { candidate ->
                candidate !== coarseBest &&
                    isDistinctActivityTransform(coarseBest, candidate, targetEndMs)
            }
            .maxByOrNull { it.score }
        val margin = coarseBest.score - (secondDistinct?.score ?: 0.0)

        val referenceFine = referenceActivity.fine
        val targetFine = if (coarseBest.scale == 1.0) {
            targetActivity.fine
        } else {
            buildActivityTimeline(target, coarseBest.scale, ACTIVITY_FINE_BIN_MS)
                ?: return null
        }

        val plateau = coarsePlateauMs(coarseCandidates, coarseBest)
        val fineBest = fineActivityBest(
            referenceFine = referenceFine,
            targetFine = targetFine,
            referenceStarts = referenceActivity.starts,
            targetStarts = targetActivity.starts,
            scale = coarseBest.scale,
            plateauStartMs = plateau.first,
            plateauEndMs = plateau.last,
            cancellationCheck = cancellationCheck,
        )

        val best = fineBest ?: coarseBest
        val unitSeed = precomputedUnitEvidence?.seed?.let { seed ->
            // Preserve the original branch exactly: a fine unit-scale seed was carried forward
            // only when unit scale itself won the global coarse search.
            if (coarseBest.scale == 1.0) seed else seed.copy(fine = null)
        } ?: buildDelayOnlySearchSeed(unitScaleCandidates)?.let { seed ->
            if (coarseBest.scale == 1.0) seed.copy(fine = fineBest) else seed
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
     * Contiguous coarse offsets around [best] (same scale) scoring within
     * [ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON] of it, capped at
     * [ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS] on each side. [candidates] of one scale are in
     * ascending offset order, as every coarse sweep produces them.
     */
    private fun coarsePlateauMs(
        candidates: List<ActivityCandidate>,
        best: ActivityCandidate,
    ): LongRange {
        val bestIndex = candidates.indexOfFirst { it === best }
        if (bestIndex < 0) return best.interceptMs..best.interceptMs
        val floor = best.score - ACTIVITY_COARSE_PLATEAU_SCORE_EPSILON

        fun extent(step: Int): Long {
            var edge = best.interceptMs
            var index = bestIndex + step
            while (index in candidates.indices) {
                val candidate = candidates[index]
                if (
                    candidate.scale != best.scale ||
                    candidate.interceptMs != edge + step * ACTIVITY_COARSE_BIN_MS ||
                    candidate.score < floor ||
                    abs(candidate.interceptMs - best.interceptMs) >
                    ACTIVITY_COARSE_PLATEAU_MAX_EXTENT_MS
                ) {
                    break
                }
                edge = candidate.interceptMs
                index += step
            }
            return edge
        }

        return extent(-1)..extent(1)
    }

    /**
     * Best fine-bin offset from the plateau start minus the fine radius to its end plus it.
     * Offsets whose coverage score ties the best within [ACTIVITY_FINE_TIE_SCORE_EPSILON] are
     * ranked by cue-start agreement, then by score.
     */
    private fun fineActivityBest(
        referenceFine: ActivityTimeline,
        targetFine: ActivityTimeline,
        referenceStarts: LongArray,
        targetStarts: LongArray,
        scale: Double,
        plateauStartMs: Long,
        plateauEndMs: Long,
        cancellationCheck: (() -> Unit)?,
    ): ActivityCandidate? {
        val candidates = ArrayList<ActivityCandidate>()
        var fineBest: ActivityCandidate? = null
        var offsetMs = plateauStartMs - ACTIVITY_FINE_RADIUS_MS
        while (offsetMs <= plateauEndMs + ACTIVITY_FINE_RADIUS_MS) {
            cancellationCheck?.invoke()
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                val candidate = ActivityCandidate(
                    scale = scale,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
                candidates += candidate
                val current = fineBest
                if (current == null || candidate.score > current.score) fineBest = candidate
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }
        val best = fineBest ?: return null

        var chosen = best
        var chosenOnsets = -1.0
        for (candidate in candidates) {
            if (candidate.score < best.score - ACTIVITY_FINE_TIE_SCORE_EPSILON) continue
            cancellationCheck?.invoke()
            val onsets = onsetAgreement(
                referenceStarts = referenceStarts,
                targetStarts = targetStarts,
                scale = scale,
                interceptMs = candidate.interceptMs,
            )
            if (
                onsets > chosenOnsets ||
                (onsets == chosenOnsets && candidate.score > chosen.score)
            ) {
                chosen = candidate
                chosenOnsets = onsets
            }
        }
        return chosen
    }

    /**
     * How well target cue starts, mapped by [scale] and [interceptMs], land on reference starts:
     * each contributes 1 at an exact hit, falling linearly to 0 at
     * [ACTIVITY_ONSET_MATCH_TOLERANCE_MS] from the nearest reference start.
     */
    private fun onsetAgreement(
        referenceStarts: LongArray,
        targetStarts: LongArray,
        scale: Double,
        interceptMs: Long,
    ): Double {
        if (referenceStarts.isEmpty()) return 0.0
        val tolerance = ACTIVITY_ONSET_MATCH_TOLERANCE_MS
        var agreement = 0.0
        var cursor = 0
        for (start in targetStarts) {
            val mapped = (start * scale).roundToLong() + interceptMs
            while (cursor < referenceStarts.size && referenceStarts[cursor] < mapped - tolerance) {
                cursor++
            }
            if (cursor == referenceStarts.size) break
            var nearest = Long.MAX_VALUE
            var index = cursor
            while (index < referenceStarts.size && referenceStarts[index] <= mapped + tolerance) {
                nearest = min(nearest, abs(referenceStarts[index] - mapped))
                index++
            }
            if (nearest <= tolerance) {
                agreement += (tolerance - nearest).toDouble() / tolerance.toDouble()
            }
        }
        return agreement
    }

    private fun buildDelayOnlySearchSeed(
        candidates: List<ActivityCandidate>,
    ): DelayOnlySearchSeed? {
        val coarse = candidates.maxByOrNull { it.score } ?: return null
        val secondDistinct = candidates.asSequence()
            .filter { candidate ->
                abs(candidate.interceptMs - coarse.interceptMs) >=
                    DELAY_ONLY_DISTINCT_OFFSET_MS
            }
            .maxByOrNull { it.score }
        val plateau = coarsePlateauMs(candidates, coarse)
        return DelayOnlySearchSeed(
            coarse = coarse,
            margin = coarse.score - (secondDistinct?.score ?: 0.0),
            fine = null,
            plateauStartMs = plateau.first,
            plateauEndMs = plateau.last,
        )
    }

    private fun buildActivityTimeline(
        cues: List<SubtitleSyncCue>,
        scale: Double,
        binMs: Long,
    ): ActivityTimeline? {
        if (!scale.isFinite() || scale !in ACTIVITY_MIN_SCALE..ACTIVITY_MAX_SCALE) return null
        if (cues.isEmpty() || binMs <= 0L) return null

        val scaled = ArrayList<Pair<Long, Long>>(cues.size)
        var maxEndMs = 0L
        for (cue in cues) {
            val safeStart = cue.startTimeMs.coerceAtLeast(0L)
            val safeEnd = cue.endTimeMs
                .coerceAtLeast(safeStart + 1L)
                .coerceAtMost(safeStart + ACTIVITY_MAX_CUE_DURATION_MS)
            val start = (safeStart * scale).roundToLong().coerceAtLeast(0L)
            val end = (safeEnd * scale).roundToLong().coerceAtLeast(start + 1L)
            if (end > ACTIVITY_MAX_TIMELINE_MS) return null
            scaled += start to end
            maxEndMs = max(maxEndMs, end)
        }

        val binCount = max(1, ceil((maxEndMs + 1L).toDouble() / binMs.toDouble()).toInt())
        val bins = BooleanArray(binCount)
        for ((start, end) in scaled) {
            val first = (start / binMs).toInt().coerceIn(0, binCount - 1)
            val lastExclusive = ceil(end.toDouble() / binMs.toDouble())
                .toInt()
                .coerceIn(first + 1, binCount)
            for (index in first until lastExclusive) bins[index] = true
        }

        var activeCount = 0
        for (active in bins) if (active) activeCount++
        if (activeCount == 0) return null

        val activeIndexes = IntArray(activeCount)
        val prefix = IntArray(binCount + 1)
        val packed = LongArray((binCount + 63) ushr 6)
        var cursor = 0
        for (index in bins.indices) {
            if (bins[index]) {
                activeIndexes[cursor++] = index
                packed[index ushr 6] =
                    packed[index ushr 6] or (1L shl (index and 63))
                prefix[index + 1] = prefix[index] + 1
            } else {
                prefix[index + 1] = prefix[index]
            }
        }
        return ActivityTimeline(
            bins = bins,
            packed = packed,
            activeIndexes = activeIndexes,
            prefix = prefix,
            firstActive = activeIndexes.first(),
            lastActive = activeIndexes.last(),
        )
    }

    private fun countShiftedActivityIntersection(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        sourceStart: Int,
        sourceEnd: Int,
    ): Int {
        if (sourceEnd < sourceStart) return 0

        val firstWord = sourceStart ushr 6
        val lastWord = sourceEnd ushr 6
        var intersection = 0

        for (wordIndex in firstWord..lastWord) {
            val alignedReference = packedActivityWindow(
                packed = reference.packed,
                startBit = (wordIndex shl 6) + offsetBins,
            )
            val firstBit = if (wordIndex == firstWord) sourceStart and 63 else 0
            val lastBit = if (wordIndex == lastWord) sourceEnd and 63 else 63
            val mask = activityRangeMask(firstBit, lastBit)
            intersection +=
                (target.packed[wordIndex] and alignedReference and mask).countOneBits()
        }

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
        val sourceEnd = min(target.bins.lastIndex, reference.bins.lastIndex - offsetBins)
        if (sourceEnd < sourceStart) return null
        val visibleTarget = target.prefix[sourceEnd + 1] - target.prefix[sourceStart]
        if (visibleTarget <= 0) return null

        val intersection = countShiftedActivityIntersection(
            reference = reference,
            target = target,
            offsetBins = offsetBins,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
        )

        val referenceWindowStart = max(0, target.firstActive + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, target.lastActive + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        val visibility = visibleTarget.toDouble() / target.activeIndexes.size.toDouble()
        return (precision * 0.72 + recall * 0.28) *
            (0.85 + 0.15 * visibility.coerceIn(0.0, 1.0))
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
    ): IntRange {
        val baseBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()
        val referenceSpan = reference.lastActive - reference.firstActive
        val targetSpan = target.lastActive - target.firstActive
        if (targetSpan >= referenceSpan * PARTIAL_TARGET_SPAN_RATIO) return -baseBins..baseBins

        return min(-baseBins, reference.firstActive - target.firstActive - baseBins)..
            max(baseBins, reference.lastActive - target.lastActive + baseBins)
    }

    private fun isDistinctActivityTransform(
        first: ActivityCandidate,
        second: ActivityCandidate,
        targetEndMs: Double,
    ): Boolean {
        val startDifference = abs(first.interceptMs - second.interceptMs).toDouble()
        val endDifference = abs(
            targetEndMs * first.scale + first.interceptMs -
                (targetEndMs * second.scale + second.interceptMs),
        )
        return max(startDifference, endDifference) >= ACTIVITY_DISTINCT_TRANSFORM_MS
    }

    private fun coverageSegmentsPassed(
        result: AutoSyncTimelineRetimeResult,
        targetSize: Int,
    ): Int {
        if (targetSize < MIN_CUES || result.groups.isEmpty()) return 0
        val matched = BooleanArray(targetSize)
        val costs = Array(3) { mutableListOf<Double>() }
        result.groups.forEach { group ->
            val groupEnd = (group.targetStartIndex + group.targetCount).coerceAtMost(targetSize)
            for (index in group.targetStartIndex until groupEnd) matched[index] = true
            val midpoint = group.targetStartIndex + (group.targetCount - 1) / 2
            val segment =
                ((midpoint.toLong() * 3L) / targetSize.coerceAtLeast(1)).toInt().coerceIn(0, 2)
            costs[segment] += group.cost
        }

        var passed = 0
        for (segment in 0..2) {
            val start = segment * targetSize / 3
            val end = if (segment == 2) targetSize else (segment + 1) * targetSize / 3
            val length = (end - start).coerceAtLeast(1)
            var matchedCount = 0
            for (index in start until end) if (matched[index]) matchedCount++
            val coverage = matchedCount.toDouble() / length
            val segmentCosts = costs[segment]
            val averageCost =
                if (segmentCosts.isEmpty()) Double.POSITIVE_INFINITY else segmentCosts.average()
            val requiredGroups = min(COVERAGE_SEGMENT_MIN_GROUPS, length)
            if (
                coverage >= COVERAGE_SEGMENT_MIN_COVERAGE &&
                averageCost <= COVERAGE_SEGMENT_MAX_AVERAGE_COST &&
                segmentCosts.size >= requiredGroups
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

    private data class ExternalCueKey(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
    )

    internal data class ActivityTimeline(
        val bins: BooleanArray,
        val packed: LongArray,
        val activeIndexes: IntArray,
        val prefix: IntArray,
        val firstActive: Int,
        val lastActive: Int,
    )

    internal class PreparedActivity(
        val coarse: ActivityTimeline,
        val fine: ActivityTimeline,
        /** Sorted cue start times, for onset tie-breaks between equally covering offsets. */
        val starts: LongArray,
    )

    internal data class ActivityCandidate(
        val scale: Double,
        val interceptMs: Long,
        val score: Double,
    )

    internal data class DelayOnlySearchSeed(
        val coarse: ActivityCandidate,
        val margin: Double,
        val fine: ActivityCandidate?,
        /** Coarse score plateau around [coarse]; fine refinement covers all of it. */
        val plateauStartMs: Long = coarse.interceptMs,
        val plateauEndMs: Long = coarse.interceptMs,
    )

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

            // Restrict the first patch to ordinary sequential external cues. Simultaneous or
            // overlapping authored cues need stronger identity evidence than timing alone.
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

        val startError = abs(referenceStart - targetStart) / START_END_TOLERANCE_MS
        val endError = abs(referenceEnd - targetEnd) / START_END_TOLERANCE_MS
        val midpointError = abs(referenceMid - targetMid) / MIDPOINT_TOLERANCE_MS
        val durationError = abs(referenceDuration - targetDuration) / DURATION_TOLERANCE_MS
        val referenceEndEstimated =
            estimatedEndPrefix[referenceIndex + referenceCount] !=
                estimatedEndPrefix[referenceIndex]

        return if (referenceEndEstimated) {
            // MKV CueTime is authoritative even when CueDuration is absent. Keep the inferred
            // end useful, but do not let it outweigh the real embedded start timestamp.
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

    private fun backtrack(
        rows: BandedDpRows,
        finalReferenceIndex: Int,
        finalTargetIndex: Int,
    ): List<BacktrackStep>? {
        val reversed = ArrayList<BacktrackStep>()
        var referenceIndex = finalReferenceIndex
        var targetIndex = finalTargetIndex
        var guard = 0
        val guardLimit = rows.rowCount * 8 + finalReferenceIndex * 2 + 32

        while (referenceIndex != 0 || targetIndex != 0) {
            if (++guard > guardLimit) return null
            val cellIndex = rows.indexOf(targetIndex, referenceIndex)
            if (cellIndex < 0 || !rows.isReachable(cellIndex)) return null

            val step = decodeStep(rows.stepCodes[cellIndex]) ?: return null
            if (step == Step.START) return null

            val previousReferenceIndex = rows.previousReferenceIndices[cellIndex]
            val previousTargetIndex = rows.previousTargetIndices[cellIndex]
            reversed += BacktrackStep(
                step = step,
                previousReferenceIndex = previousReferenceIndex,
                previousTargetIndex = previousTargetIndex,
                referenceCount = rows.referenceCounts[cellIndex].toInt(),
                targetCount = rows.targetCounts[cellIndex].toInt(),
                localGroupCost = rows.localGroupCosts[cellIndex],
            )
            referenceIndex = previousReferenceIndex
            targetIndex = previousTargetIndex
        }

        reversed.reverse()
        return reversed
    }

    private fun relax(
        rows: BandedDpRows,
        finalRowCosts: HashMap<Int, Double>,
        finalTargetIndex: Int,
        toTargetIndex: Int,
        toReferenceIndex: Int,
        candidateCost: Double,
        previousReferenceIndex: Int,
        previousTargetIndex: Int,
        referenceCount: Int,
        targetCount: Int,
        step: Step,
        localGroupCost: Double = 0.0,
    ) {
        val cellIndex = rows.indexOf(toTargetIndex, toReferenceIndex)
        if (cellIndex < 0) return

        val currentCost = rows.costs[cellIndex]
        if (!rows.isReachable(cellIndex) || candidateCost + 1e-9 < currentCost) {
            rows.write(
                cellIndex = cellIndex,
                targetIndex = toTargetIndex,
                referenceIndex = toReferenceIndex,
                cost = candidateCost,
                previousReferenceIndex = previousReferenceIndex,
                previousTargetIndex = previousTargetIndex,
                referenceCount = referenceCount,
                targetCount = targetCount,
                step = step,
                localGroupCost = localGroupCost,
            )
            if (toTargetIndex == finalTargetIndex) {
                finalRowCosts[toReferenceIndex] = candidateCost
            }
        }
    }

    private fun rowMaxReferenceIndex(
        targetIndex: Int,
        targetSize: Int,
        referenceSize: Int,
        center: Int,
    ): Int {
        if (targetIndex == 0) {
            return min(referenceSize, center + BAND_RADIUS_CUES)
        }
        if (targetIndex == targetSize) {
            return min(referenceSize, center + BAND_RADIUS_CUES)
        }
        return min(referenceSize, center + BAND_RADIUS_CUES)
    }

    private fun isWithinBand(
        referenceIndex: Int,
        targetIndex: Int,
        centers: IntArray,
        referenceSize: Int,
    ): Boolean {
        if (referenceIndex !in 0..referenceSize) return false
        val center = centers[targetIndex.coerceIn(0, centers.lastIndex)]
        return abs(referenceIndex - center) <= BAND_RADIUS_CUES || targetIndex == 0
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

    private data class GroupShape(
        val referenceCount: Int,
        val targetCount: Int,
    )

    private const val DP_STEP_UNREACHABLE: Byte = 0
    private const val DP_STEP_START: Byte = 1
    private const val DP_STEP_GROUP: Byte = 2
    private const val DP_STEP_SKIP_REFERENCE: Byte = 3
    private const val DP_STEP_SKIP_TARGET: Byte = 4

    private enum class Step {
        START,
        GROUP,
        SKIP_REFERENCE,
        SKIP_TARGET,
    }

    private class BandedDpRows(
        targetSize: Int,
        referenceSize: Int,
        centers: IntArray,
    ) {
        val rowCount = targetSize + 1
        private val rowStarts = IntArray(rowCount)
        private val rowEnds = IntArray(rowCount)
        private val rowOffsets = IntArray(rowCount + 1)
        private val minReachableReferenceIndices = IntArray(rowCount) { Int.MAX_VALUE }

        val costs: DoubleArray
        val previousReferenceIndices: IntArray
        val previousTargetIndices: IntArray
        val referenceCounts: ByteArray
        val targetCounts: ByteArray
        val stepCodes: ByteArray
        val localGroupCosts: DoubleArray

        init {
            var totalCells = 0
            for (targetIndex in 0..targetSize) {
                val start =
                    if (targetIndex == 0) {
                        0
                    } else {
                        max(0, centers[targetIndex] - BAND_RADIUS_CUES)
                    }
                val end =
                    min(referenceSize, centers[targetIndex] + BAND_RADIUS_CUES)

                rowStarts[targetIndex] = start
                rowEnds[targetIndex] = end
                rowOffsets[targetIndex] = totalCells
                totalCells += end - start + 1
            }
            rowOffsets[rowCount] = totalCells

            costs = DoubleArray(totalCells) { Double.POSITIVE_INFINITY }
            previousReferenceIndices = IntArray(totalCells) { -1 }
            previousTargetIndices = IntArray(totalCells) { -1 }
            referenceCounts = ByteArray(totalCells)
            targetCounts = ByteArray(totalCells)
            stepCodes = ByteArray(totalCells)
            localGroupCosts = DoubleArray(totalCells)
        }

        fun setStart() {
            val cellIndex = indexOf(0, 0)
            check(cellIndex >= 0)
            costs[cellIndex] = 0.0
            stepCodes[cellIndex] = encodeStep(Step.START)
            minReachableReferenceIndices[0] = 0
        }

        fun indexOf(targetIndex: Int, referenceIndex: Int): Int {
            if (targetIndex !in 0 until rowCount) return -1
            val start = rowStarts[targetIndex]
            val end = rowEnds[targetIndex]
            if (referenceIndex < start || referenceIndex > end) return -1
            return rowOffsets[targetIndex] + referenceIndex - start
        }

        fun minReachableReferenceIndex(targetIndex: Int): Int {
            if (targetIndex !in 0 until rowCount) return -1
            val value = minReachableReferenceIndices[targetIndex]
            return if (value == Int.MAX_VALUE) -1 else value
        }

        fun isReachable(cellIndex: Int): Boolean =
            stepCodes[cellIndex] != DP_STEP_UNREACHABLE

        fun write(
            cellIndex: Int,
            targetIndex: Int,
            referenceIndex: Int,
            cost: Double,
            previousReferenceIndex: Int,
            previousTargetIndex: Int,
            referenceCount: Int,
            targetCount: Int,
            step: Step,
            localGroupCost: Double,
        ) {
            costs[cellIndex] = cost
            previousReferenceIndices[cellIndex] = previousReferenceIndex
            previousTargetIndices[cellIndex] = previousTargetIndex
            referenceCounts[cellIndex] = referenceCount.toByte()
            targetCounts[cellIndex] = targetCount.toByte()
            stepCodes[cellIndex] = encodeStep(step)
            localGroupCosts[cellIndex] = localGroupCost
            if (referenceIndex < minReachableReferenceIndices[targetIndex]) {
                minReachableReferenceIndices[targetIndex] = referenceIndex
            }
        }
    }

    private fun encodeStep(step: Step): Byte =
        when (step) {
            Step.START -> DP_STEP_START
            Step.GROUP -> DP_STEP_GROUP
            Step.SKIP_REFERENCE -> DP_STEP_SKIP_REFERENCE
            Step.SKIP_TARGET -> DP_STEP_SKIP_TARGET
        }

    private fun decodeStep(code: Byte): Step? =
        when (code) {
            DP_STEP_START -> Step.START
            DP_STEP_GROUP -> Step.GROUP
            DP_STEP_SKIP_REFERENCE -> Step.SKIP_REFERENCE
            DP_STEP_SKIP_TARGET -> Step.SKIP_TARGET
            else -> null
        }

    private data class BacktrackStep(
        val step: Step,
        val previousReferenceIndex: Int,
        val previousTargetIndex: Int,
        val referenceCount: Int,
        val targetCount: Int,
        val localGroupCost: Double,
    )
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
