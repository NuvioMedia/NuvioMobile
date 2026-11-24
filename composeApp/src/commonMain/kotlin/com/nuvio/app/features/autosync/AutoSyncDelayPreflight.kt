package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * Scheduling-only fixed-delay preflight for AutoSync V2.
 *
 * There is deliberately no independent scoring model here. Every score, margin and offset comes
 * from AutoSyncTimelineRetimer's real V2 delay-only validator. This object only ranks completed
 * downloads and carries the winning V2 alignment forward into the authoritative full matcher.
 */
internal object AutoSyncDelayPreflight {
    // Scheduling threshold only. It never authorizes a subtitle by itself; authoritative V2
    // still has to pass its existing strong/exceptional gates before an early return.
    private const val FAST_DELAY_MIN_SCORE = 0.90
    private const val FAST_DELAY_MIN_MARGIN = 0.04
    private const val FAST_DELAY_REQUIRED_SEGMENTS = 3
    private const val MIN_PROBED_BEFORE_FAST_STOP = 2

    internal data class Match(
        val referenceKey: String,
        val alignment: AutoSyncDelayOnlyAlignment,
    ) {
        val offsetMs: Double get() = alignment.offsetMs
        val score: Double get() = alignment.score
        val margin: Double get() = alignment.margin
        val segmentsPassed: Int get() = alignment.segmentsPassed
    }

    internal data class Evidence(
        val search: AutoSyncTimelineRetimer.DelayOnlySearchEvidence,
        val validatedAlignment: AutoSyncDelayOnlyAlignment?,
    )

    internal data class Result(
        val best: Match?,
        val evidenceByReferenceKey: Map<String, Evidence>,
    )

    internal fun evaluate(
        referenceTracks: List<ReferenceTrack>,
        target: List<SubtitleSyncCue>,
        referenceActivityCache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
        preparedTargetActivity: AutoSyncTimelineRetimer.PreparedActivity? = null,
        earlyStopFastDelay: Boolean = false,
        maxUnmatchedReferences: Int = Int.MAX_VALUE,
        cancellationCheck: (() -> Unit)? = null,
    ): Result {
        val targetActivity =
            preparedTargetActivity
                ?: AutoSyncTimelineRetimer.prepareUnitActivity(target)
                ?: return Result(best = null, evidenceByReferenceKey = emptyMap())

        var best: Match? = null
        var bestFastEligible = false
        val evidenceByReferenceKey = linkedMapOf<String, Evidence>()
        var probedCount = 0

        for (track in referenceTracks) {
            cancellationCheck?.invoke()
            val preparedReference = AutomaticSubtitleSync.preparedReferenceActivity(
                track = track,
                cache = referenceActivityCache,
            ) ?: continue

            val searchEvidence =
                AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(
                    referenceActivity = preparedReference,
                    targetActivity = targetActivity,
                    cancellationCheck = cancellationCheck,
                ) ?: continue
            probedCount++

            // Match the exact delay-margin relaxation used by authoritative V2.
            val relaxDelayMargin = AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = AutomaticSubtitleSync.isSdhReferenceTrack(track),
                referenceSize = track.cues.size,
                targetSize = target.size,
            )

            val alignment =
                AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                    referenceActivity = preparedReference,
                    targetActivity = targetActivity,
                    targetSize = target.size,
                    allowAmbiguousMargin = relaxDelayMargin,
                    precomputedEvidence = searchEvidence,
                    cancellationCheck = cancellationCheck,
                )

            evidenceByReferenceKey[track.key] = Evidence(
                search = searchEvidence,
                validatedAlignment = alignment,
            )
            if (alignment != null) {
                val candidate = Match(
                    referenceKey = track.key,
                    alignment = alignment,
                )
                val candidateFastEligible = !relaxDelayMargin &&
                    alignment.score >= FAST_DELAY_MIN_SCORE &&
                    alignment.margin >= FAST_DELAY_MIN_MARGIN &&
                    alignment.segmentsPassed >= FAST_DELAY_REQUIRED_SEGMENTS

                val currentBest = best
                if (
                    currentBest == null ||
                    candidate.score > currentBest.score ||
                    (
                        candidate.score == currentBest.score &&
                            candidate.margin > currentBest.margin
                        ) ||
                    (
                        candidate.score == currentBest.score &&
                            candidate.margin == currentBest.margin &&
                            candidate.segmentsPassed > currentBest.segmentsPassed
                        )
                ) {
                    best = candidate
                    if (candidateFastEligible) bestFastEligible = true
                }
            }

            if (earlyStopFastDelay && bestFastEligible && probedCount >= MIN_PROBED_BEFORE_FAST_STOP) {
                break
            }
            if (best == null && probedCount >= maxUnmatchedReferences) {
                break
            }
        }

        return Result(
            best = best,
            evidenceByReferenceKey = evidenceByReferenceKey,
        )
    }

    internal fun bestMatch(
        referenceTracks: List<ReferenceTrack>,
        target: List<SubtitleSyncCue>,
        referenceActivityCache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
        preparedTargetActivity: AutoSyncTimelineRetimer.PreparedActivity? = null,
        cancellationCheck: (() -> Unit)? = null,
    ): Match? =
        evaluate(
            referenceTracks = referenceTracks,
            target = target,
            referenceActivityCache = referenceActivityCache,
            preparedTargetActivity = preparedTargetActivity,
            cancellationCheck = cancellationCheck,
        ).best

}
