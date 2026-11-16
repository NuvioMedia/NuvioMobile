package com.nuvio.app.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

private const val AUDIO_OUTPUT_TAG = "NuvioAudioOutput"

/** Lets flapping add/remove events settle into one output decision. */
private const val AUDIO_OUTPUT_CHANGE_DEBOUNCE_MS = 700L

private const val OUTPUT_RANK_UNSUPPORTED = 100

@Composable
internal actual fun rememberAudioOutputKey(enabled: Boolean): String? {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    var outputKey by remember(audioManager, enabled) {
        mutableStateOf(if (enabled) audioManager?.mediaOutputKey() else null)
    }

    DisposableEffect(audioManager, enabled) {
        if (!enabled || audioManager == null) return@DisposableEffect onDispose {}
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable {
            val key = audioManager.mediaOutputKey()
            if (key != outputKey) {
                Log.d(AUDIO_OUTPUT_TAG, "Media audio output: $key")
                outputKey = key
            }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleRefresh()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = scheduleRefresh()

            private fun scheduleRefresh() {
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, AUDIO_OUTPUT_CHANGE_DEBOUNCE_MS)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, handler)
        onDispose {
            handler.removeCallbacks(refresh)
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    return outputKey
}

/** Whether media audio plays on a Bluetooth device right now. */
@SuppressLint("InlinedApi")
internal fun Context.isMediaOutputBluetooth(): Boolean {
    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return when (audioManager.mediaOutputDevice()?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
        else -> false
    }
}

/**
 * Key of the device media audio plays on, such as `type:bluetooth_a2dp|name:buds_pro`. API 33+
 * asks where media is routed; older versions pick the likeliest connected output.
 */
private fun AudioManager.mediaOutputKey(): String? = mediaOutputDevice()?.outputKey()

private fun AudioManager.mediaOutputDevice(): AudioDeviceInfo? =
    mediaRoutedDevice()
        ?: getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { outputRank(it.type) }
            ?.takeIf { outputRank(it.type) < OUTPUT_RANK_UNSUPPORTED }

private fun AudioManager.mediaRoutedDevice(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        getAudioDevicesForAttributes(attributes).firstOrNull()
    }.getOrNull()
}

private fun AudioDeviceInfo.outputKey(): String {
    val typeName = outputTypeName(type)
    val name = productName?.toString()
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace(Regex("\\s+"), "_")
        ?.replace(Regex("[^a-z0-9._:-]"), "")
        ?.ifBlank { null }
        ?: typeName
    return "type:$typeName|name:$name"
}

/** Lower is likelier to carry media while several outputs are connected. */
@SuppressLint("InlinedApi")
private fun outputRank(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
    AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> 3
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 10
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> 20
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_HDMI_EARC -> 30
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 40
    else -> OUTPUT_RANK_UNSUPPORTED
}

@SuppressLint("InlinedApi")
private fun outputTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
    else -> "type_$type"
}
