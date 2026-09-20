package com.nuvio.app.features.downloads

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyDownloadMigrationRepositoryTest {
    @Test
    fun completedLegacyDownloadMovesIntoTheFolderWithItsSubtitles() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        val legacyFile = legacyVideoFile(context, "legacy.mkv", "legacy video bytes")
        val legacyUri = legacyFile.toURI().toString()
        writeLegacySubtitle(legacyUri, "0.srt", srt)
        seedCompletedLegacyDownload(legacyUri, "legacy.mkv")

        DownloadsRepository.ensureLoaded()
        val storedUri = awaitStoredFileUri()

        val migrated = DownloadsRepository.uiState.value.items.single()
        assertEquals(DownloadStatus.Completed, migrated.status)
        assertEquals("legacy video bytes", readDocument(context, storedUri))
        assertFalse(legacyFile.exists())
        assertFalse(File("${legacyFile.path}.subtitles").exists())
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv"))
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv.subtitles/manifest.json"))
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv.subtitles/0.srt"))
        val track = DownloadSubtitles.localSubtitles(storedUri).single()
        assertEquals(srt, readDocument(context, track.url))
        assertTrue(DownloadsStorage.isLegacyMigrationComplete())
    }

    @Test
    fun failedCopyKeepsTheLegacyFileAndRetriesOnTheNextLoad() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        provider.failCreateDocument = true
        val legacyFile = legacyVideoFile(context, "legacy.mkv", "legacy video bytes")
        val legacyUri = legacyFile.toURI().toString()
        seedCompletedLegacyDownload(legacyUri, "legacy.mkv")

        DownloadsRepository.ensureLoaded()
        withTimeout(5_000) {
            while (provider.createDocumentAttempts == 0) delay(10)
        }

        assertEquals(legacyUri, DownloadsRepository.uiState.value.items.single().localFileUri)
        assertTrue(legacyFile.exists())
        assertFalse(DownloadsStorage.isLegacyMigrationComplete())

        provider.failCreateDocument = false
        DownloadsRepository.onProfileChanged()
        val storedUri = awaitStoredFileUri()

        assertFalse(legacyFile.exists())
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv"))
        assertEquals("legacy video bytes", readDocument(context, storedUri))
        assertTrue(DownloadsStorage.isLegacyMigrationComplete())
    }

    @Test
    fun interruptedMigrationRecoversSubtitlesBeforeMarkingTheProfileComplete() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        provider.createDocument("primary:Movies/legacy.mkv", "legacy video bytes")
        val legacyUri = File(File(context.filesDir, "downloads"), "legacy.mkv").toURI().toString()
        writeLegacySubtitle(legacyUri, "0.srt", srt)
        seedCompletedLegacyDownload(legacyUri, "legacy.mkv", legacyMigrationPending = true)

        DownloadsRepository.ensureLoaded()
        val storedUri = awaitStoredFileUri()

        val migrated = DownloadsRepository.uiState.value.items.single()
        assertFalse(migrated.legacyMigrationPending)
        assertEquals("legacy video bytes", readDocument(context, storedUri))
        assertFalse(File(context.filesDir, "downloads/legacy.mkv.subtitles").exists())
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv.subtitles/manifest.json"))
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv.subtitles/0.srt"))
        assertEquals(srt, readDocument(context, DownloadSubtitles.localSubtitles(storedUri).single().url))
        assertTrue(DownloadsStorage.isLegacyMigrationComplete())
    }

    @Test
    fun failedSubtitleMigrationKeepsTheItemPendingAndRetriesOnTheNextLoad() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        provider.createDocument("primary:Movies/legacy.mkv", "legacy video bytes")
        provider.failCreateDocument = true
        val legacyUri = File(File(context.filesDir, "downloads"), "legacy.mkv").toURI().toString()
        writeLegacySubtitle(legacyUri, "0.srt", srt)
        seedCompletedLegacyDownload(legacyUri, "legacy.mkv", legacyMigrationPending = true)

        DownloadsRepository.ensureLoaded()
        withTimeout(5_000) {
            while (provider.createDocumentAttempts == 0) delay(10)
        }

        val pending = DownloadsRepository.uiState.value.items.single()
        assertTrue(pending.legacyMigrationPending)
        assertFalse(DownloadsStorage.isLegacyMigrationComplete())
        assertTrue(File(context.filesDir, "downloads/legacy.mkv.subtitles").exists())

        provider.failCreateDocument = false
        DownloadsRepository.onProfileChanged()
        val storedUri = awaitStoredFileUri()

        assertFalse(DownloadsRepository.uiState.value.items.single().legacyMigrationPending)
        assertTrue(provider.documentExists("primary:Movies/legacy.mkv.subtitles/0.srt"))
        assertEquals(srt, readDocument(context, DownloadSubtitles.localSubtitles(storedUri).single().url))
        assertTrue(DownloadsStorage.isLegacyMigrationComplete())
    }

    @Test
    fun migrationDoesNotRunAgainOnceTheProfileFlagIsSet() {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        registerFakeDocumentsProvider()
        DownloadsStorage.markLegacyMigrationComplete()
        val legacyFile = legacyVideoFile(context, "legacy.mkv", "legacy video bytes")
        seedCompletedLegacyDownload(legacyFile.toURI().toString(), "legacy.mkv")

        DownloadsRepository.ensureLoaded()

        val item = DownloadsRepository.uiState.value.items.single()
        assertEquals(legacyFile.toURI().toString(), item.localFileUri)
        assertTrue(legacyFile.exists())
    }

    private fun initializedApplication(): Application =
        RuntimeEnvironment.getApplication().also {
            DownloadsStorage.initialize(it)
            DownloadLocationManager.initialize(it)
            DownloadsRepository.clearLocalState()
        }

    private fun legacyVideoFile(context: Application, fileName: String, contents: String): File =
        File(File(context.filesDir, "downloads").apply { mkdirs() }, fileName).apply { writeText(contents) }

    private fun writeLegacySubtitle(videoUri: String, fileName: String, contents: String) {
        val storage = DownloadSubtitleStorage(videoUri)
        storage.write(fileName, contents)
        storage.write(
            "manifest.json",
            """{"complete":true,"tracks":[{"sourceUrl":"https://example.com/en.srt","fileName":"$fileName","language":"en","name":"English"}]}""",
        )
    }

    private fun seedCompletedLegacyDownload(
        legacyUri: String,
        fileName: String,
        legacyMigrationPending: Boolean = false,
    ) {
        val item = downloadItem(id = "legacy-download").copy(
            fileName = fileName,
            localFileUri = legacyUri,
            status = DownloadStatus.Completed,
            legacyMigrationPending = legacyMigrationPending,
            downloadedBytes = 18L,
            totalBytes = 18L,
        )
        DownloadsStorage.savePayload(Json.encodeToString(SeededDownloadsPayload(items = listOf(item))))
    }

    private suspend fun awaitStoredFileUri(): String {
        var uri: String? = null
        withTimeout(5_000) {
            while (true) {
                uri = DownloadsRepository.uiState.value.items.firstOrNull()?.localFileUri
                if (uri?.startsWith("content://") == true) break
                delay(10)
            }
        }
        return checkNotNull(uri)
    }

    private fun readDocument(context: Application, uri: String): String =
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().decodeToString() }.orEmpty()

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
}

@Serializable
private data class SeededDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)
