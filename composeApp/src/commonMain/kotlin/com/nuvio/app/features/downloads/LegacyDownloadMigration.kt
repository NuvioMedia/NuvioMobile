package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException

internal object LegacyDownloadMigration {
    fun itemsToMigrate(items: List<DownloadItem>): List<DownloadItem> =
        items.filter(::isLegacyDownload)

    fun isLegacyStoredFileUri(storedFileUri: String?): Boolean =
        storedFileUri?.startsWith("file:") == true

    suspend fun migrate(
        items: List<DownloadItem>,
        onMigrated: (DownloadItem, String) -> Unit,
    ): Boolean {
        var migratedAll = true
        for (item in items) {
            val legacyUri = item.localFileUri ?: continue
            val migratedUri = runMigrationStep {
                DownloadLocationManager.finalizeDownload(legacyUri, item.fileName)
            }
            if (migratedUri == null) {
                migratedAll = false
                continue
            }
            runMigrationStep {
                if (migratedUri != legacyUri) {
                    DownloadSubtitles.migrate(legacyUri, migratedUri)
                }
            }
            onMigrated(item, migratedUri)
        }
        return migratedAll
    }

    private fun isLegacyDownload(item: DownloadItem): Boolean =
        item.status == DownloadStatus.Completed && isLegacyStoredFileUri(item.localFileUri)

    private suspend fun <T> runMigrationStep(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
}
