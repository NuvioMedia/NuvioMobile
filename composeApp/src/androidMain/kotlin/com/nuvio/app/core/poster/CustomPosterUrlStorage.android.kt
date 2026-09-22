package com.nuvio.app.core.poster

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object CustomPosterUrlStorage {
    private const val preferencesName = "nuvio_custom_poster_url"
    private const val patternKey = "custom_poster_url_pattern"

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
}
