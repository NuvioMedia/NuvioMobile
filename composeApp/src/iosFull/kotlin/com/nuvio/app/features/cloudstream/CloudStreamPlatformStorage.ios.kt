package com.nuvio.app.features.cloudstream

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
internal actual object CloudStreamPlatformStorage {
    private const val stateKey = "cloudstream_state"
    private var activeProfileId = 1

    actual fun initialize(context: Any?) = Unit

    actual fun setActiveProfile(profileId: Int) {
        activeProfileId = profileId.coerceAtLeast(1)
    }

    actual fun loadState(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${stateKey}_$profileId")

    actual fun saveState(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "${stateKey}_$profileId")
    }

    actual fun savePackageAtomically(storageKey: String, bytes: ByteArray) {
        val directory = packagesDirectory()
        val destination = "$directory/$storageKey.cs3"
        val temporary = "$directory/$storageKey-${NSUUID().UUIDString}.tmp"
        val backup = "$directory/$storageKey.backup"
        val manager = NSFileManager.defaultManager
        require(bytes.writeToFile(temporary)) { "Could not write CloudStream package" }
        try {
            manager.removeItemAtPath(backup, error = null)
            if (manager.fileExistsAtPath(destination)) {
                require(manager.moveItemAtPath(destination, backup, error = null)) {
                    "Could not prepare existing CloudStream package for update"
                }
            }
            if (!manager.moveItemAtPath(temporary, destination, error = null)) {
                if (manager.fileExistsAtPath(backup)) {
                    manager.moveItemAtPath(backup, destination, error = null)
                }
                error("Could not commit CloudStream package")
            }
            manager.removeItemAtPath(backup, error = null)
        } finally {
            manager.removeItemAtPath(temporary, error = null)
        }
    }

    actual fun packageExists(storageKey: String): Boolean {
        val manager = NSFileManager.defaultManager
        val directory = packagesDirectory()
        val destination = "$directory/$storageKey.cs3"
        val backup = "$directory/$storageKey.backup"
        if (manager.fileExistsAtPath(destination)) {
            manager.removeItemAtPath(backup, error = null)
            return true
        }
        // Recover the previous package if the app was terminated during replacement.
        if (manager.fileExistsAtPath(backup) && manager.moveItemAtPath(backup, destination, error = null)) {
            return true
        }
        val legacy = "${packagesRootDirectory()}/$storageKey.cs3"
        return manager.fileExistsAtPath(legacy) && manager.copyItemAtPath(legacy, destination, error = null)
    }

    actual fun migratePackage(oldStorageKey: String, newStorageKey: String): Boolean {
        val directory = packagesDirectory()
        val source = "$directory/$oldStorageKey.cs3"
        val destination = "$directory/$newStorageKey.cs3"
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(destination)) return true
        if (manager.fileExistsAtPath(source) && manager.moveItemAtPath(source, destination, error = null)) return true
        val legacy = "${packagesRootDirectory()}/$oldStorageKey.cs3"
        return manager.fileExistsAtPath(legacy) && manager.copyItemAtPath(legacy, destination, error = null)
    }

    actual fun packagePath(storageKey: String): String? {
        if (!packageExists(storageKey)) return null
        return "${packagesDirectory()}/$storageKey.cs3"
    }

    actual fun deletePackage(storageKey: String) {
        val directory = packagesDirectory()
        NSFileManager.defaultManager.removeItemAtPath("$directory/$storageKey.cs3", error = null)
        NSFileManager.defaultManager.removeItemAtPath("$directory/$storageKey.backup", error = null)
    }

    actual fun clearPackages() {
        NSFileManager.defaultManager.removeItemAtPath(packagesRootDirectory(), error = null)
    }

    actual fun clearAllState() {
        (1..20).forEach { profileId ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey("${stateKey}_$profileId")
        }
    }

    private fun packagesDirectory(): String {
        val directory = "${packagesRootDirectory()}/profile-$activeProfileId"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return directory
    }

    private fun packagesRootDirectory(): String {
        val directory = NSHomeDirectory().trimEnd('/') + "/Library/Application Support/NuvioCloudStream/packages"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return directory
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.writeToFile(path: String): Boolean = usePinned { pinned ->
    val file = fopen(path, "wb") ?: return false
    try {
        val written = if (isEmpty()) 0UL else fwrite(pinned.addressOf(0), 1.convert(), size.convert(), file)
        written.toLong() == size.toLong()
    } finally {
        fclose(file)
    }
}
