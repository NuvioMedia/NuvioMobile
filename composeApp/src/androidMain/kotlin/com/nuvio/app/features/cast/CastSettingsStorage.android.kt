package com.nuvio.app.features.cast

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CastSettingsStorage {
    private const val preferencesName = "nuvio_cast_settings"
    private const val proxyEnabledKey = "cast_proxy_enabled"
    private const val transcodeModeKey = "cast_transcode_mode"
    private const val maxResolutionKey = "cast_max_resolution"
    private const val useHardwareAccelerationKey = "cast_use_hw_accel"
    private const val transcodeAudioToAacKey = "cast_transcode_audio_aac"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadProxyEnabled(): Boolean? = loadBoolean(proxyEnabledKey)
    actual fun saveProxyEnabled(enabled: Boolean) = saveBoolean(proxyEnabledKey, enabled)

    actual fun loadTranscodeMode(): String? = loadString(transcodeModeKey)
    actual fun saveTranscodeMode(mode: String) = saveString(transcodeModeKey, mode)

    actual fun loadMaxResolution(): String? = loadString(maxResolutionKey)
    actual fun saveMaxResolution(resolution: String) = saveString(maxResolutionKey, resolution)

    actual fun loadUseHardwareAcceleration(): Boolean? = loadBoolean(useHardwareAccelerationKey)
    actual fun saveUseHardwareAcceleration(enabled: Boolean) = saveBoolean(useHardwareAccelerationKey, enabled)

    actual fun loadTranscodeAudioToAac(): Boolean? = loadBoolean(transcodeAudioToAacKey)
    actual fun saveTranscodeAudioToAac(enabled: Boolean) = saveBoolean(transcodeAudioToAacKey, enabled)

    private fun loadBoolean(keyBase: String): Boolean? =
        preferences?.let { sp ->
            val key = ProfileScopedKey.of(keyBase)
            if (sp.contains(key)) sp.getBoolean(key, false) else null
        }

    private fun saveBoolean(keyBase: String, value: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(keyBase), value)?.apply()
    }

    private fun loadString(keyBase: String): String? =
        preferences?.getString(ProfileScopedKey.of(keyBase), null)

    private fun saveString(keyBase: String, value: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(keyBase), value)?.apply()
    }
}
