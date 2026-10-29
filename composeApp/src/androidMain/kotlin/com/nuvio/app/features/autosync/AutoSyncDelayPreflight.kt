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
        cancellationCheck: (() -> Unit)? = null,
    ): Result {
        val targetActivity =
            preparedTargetActivity
                ?: AutoSyncTimelineRetimer.prepareUnitActivity(target)
                ?: return Result(best = null, evidenceByReferenceKey = emptyMap())

        var best: Match? = null
        val evidenceByReferenceKey = linkedMapOf<String, Evidence>()

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
            if (alignment == null) continue

            val candidate = Match(
                referenceKey = track.key,
                alignment = alignment,
            )

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
