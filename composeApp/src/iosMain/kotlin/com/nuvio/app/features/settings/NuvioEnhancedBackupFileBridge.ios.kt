package com.nuvio.app.features.settings

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
internal actual object NuvioEnhancedBackupFileBridge {
    private var importDelegate: BackupDocumentPickerDelegate? = null

    actual fun exportBackup(
        fileName: String,
        payload: String,
        onResult: (Result<String>) -> Unit,
    ) {
        runCatching {
            val safeName = fileName.ifBlank { "nuvio-backup.json" }
            val filePath = NSTemporaryDirectory().trimEnd('/') + "/" + safeName
            if (!payload.encodeToByteArray().writeToFile(filePath)) {
                error("Could not write backup file.")
            }
            val fileUrl = NSURL.fileURLWithPath(filePath)
            val presenter = topViewController() ?: error("Backup export is not available right now.")
            val controller = UIActivityViewController(
                activityItems = listOf(fileUrl),
                applicationActivities = null,
            )
            presenter.presentViewController(controller, animated = true, completion = null)
            filePath
        }.fold(
            onSuccess = { path -> onResult(Result.success(path)) },
            onFailure = { error -> onResult(Result.failure(error)) },
        )
    }

    actual fun importBackup(
        onResult: (Result<String>) -> Unit,
    ) {
        runCatching {
            val presenter = topViewController() ?: error("Backup import is not available right now.")
            val controller = UIDocumentPickerViewController(
                documentTypes = listOf("public.json", "public.text", "public.data"),
                inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
            )
            val delegate = BackupDocumentPickerDelegate(
                onResult = { result ->
                    importDelegate = null
                    onResult(result)
                },
            )
            importDelegate = delegate
            controller.delegate = delegate
            presenter.presentViewController(controller, animated = true, completion = null)
        }.onFailure { error ->
            importDelegate = null
            onResult(Result.failure(error))
        }
    }

    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }

    private class BackupDocumentPickerDelegate(
        private val onResult: (Result<String>) -> Unit,
    ) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>,
        ) {
            val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
            if (url == null) {
                onResult(Result.failure(IllegalStateException("No backup file selected.")))
                return
            }

            val accessed = url.startAccessingSecurityScopedResource()
            try {
                val path = url.path ?: error("Could not read backup file path.")
                val payload = readUtf8File(path) ?: error("Could not read backup file.")
                onResult(Result.success(payload))
            } catch (error: Throwable) {
                onResult(Result.failure(error))
            } finally {
                if (accessed) {
                    url.stopAccessingSecurityScopedResource()
                }
            }
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onResult(Result.failure(IllegalStateException("Backup import cancelled.")))
        }
    }

    private fun ByteArray.writeToFile(path: String): Boolean =
        usePinned { pinned ->
            val file = fopen(path, "wb") ?: return false
            try {
                val written = fwrite(
                    pinned.addressOf(0),
                    1.convert(),
                    size.convert(),
                    file,
                )
                written.toLong() == size.toLong()
            } finally {
                fclose(file)
            }
        }

    private fun readUtf8File(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        return try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            if (size < 0) return null
            fseek(file, 0, SEEK_SET)
            val bytes = ByteArray(size)
            val read = bytes.usePinned { pinned ->
                fread(
                    pinned.addressOf(0),
                    1.convert(),
                    size.convert(),
                    file,
                )
            }
            if (read.toLong() != size.toLong()) return null
            bytes.decodeToString()
        } finally {
            fclose(file)
        }
    }
}
