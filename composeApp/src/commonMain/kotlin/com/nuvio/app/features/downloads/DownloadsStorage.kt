package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun isLegacyMigrationComplete(): Boolean
    fun markLegacyMigrationComplete()
}
