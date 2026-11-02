package com.nuvio.app.features.livetv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.nuvio.app.features.livetv.data.LIVE_TV_BACKUP_FILE_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal actual fun rememberLiveTvBackupImporter(onResult: (Result<String?>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            latestResult.value(Result.success(null))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_BACKUP_FILE_BYTES) { "The file is too large to be a backup" }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
            }
            latestResult.value(result)
        }
    }
    // JSON first; "*/*" too, since some file managers don't tag .json files as application/json.
    return { launcher.launch(arrayOf("application/json", "*/*")) }
}

@Composable
internal actual fun rememberLiveTvBackupExporter(text: () -> String, onResult: (Result<Boolean>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestText = rememberUpdatedState(text)
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            latestResult.value(Result.success(false))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val body = latestText.value()
                    // "wt": truncate, so overwriting a longer file leaves no tail behind.
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    true
                }
            }
            latestResult.value(result)
        }
    }
    return { launcher.launch(LIVE_TV_BACKUP_FILE_NAME) }
}
