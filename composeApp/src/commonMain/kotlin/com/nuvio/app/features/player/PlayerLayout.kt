package com.nuvio.app.features.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_resize_fill
import nuvio.composeapp.generated.resources.compose_player_resize_fit
import nuvio.composeapp.generated.resources.compose_player_resize_zoom
import org.jetbrains.compose.resources.StringResource
import kotlin.math.max

internal data class PlayerLayoutMetrics(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val titleSize: TextUnit,
    val episodeInfoSize: TextUnit,
    val metadataSize: TextUnit,
    val centerGap: Dp,
    val centerLift: Dp,
    val sliderBottomOffset: Dp,
    val sliderTouchHeight: Dp,
    val sliderScaleY: Float,
    val timeSize: TextUnit,
    val headerIconSize: Dp,
    val sideButtonPadding: Dp,
    val sideIconSize: Dp,
    val playButtonPadding: Dp,
    val playIconSize: Dp,
) {
    companion object {
        fun fromWidth(width: Dp): PlayerLayoutMetrics =
            when {
                width >= 1440.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 28.dp,
                    verticalPadding = 24.dp,
                    titleSize = 28.dp.value.sp,
                    episodeInfoSize = 16.dp.value.sp,
                    metadataSize = 14.dp.value.sp,
                    centerGap = 112.dp,
                    centerLift = 24.dp,
                    sliderBottomOffset = 28.dp,
                    sliderTouchHeight = 28.dp,
                    sliderScaleY = 0.72f,
                    timeSize = 14.dp.value.sp,
                    headerIconSize = 24.dp,
                    sideButtonPadding = 14.dp,
                    sideIconSize = 34.dp,
                    playButtonPadding = 18.dp,
                    playIconSize = 44.dp,
                )
                width >= 1024.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 24.dp,
                    verticalPadding = 20.dp,
                    titleSize = 24.dp.value.sp,
                    episodeInfoSize = 15.dp.value.sp,
                    metadataSize = 13.dp.value.sp,
                    centerGap = 88.dp,
                    centerLift = 18.dp,
                    sliderBottomOffset = 24.dp,
                    sliderTouchHeight = 26.dp,
                    sliderScaleY = 0.74f,
                    timeSize = 13.dp.value.sp,
                    headerIconSize = 22.dp,
                    sideButtonPadding = 13.dp,
                    sideIconSize = 32.dp,
                    playButtonPadding = 16.dp,
                    playIconSize = 42.dp,
                )
                width >= 768.dp -> PlayerLayoutMetrics(
                    horizontalPadding = 20.dp,
                    verticalPadding = 16.dp,
                    titleSize = 22.dp.value.sp,
                    episodeInfoSize = 14.dp.value.sp,
                    metadataSize = 12.dp.value.sp,
                    centerGap = 72.dp,
                    centerLift = 14.dp,
                    sliderBottomOffset = 20.dp,
                    sliderTouchHeight = 24.dp,
                    sliderScaleY = 0.78f,
                    timeSize = 12.dp.value.sp,
                    headerIconSize = 20.dp,
                    sideButtonPadding = 12.dp,
                    sideIconSize = 30.dp,
                    playButtonPadding = 15.dp,
                    playIconSize = 38.dp,
                )
                else -> PlayerLayoutMetrics(
                    horizontalPadding = 20.dp,
                    verticalPadding = 16.dp,
                    titleSize = 18.dp.value.sp,
                    episodeInfoSize = 14.dp.value.sp,
                    metadataSize = 12.dp.value.sp,
                    centerGap = 56.dp,
                    centerLift = 10.dp,
                    sliderBottomOffset = 16.dp,
                    sliderTouchHeight = 22.dp,
                    sliderScaleY = 0.82f,
                    timeSize = 12.dp.value.sp,
                    headerIconSize = 20.dp,
                    sideButtonPadding = 10.dp,
                    sideIconSize = 26.dp,
                    playButtonPadding = 13.dp,
                    playIconSize = 34.dp,
                )
            }
    }
}

@Composable
internal fun playerHorizontalSafePadding(): Dp {
    val layoutDirection = LocalLayoutDirection.current
    val safePadding = WindowInsets.safeContent.asPaddingValues()
    val left = safePadding.calculateLeftPadding(layoutDirection)
    val right = safePadding.calculateRightPadding(layoutDirection)
    return if (left > right) left else right
}

@Composable
internal fun playerTimelineBottomInsets(metrics: PlayerLayoutMetrics): WindowInsets {
    val safeInsets = WindowInsets.safeContent.only(WindowInsetsSides.Bottom)
    val contentInsets = WindowInsets(bottom = metrics.sliderBottomOffset / 2)
    return if (isIos) safeInsets.union(contentInsets) else safeInsets.add(contentInsets)
}

internal fun PlayerResizeMode.next(): PlayerResizeMode =
    when (this) {
        PlayerResizeMode.Fit -> PlayerResizeMode.Fill
        PlayerResizeMode.Fill -> PlayerResizeMode.Zoom
        PlayerResizeMode.Zoom -> PlayerResizeMode.Fit
    }

internal val PlayerResizeMode.labelRes: StringResource
    get() = when (this) {
        PlayerResizeMode.Fit -> Res.string.compose_player_resize_fit
        PlayerResizeMode.Fill -> Res.string.compose_player_resize_fill
        PlayerResizeMode.Zoom -> Res.string.compose_player_resize_zoom
    }

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun formatPlaybackRuntime(positionMs: Long, durationMs: Long, showRemainingTime: Boolean): String =
    if (showRemainingTime) {
        val remainingMs = (durationMs.coerceAtLeast(0L) - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        "−${formatPlaybackTime(remainingMs)}"
    } else {
        "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}"
    }

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    val normalized = speed.toString().trimEnd('0').trimEnd('.')
    return "${normalized}x"
}
