package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"
    private const val legacyMigrationKey = "downloads_legacy_migration_complete"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    actual fun isLegacyMigrationComplete(): Boolean =
        preferences?.getBoolean(ProfileScopedKey.of(legacyMigrationKey), false) == true

    actual fun markLegacyMigrationComplete() {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(legacyMigrationKey), true)
            ?.apply()
    }
}
