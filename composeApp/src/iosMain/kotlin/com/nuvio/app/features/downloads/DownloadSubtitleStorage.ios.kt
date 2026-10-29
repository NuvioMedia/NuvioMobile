package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory = requireNotNull(NSURL(string = localVideoUri).path) + ".subtitles"

    actual fun read(fileName: String): String? =
        NSString.stringWithContentsOfFile(path(fileName), NSUTF8StringEncoding, null)

    actual fun write(fileName: String, text: String) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        check(NSString.create(string = text).writeToFile(path(fileName), true, NSUTF8StringEncoding, null))
    }

    actual fun localFileUri(fileName: String): String? =
        path(fileName).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { NSURL.fileURLWithPath(it).absoluteString }

    actual fun remove() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    private fun path(fileName: String): String {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..")
        return "$directory/$fileName"
    }
}
