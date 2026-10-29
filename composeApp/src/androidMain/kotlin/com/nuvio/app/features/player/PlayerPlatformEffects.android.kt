package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.ComponentActivity
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

@Composable
actual fun LockPlayerToLandscape() {
    val activity = LocalContext.current.findActivity() ?: return
    if (!activity.shouldForceLandscapePlayer()) return

    DisposableEffect(activity) {
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            activity.requestedOrientation = previousOrientation
        }
    }
}

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        HidePlayerSystemBars()
        content()
    }
}

@Composable
actual fun HidePlayerSystemBars() {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: activity.window

    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior

        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) = Unit

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) {
    val activity = LocalContext.current.findActivity() ?: return

    DisposableEffect(activity) {
        onDispose {
            PlayerPictureInPictureManager.clearSession(activity)
        }
    }

    SideEffect {
        PlayerPictureInPictureManager.updateSession(
            activity = activity,
            isActive = true,
            isPlaying = isPlaying,
            videoSize = videoSize,
        )
    }
}

@Composable
actual fun rememberIsInPictureInPicture(): Boolean {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    val componentActivity = activity as? ComponentActivity ?: return false
    var pipState by remember(activity) { mutableStateOf(componentActivity.isInPictureInPictureMode) }
    DisposableEffect(componentActivity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipState = info.isInPictureInPictureMode
        }
        componentActivity.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            componentActivity.removeOnPictureInPictureModeChangedListener(listener)
        }
    }
    return pipState
}

@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

    val controller = remember(activity, audioManager) {
        AndroidPlayerGestureController(
            activity = activity,
            audioManager = audioManager,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.shouldForceLandscapePlayer(): Boolean {
    if (resources.configuration.smallestScreenWidthDp >= 600) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) return false
    return true
}

private class AndroidPlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager,
) : PlayerGestureController {
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue in 0f..1f) {
            windowValue.coerceIn(0f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = target
        activity.window.attributes = attributes
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        val fraction = currentVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = currentVolume == 0,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        val fraction = targetVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = targetVolume == 0,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true

        val attributes = activity.window.attributes
        attributes.screenBrightness = when {
            originalBrightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> originalBrightness.coerceIn(0f, 1f)
        }
        activity.window.attributes = attributes
    }

    private fun readSystemBrightness(): Float =
        runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(127)
            .coerceIn(0, 255)
            .toFloat() / 255f
}
