package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncNoFitTrackerTest {
    @Test
    fun abandonsOnlyAfterThreeIndependentSourcesFoundNoFit() {
        val tracker = AutoSyncNoFitTracker()
        val english = activity(seed = 1)

        assertFalse(tracker.recordNoFit(english))
        assertFalse(tracker.recordNoFit(activity(seed = 1))) // variant of the same source
        assertEquals(1, tracker.sourceCount)
        assertFalse(tracker.recordNoFit(activity(seed = 2)))
        assertTrue(tracker.recordNoFit(activity(seed = 3)))
    }

    @Test
    fun onlyAmbiguousRejectionsCountAsNoFit() {
        val reference = timeline(seed = 1)
        val fit = checkNotNull(AutoSyncTimelineRetimer.retime(reference, reference, 1.0, 0.0, true))

        assertTrue(AutoSyncNoFitTracker.isNoFit(null))
        assertFalse(AutoSyncNoFitTracker.isNoFit(fit))
        assertTrue(AutoSyncNoFitTracker.isNoFit(fit.copy(confident = false, activityMargin = 0.004)))
        assertFalse(AutoSyncNoFitTracker.isNoFit(fit.copy(confident = false, activityMargin = 0.015)))
    }

    @Test
    fun onlyARealAmbiguousResultAgainstAFullReferenceConfirmsANoFit() {
        val reference = timeline(seed = 1)
        val fit = checkNotNull(AutoSyncTimelineRetimer.retime(reference, reference, 1.0, 0.0, true))
        val ambiguous = fit.copy(confident = false, activityMargin = 0.004)

        assertTrue(AutoSyncNoFitTracker.confirmsNoFit(ambiguous, sampledReference = false))
        assertFalse(AutoSyncNoFitTracker.confirmsNoFit(null, sampledReference = false))
        assertFalse(AutoSyncNoFitTracker.confirmsNoFit(ambiguous, sampledReference = true))
        assertFalse(AutoSyncNoFitTracker.confirmsNoFit(fit, sampledReference = false))
        assertFalse(
            AutoSyncNoFitTracker.confirmsNoFit(
                fit.copy(confident = false, activityMargin = 0.015),
                sampledReference = false,
            ),
        )
    }

    @Test
    fun aNoFitIsConfirmedOnlyWhenEveryTriedReferenceAgreed() {
        // Every ranked reference tried, all no-fit.
        assertTrue(AutoSyncNoFitTracker.isConfirmedNoFit(3, 3, rankedReferences = 3, abandoned = false))
        // Abandoned early by the tracker after enough independent sources.
        assertTrue(AutoSyncNoFitTracker.isConfirmedNoFit(3, 3, rankedReferences = 5, abandoned = true))
        // One reference left untried.
        assertFalse(AutoSyncNoFitTracker.isConfirmedNoFit(2, 2, rankedReferences = 3, abandoned = false))
        // One pair was inconclusive or a fit.
        assertFalse(AutoSyncNoFitTracker.isConfirmedNoFit(3, 2, rankedReferences = 3, abandoned = false))
        // Never evaluated.
        assertFalse(AutoSyncNoFitTracker.isConfirmedNoFit(0, 0, rankedReferences = 0, abandoned = false))
    }

    private fun activity(seed: Int) =
        checkNotNull(AutoSyncTimelineRetimer.prepareUnitActivity(timeline(seed)))

    private fun timeline(seed: Int): List<SubtitleSyncCue> {
        val random = kotlin.random.Random(seed)
        var start = 30_000L
        return (0 until 300).map { index ->
            if (index > 0) start += 1_200L + random.nextInt(6_000)
            SubtitleSyncCue(start, start + 900L + random.nextInt(2_000), "cue $index")
        }
    }
}
