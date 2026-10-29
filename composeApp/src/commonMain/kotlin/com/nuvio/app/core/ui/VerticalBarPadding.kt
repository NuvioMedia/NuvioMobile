package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalVerticalBarPadding = compositionLocalOf { PaddingValues() }

@Composable
fun PaddingValues.withVerticalBar(): PaddingValues {
    val verticalBar = LocalVerticalBarPadding.current
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection) + verticalBar.calculateStartPadding(layoutDirection),
        top = calculateTopPadding(),
        end = calculateEndPadding(layoutDirection) + verticalBar.calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding(),
    )
}

@Composable
fun centeredVerticalBarPadding(minimum: Dp = 0.dp): PaddingValues {
    val verticalBar = LocalVerticalBarPadding.current
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        horizontal = maxOf(
            minimum,
            verticalBar.calculateStartPadding(layoutDirection),
            verticalBar.calculateEndPadding(layoutDirection),
        ),
    )
}

@Composable
fun verticalBarWidth(): Dp {
    val verticalBar = LocalVerticalBarPadding.current
    val layoutDirection = LocalLayoutDirection.current
    return verticalBar.calculateStartPadding(layoutDirection) + verticalBar.calculateEndPadding(layoutDirection)
}
