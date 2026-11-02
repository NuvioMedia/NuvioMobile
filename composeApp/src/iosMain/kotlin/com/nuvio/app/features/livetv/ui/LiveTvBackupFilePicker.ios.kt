package com.nuvio.app.features.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

// Live TV targets Android; on iOS the rows report that backup files aren't available.

@Composable
internal actual fun rememberLiveTvBackupImporter(onResult: (Result<String?>) -> Unit): () -> Unit {
    val latestResult = rememberUpdatedState(onResult)
    return { latestResult.value(Result.failure(BackupFilePickerUnavailable())) }
}

@Composable
internal actual fun rememberLiveTvBackupExporter(text: () -> String, onResult: (Result<Boolean>) -> Unit): () -> Unit {
    val latestResult = rememberUpdatedState(onResult)
    return { latestResult.value(Result.failure(BackupFilePickerUnavailable())) }
}
