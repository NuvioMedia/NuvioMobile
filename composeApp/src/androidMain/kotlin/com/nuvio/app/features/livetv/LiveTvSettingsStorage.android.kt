package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object LiveTvSettingsStorage {
    private const val preferencesName = "nuvio_live_tv_settings"
    private const val plutoEnabledKey = "pluto_enabled"
    private const val xtreamConfigKey = "xtream_config"
    private const val favoritesKey = "favorite_channel_ids"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPlutoEnabled(): Boolean? =
        preferences?.let { prefs ->
            val key = ProfileScopedKey.of(plutoEnabledKey)
            if (prefs.contains(key)) prefs.getBoolean(key, true) else null
        }

    actual fun savePlutoEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(plutoEnabledKey), enabled)
            ?.apply()
    }

    actual fun loadXtreamConfigJson(): String? =
        preferences?.getString(ProfileScopedKey.of(xtreamConfigKey), null)

    actual fun saveXtreamConfigJson(json: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(xtreamConfigKey), json)
            ?.apply()
    }

    actual fun loadFavoriteChannelIdsJson(): String? =
        preferences?.getString(ProfileScopedKey.of(favoritesKey), null)

    actual fun saveFavoriteChannelIdsJson(json: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(favoritesKey), json)
            ?.apply()
    }
}
