package com.nuvio.app.features.downloads

import kotlinx.coroutines.CancellationException

internal object LegacyDownloadMigration {
    fun itemsToMigrate(items: List<DownloadItem>): List<DownloadItem> =
        items.filter { isLegacyDownload(it) || isPendingMigration(it) }

    fun isLegacyStoredFileUri(storedFileUri: String?): Boolean =
        storedFileUri?.startsWith("file:") == true

    suspend fun migrate(
        items: List<DownloadItem>,
        onMigrationStarted: (DownloadItem) -> Unit,
        onMigrated: (DownloadItem, String) -> Unit,
    ): Boolean {
        var migratedAll = true
        for (item in items) {
            val storedFileUri = item.localFileUri ?: continue
            onMigrationStarted(item)

            val migratedUri = if (isLegacyStoredFileUri(storedFileUri)) {
                runQuietlySuspending {
                    DownloadLocationManager.finalizeDownload(storedFileUri, item.fileName)
                }
            } else {
                storedFileUri
            }
            if (migratedUri == null) {
                migratedAll = false
                continue
            }

            if (migratedUri != storedFileUri) {
                val subtitlesMigrated = runQuietlySuspending {
                    DownloadSubtitles.migrate(storedFileUri, migratedUri)
                } ?: false
                if (!subtitlesMigrated) {
                    migratedAll = false
                    continue
                }
            }

            onMigrated(item, migratedUri)
        }
        return migratedAll
    }

    private fun isLegacyDownload(item: DownloadItem): Boolean =
        item.status == DownloadStatus.Completed && isLegacyStoredFileUri(item.localFileUri)

    private fun isPendingMigration(item: DownloadItem): Boolean =
        item.status == DownloadStatus.Completed && item.legacyMigrationPending
}

internal suspend inline fun <T> runQuietlySuspending(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
