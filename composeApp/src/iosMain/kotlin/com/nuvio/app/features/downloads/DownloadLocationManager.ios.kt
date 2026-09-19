package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadLocationManager {
    actual fun ensureLocationSet(): Boolean {
        val path = downloadsDirectoryPath()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    actual fun currentLocationLabel(): String = downloadsDirectoryPath()

    actual fun requestFolderPicker(): Boolean = false

    actual fun openDownloadLocation(): Boolean {
        val url = NSURL.fileURLWithPath(downloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }

    actual fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String {
        val sourcePath = sourceFileUri.toLocalPath()
            ?: error("Unsupported download source: $sourceFileUri")
        val destinationPath = "${downloadsDirectoryPath()}/$destinationFileName"
        if (sourcePath == destinationPath) {
            return fileUri(destinationPath)
        }
        removePathIfExists(destinationPath)
        val moved = NSFileManager.defaultManager.moveItemAtPath(
            srcPath = sourcePath,
            toPath = destinationPath,
            error = null,
        )
        if (!moved) {
            check(
                NSFileManager.defaultManager.copyItemAtPath(
                    srcPath = sourcePath,
                    toPath = destinationPath,
                    error = null,
                ),
            ) {
                "Could not finalize the downloaded file"
            }
            removePathIfExists(sourcePath)
        }
        return fileUri(destinationPath)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path -> return fileUri(path) }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
            fileUri(currentPath)
        } else {
            null
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    private fun fileUri(path: String): String =
        NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
}
