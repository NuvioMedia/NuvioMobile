package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.URI

internal actual object DownloadLocationManager {
    private const val DOWNLOADS_DIRECTORY_NAME = "downloads"

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun ensureLocationSet(): Boolean = true

    actual fun currentLocationLabel(): String = downloadsDirectoryOrNull()?.absolutePath.orEmpty()

    actual fun openDownloadLocation(): Boolean {
        val context = appContext ?: return false
        val directory = downloadsDirectory().apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                directory,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    actual fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String {
        val source = sourceFileUri.toLocalFileOrNull()
            ?: error("Unsupported download source: $sourceFileUri")
        val destination = File(downloadsDirectory(), destinationFileName)
        if (source.absolutePath == destination.absolutePath) {
            return destination.toURI().toString()
        }
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Cannot create downloads directory"
        }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
        return destination.toURI().toString()
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val baseDirectory = downloadsDirectoryOrNull() ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val localFile = File(baseDirectory, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun downloadsDirectoryOrNull(): File? =
        appContext?.let { File(it.filesDir, DOWNLOADS_DIRECTORY_NAME) }

    private fun downloadsDirectory(): File =
        checkNotNull(downloadsDirectoryOrNull()) { "Downloads are not initialized" }
}

private fun String.toLocalFileOrNull(): File? = runCatching {
    if (startsWith("file:")) {
        File(URI(this))
    } else {
        File(this)
    }
}.getOrNull()
