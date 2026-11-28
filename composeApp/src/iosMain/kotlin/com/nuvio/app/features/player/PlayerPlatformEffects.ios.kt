package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.outputVolume
import platform.Foundation.NSNotificationCenter
import platform.MediaPlayer.MPVolumeView
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIScreen
import platform.UIKit.UISlider

private const val playerPictureInPictureDidStartNotification = "NuvioPlayerPictureInPictureDidStart"
private const val playerPictureInPictureDidStopNotification = "NuvioPlayerPictureInPictureDidStop"

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

// The iOS player has no audio delay control yet.
@Composable
internal actual fun rememberAudioOutputKey(enabled: Boolean): String? = null

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? {
    val controller = remember { IOSPlayerGestureController() }

    DisposableEffect(controller) {
        // La luminosità è globale: va restituita appena l'app perde il foreground
        // (Apple QA1751), altrimenti resta alzata su Home e PiP, dove il player non
        // viene distrutto e nessuno la ripristinerebbe.
        val resignObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationWillResignActiveNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            controller.restoreBrightness()
        }
        val activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            controller.reapplyBrightness()
        }
        // Su iPad il PiP può partire con l'app ancora attiva: nessun resign, quindi il
        // delegate del PiP è l'unico punto in cui restituire la luminosità.
        val pipStartObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = playerPictureInPictureDidStartNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            controller.restoreBrightness()
        }
        val pipStopObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = playerPictureInPictureDidStopNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            // Sulla Home il PiP si chiude con l'app in background: riaccendere lo schermo
            // lì sarebbe di nuovo il problema iniziale.
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) {
                controller.reapplyBrightness()
            }
        }

        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(resignObserver)
            NSNotificationCenter.defaultCenter.removeObserver(activeObserver)
            NSNotificationCenter.defaultCenter.removeObserver(pipStartObserver)
            NSNotificationCenter.defaultCenter.removeObserver(pipStopObserver)
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
    private var originalBrightness: Double? = null
    private var lastBrightness: Double? = null

    override fun currentBrightness(): Float =
        UIScreen.mainScreen.brightness.toFloat().coerceIn(0f, 1f)

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        if (originalBrightness == null) {
            originalBrightness = UIScreen.mainScreen.brightness
        }
        lastBrightness = target.toDouble()
        UIScreen.mainScreen.brightness = target.toDouble()
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val current = AVAudioSession.sharedInstance().outputVolume.coerceIn(0f, 1f)
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
        val original = originalBrightness ?: return
        originalBrightness = null
        UIScreen.mainScreen.brightness = original
    }

    fun reapplyBrightness() {
        val value = lastBrightness ?: return
        if (originalBrightness == null) {
            originalBrightness = UIScreen.mainScreen.brightness
        }
        UIScreen.mainScreen.brightness = value
    }
}
