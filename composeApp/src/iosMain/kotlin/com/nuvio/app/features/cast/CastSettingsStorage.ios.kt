package com.nuvio.app.features.cast

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object CastSettingsStorage {
    private const val proxyEnabledKey = "cast_proxy_enabled"
    private const val transcodeModeKey = "cast_transcode_mode"
    private const val maxResolutionKey = "cast_max_resolution"
    private const val useHardwareAccelerationKey = "cast_use_hw_accel"
    private const val transcodeAudioToAacKey = "cast_transcode_audio_aac"

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

    private fun loadBoolean(keyBase: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(keyBase)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    private fun saveBoolean(keyBase: String, value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = ProfileScopedKey.of(keyBase))
    }

    private fun loadString(keyBase: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase))

    private fun saveString(keyBase: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(keyBase))
    }
}
