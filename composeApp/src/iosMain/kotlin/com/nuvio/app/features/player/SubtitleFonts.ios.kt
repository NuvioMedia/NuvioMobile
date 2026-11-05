package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import platform.Foundation.NSFileManager
import platform.Foundation.NSBundle
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
internal actual object SubtitleFontStorage {
    private val bundledFamily: FontFamily by lazy {
        NSBundle.mainBundle.pathForResource("NotoSans-Regular", "ttf")?.let(::readFontFile)?.let {
            FontFamily(Font(identity = "subtitle-default", data = it))
        } ?: FontFamily.SansSerif
    }

    actual fun defaultFamily(): FontFamily = bundledFamily

    private val directory get() = "${NSHomeDirectory()}/Library/Application Support/subtitle-fonts"

    private fun path(name: String): String {
        require(name == "fonts.json" || name.matches(Regex("custom-[a-f0-9-]{36}")))
        return "$directory/$name"
    }

    actual fun read(name: String): ByteArray? = readFontFile(path(name))

    actual fun write(name: String, bytes: ByteArray) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val target = path(name)
        val temporary = "$target.tmp"
        val file = requireNotNull(fopen(temporary, "wb"))
        try {
            bytes.usePinned { check(fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() == bytes.size) }
        } finally {
            fclose(file)
        }
        check(rename(temporary, target) == 0)
    }

    actual fun remove(name: String) { NSFileManager.defaultManager.removeItemAtPath(path(name), null) }

    actual fun fontFamily(id: String): FontFamily? = read(id)?.let { bytes ->
        val data = Data.makeFromBytes(bytes)
        try {
            requireNotNull(FontMgr.default.makeFromData(data)).close()
        } finally {
            data.close()
        }
        FontFamily(Font(identity = id, data = bytes))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFontFile(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size !in 1..MaxSubtitleFontBytes.toLong()) return null
        rewind(file)
        ByteArray(size.toInt()).also { bytes ->
            bytes.usePinned { check(fread(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() == bytes.size) }
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberSubtitleFontImporter(onResult: (Result<SubtitleFont>) -> Unit): () -> Unit {
    val presenter = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        val accessed = url.startAccessingSecurityScopedResource()
                        try {
                            runCatching {
                                SubtitleFonts.import(
                                    url.lastPathComponent.orEmpty(),
                                    requireNotNull(readFontFile(requireNotNull(url.path))),
                                )
                            }
                        } finally {
                            if (accessed) url.stopAccessingSecurityScopedResource()
                        }
                    }
                    latestResult.value(result)
                }
            }
        }
    }
    return {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.font", "public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, true, null)
    }
}
