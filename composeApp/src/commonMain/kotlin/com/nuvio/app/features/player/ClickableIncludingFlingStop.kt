package com.nuvio.app.features.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Like [Modifier.clickable], but still delivers [onClick] when LazyColumn consumed the
 * down event to cancel an in-progress fling or overscroll settle.
 */
@Composable
internal fun Modifier.clickableIncludingFlingStop(onClick: () -> Unit): Modifier {
    val latestOnClick by rememberUpdatedState(onClick)
    return clickable(onClick = latestOnClick)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!down.isConsumed) return@awaitEachGesture

                val slop = viewConfiguration.touchSlop
                val origin = down.position
                val pointerId = down.id
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                        ?: return@awaitEachGesture
                    val movement = (change.position - origin).getDistance()
                    if (change.changedToUpIgnoreConsumed()) {
                        if (shouldDeliverFlingStopClick(
                                downConsumed = true,
                                movement = movement,
                                touchSlop = slop,
                                pointerUp = true,
                            )
                        ) {
                            latestOnClick()
                        }
                        return@awaitEachGesture
                    }
                    if (movement > slop) return@awaitEachGesture
                }
            }
        }
}

internal fun shouldDeliverFlingStopClick(
    downConsumed: Boolean,
    movement: Float,
    touchSlop: Float,
    pointerUp: Boolean,
): Boolean = downConsumed && pointerUp && movement <= touchSlop
