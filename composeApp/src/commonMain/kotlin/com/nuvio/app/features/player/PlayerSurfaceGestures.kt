package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun Modifier.playerSurfaceTapGestures(
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    playerControlsLockedState: State<Boolean>,
    onSurfaceTap: State<(Offset) -> Unit>,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    activateHoldToSpeedState: State<() -> Unit>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
): Modifier =
    pointerInput(layoutSize, playbackGesturesEnabled) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                deactivateHoldToSpeedState.value()
            },
            onTap = { offset -> onSurfaceTap.value(offset) },
            onDoubleTap = if (playbackGesturesEnabled) {
                { offset -> onSurfaceDoubleTap.value(offset) }
            } else null,
            onLongPress = if (playbackGesturesEnabled) {
                {
                    if (playerControlsLockedState.value) {
                        revealLockedOverlayState.value()
                    } else {
                        activateHoldToSpeedState.value()
                    }
                }
            } else null,
        )
    }

internal fun Modifier.playerSurfaceDragGestures(
    gestureController: PlayerGestureController?,
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    sideGestureSystemEdgeExclusionPx: Float,
    playerControlsLockedState: State<Boolean>,
    touchGesturesEnabledState: State<Boolean>,
    isHoldToSpeedGestureActiveState: State<Boolean>,
    currentPositionMsState: State<Long>,
    currentDurationMsState: State<Long>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    showHorizontalSeekPreviewState: State<(Long, Long) -> Unit>,
    showBrightnessFeedbackState: State<(Float) -> Unit>,
    showVolumeFeedbackState: State<(PlayerAudioLevel) -> Unit>,
    clearLiveGestureFeedbackState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
    commitHorizontalSeekState: State<(Long) -> Unit>,
): Modifier =
    pointerInput(gestureController, layoutSize, sideGestureSystemEdgeExclusionPx, playbackGesturesEnabled) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            if (playerControlsLockedState.value) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                }
                return@awaitEachGesture
            }
            if (!touchGesturesEnabledState.value) {
                return@awaitEachGesture
            }
            val controller = gestureController
            val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val sideGestureEdgeExclusionPx = sideGestureSystemEdgeExclusionPx
                .coerceAtMost(height * 0.25f)
            val isInSideGestureSystemEdge =
                down.position.y <= sideGestureEdgeExclusionPx ||
                    down.position.y >= height - sideGestureEdgeExclusionPx
            val region = when {
                isInSideGestureSystemEdge -> null
                down.position.x < width * PlayerLeftGestureBoundary -> PlayerSideGesture.Brightness
                down.position.x > width * PlayerRightGestureBoundary -> PlayerSideGesture.Volume
                else -> null
            }

            val initialBrightness = if (region == PlayerSideGesture.Brightness) {
                controller?.currentBrightness()
            } else {
                null
            }
            val initialVolume = if (region == PlayerSideGesture.Volume) {
                controller?.currentVolume()
            } else {
                null
            }

            var totalDx = 0f
            var totalDy = 0f
            var gestureMode: PlayerGestureMode? = null
            var verticalGestureActivationDy = 0f
            val horizontalSeekBaselineMs = currentPositionMsState.value
            var horizontalSeekPreviewMs = horizontalSeekBaselineMs

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (gestureMode == null) {
                    val holdToSpeedActive = isHoldToSpeedGestureActiveState.value
                    val verticalGestureActivationSlop = maxOf(
                        viewConfiguration.touchSlop * PlayerVerticalGestureTouchSlopMultiplier,
                        height * PlayerVerticalGestureMinHeightFraction,
                    )
                    val horizontalDominant =
                        !holdToSpeedActive &&
                            abs(totalDx) > viewConfiguration.touchSlop &&
                            abs(totalDx) > abs(totalDy)
                    val verticalDominant =
                        !holdToSpeedActive &&
                            abs(totalDy) > verticalGestureActivationSlop &&
                            abs(totalDy) > abs(totalDx) * PlayerVerticalGestureDominanceRatio

                    gestureMode = when {
                        horizontalDominant -> {
                            deactivateHoldToSpeedState.value()
                            PlayerGestureMode.HorizontalSeek
                        }

                        verticalDominant && region == PlayerSideGesture.Brightness && initialBrightness != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Brightness
                        }

                        verticalDominant && region == PlayerSideGesture.Volume && initialVolume != null -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Volume
                        }

                        else -> null
                    }

                    if (gestureMode == null) {
                        continue
                    }
                }

                when (gestureMode) {
                    PlayerGestureMode.HorizontalSeek -> {
                        val sensitivitySeconds = when {
                            currentDurationMsState.value >= 3_600_000L -> 120f
                            currentDurationMsState.value >= 1_800_000L -> 90f
                            else -> 60f
                        }
                        val previewOffsetMs =
                            ((totalDx / width) * sensitivitySeconds * 1000f).roundToLong()
                        val unclampedPreviewMs = horizontalSeekBaselineMs + previewOffsetMs
                        horizontalSeekPreviewMs = currentDurationMsState.value
                            .takeIf { it > 0L }
                            ?.let { durationMs ->
                                unclampedPreviewMs.coerceIn(0L, durationMs)
                            }
                            ?: unclampedPreviewMs.coerceAtLeast(0L)
                        showHorizontalSeekPreviewState.value(
                            horizontalSeekPreviewMs,
                            horizontalSeekBaselineMs,
                        )
                    }

                    PlayerGestureMode.Brightness -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setBrightness((initialBrightness ?: 0f) + gestureDeltaFraction)
                            ?.let(showBrightnessFeedbackState.value)
                    }

                    PlayerGestureMode.Volume -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setVolume((initialVolume?.fraction ?: 0f) + gestureDeltaFraction)
                            ?.let(showVolumeFeedbackState.value)
                    }
                }
                change.consume()
            }

            if (gestureMode == PlayerGestureMode.HorizontalSeek && !isHoldToSpeedGestureActiveState.value) {
                commitHorizontalSeekState.value(horizontalSeekPreviewMs)
                clearLiveGestureFeedbackState.value()
            }
        }
    }
