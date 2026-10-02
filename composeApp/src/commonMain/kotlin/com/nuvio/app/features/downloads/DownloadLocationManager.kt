package com.nuvio.app.features.downloads

internal expect object DownloadLocationManager {
    fun ensureLocationSet(): Boolean

    fun currentLocationLabel(): String

    fun openDownloadLocation(): Boolean

    fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun removeFile(localFileUri: String?): Boolean
}
