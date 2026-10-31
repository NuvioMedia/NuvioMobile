package com.nuvio.app.features.player

import android.graphics.Typeface
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal actual object SubtitleFontStorage {
    actual fun defaultFamily(): FontFamily = FontFamily.SansSerif

    private fun file(name: String): File {
        require(name == "fonts.json" || name.matches(Regex("custom-[a-f0-9-]{36}")))
        val context = requireNotNull(PlayerSettingsStorage.applicationContext())
        return File(context.filesDir, "subtitle-fonts").resolve(name)
    }

    actual fun read(name: String): ByteArray? = file(name).takeIf {
        it.isFile && it.length() <= MaxSubtitleFontBytes
    }?.readBytes()

    actual fun write(name: String, bytes: ByteArray) {
        val target = file(name)
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }

    actual fun remove(name: String) { file(name).delete() }

    actual fun fontFamily(id: String): FontFamily? = file(id).takeIf { it.isFile }?.let {
        FontFamily(Typeface.createFromFile(it))
    }
}

@Composable
internal actual fun rememberSubtitleFontImporter(onResult: (Result<SubtitleFont>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("Missing font name")
                    val bytes = requireNotNull(resolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MaxSubtitleFontBytes)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    SubtitleFonts.import(name, bytes)
                }
            }
            latestResult.value(result)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
