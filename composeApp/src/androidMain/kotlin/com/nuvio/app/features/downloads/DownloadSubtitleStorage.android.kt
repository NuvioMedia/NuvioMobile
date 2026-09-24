package com.nuvio.app.features.downloads

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory: SubtitleDirectory = if (localVideoUri.startsWith("content:")) {
        SafSubtitleDirectory(Uri.parse(localVideoUri))
    } else {
        LegacySubtitleDirectory(File(File(URI(localVideoUri)).path + SUBTITLES_SUFFIX))
    }

    actual fun read(fileName: String): String? = directory.read(fileName)

    actual fun write(fileName: String, text: String) = directory.write(fileName, text)

    actual fun localFileUri(fileName: String): String? = directory.fileUri(fileName)

    actual fun remove() = directory.remove()
}

private interface SubtitleDirectory {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun fileUri(fileName: String): String?
    fun remove()
}

private class LegacySubtitleDirectory(private val directory: File) : SubtitleDirectory {
    override fun read(fileName: String): String? =
        runCatching { AtomicFile(file(fileName)).readFully().decodeToString() }.getOrNull()

    override fun write(fileName: String, text: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle directory" }
        val file = AtomicFile(file(fileName))
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    override fun fileUri(fileName: String): String? =
        file(fileName).takeIf { it.isFile }?.toURI()?.toString()

    override fun remove() {
        directory.deleteRecursively()
    }

    private fun file(fileName: String): File {
        requireValidFileName(fileName)
        return File(directory, fileName)
    }
}

/**
 * Writes the `<video>.<ext>.subtitles/` directory beside a downloaded `content://` video, so a
 * folder-scoped download stays self-contained and playable from its stored-file URI.
 */
private class SafSubtitleDirectory(private val videoUri: Uri) : SubtitleDirectory {
    override fun read(fileName: String): String? {
        val resolver = contentResolver() ?: return null
        val document = findFile(fileName) ?: return null
        return runCatching {
            resolver.openInputStream(document)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    override fun write(fileName: String, text: String) {
        requireValidFileName(fileName)
        val resolver = contentResolver() ?: error("Downloads are not initialized")
        val directory = ensureDirectory() ?: error("Cannot create the subtitle directory")
        findChild(directory, fileName)?.let { existing ->
            SafDocuments.delete(resolver, existing)
        }
        val document = SafDocuments.createDocument(resolver, directory, SUBTITLE_MIME_TYPE, fileName)
            ?: error("Cannot create the subtitle file")
        val output = resolver.openOutputStream(document, "w")
            ?: error("Cannot write the subtitle file")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun fileUri(fileName: String): String? {
        val resolver = contentResolver() ?: return null
        val document = findFile(fileName) ?: return null
        return document.takeIf { !SafDocuments.isDirectory(resolver, it) }?.toString()
    }

    override fun remove() {
        val resolver = contentResolver() ?: return
        val directory = findDirectory() ?: return
        SafDocuments.delete(resolver, directory)
    }

    private fun findFile(fileName: String): Uri? {
        requireValidFileName(fileName)
        val directory = findDirectory() ?: return null
        return findChild(directory, fileName)
    }

    private fun findDirectory(): Uri? {
        val parent = parentDocument() ?: return null
        val directoryName = directoryName() ?: return null
        return SafDocuments.findChild(contentResolver() ?: return null, parent.treeUri, parent.documentId, directoryName)
    }

    private fun ensureDirectory(): Uri? {
        findDirectory()?.let { return it }
        val parent = parentDocument() ?: return null
        val directoryName = directoryName() ?: return null
        val resolver = contentResolver() ?: return null
        val created = SafDocuments.createDocument(
            resolver,
            parent.documentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName,
        ) ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(parent.treeUri, DocumentsContract.getDocumentId(created))
        }.getOrNull()
    }

    private fun directoryName(): String? =
        videoDisplayName()?.let { "$it$SUBTITLES_SUFFIX" }

    private fun findChild(documentUri: Uri, displayName: String): Uri? =
        runCatching {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                documentUri.authority,
                DocumentsContract.getTreeDocumentId(documentUri),
            )
            SafDocuments.findChild(
                contentResolver() ?: return null,
                treeUri,
                DocumentsContract.getDocumentId(documentUri),
                displayName,
            )
        }.getOrNull()

    private fun parentDocument(): ParentDocument? {
        runCatching { DocumentsContract.getTreeDocumentId(videoUri) }.getOrNull()?.let { treeDocumentId ->
            val treeUri = runCatching {
                DocumentsContract.buildTreeDocumentUri(videoUri.authority, treeDocumentId)
            }.getOrNull() ?: return null
            return ParentDocument(treeUri, treeDocumentId)
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return null
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocumentId.isBlank() || parentDocumentId == documentId) return null
        val treeUri = runCatching {
            DocumentsContract.buildTreeDocumentUri(videoUri.authority, parentDocumentId)
        }.getOrNull() ?: return null
        return ParentDocument(treeUri, parentDocumentId)
    }

    private fun videoDisplayName(): String? = queryVideoDisplayName() ?: documentIdFileName()

    private fun queryVideoDisplayName(): String? {
        val resolver = contentResolver() ?: return null
        return runCatching {
            resolver.query(
                videoUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun documentIdFileName(): String? =
        runCatching { DocumentsContract.getDocumentId(videoUri) }
            .getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }

    private fun contentResolver(): ContentResolver? =
        DownloadsAndroidContext.contentResolverOrNull()

    private data class ParentDocument(val treeUri: Uri, val documentId: String) {
        val documentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
}

private fun requireValidFileName(fileName: String) {
    require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..") {
        "Invalid subtitle file name"
    }
}

private const val SUBTITLES_SUFFIX = ".subtitles"
private const val SUBTITLE_MIME_TYPE = "application/octet-stream"
