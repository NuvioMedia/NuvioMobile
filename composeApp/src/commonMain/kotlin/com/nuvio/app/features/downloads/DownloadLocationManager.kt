package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.StateFlow

internal expect object DownloadLocationManager {
    val locationLabel: StateFlow<String>

    fun ensureLocationSet(): Boolean

    suspend fun ensureLocationSelectedOrPrompt(): Boolean

    fun currentLocationLabel(): String

    fun requestFolderPicker(): Boolean

    fun openDownloadLocation(): Boolean

    suspend fun finalizeDownload(sourceFileUri: String, destinationFileName: String): String

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun removeFile(localFileUri: String?): Boolean
}
