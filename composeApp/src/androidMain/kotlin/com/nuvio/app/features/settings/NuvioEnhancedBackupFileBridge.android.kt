package com.nuvio.app.features.settings

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

internal actual object NuvioEnhancedBackupFileBridge {
    private const val requestExportBackup = 51041
    private const val requestImportBackup = 51042

    private var activityRef: WeakReference<AppCompatActivity>? = null
    private var pendingExportPayload: String? = null
    private var pendingExportCallback: ((Result<String>) -> Unit)? = null
    private var pendingImportCallback: ((Result<String>) -> Unit)? = null

    fun bindActivity(activity: AppCompatActivity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: AppCompatActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    // Activity results are routed through MainActivity to preserve the shared bridge contract.
    @Suppress("DEPRECATION")
    actual fun exportBackup(
        fileName: String,
        payload: String,
        onResult: (Result<String>) -> Unit,
    ) {
        val activity = activityRef?.get()
        if (activity == null) {
            onResult(Result.failure(IllegalStateException("Backup export is not available right now.")))
            return
        }

        pendingExportPayload = payload
        pendingExportCallback = onResult
        runCatching {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            activity.startActivityForResult(intent, requestExportBackup)
        }.onFailure { error ->
            clearPendingExport()
            onResult(Result.failure(error))
        }
    }

    @Suppress("DEPRECATION")
    actual fun importBackup(
        onResult: (Result<String>) -> Unit,
    ) {
        val activity = activityRef?.get()
        if (activity == null) {
            onResult(Result.failure(IllegalStateException("Backup import is not available right now.")))
            return
        }

        pendingImportCallback = onResult
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, requestImportBackup)
        }.onFailure { error ->
            clearPendingImport()
            onResult(Result.failure(error))
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return when (requestCode) {
            requestExportBackup -> {
                val callback = pendingExportCallback
                val payload = pendingExportPayload
                clearPendingExport()
                if (callback != null) {
                    callback(handleExportResult(resultCode, data, payload))
                }
                true
            }
            requestImportBackup -> {
                val callback = pendingImportCallback
                clearPendingImport()
                if (callback != null) {
                    callback(handleImportResult(resultCode, data))
                }
                true
            }
            else -> false
        }
    }

    private fun handleExportResult(
        resultCode: Int,
        data: Intent?,
        payload: String?,
    ): Result<String> = runCatching {
        if (resultCode != Activity.RESULT_OK) error("Backup export cancelled.")
        val uri = data?.data ?: error("No backup destination selected.")
        val text = payload ?: error("Backup payload is missing.")
        val resolver = activityRef?.get()?.contentResolver ?: error("Backup export is not available right now.")
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(text)
        } ?: error("Could not open backup destination.")
        uri.toString()
    }

    private fun handleImportResult(
        resultCode: Int,
        data: Intent?,
    ): Result<String> = runCatching {
        if (resultCode != Activity.RESULT_OK) error("Backup import cancelled.")
        val uri = data?.data ?: error("No backup file selected.")
        val resolver = activityRef?.get()?.contentResolver ?: error("Backup import is not available right now.")
        resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText()
        } ?: error("Could not open backup file.")
    }

    private fun clearPendingExport() {
        pendingExportPayload = null
        pendingExportCallback = null
    }

    private fun clearPendingImport() {
        pendingImportCallback = null
    }
}
