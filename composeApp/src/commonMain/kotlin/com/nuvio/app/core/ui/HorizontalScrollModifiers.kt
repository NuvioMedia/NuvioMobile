package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val NoPadding = PaddingValues()

/**
 * Keeps the initial horizontal inset while allowing a horizontal scroller's
 * content to move through that inset once scrolling starts.
 */
fun Modifier.nuvioHorizontalScrollBleed(horizontalPadding: Dp): Modifier =
    nuvioHorizontalScrollBleed(PaddingValues(horizontal = horizontalPadding.coerceAtLeast(0.dp)))

fun Modifier.nuvioHorizontalScrollBleed(padding: PaddingValues): Modifier {
    if (padding == NoPadding) return this

    return layout { measurable, constraints ->
        val leftPx = padding.calculateLeftPadding(layoutDirection).roundToPx()
        val rightPx = padding.calculateRightPadding(layoutDirection).roundToPx()
        if (!constraints.hasBoundedWidth || leftPx + rightPx == 0) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }

        val visibleWidth = constraints.maxWidth
        val expandedWidth = visibleWidth + leftPx + rightPx
        val expandedConstraints = Constraints(
            minWidth = expandedWidth,
            maxWidth = expandedWidth,
            minHeight = constraints.minHeight,
            maxHeight = constraints.maxHeight,
        )
        val placeable = measurable.measure(expandedConstraints)

        layout(visibleWidth, placeable.height) {
            placeable.place(-leftPx, 0)
        }
    }
}
