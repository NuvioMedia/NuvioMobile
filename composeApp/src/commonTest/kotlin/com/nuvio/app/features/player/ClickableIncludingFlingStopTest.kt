package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickableIncludingFlingStopTest {
    @Test
    fun unconsumedDownLeavesTheClickToClickable() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = false,
                movement = 0f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    @Test
    fun consumedDownAndUpWithinSlopSelectsTheRow() {
        assertTrue(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 4f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    @Test
    fun consumedDownPastSlopIsADragNotAClick() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 24f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    @Test
    fun consumedDownWithoutUpDoesNotClick() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 0f,
                touchSlop = 18f,
                pointerUp = false,
            ),
        )
    }
}
