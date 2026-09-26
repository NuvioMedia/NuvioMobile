package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a playback counts as finished for Simkl: the credits marker from IntroDB when there is one,
 * and the percentage the user set when there is not.
 */
class SimklRewatchCompletionTest {

    @Test
    fun `without a marker the user threshold decides`() {
        assertEquals(95.0, resolvedSimklCompletionPercent(95.0, null), 0.0001)
        assertEquals(80.0, resolvedSimklCompletionPercent(80.0, null), 0.0001)
    }

    @Test
    fun `the credits are the end of the content, above the user threshold as well`() {
        // Stopping at 85 when the credits start at 93 is not a finished playback, however low the user
        // set their own bar, so the marker decides, read one point early as its tolerance.
        assertEquals(92.0, resolvedSimklCompletionPercent(80.0, 93.0), 0.0001)
        assertEquals(95.5, resolvedSimklCompletionPercent(90.0, 96.5), 0.0001)
    }

    @Test
    fun `the credits end a playback earlier than the user threshold would`() {
        // The user asked for 95, the credits start at 88: the content is over at 87 once the marker is
        // read with its tolerance.
        assertEquals(87.0, resolvedSimklCompletionPercent(95.0, 88.0), 0.0001)
    }

    @Test
    fun `a marker that cannot survive the tolerance is ignored`() {
        // Simkl records a watch from 80 percent up, so a marker that lands under that once the tolerance
        // is taken off cannot be used: a stop reported there is not counted by the account at all.
        assertEquals(95.0, resolvedSimklCompletionPercent(95.0, 80.5), 0.0001)
        assertEquals(95.0, resolvedSimklCompletionPercent(95.0, 79.99), 0.0001)
        assertEquals(95.0, resolvedSimklCompletionPercent(95.0, 62.0), 0.0001)
    }

    @Test
    fun `a threshold under Simkl's bar is raised to it`() {
        assertEquals(80.0, resolvedSimklCompletionPercent(70.0, null), 0.0001)
        assertEquals(80.0, resolvedSimklCompletionPercent(70.0, 60.0), 0.0001)
    }

    @Test
    fun `nonsense values fall back to the threshold`() {
        assertEquals(90.0, resolvedSimklCompletionPercent(90.0, Double.NaN), 0.0001)
        assertEquals(80.0, resolvedSimklCompletionPercent(Double.NaN, null), 0.0001)
    }
}
