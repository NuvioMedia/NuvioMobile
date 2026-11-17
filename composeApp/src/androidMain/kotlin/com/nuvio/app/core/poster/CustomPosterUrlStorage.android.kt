package com.nuvio.app.core.poster

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object CustomPosterUrlStorage {
    private const val preferencesName = "nuvio_custom_poster_url"
    private const val patternKey = "custom_poster_url_pattern"
    private const val enabledScreensKey = "custom_poster_enabled_screens"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPattern(): String? =
        preferences?.getString(ProfileScopedKey.of(patternKey), null)

    actual fun savePattern(pattern: String?) {
        preferences
            ?.edit()
            ?.apply {
                if (pattern.isNullOrBlank()) remove(ProfileScopedKey.of(patternKey))
                else putString(ProfileScopedKey.of(patternKey), pattern.trim())
            }
            ?.apply()
    }

    actual fun loadEnabledScreens(): Set<String>? =
        preferences?.getStringSet(ProfileScopedKey.of(enabledScreensKey), null)

    actual fun saveEnabledScreens(keys: Set<String>?) {
        preferences
            ?.edit()
            ?.apply {
                if (keys == null) remove(ProfileScopedKey.of(enabledScreensKey))
                else putStringSet(ProfileScopedKey.of(enabledScreensKey), keys)
            }
            ?.apply()
    }
}
