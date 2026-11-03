package com.nuvio.app.features.cloudstream

internal actual object CloudStreamPlatformStorage {
    actual fun initialize(context: Any?) = Unit
    actual fun setActiveProfile(profileId: Int) = Unit
    actual fun loadState(profileId: Int): String? = null
    actual fun saveState(profileId: Int, payload: String) = Unit
    actual fun savePackageAtomically(storageKey: String, bytes: ByteArray) = Unit
    actual fun packageExists(storageKey: String): Boolean = false
    actual fun migratePackage(oldStorageKey: String, newStorageKey: String): Boolean = false
    actual fun packagePath(storageKey: String): String? = null
    actual fun deletePackage(storageKey: String) = Unit
    actual fun clearPackages() = Unit
    actual fun clearAllState() = Unit
}
