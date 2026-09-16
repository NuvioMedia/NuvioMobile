package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import platform.Foundation.NSNotificationCenter
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIScreen
import platform.UIKit.UISlider

private const val lockPlayerToLandscapeNotification = "NuvioPlayerLockLandscape"
private const val unlockPlayerOrientationNotification = "NuvioPlayerUnlockOrientation"
private var playerLandscapeLockCount = 0

@Composable
actual fun LockPlayerToLandscape() {
    DisposableEffect(Unit) {
        playerLandscapeLockCount += 1
        if (playerLandscapeLockCount == 1) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                lockPlayerToLandscapeNotification,
                null,
            )
        }

        onDispose {
            playerLandscapeLockCount -= 1
            if (playerLandscapeLockCount == 0) {
                NSNotificationCenter.defaultCenter.postNotificationName(
                    unlockPlayerOrientationNotification,
                    null,
                )
            }
        }
    }
}

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            useSoftwareKeyboardInset = false,
        ),
        content = content,
    )
}

@Composable
actual fun HidePlayerSystemBars() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.setIdleTimerDisabled(keepScreenAwake)
    }

    DisposableEffect(Unit) {
        onDispose {
            UIApplication.sharedApplication.setIdleTimerDisabled(false)
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? {
    val controller = remember { IOSPlayerGestureController() }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private class IOSPlayerGestureController : PlayerGestureController {
    private val volumeView = MPVolumeView().apply {
        hidden = true
        alpha = 0.01
    }
    private val originalBrightness = UIScreen.mainScreen.brightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float =
        UIScreen.mainScreen.brightness.toFloat().coerceIn(0f, 1f)

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        UIScreen.mainScreen.brightness = target.toDouble()
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val current = (volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()?.value ?: 0f)
            .coerceIn(0f, 1f)
        return PlayerAudioLevel(
            fraction = current,
            isMuted = current <= 0.001f,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val target = level.coerceIn(0f, 1f)
        val slider = volumeView.subviews.filterIsInstance<UISlider>().firstOrNull()
            ?: return currentVolume()
        slider.value = target
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        return PlayerAudioLevel(
            fraction = target,
            isMuted = target <= 0.001f,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true
        UIScreen.mainScreen.brightness = originalBrightness
    }
}
