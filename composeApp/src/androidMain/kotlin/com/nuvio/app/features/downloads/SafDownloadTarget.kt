package com.nuvio.app.features.downloads

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

internal const val APPEND_NOT_SUPPORTED_MESSAGE = "This folder may not support resumable downloads."
internal const val MISSING_LOCATION_MESSAGE = "Choose a download folder"

internal class AppendNotSupportedException : IOException(APPEND_NOT_SUPPORTED_MESSAGE)

internal class MissingLocationException : Exception(MISSING_LOCATION_MESSAGE)

/**
 * A single download's write destination inside the chosen folder.
 * Owns the `<name>.part` document.
 */
internal class SafDownloadTarget private constructor(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val treeDocumentId: String,
    private val treeDocumentUri: Uri,
    val destinationTreeUri: String,
    val destinationDocumentUri: String,
    val destinationFileName: String,
) {
    private val partDocumentUri: Uri = Uri.parse(destinationDocumentUri)

    fun size(): Long = SafDocuments.size(resolver, partDocumentUri)

    fun openStream(append: Boolean): OutputStream {
        val mode = if (append) "wa" else "w"
        return try {
            resolver.openOutputStream(partDocumentUri, mode)
                ?: if (append) throw AppendNotSupportedException() else throw IOException(OPEN_FAILED)
        } catch (error: AppendNotSupportedException) {
            throw error
        } catch (error: FileNotFoundException) {
            if (append) throw AppendNotSupportedException() else throw error
        } catch (error: UnsupportedOperationException) {
            if (append) throw AppendNotSupportedException() else throw error
        }
    }

    fun finish(): String {
        rename()?.let { return it.toString() }

        SafDocuments.findChild(resolver, treeUri, treeDocumentId, destinationFileName)
            ?.let { SafDocuments.delete(resolver, it) }
        return copyIntoPlace()
    }

    fun discard(): Boolean = SafDocuments.delete(resolver, partDocumentUri)

    private fun rename(): Uri? = runCatching {
        DocumentsContract.renameDocument(resolver, partDocumentUri, destinationFileName)
    }.getOrNull()

    private fun copyIntoPlace(): String {
        val finalUri = SafDocuments.createDocument(
            resolver = resolver,
            parentDocumentUri = treeDocumentUri,
            mimeType = mimeTypeForFileName(destinationFileName),
            displayName = destinationFileName,
        ) ?: throw IOException(CREATE_FAILED)

        try {
            val output = resolver.openOutputStream(finalUri, "w") ?: throw IOException(CREATE_FAILED)
            output.use { stream ->
                val input = resolver.openInputStream(partDocumentUri) ?: throw IOException(OPEN_FAILED)
                input.use { it.copyTo(stream) }
            }
        } catch (error: Throwable) {
            SafDocuments.delete(resolver, finalUri)
            throw error
        }

        SafDocuments.delete(resolver, partDocumentUri)
        return finalUri.toString()
    }

    companion object {
        fun create(treeValue: String, destinationFileName: String): SafDownloadTarget? {
            val parsed = parseTree(treeValue) ?: return null
            val partName = partFileName(destinationFileName)
            val partUri = SafDocuments.findChild(parsed.resolver, parsed.treeUri, parsed.treeDocumentId, partName)
                ?: SafDocuments.createDocument(
                    resolver = parsed.resolver,
                    parentDocumentUri = parsed.treeDocumentUri,
                    mimeType = mimeTypeForFileName(destinationFileName),
                    displayName = partName,
                )
                ?: throw IOException(CREATE_FAILED)

            return of(parsed, partUri, destinationFileName)
        }

        fun open(treeValue: String, documentValue: String, destinationFileName: String): SafDownloadTarget? {
            val parsed = parseTree(treeValue) ?: return null
            val partUri = runCatching { Uri.parse(documentValue) }.getOrNull() ?: return null
            if (!SafDocuments.documentExists(parsed.resolver, partUri)) return null

            return of(parsed, partUri, destinationFileName)
        }

        fun findFinalized(treeValue: String?, destinationFileName: String): String? {
            if (treeValue == null) return null
            val parsed = parseTree(treeValue) ?: return null
            return SafDocuments.findChild(
                parsed.resolver,
                parsed.treeUri,
                parsed.treeDocumentId,
                destinationFileName,
            )?.toString()
        }

        private fun of(parsed: ParsedTree, partUri: Uri, destinationFileName: String): SafDownloadTarget =
            SafDownloadTarget(
                resolver = parsed.resolver,
                treeUri = parsed.treeUri,
                treeDocumentId = parsed.treeDocumentId,
                treeDocumentUri = parsed.treeDocumentUri,
                destinationTreeUri = parsed.treeUri.toString(),
                destinationDocumentUri = partUri.toString(),
                destinationFileName = destinationFileName,
            )

        private fun parseTree(treeValue: String): ParsedTree? {
            val resolver = DownloadsAndroidContext.contentResolverOrNull() ?: return null
            val treeUri = runCatching { Uri.parse(treeValue) }.getOrNull() ?: return null
            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return null
            return ParsedTree(
                resolver = resolver,
                treeUri = treeUri,
                treeDocumentId = treeDocumentId,
                treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
            )
        }
    }
}

private data class ParsedTree(
    val resolver: ContentResolver,
    val treeUri: Uri,
    val treeDocumentId: String,
    val treeDocumentUri: Uri,
)

internal fun partFileName(fileName: String): String = "$fileName.part"

private const val OPEN_FAILED = "Could not open the download file in the selected folder"
private const val CREATE_FAILED = "Could not create the download file in the selected folder"
