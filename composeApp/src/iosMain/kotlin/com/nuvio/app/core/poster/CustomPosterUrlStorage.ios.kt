package com.nuvio.app.core.poster

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object CustomPosterUrlStorage {
    private const val patternKey = "custom_poster_url_pattern"

    actual fun loadPattern(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(patternKey))

    actual fun savePattern(pattern: String?) {
        if (pattern.isNullOrBlank()) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(patternKey))
        } else {
            NSUserDefaults.standardUserDefaults.setObject(pattern.trim(), forKey = ProfileScopedKey.of(patternKey))
        }
    }
}
