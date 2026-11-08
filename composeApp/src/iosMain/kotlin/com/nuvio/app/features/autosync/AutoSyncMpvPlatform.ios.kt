package com.nuvio.app.features.autosync

import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/** Writes retimed subtitles to Caches/subtitles and shows messages as Nuvio toasts. */
internal object IosAutoSyncMpvPlatform : AutoSyncMpvPlatform {

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return@withContext null
            val directory = "$caches/subtitles"
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.createDirectoryAtPath(directory, true, null, null)) return@withContext null
            val path = "$directory/$fileName"
            fileManager.contentsOfDirectoryAtPath(directory, null)
                ?.filterIsInstance<String>()
                ?.filter { it.startsWith(stalePrefix) && it != fileName }
                ?.forEach { fileManager.removeItemAtPath("$directory/$it", null) }
            val written = NSString.create(string = content).writeToFile(path, true, NSUTF8StringEncoding, null)
            if (written) path else null
        }

    override fun showMessage(text: String) {
        NuvioToastController.show(text)
    }
}
