package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object LiveTvSettingsStorage {
    private const val plutoEnabledKey = "pluto_enabled"
    private const val xtreamConfigKey = "xtream_config"
    private const val favoritesKey = "favorite_channel_ids"

    actual fun loadPlutoEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(plutoEnabledKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    actual fun savePlutoEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(plutoEnabledKey))
    }

    actual fun loadXtreamConfigJson(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(xtreamConfigKey))

    actual fun saveXtreamConfigJson(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = ProfileScopedKey.of(xtreamConfigKey))
    }

    actual fun loadFavoriteChannelIdsJson(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(favoritesKey))

    actual fun saveFavoriteChannelIdsJson(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = ProfileScopedKey.of(favoritesKey))
    }
}
