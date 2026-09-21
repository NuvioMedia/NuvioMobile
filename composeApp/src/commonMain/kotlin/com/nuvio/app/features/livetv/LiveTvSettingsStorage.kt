package com.nuvio.app.features.livetv

internal expect object LiveTvSettingsStorage {
    fun loadPlutoEnabled(): Boolean?
    fun savePlutoEnabled(enabled: Boolean)
    fun loadXtreamConfigJson(): String?
    fun saveXtreamConfigJson(json: String)
    fun loadFavoriteChannelIdsJson(): String?
    fun saveFavoriteChannelIdsJson(json: String)
}
