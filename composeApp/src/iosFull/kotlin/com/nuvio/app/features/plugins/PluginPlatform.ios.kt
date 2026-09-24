@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.plugins

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

internal object PluginStorage {
    private const val pluginsStateKey = "plugins_state"
    private const val scraperCodeDirectoryName = "nuvio_plugin_scrapers"
    private val scraperCodeLock = SynchronizedObject()

    fun loadState(profileId: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "${pluginsStateKey}_$profileId",
        )
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(scraperCodePath(profileId, scraperId))

    fun loadScraperCode(profileId: Int, scraperId: String): String? = synchronized(scraperCodeLock) {
        readUtf8File(scraperCodePath(profileId, scraperId))
    }

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean {
        val manager = NSFileManager.defaultManager
        val target = scraperCodePath(profileId, scraperId)
        if (!overwrite && manager.fileExistsAtPath(target)) return true
        return synchronized(scraperCodeLock) {
            val directory = scraperCodeDirectory(profileId)
            if (!manager.createDirectoryAtPath(directory, true, null, null)) return@synchronized false
            if (!overwrite && manager.fileExistsAtPath(target)) return@synchronized true

            val temporary = "$target.tmp"
            val backup = "$target.backup"
            if (!writeUtf8File(temporary, code)) return@synchronized false

            try {
                if (!overwrite && manager.fileExistsAtPath(target)) return@synchronized true
                if (manager.fileExistsAtPath(backup) && !manager.removeItemAtPath(backup, null)) {
                    return@synchronized false
                }
                val hadTarget = manager.fileExistsAtPath(target)
                if (hadTarget && !manager.moveItemAtPath(target, backup, null)) return@synchronized false
                if (!manager.moveItemAtPath(temporary, target, null)) {
                    if (hadTarget) manager.moveItemAtPath(backup, target, null)
                    return@synchronized false
                }
                if (manager.fileExistsAtPath(backup)) manager.removeItemAtPath(backup, null)
                true
            } finally {
                if (manager.fileExistsAtPath(temporary)) manager.removeItemAtPath(temporary, null)
                if (manager.fileExistsAtPath(backup) && !manager.fileExistsAtPath(target)) {
                    manager.moveItemAtPath(backup, target, null)
                }
            }
        }
    }

    fun loadScraperSettings(scraperId: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("settings_${scraperId}")

    fun saveScraperSettings(scraperId: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "settings_${scraperId}",
        )
    }

    fun loadCfSession(host: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("cf_session_${host}")

    fun saveCfSession(host: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = "cf_session_${host}",
        )
    }

    fun removeCfSession(host: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey("cf_session_${host}")
    }

    private fun scraperCodeDirectory(profileId: Int): String =
        "${NSHomeDirectory()}/Library/Application Support/$scraperCodeDirectoryName/$profileId"

    private fun scraperCodePath(profileId: Int, scraperId: String): String =
        "${scraperCodeDirectory(profileId)}/${pluginDigestHex("SHA256", scraperId)}.js"
}

@OptIn(ExperimentalForeignApi::class)
private fun readUtf8File(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0L, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < 0L || size > Int.MAX_VALUE.toLong()) return null
        rewind(file)
        val bytes = ByteArray(size.toInt())
        if (bytes.isNotEmpty()) {
            val read = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
            }
            if (read.toLong() != bytes.size.toLong()) return null
        }
        bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeUtf8File(path: String, value: String): Boolean {
    val bytes = value.encodeToByteArray()
    val file = fopen(path, "wb") ?: return false
    return try {
        if (bytes.isEmpty()) {
            true
        } else {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
            }
            written.toLong() == bytes.size.toLong()
        }
    } finally {
        fclose(file)
    }
}

internal fun currentPluginPlatform(): String = "ios"

internal fun currentEpochMillis(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0).toLong()
