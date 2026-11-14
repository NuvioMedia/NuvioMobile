package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val PlayerSubtitleLiftGap = 8.dp

internal val PlayerSubtitleRoomSpacing = 12.dp

internal const val PLAYER_SUBTITLE_MAX_LIFT = 0.6f

internal const val PLAYER_SUBTITLE_ROOM_LINES = 2.5f

internal fun Modifier.reportSubtitleClearance(gapPx: Float, onClearance: (Float) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val rootHeight = coordinates.findRootCoordinates().size.height.toFloat()
        val clearance = if (coordinates.size.height == 0 || rootHeight <= 0f) {
            0f
        } else {
            ((rootHeight - coordinates.boundsInRoot().top + gapPx) / rootHeight).coerceIn(0f, PLAYER_SUBTITLE_MAX_LIFT)
        }
        onClearance(clearance)
    }

internal fun subtitleLiftFraction(vararg clearances: Float): Float =
    (clearances.maxOrNull() ?: 0f).coerceIn(0f, PLAYER_SUBTITLE_MAX_LIFT)

internal fun mpvSubtitlePositionWithLift(position: Int, liftFraction: Float): Int {
    if (liftFraction <= 0f) return position
    return minOf(position, (100f * (1f - liftFraction)).roundToInt())
}

internal const val PLAYER_SUBTITLE_LIFT_DROP_TIMEOUT_MS = 3_000L

internal data class SubtitleLiftDecision(
    val appliedLift: Float,
    val pendingDropCueVersion: Long?,
)

internal const val PLAYER_CONTROLS_FADE_OUT_MS = 300L

internal fun shouldApplySubtitleLiftImmediately(paused: Boolean, controlsVisible: Boolean): Boolean =
    paused || !controlsVisible

internal fun nextSubtitleLift(
    targetLift: Float,
    appliedLift: Float,
    cueVersion: Long,
    cueVisible: Boolean,
    applyImmediately: Boolean,
    pendingDropCueVersion: Long?,
    dropTimedOut: Boolean,
): SubtitleLiftDecision {
    if (targetLift >= appliedLift || !cueVisible || applyImmediately || dropTimedOut) {
        return SubtitleLiftDecision(targetLift, null)
    }
    if (pendingDropCueVersion == null) return SubtitleLiftDecision(appliedLift, cueVersion)
    if (cueVersion != pendingDropCueVersion) return SubtitleLiftDecision(targetLift, null)
    return SubtitleLiftDecision(appliedLift, pendingDropCueVersion)
}
