package com.nuvio.app.features.livetv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LiveTvSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private var hasLoaded = false
    private var plutoEnabledValue = true
    private var xtreamConfigValue = XtreamCodesConfig()
    private var favoriteChannelIdsValue = emptySet<String>()

    private val _plutoEnabled = MutableStateFlow(true)
    val plutoEnabled: StateFlow<Boolean> = _plutoEnabled.asStateFlow()

    private val _xtreamConfig = MutableStateFlow(XtreamCodesConfig())
    val xtreamConfig: StateFlow<XtreamCodesConfig> = _xtreamConfig.asStateFlow()

    private val _favoriteChannelIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteChannelIds: StateFlow<Set<String>> = _favoriteChannelIds.asStateFlow()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun setPlutoEnabled(enabled: Boolean) {
        ensureLoaded()
        if (plutoEnabledValue == enabled) return
        plutoEnabledValue = enabled
        _plutoEnabled.value = enabled
        LiveTvSettingsStorage.savePlutoEnabled(enabled)
        LiveTvRepository.refresh()
    }

    fun updateXtreamConfig(config: XtreamCodesConfig) {
        ensureLoaded()
        if (xtreamConfigValue == config) return
        xtreamConfigValue = config
        _xtreamConfig.value = config
        LiveTvSettingsStorage.saveXtreamConfigJson(json.encodeToString(config))
        LiveTvRepository.refresh()
    }

    fun toggleFavorite(channelId: String) {
        ensureLoaded()
        favoriteChannelIdsValue = if (channelId in favoriteChannelIdsValue) {
            favoriteChannelIdsValue - channelId
        } else {
            favoriteChannelIdsValue + channelId
        }
        _favoriteChannelIds.value = favoriteChannelIdsValue
        LiveTvSettingsStorage.saveFavoriteChannelIdsJson(
            json.encodeToString(favoriteChannelIdsValue.toList()),
        )
        LiveTvRepository.publishFavorites(favoriteChannelIdsValue)
    }

    fun isFavorite(channelId: String): Boolean =
        channelId in favoriteChannelIdsValue

    private fun loadFromDisk() {
        plutoEnabledValue = LiveTvSettingsStorage.loadPlutoEnabled() ?: true
        xtreamConfigValue = LiveTvSettingsStorage.loadXtreamConfigJson()
            ?.let { runCatching { json.decodeFromString<XtreamCodesConfig>(it) }.getOrNull() }
            ?: XtreamCodesConfig()
        favoriteChannelIdsValue = LiveTvSettingsStorage.loadFavoriteChannelIdsJson()
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?.toSet()
            ?: emptySet()
        _plutoEnabled.value = plutoEnabledValue
        _xtreamConfig.value = xtreamConfigValue
        _favoriteChannelIds.value = favoriteChannelIdsValue
        hasLoaded = true
    }
}
