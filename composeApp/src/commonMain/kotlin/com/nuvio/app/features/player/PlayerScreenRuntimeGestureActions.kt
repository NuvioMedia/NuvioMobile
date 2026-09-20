package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlayerSurfaceGestureCallbacks(
    val onSurfaceTap: State<(Offset) -> Unit>,
    val onSurfaceDoubleTap: State<(Offset) -> Unit>,
    val activateHoldToSpeed: State<() -> Unit>,
    val deactivateHoldToSpeed: State<() -> Unit>,
    val showHorizontalSeekPreview: State<(Long, Long) -> Unit>,
    val showBrightnessFeedback: State<(Float) -> Unit>,
    val showVolumeFeedback: State<(PlayerAudioLevel) -> Unit>,
    val clearLiveGestureFeedback: State<() -> Unit>,
    val revealLockedOverlay: State<() -> Unit>,
    val isHoldToSpeedGestureActive: State<Boolean>,
    val touchGesturesEnabled: State<Boolean>,
    val playerControlsLocked: State<Boolean>,
    val currentPositionMs: State<Long>,
    val currentDurationMs: State<Long>,
    val commitHorizontalSeek: State<(Long) -> Unit>,
)

internal fun PlayerScreenRuntime.showGestureFeedback(feedback: GestureFeedbackState) {
    gestureMessageJob?.cancel()
    gestureFeedback = feedback
    gestureMessageJob = scope.launch {
        delay(900)
        gestureFeedback = null
    }
}

internal fun PlayerScreenRuntime.showGestureMessage(message: String) {
    showGestureFeedback(GestureFeedbackState(message = message))
}

internal fun PlayerScreenRuntime.clearLiveGestureFeedback() {
    liveGestureFeedback = null
}

internal fun PlayerScreenRuntime.revealLockedOverlay() {
    controlsVisible = false
    lockedOverlayVisible = true
}

internal fun PlayerScreenRuntime.lockPlayerControls() {
    playerControlsLocked = true
    controlsVisible = false
    lockedOverlayVisible = false
    pausedOverlayVisible = false
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    gestureMessageJob?.cancel()
    gestureFeedback = null
    liveGestureFeedback = null
    renderedGestureFeedback = null
    showAudioModal = false
    showSubtitleModal = false
    showVideoSettingsModal = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    PlayerStreamsRepository.clearEpisodeStreams()
}

internal fun PlayerScreenRuntime.unlockPlayerControls() {
    playerControlsLocked = false
    lockedOverlayVisible = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.showSeekFeedback(direction: PlayerSeekDirection, amountMs: Long) {
    val seconds = amountMs / 1000L
    if (seconds <= 0L) return
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (direction == PlayerSeekDirection.Forward) {
                Res.string.compose_player_seek_feedback_forward
            } else {
                Res.string.compose_player_seek_feedback_backward
            },
            messageArgs = listOf(seconds),
            icon = if (direction == PlayerSeekDirection.Forward) {
                GestureFeedbackIcon.SeekForward
            } else {
                GestureFeedbackIcon.SeekBackward
            },
        ),
    )
}

internal fun PlayerScreenRuntime.showHorizontalSeekPreview(previewPositionMs: Long, baselinePositionMs: Long) {
    val deltaMs = previewPositionMs - baselinePositionMs
    val direction = if (deltaMs < 0L) PlayerSeekDirection.Backward else PlayerSeekDirection.Forward
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackTime(previewPositionMs),
        icon = if (direction == PlayerSeekDirection.Forward) {
            GestureFeedbackIcon.SeekForward
        } else {
            GestureFeedbackIcon.SeekBackward
        },
        secondaryMessageRes = if (deltaMs >= 0L) {
            Res.string.compose_player_seek_delta_forward
        } else {
            Res.string.compose_player_seek_delta_backward
        },
        secondaryMessageArgs = listOf((abs(deltaMs) / 1000f).roundToInt()),
        secondaryMessageColor = if (direction == PlayerSeekDirection.Forward) {
            Color(0xFF6EE7A8)
        } else {
            Color(0xFFFF9A76)
        },
    )
}

