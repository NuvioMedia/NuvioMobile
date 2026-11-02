package com.nuvio.app.features.livetv.ui

import androidx.compose.runtime.Composable

/** Picking backup files isn't possible on this platform (iOS for now). */
internal class BackupFilePickerUnavailable : Exception("Backup files aren't available on this device")

/** Backup files larger than this aren't read: a real one is a few hundred KB at most. */
internal const val MAX_BACKUP_FILE_BYTES = 16 * 1024 * 1024

/**
 * Lets the user pick a file to import; returns a function that opens the picker. [onResult]
 * gets the file's text, `null` when they cancelled, or the error (a
 * [BackupFilePickerUnavailable] where there is no picker).
 */
@Composable
internal expect fun rememberLiveTvBackupImporter(onResult: (Result<String?>) -> Unit): () -> Unit

/**
 * Lets the user pick where to save a backup; returns a function that opens the picker.
 * [text] is read when the place is chosen, so the file holds the setup as it is then.
 * [onResult] gets `true` when written, `false` when they cancelled, or the error.
 */
@Composable
internal expect fun rememberLiveTvBackupExporter(text: () -> String, onResult: (Result<Boolean>) -> Unit): () -> Unit
