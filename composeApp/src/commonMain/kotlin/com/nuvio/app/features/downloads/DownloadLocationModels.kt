package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadLocationMode {
    ANDROID_SAF,
    IOS_FOLDER,
}

@Serializable
data class DownloadLocationPref(
    val mode: DownloadLocationMode,
    val value: String,
)

internal fun mimeTypeForFileName(fileName: String): String {
    val extension = fileName
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase()

    return when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "mts", "m2ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpg", "mpeg" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "ogv" -> "video/ogg"
        else -> "application/octet-stream"
    }
}