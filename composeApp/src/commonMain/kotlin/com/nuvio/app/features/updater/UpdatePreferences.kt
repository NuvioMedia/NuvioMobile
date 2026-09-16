package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UpdatePreferences(versionName: String = AppVersionConfig.VERSION_NAME) {
    private val storedChannel = UpdateChannel.fromStoredValue(AppUpdaterPlatform.getUpdateChannel())
    private val _channel = MutableStateFlow(storedChannel ?: UpdateChannel.defaultForVersion(versionName))
    val channel = _channel.asStateFlow()

    init {
        if (storedChannel == null && AppUpdaterPlatform.isSupported) {
            AppUpdaterPlatform.setUpdateChannel(_channel.value.storedValue)
        }
    }

    fun setChannel(channel: UpdateChannel) {
        if (_channel.value == channel) return
        AppUpdaterPlatform.setUpdateChannel(channel.storedValue)
        AppUpdaterPlatform.setIgnoredTag(null)
        _channel.value = channel
    }

    companion object {
        val shared by lazy { UpdatePreferences() }
    }
}
