package com.nuvio.app.features.mdblist

import platform.Foundation.NSUserDefaults

internal actual object PlatformMdbListSyncStorage : MdbListSyncStorage {
    private const val suite = "nuvio_mdblist_sync"
    private val preferences = NSUserDefaults(suiteName = suite)

    actual override suspend fun load(profileId: Int): String? = preferences.stringForKey("profile.$profileId")

    actual override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        checkScope()
        preferences.setObject(payload, forKey = "profile.$profileId")
    }

    actual override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        preferences.removeObjectForKey("profile.$profileId")
    }

    actual fun clearAll() {
        preferences.removePersistentDomainForName(suite)
    }
}