internal fun PlayerScreenRuntime.showBrightnessFeedback(level: Float) {
    val percentage = (level.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.compose_player_brightness_level,
            messageArgs = listOf("$percentage%"),
            icon = GestureFeedbackIcon.Brightness,
            level = level.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.showVolumeFeedback(level: PlayerAudioLevel) {
    val percentage = (level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = if (level.isMuted) {
                Res.string.compose_player_muted
            } else {
                Res.string.compose_player_volume_level
            },
            messageArgs = if (level.isMuted) emptyList() else listOf("$percentage%"),
            icon = if (level.isMuted) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
            isDanger = level.isMuted,
            level = if (level.isMuted) 0f else level.fraction.coerceIn(0f, 1f),
        ),
    )
}

internal fun PlayerScreenRuntime.togglePlayback() {
    if (playbackSnapshot.isPlaying) {
        shouldPlay = false
        playerController?.pause()
    } else {
        if (playbackSnapshot.isEnded) {
            playerController?.seekTo(0L)
        }
        shouldPlay = true
        playerController?.play()
    }
    controlsVisible = true
}

internal fun PlayerScreenRuntime.seekBy(offsetMs: Long) {
    val fromMs = playbackSnapshot.positionMs
    val targetMs = (fromMs + offsetMs).coerceAtLeast(0L)
        .let { if (playbackSnapshot.durationMs > 0L) it.coerceAtMost(playbackSnapshot.durationMs) else it }
    lastManualSkipSeekPositions = fromMs to targetMs
    playerController?.seekBy(offsetMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
    when {
        offsetMs > 0L -> showSeekFeedback(PlayerSeekDirection.Forward, offsetMs)
        offsetMs < 0L -> showSeekFeedback(PlayerSeekDirection.Backward, abs(offsetMs))
    }
}

internal fun PlayerScreenRuntime.handleDoubleTapSeek(direction: PlayerSeekDirection) {
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val currentSeekState = accumulatedSeekState
    val nextState = if (currentSeekState?.direction == direction) {
        currentSeekState.copy(amountMs = currentSeekState.amountMs + PlayerDoubleTapSeekStepMs)
    } else {
        PlayerAccumulatedSeekState(
            direction = direction,
            baselinePositionMs = currentPositionMs,
            amountMs = PlayerDoubleTapSeekStepMs,
        )
    }
    accumulatedSeekState = nextState

    val maxDurationMs = playbackSnapshot.durationMs.takeIf { it > 0L }
    val targetPositionMs = when (direction) {
        PlayerSeekDirection.Backward -> {
            (nextState.baselinePositionMs - nextState.amountMs).coerceAtLeast(0L)
        }
        PlayerSeekDirection.Forward -> {
            val unclamped = nextState.baselinePositionMs + nextState.amountMs
            maxDurationMs?.let { unclamped.coerceAtMost(it) } ?: unclamped
        }
    }
    lastManualSkipSeekPositions = currentPositionMs to targetPositionMs
    playerController?.seekTo(targetPositionMs)
    scheduleProgressSyncAfterSeek()
    showSeekFeedback(direction, nextState.amountMs)

    accumulatedSeekResetJob?.cancel()
    accumulatedSeekResetJob = scope.launch {
        delay(PlayerDoubleTapSeekResetDelayMs)
        accumulatedSeekState = null
    }
}

internal fun PlayerScreenRuntime.cycleResizeMode() {
    val nextMode = resizeMode.next()
    resizeMode = nextMode
    lastSyncedSettingsResizeMode = nextMode
    PlayerSettingsRepository.setResizeMode(nextMode)
    showGestureMessage(
        when (nextMode) {
            PlayerResizeMode.Fit -> resizeModeFitLabel
            PlayerResizeMode.Fill -> resizeModeFillLabel
            PlayerResizeMode.Zoom -> resizeModeZoomLabel
        },
    )
    controlsVisible = true
}

internal fun PlayerScreenRuntime.cyclePlaybackSpeed() {
    val speeds = listOf(1f, 1.25f, 1.5f, 2f)
    val current = playbackSnapshot.playbackSpeed
    val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
    playerController?.setPlaybackSpeed(next)
    showGestureMessage(formatPlaybackSpeedLabel(next))
    controlsVisible = true
}

internal fun PlayerScreenRuntime.activateHoldToSpeed() {
    if (!playerSettingsUiState.holdToSpeedEnabled) return
    val controller = playerController ?: return
    if (speedBoostRestoreSpeed != null) return

    val targetSpeed = playerSettingsUiState.holdToSpeedValue
    val currentSpeed = playbackSnapshot.playbackSpeed
    if (abs(currentSpeed - targetSpeed) < 0.01f) return

    isHoldToSpeedGestureActive = true
    speedBoostRestoreSpeed = currentSpeed
    controller.setPlaybackSpeed(targetSpeed)
    liveGestureFeedback = GestureFeedbackState(
        message = formatPlaybackSpeedLabel(targetSpeed),
        icon = GestureFeedbackIcon.Speed,
    )
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

internal fun PlayerScreenRuntime.deactivateHoldToSpeed() {
    isHoldToSpeedGestureActive = false
    val restoreSpeed = speedBoostRestoreSpeed ?: return
    playerController?.setPlaybackSpeed(restoreSpeed)
    speedBoostRestoreSpeed = null
    liveGestureFeedback = null
}

@Composable
internal fun PlayerScreenRuntime.rememberSurfaceGestureCallbacks(): PlayerSurfaceGestureCallbacks {
    val onSurfaceTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        val centerStart = layoutSize.width * PlayerLeftGestureBoundary
        val centerEnd = layoutSize.width * PlayerRightGestureBoundary
        if (controlsVisible && offset.x in centerStart..centerEnd) {
            controlsVisible = false
        } else {
            controlsVisible = !controlsVisible
        }
    }
    val onSurfaceDoubleTap = rememberUpdatedState { offset: Offset ->
        if (playerControlsLocked) {
            revealLockedOverlay()
            return@rememberUpdatedState
        }
        if (!playerSettingsUiState.touchGesturesEnabled) {
            controlsVisible = !controlsVisible
            return@rememberUpdatedState
        }
        when {
            offset.x < layoutSize.width * PlayerLeftGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Backward)
            }
            offset.x > layoutSize.width * PlayerRightGestureBoundary -> {
                handleDoubleTapSeek(PlayerSeekDirection.Forward)
            }
            else -> controlsVisible = !controlsVisible
        }
    }
    return PlayerSurfaceGestureCallbacks(
        onSurfaceTap = onSurfaceTap,
        onSurfaceDoubleTap = onSurfaceDoubleTap,
        activateHoldToSpeed = rememberUpdatedState(::activateHoldToSpeed),
        deactivateHoldToSpeed = rememberUpdatedState(::deactivateHoldToSpeed),
        showHorizontalSeekPreview = rememberUpdatedState(::showHorizontalSeekPreview),
        showBrightnessFeedback = rememberUpdatedState(::showBrightnessFeedback),
        showVolumeFeedback = rememberUpdatedState(::showVolumeFeedback),
        clearLiveGestureFeedback = rememberUpdatedState(::clearLiveGestureFeedback),
        revealLockedOverlay = rememberUpdatedState(::revealLockedOverlay),
        isHoldToSpeedGestureActive = rememberUpdatedState(isHoldToSpeedGestureActive),
        touchGesturesEnabled = rememberUpdatedState(playerSettingsUiState.touchGesturesEnabled),
        playerControlsLocked = rememberUpdatedState(playerControlsLocked),
        currentPositionMs = rememberUpdatedState(playbackSnapshot.positionMs.coerceAtLeast(0L)),
        currentDurationMs = rememberUpdatedState(playbackSnapshot.durationMs),
        commitHorizontalSeek = rememberUpdatedState { targetPositionMs: Long ->
            lastManualSkipSeekPositions = playbackSnapshot.positionMs to targetPositionMs
            playerController?.seekTo(targetPositionMs)
            scheduleProgressSyncAfterSeek()
        },
    )
}
