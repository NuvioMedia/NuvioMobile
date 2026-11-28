package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UpdatePreferences(versionName: String = AppVersionConfig.VERSION_NAME) {
    private val storedChannel = UpdateChannel.fromStoredValue(AppUpdaterPlatform.getUpdateChannel())
    private val _channel = MutableStateFlow(storedChannel ?: UpdateChannel.defaultForVersion(versionName))
    val channel = _channel.asStateFlow()

    private val _showBanner = MutableStateFlow(AppUpdaterPlatform.getShowUpdateBanner())
    val showBanner = _showBanner.asStateFlow()

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

    fun setShowBanner(show: Boolean) {
        if (_showBanner.value == show) return
        AppUpdaterPlatform.setShowUpdateBanner(show)
        _showBanner.value = show
    }

    companion object {
        val shared by lazy { UpdatePreferences() }
    }
}
