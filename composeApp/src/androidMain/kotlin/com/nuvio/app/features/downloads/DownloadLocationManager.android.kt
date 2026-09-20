package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual object DownloadLocationManager {
    private const val DOWNLOADS_DIRECTORY_NAME = "downloads"
    private const val LOCATION_PREFERENCES_NAME = "nuvio_download_location"
    private const val LOCATION_PREFERENCE_KEY = "download_location_pref"

    private val locationJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _locationLabel = MutableStateFlow("")
    actual val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    private var appContext: Context? = null
    private var locationPref: DownloadLocationPref? = null
    private var folderPickerLauncher: (() -> Unit)? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        locationPref = loadLocationPref()
        refreshLocationLabel()
    }

    fun bindFolderPicker(launcher: (() -> Unit)?) {
        folderPickerLauncher = launcher
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val context = appContext ?: return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        persistLocationPref(
            DownloadLocationPref(
                mode = DownloadLocationMode.ANDROID_SAF,
                value = uri.toString(),
            ),
        )
    }

    actual fun ensureLocationSet(): Boolean = true

    actual fun currentLocationLabel(): String {
        val pref = locationPref
        if (pref?.mode == DownloadLocationMode.ANDROID_SAF) {
            return safLocationLabel(pref.value)
        }
        return downloadsDirectoryOrNull()?.absolutePath.orEmpty()
    }

    actual fun requestFolderPicker(): Boolean {
        val launcher = folderPickerLauncher ?: return false
        return runCatching {
            launcher()
            true
        }.getOrDefault(false)
    }

    actual fun openDownloadLocation(): Boolean {
        val pref = locationPref
        if (pref?.mode == DownloadLocationMode.ANDROID_SAF) {
            return openTreeLocation(pref.value)
        }
        return openInternalDownloadsLocation()
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

    private fun openInternalDownloadsLocation(): Boolean {
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

        return startFirstWorkingIntent(context, intents)
    }

    private fun openTreeLocation(value: String): Boolean {
        val context = appContext ?: return false
        val treeUri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    documentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
            },
        )

        return startFirstWorkingIntent(context, intents)
    }

    private fun startFirstWorkingIntent(context: Context, intents: List<Intent>): Boolean =
        intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

    private fun safLocationLabel(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val documentId = uri?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        }.orEmpty()
        val path = documentId.substringAfter(':', documentId).trim('/')
        return path.ifBlank { documentId.trimEnd(':').ifBlank { value } }
    }

    private fun loadLocationPref(): DownloadLocationPref? {
        val raw = appContext
            ?.getSharedPreferences(LOCATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(LOCATION_PREFERENCE_KEY, null)
            ?: return null
        return runCatching {
            locationJson.decodeFromString<DownloadLocationPref>(raw)
        }.getOrNull()
    }

    private fun persistLocationPref(pref: DownloadLocationPref) {
        appContext
            ?.getSharedPreferences(LOCATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(LOCATION_PREFERENCE_KEY, locationJson.encodeToString(pref))
            ?.apply()
        locationPref = pref
        refreshLocationLabel()
    }

    private fun refreshLocationLabel() {
        _locationLabel.value = currentLocationLabel()
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
