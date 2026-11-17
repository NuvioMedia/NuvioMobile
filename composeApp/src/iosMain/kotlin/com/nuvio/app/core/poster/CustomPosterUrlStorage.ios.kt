package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object CustomPosterUrlStorage {
    private const val patternKey = "custom_poster_url_pattern"
    private const val enabledScreensKey = "custom_poster_enabled_screens"

    actual fun loadPattern(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(patternKey))

    actual fun savePattern(pattern: String?) {
        if (pattern.isNullOrBlank()) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(patternKey))
        } else {
            NSUserDefaults.standardUserDefaults.setObject(pattern.trim(), forKey = ProfileScopedKey.of(patternKey))
        }
    }

    @Suppress("UNCHECKED_CAST")
    actual fun loadEnabledScreens(): Set<String>? {
        val array = NSUserDefaults.standardUserDefaults.arrayForKey(
            ProfileScopedKey.of(enabledScreensKey)
        ) as? List<String> ?: return null
        return array.toSet()
    }

    actual fun saveEnabledScreens(keys: Set<String>?) {
        if (keys == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(enabledScreensKey))
        } else {
            NSUserDefaults.standardUserDefaults.setObject(keys.toList(), forKey = ProfileScopedKey.of(enabledScreensKey))
        }
    }
}
