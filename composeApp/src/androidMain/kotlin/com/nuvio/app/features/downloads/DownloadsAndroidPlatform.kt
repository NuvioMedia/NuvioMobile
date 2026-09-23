package com.nuvio.app.features.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

internal object DownloadsAndroidContext {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun contextOrNull(): Context? = appContext

    fun requireContext(): Context = checkNotNull(appContext) { "Downloads are not initialized" }

    fun contentResolverOrNull(): ContentResolver? = appContext?.contentResolver

    fun contentResolver(): ContentResolver = requireContext().contentResolver
}

internal const val INTERNAL_DOWNLOADS_DIRECTORY_NAME = "downloads"

internal fun internalDownloadsDirectory(context: Context): File =
    File(context.filesDir, INTERNAL_DOWNLOADS_DIRECTORY_NAME)

internal fun internalDownloadsDirectoryOrNull(): File? =
    DownloadsAndroidContext.contextOrNull()?.let(::internalDownloadsDirectory)

internal fun internalDownloadsDirectory(): File =
    internalDownloadsDirectory(DownloadsAndroidContext.requireContext())

/**
 * The storage-access-framework document operations shared by the download location and the
 * subtitle sidecar storage. Keeps document lookup/create/delete policy in one place.
 */
internal object SafDocuments {
    private val childProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )

    fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        displayName: String,
    ): Uri? = runCatching {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId),
            childProjection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idColumn),
                    )
                }
            }
            null
        }
    }.getOrNull()

    fun createDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        mimeType: String,
        displayName: String,
    ): Uri? = runCatching {
        DocumentsContract.createDocument(resolver, parentDocumentUri, mimeType, displayName)
    }.getOrNull()

    fun delete(resolver: ContentResolver, documentUri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }.getOrDefault(false)

    fun documentExists(resolver: ContentResolver, documentUri: Uri): Boolean = runCatching {
        resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    fun size(resolver: ContentResolver, documentUri: Uri): Long = runCatching {
        resolver.openFileDescriptor(documentUri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    fun isDirectory(resolver: ContentResolver, documentUri: Uri): Boolean =
        runCatching {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            }
        }.getOrNull() == true
}
