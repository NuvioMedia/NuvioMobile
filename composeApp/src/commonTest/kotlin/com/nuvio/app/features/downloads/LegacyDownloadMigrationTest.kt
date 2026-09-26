package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyDownloadMigrationTest {
    @Test
    fun onlyCompletedDownloadsWithFileUrisAreMigrationCandidates() {
        val items = listOf(
            item(
                id = "legacy",
                status = DownloadStatus.Completed,
                localFileUri = "file:/data/user/0/com.nuvio/files/downloads/movie.mkv",
            ),
            item(
                id = "folder",
                status = DownloadStatus.Completed,
                localFileUri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fmovie.mkv",
            ),
            item(
                id = "downloading",
                status = DownloadStatus.Downloading,
                localFileUri = "file:/data/user/0/com.nuvio/files/downloads/partial.mkv",
            ),
            item(
                id = "paused",
                status = DownloadStatus.Paused,
                localFileUri = "file:/data/user/0/com.nuvio/files/downloads/paused.mkv",
            ),
            item(
                id = "failed",
                status = DownloadStatus.Failed,
                localFileUri = "file:/data/user/0/com.nuvio/files/downloads/failed.mkv",
            ),
            item(id = "missing-uri", status = DownloadStatus.Completed, localFileUri = null),
        )

        assertEquals(
            listOf("legacy"),
            LegacyDownloadMigration.itemsToMigrate(items).map(DownloadItem::id),
        )
    }

    @Test
    fun pendingMigrationsAreRetriedEvenWhenTheStoredFileWasHealed() {
        val items = listOf(
            item(
                id = "pending",
                status = DownloadStatus.Completed,
                localFileUri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fmovie.mkv",
                legacyMigrationPending = true,
            ),
            item(
                id = "healed",
                status = DownloadStatus.Completed,
                localFileUri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fmovie.mkv",
            ),
            item(
                id = "pending-not-completed",
                status = DownloadStatus.Paused,
                localFileUri = "file:/data/user/0/com.nuvio/files/downloads/paused.mkv",
                legacyMigrationPending = true,
            ),
        )

        assertEquals(
            listOf("pending"),
            LegacyDownloadMigration.itemsToMigrate(items).map(DownloadItem::id),
        )
    }

    @Test
    fun fileUrisAreRecognizedAsLegacyStoredFiles() {
        assertTrue(LegacyDownloadMigration.isLegacyStoredFileUri("file:/data/user/0/com.nuvio/files/downloads/movie.mkv"))
        assertTrue(LegacyDownloadMigration.isLegacyStoredFileUri("file:///data/user/0/com.nuvio/files/downloads/movie.mkv"))
        assertFalse(
            LegacyDownloadMigration.isLegacyStoredFileUri(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2Fmovie.mkv",
            ),
        )
        assertFalse(LegacyDownloadMigration.isLegacyStoredFileUri(null))
        assertFalse(LegacyDownloadMigration.isLegacyStoredFileUri(""))
    }

    private fun item(
        id: String,
        status: DownloadStatus,
        localFileUri: String?,
        legacyMigrationPending: Boolean = false,
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Test movie",
        streamTitle = "Test stream",
        providerName = "Test",
        sourceUrl = "https://example.com/video.mkv",
        localFileUri = localFileUri,
        fileName = "$id.mkv",
        status = status,
        legacyMigrationPending = legacyMigrationPending,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
