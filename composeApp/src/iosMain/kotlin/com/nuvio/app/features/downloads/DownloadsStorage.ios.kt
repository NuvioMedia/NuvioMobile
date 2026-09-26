package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val legacyMigrationKey = "downloads_legacy_migration_complete"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun isLegacyMigrationComplete(): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(ProfileScopedKey.of(legacyMigrationKey))

    actual fun markLegacyMigrationComplete() {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = ProfileScopedKey.of(legacyMigrationKey))
    }
}
