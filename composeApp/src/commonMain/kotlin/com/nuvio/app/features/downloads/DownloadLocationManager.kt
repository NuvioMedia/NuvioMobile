package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.StateFlow

internal expect object DownloadLocationManager {
    val locationLabel: StateFlow<String>

    fun ensureLocationSet(): Boolean

    suspend fun ensureLocationSelected(): Boolean

    fun currentLocationLabel(): String

    fun requestFolderPicker(): Boolean

    fun openDownloadLocation(): Boolean

    fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun removeFile(localFileUri: String?): Boolean
}
