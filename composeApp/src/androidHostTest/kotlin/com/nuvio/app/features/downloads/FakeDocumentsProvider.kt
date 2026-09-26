package com.nuvio.app.features.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

internal class FakeDocumentsProvider : ContentProvider() {
    @Volatile
    var failCreateDocument: Boolean = false

    private val createAttempts = AtomicInteger()

    val createDocumentAttempts: Int
        get() = createAttempts.get()

    var appendSupported: Boolean = true
    var renameSupported: Boolean = true
    val createdMimeTypes = mutableListOf<String>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        @Suppress("DEPRECATION")
        val target = (extras?.getParcelable(EXTRA_URI) as? Uri)
            ?: arg?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return null
        return when (method) {
            METHOD_CREATE_DOCUMENT -> {
                createAttempts.incrementAndGet()
                if (failCreateDocument) return null
                val displayName = extras?.getString(EXTRA_DISPLAY_NAME) ?: return null
                val mimeType = extras.getString(EXTRA_MIME_TYPE)
                mimeType?.let { createdMimeTypes += it }
                val parent = fileFor(documentIdOf(target))
                check(parent.isDirectory || parent.mkdirs())
                val child = File(parent, displayName)
                val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    child.mkdirs() || child.isDirectory
                } else {
                    child.createNewFile() || child.exists()
                }
                if (!created) return null
                Bundle().apply {
                    putParcelable(
                        EXTRA_URI,
                        DocumentsContract.buildDocumentUri(target.authority, documentIdFor(child)),
                    )
                }
            }
            METHOD_DELETE_DOCUMENT -> {
                fileFor(documentIdOf(target)).deleteRecursively()
                Bundle().apply { putBoolean(EXTRA_RESULT, true) }
            }
            METHOD_RENAME_DOCUMENT -> {
                if (!renameSupported) return null
                val displayName = extras?.getString(EXTRA_DISPLAY_NAME) ?: return null
                val file = fileFor(documentIdOf(target))
                if (!file.exists()) return null
                val renamed = File(file.parentFile, displayName)
                if (!file.renameTo(renamed)) return null
                Bundle().apply {
                    putParcelable(
                        EXTRA_URI,
                        DocumentsContract.buildDocumentUri(target.authority, documentIdFor(renamed)),
                    )
                }
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns.toTypedArray())
        val children = childFiles(uri)
        if (children != null) {
            children.forEach { child -> cursor.addRow(row(columns, child)) }
        } else {
            val file = fileFor(documentIdOf(uri))
            if (file.exists()) cursor.addRow(row(columns, file))
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(documentIdOf(uri))
        val flags = when {
            mode.contains('a') -> {
                if (!appendSupported) throw FileNotFoundException("Append is not supported: $uri")
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_APPEND
            }
            mode.contains('w') -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    fun createDocument(documentId: String, contents: String = "bytes"): File {
        val file = fileFor(documentId)
        file.parentFile?.mkdirs()
        file.writeText(contents)
        return file
    }

    fun documentExists(documentId: String): Boolean = fileFor(documentId).exists()

    fun readDocument(documentId: String): String? =
        fileFor(documentId).takeIf { it.isFile }?.readText()

    fun documentSize(documentId: String): Long =
        fileFor(documentId).takeIf { it.isFile }?.length() ?: 0L

    private fun childFiles(uri: Uri): List<File>? {
        val segments = uri.pathSegments
        if (segments.lastOrNull() != "children") return null
        val parent = fileFor(segments.drop(3).dropLast(1).joinToString("/"))
        return parent.listFiles()?.toList().orEmpty()
    }

    private fun row(columns: List<String>, file: File): Array<Any?> =
        columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentIdFor(file)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                    if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> file.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            }
        }.toTypedArray()

    private fun documentIdOf(uri: Uri): String {
        val segments = uri.pathSegments
        return if (segments.lastOrNull() == "children") {
            segments.drop(3).dropLast(1).joinToString("/")
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    }

    private fun documentIdFor(file: File): String {
        val relative = file.relativeToOrNull(root())?.invariantSeparatorsPath ?: file.name
        return "primary:$relative"
    }

    private fun fileFor(documentId: String): File =
        File(root(), documentId.substringAfter(':', documentId))

    private fun root(): File =
        File(requireNotNull(context).cacheDir, "fake-documents").apply { mkdirs() }

    private companion object {
        const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
        const val EXTRA_URI = "uri"
        const val EXTRA_DISPLAY_NAME = "_display_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_RESULT = "result"
    }
}
