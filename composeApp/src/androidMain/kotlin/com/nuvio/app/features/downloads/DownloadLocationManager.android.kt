package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual object DownloadLocationManager {
    private const val LOCATION_PREFERENCES_NAME = "nuvio_download_location"
    private const val LOCATION_PREFERENCE_KEY = "download_location_pref"

    private val locationJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _locationLabel = MutableStateFlow("")
    actual val locationLabel: StateFlow<String> = _locationLabel.asStateFlow()

    private var locationPref: DownloadLocationPref? = null
    private var folderPickerLauncher: (() -> Unit)? = null
    private var pendingLocationSelection: CancellableContinuation<Boolean>? = null

    fun initialize(context: Context) {
        DownloadsAndroidContext.initialize(context)
        locationPref = loadLocationPref()
        clearInvalidLocationIfNeeded()
        refreshLocationLabel()
    }

    fun bindFolderPicker(launcher: (() -> Unit)?) {
        folderPickerLauncher = launcher
    }

    fun onFolderPicked(uri: Uri?) {
        val continuation = pendingLocationSelection
        pendingLocationSelection = null
        if (uri == null) {
            continuation?.resumeWith(Result.success(false))
            return
        }
        val context = DownloadsAndroidContext.contextOrNull()
        if (context == null) {
            continuation?.resumeWith(Result.success(false))
            return
        }
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!persisted) {
            continuation?.resumeWith(Result.success(false))
            return
        }
        persistLocationPref(
            DownloadLocationPref(
                mode = DownloadLocationMode.ANDROID_SAF,
                value = uri.toString(),
            ),
        )
        continuation?.resumeWith(Result.success(true))
    }

    actual fun ensureLocationSet(): Boolean {
        if (clearInvalidLocationIfNeeded()) {
            refreshLocationLabel()
        }
        return locationPref != null
    }

    actual suspend fun ensureLocationSelectedOrPrompt(): Boolean {
        if (ensureLocationSet()) return true
        return suspendCancellableCoroutine { continuation ->
            pendingLocationSelection?.cancel()
            pendingLocationSelection = continuation
            continuation.invokeOnCancellation {
                if (pendingLocationSelection === continuation) {
                    pendingLocationSelection = null
                }
            }
            if (!requestFolderPicker()) {
                pendingLocationSelection = null
                continuation.resumeWith(Result.success(false))
            }
        }
    }

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

    actual suspend fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String =
        withContext(Dispatchers.IO) {
            val source = sourceFileUri.toLocalFileOrNull()
                ?: error("Unsupported download source: $sourceFileUri")
            val pref = locationPref
            if (pref?.mode == DownloadLocationMode.ANDROID_SAF) {
                return@withContext copyIntoTreeLocation(pref.value, source, destinationFileName)
            }
            val destination = File(downloadsDirectory(), destinationFileName)
            if (source.absolutePath == destination.absolutePath) {
                return@withContext destination.toURI().toString()
            }
            check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                "Cannot create downloads directory"
            }
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
            destination.toURI().toString()
        }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        val contentUri = localFileUri?.toContentUriOrNull()
        if (contentUri != null && isContentUriAccessible(contentUri)) {
            return contentUri.toString()
        }

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
        val contentUri = localFileUri.toContentUriOrNull()
        if (contentUri != null) {
            val resolver = DownloadsAndroidContext.contentResolverOrNull() ?: return false
            return SafDocuments.delete(resolver, contentUri)
        }
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    internal fun currentDownloadTreeUri(): String? {
        val pref = locationPref ?: return null
        if (pref.mode != DownloadLocationMode.ANDROID_SAF) return null
        return withWritableTree(pref.value) { pref.value }
    }

    internal fun createDownloadTarget(destinationTreeUri: String, destinationFileName: String): SafDownloadTarget? =
        withWritableTree(destinationTreeUri) { SafDownloadTarget.create(destinationTreeUri, destinationFileName) }

    internal fun createDownloadTarget(destinationFileName: String): SafDownloadTarget? =
        currentDownloadTreeUri()?.let { createDownloadTarget(it, destinationFileName) }

    internal fun openDownloadTarget(
        destinationTreeUri: String,
        destinationDocumentUri: String,
        destinationFileName: String,
    ): SafDownloadTarget? = withWritableTree(destinationTreeUri) {
        SafDownloadTarget.open(destinationTreeUri, destinationDocumentUri, destinationFileName)
    }

    internal fun findFinalizedDownloadFile(destinationTreeUri: String?, destinationFileName: String): String? =
        withWritableTree(destinationTreeUri) { SafDownloadTarget.findFinalized(destinationTreeUri, destinationFileName) }

    internal fun fileSize(fileUri: String?): Long {
        val contentUri = fileUri?.toContentUriOrNull() ?: return 0L
        val resolver = DownloadsAndroidContext.contentResolverOrNull() ?: return 0L
        return SafDocuments.size(resolver, contentUri)
    }

    private fun <T> withWritableTree(treeValue: String?, block: () -> T): T? {
        val context = DownloadsAndroidContext.contextOrNull() ?: return null
        val treeUri = treeValue?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (!hasWritableTreePermission(context, treeUri)) return null
        return block()
    }

    private fun copyIntoTreeLocation(treeValue: String, source: File, destinationFileName: String): String {
        val resolver = DownloadsAndroidContext.contentResolver()
        val treeUri = runCatching { Uri.parse(treeValue) }.getOrNull()
            ?: error("Unsupported download location: $treeValue")
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error("Unsupported download location: $treeValue")
        val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

        SafDocuments.findChild(resolver, treeUri, treeDocumentId, destinationFileName)?.let { existing ->
            SafDocuments.delete(resolver, existing)
        }

        val documentUri = SafDocuments.createDocument(
            resolver = resolver,
            parentDocumentUri = treeDocumentUri,
            mimeType = mimeTypeForFileName(destinationFileName),
            displayName = destinationFileName,
        ) ?: error("Could not create the download file in the selected folder")

        try {
            val output = resolver.openOutputStream(documentUri, "w")
                ?: error("Could not open the download file in the selected folder")
            output.use { stream ->
                source.inputStream().use { input -> input.copyTo(stream) }
            }
        } catch (error: Throwable) {
            SafDocuments.delete(resolver, documentUri)
            throw error
        }

        source.delete()
        return documentUri.toString()
    }

    private fun openInternalDownloadsLocation(): Boolean {
        val context = DownloadsAndroidContext.contextOrNull() ?: return false
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
        val context = DownloadsAndroidContext.contextOrNull() ?: return false
        val treeUri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val intents = buildList {
            val documentUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            }.getOrNull()
            if (documentUri != null) {
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    },
                )
            }
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                },
            )
        }

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

    private fun clearInvalidLocationIfNeeded(): Boolean {
        val pref = locationPref ?: return false
        if (pref.mode != DownloadLocationMode.ANDROID_SAF) return false
        val context = DownloadsAndroidContext.contextOrNull() ?: return false
        val treeUri = runCatching { Uri.parse(pref.value) }.getOrNull()
        if (treeUri != null && hasWritableTreePermission(context, treeUri)) return false

        clearLocationPref()
        return true
    }

    private fun clearLocationPref() {
        locationPref = null
        DownloadsAndroidContext.contextOrNull()
            ?.getSharedPreferences(LOCATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(LOCATION_PREFERENCE_KEY)
            ?.apply()
    }

    private fun refreshLocationLabel() {
        _locationLabel.value = currentLocationLabel()
    }

    private fun isContentUriAccessible(uri: Uri): Boolean {
        val context = DownloadsAndroidContext.contextOrNull() ?: return false
        val treeUri = runCatching {
            DocumentsContract.buildTreeDocumentUri(
                uri.authority,
                DocumentsContract.getTreeDocumentId(uri),
            )
        }.getOrNull()
        if (treeUri != null && !hasReadableTreePermission(context, treeUri)) return false

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() } == true
        }.getOrDefault(false)
    }

    private fun hasWritableTreePermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission &&
                permission.isWritePermission &&
                permission.uri == treeUri
        }

    private fun hasReadableTreePermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == treeUri
        }

    private fun loadLocationPref(): DownloadLocationPref? {
        val raw = DownloadsAndroidContext.contextOrNull()
            ?.getSharedPreferences(LOCATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.getString(LOCATION_PREFERENCE_KEY, null)
            ?: return null
        return runCatching {
            locationJson.decodeFromString<DownloadLocationPref>(raw)
        }.getOrNull()
    }

    private fun persistLocationPref(pref: DownloadLocationPref) {
        DownloadsAndroidContext.contextOrNull()
            ?.getSharedPreferences(LOCATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(LOCATION_PREFERENCE_KEY, locationJson.encodeToString(pref))
            ?.apply()
        locationPref = pref
        refreshLocationLabel()
    }

    private fun downloadsDirectoryOrNull(): File? = internalDownloadsDirectoryOrNull()

    private fun downloadsDirectory(): File = internalDownloadsDirectory()
}

private fun String.toLocalFileOrNull(): File? = runCatching {
    if (startsWith("file:")) {
        File(URI(this))
    } else {
        File(this)
    }
}.getOrNull()

private fun String.toContentUriOrNull(): Uri? =
    takeIf { it.startsWith("content:") }?.let(Uri::parse)
