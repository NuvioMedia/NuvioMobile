package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings this feature adds and what each combination of them does: the completion threshold, the
 * rewatch mode, the plan it needs, and the next-up mode that reads the sessions back.
 *
 * The write on the scrobble and the question asked afterwards are the two places a rewatch can be
 * recorded, so both are covered here as the gates they are: the mode, the plan, the action, whether the
 * playback finished, and whether the item is a repeat viewing at all.
 */
class SimklRewatchPolicyTest {

    @Test
    fun `the threshold is kept inside the range the account accepts`() {
        assertEquals(80, coerceSimklWatchedThresholdPercent(70))
        assertEquals(80, coerceSimklWatchedThresholdPercent(80))
        assertEquals(85, coerceSimklWatchedThresholdPercent(85))
        assertEquals(95, coerceSimklWatchedThresholdPercent(95))
        assertEquals(95, coerceSimklWatchedThresholdPercent(99))
        assertEquals(80, SimklWatchedThresholdRange.first)
        assertEquals(95, SimklWatchedThresholdRange.last)
    }

    @Test
    fun `rewatch bookkeeping is off until the user asks for it`() {
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.Default)
        assertEquals(80, SIMKL_WATCHED_THRESHOLD_DEFAULT_PERCENT)
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.Default)
    }

    @Test
    fun `only automatic mode on a plan that allows it writes a rewatch on a finished stop`() {
        assertTrue(record(SimklRewatchMode.AUTOMATIC, "pro"))
        assertTrue(record(SimklRewatchMode.AUTOMATIC, " VIP "))

        // The plan decides, and every other mode leaves the flag off so nothing is written server side.
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "free"))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, null))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "unconfirmed"))
        assertFalse(record(SimklRewatchMode.MANUAL, "pro"))
        assertFalse(record(SimklRewatchMode.OFF, "pro"))
    }

    @Test
    fun `only a finished stop carries the flag`() {
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.PAUSE))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.START))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", progressPercent = 79.0))
        assertTrue(record(SimklRewatchMode.AUTOMATIC, "pro", progressPercent = 80.0))
    }

    @Test
    fun `a stop before the credits is not a finished stop, whatever the user bar`() {
        // The threshold says 80 and the playback stopped at 85, but the credits start at 93, so the
        // playback is not over and no rewatch is written for it.
        val completion = resolvedSimklCompletionPercent(userThresholdPercent = 80.0, contentEndPercent = 93.0)

        assertEquals(92.0, completion, 0.0001)
        assertFalse(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 85.0,
                completionThresholdPercent = completion,
            ),
        )
        assertTrue(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 92.0,
                completionThresholdPercent = completion,
            ),
        )
    }

    @Test
    fun `only manual mode asks, and only for a repeat viewing Simkl would keep`() {
        val nowEpochMs = 10_000_000_000L
        val olderThanTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS - 1L
        val insideTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS + 1L
        val watchedLongAgo = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = olderThanTheGap)

        assertTrue(ask(mode = SimklRewatchMode.MANUAL, accountType = "pro", priorWatch = watchedLongAgo))

        // The other modes never ask, and neither does a plan that cannot record a rewatch.
        assertFalse(ask(mode = SimklRewatchMode.OFF, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.AUTOMATIC, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = "free", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = null, priorWatch = watchedLongAgo))

        // Only a finished stop that Simkl accepted as a scrobble is worth asking about.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                action = TrackingScrobbleAction.PAUSE,
            ),
        )
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                outcome = SimklScrobbleOutcome.PAUSE,
            ),
        )
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                progressPercent = 79.0,
            ),
        )
        // The same gate the write uses: credits at 93 mean a stop at 85 has not finished.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                progressPercent = 85.0,
                completionThresholdPercent = 92.0,
            ),
        )

        // An item the account does not hold is not a repeat viewing at all.
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = "pro", priorWatch = SimklPriorWatch.None))

        // Simkl folds a watch from the last two days into the session it already has, so a confirmation
        // would do nothing. A row with no timestamp is asked about, since nothing says otherwise.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = insideTheGap),
            ),
        )
        assertTrue(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = null),
            ),
        )
    }

    @Test
    fun `the next-up setting decides how much of a run is read back`() {
        assertEquals(1, SimklRewatchNextUpMode.ALWAYS.minimumRunEpisodes)
        assertEquals(2, SimklRewatchNextUpMode.AFTER_TWO.minimumRunEpisodes)
        assertEquals(null, SimklRewatchNextUpMode.NEVER.minimumRunEpisodes)
        assertEquals(SimklRewatchNextUpMode.AFTER_TWO, SimklRewatchNextUpMode.fromStorage("after_two"))
        assertEquals(SimklRewatchNextUpMode.NEVER, SimklRewatchNextUpMode.fromStorage("Never"))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage("nonsense"))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage(null))
    }

    @Test
    fun `rewatches need a plan that allows them, and a free account keeps the picker on off`() {
        assertTrue(isSimklRewatchPlanEligible("pro"))
        assertTrue(isSimklRewatchPlanEligible(" VIP "))
        assertFalse(isSimklRewatchPlanEligible("free"))
        assertFalse(isSimklRewatchPlanEligible(""))
        assertFalse(isSimklRewatchPlanEligible(null))

        // Off is always available, because turning it off never needs the plan.
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.OFF, "free"))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.OFF, null))
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.MANUAL, "free"))
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, null))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.MANUAL, "pro"))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, "vip"))
    }

    private fun record(
        mode: SimklRewatchMode,
        accountType: String?,
        action: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
        progressPercent: Double = 95.0,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    ): Boolean = shouldRecordSimklRewatchOnStop(
        mode = mode,
        accountType = accountType,
        action = action,
        progressPercent = progressPercent,
        completionThresholdPercent = completionThresholdPercent,
    )

    private fun ask(
        mode: SimklRewatchMode,
        accountType: String?,
        priorWatch: SimklPriorWatch,
        nowEpochMs: Long = 10_000_000_000L,
        action: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
        outcome: SimklScrobbleOutcome = SimklScrobbleOutcome.SCROBBLE,
        progressPercent: Double = 95.0,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    ): Boolean = shouldPromptSimklRewatch(
        mode = mode,
        accountType = accountType,
        action = action,
        outcome = outcome,
        progressPercent = progressPercent,
        priorWatch = priorWatch,
        nowEpochMs = nowEpochMs,
        completionThresholdPercent = completionThresholdPercent,
    )
}
