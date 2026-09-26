package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadLocationManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun noStoredLocationReportsUnsetButKeepsInternalLabel() {
        val context = initializedApplication()

        assertFalse(DownloadLocationManager.ensureLocationSet())
        assertEquals(
            File(context.filesDir, "downloads").absolutePath,
            DownloadLocationManager.currentLocationLabel(),
        )
    }

    @Test
    fun revokedSafLocationIsClearedAndReportsUnset() {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        assertTrue(DownloadLocationManager.ensureLocationSet())

        revokePersistedPermission(context, SAF_MOVIES_URI)

        assertFalse(DownloadLocationManager.ensureLocationSet())
        val internalLabel = File(context.filesDir, "downloads").absolutePath
        assertEquals(internalLabel, DownloadLocationManager.currentLocationLabel())
        assertEquals(internalLabel, DownloadLocationManager.locationLabel.value)

        DownloadLocationManager.initialize(context)
        assertEquals(internalLabel, DownloadLocationManager.currentLocationLabel())
        assertFalse(DownloadLocationManager.ensureLocationSet())
    }

    @Test
    fun revokedSafStoredFileResolvesToUnavailable() {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val documentUri = safDocumentUri("primary:Movies/video.mkv")
        registerFakeDocumentsProvider(documentUri).createDocument("primary:Movies/video.mkv")

        assertEquals(
            documentUri.toString(),
            DownloadLocationManager.resolveLocalFileUri(documentUri.toString(), "video.mkv"),
        )

        revokePersistedPermission(context, SAF_MOVIES_URI)

        assertNull(DownloadLocationManager.resolveLocalFileUri(documentUri.toString(), "video.mkv"))
    }

    @Test
    fun missingSafDocumentResolvesToUnavailable() {
        initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val documentUri = safDocumentUri("primary:Movies/missing.mkv")

        assertNull(DownloadLocationManager.resolveLocalFileUri(documentUri.toString(), "missing.mkv"))
    }

    @Test
    fun finalizeDownloadMovesTempFileIntoInternalLocation() = runBlocking {
        val context = initializedApplication()
        val temp = File(temporary.newFolder(), "video.mkv.part").apply { writeText("video bytes") }

        val uri = DownloadLocationManager.finalizeDownload(temp.toURI().toString(), "video.mkv")

        assertTrue(uri.startsWith("file:"))
        val finalized = File(URI(uri))
        assertEquals(File(context.filesDir, "downloads/video.mkv").canonicalPath, finalized.canonicalPath)
        assertEquals("video bytes", finalized.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun finalizeDownloadCopiesTempFileIntoSelectedFolderAndDeletesTemp() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider(SAF_MOVIES_URI)
        val temp = File(temporary.newFolder(), "video.mkv.part").apply { writeText("video bytes") }

        val uri = DownloadLocationManager.finalizeDownload(temp.toURI().toString(), "video.mkv")

        assertTrue(uri.startsWith("content://"))
        assertEquals(
            "video bytes",
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().decodeToString() },
        )
        assertTrue(provider.documentExists("primary:Movies/video.mkv"))
        assertFalse(temp.exists())
    }

    @Test
    fun finalizedDocumentUriResolvesForPlayback() = runBlocking {
        initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        registerFakeDocumentsProvider(SAF_MOVIES_URI)
        val temp = File(temporary.newFolder(), "video.mkv.part").apply { writeText("video bytes") }

        val uri = DownloadLocationManager.finalizeDownload(temp.toURI().toString(), "video.mkv")

        assertEquals(uri, DownloadLocationManager.resolveLocalFileUri(uri, "video.mkv"))
    }

    @Test
    fun finalizeDownloadReplacesExistingFileInSelectedFolder() = runBlocking {
        val context = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider(SAF_MOVIES_URI)
        provider.createDocument("primary:Movies/video.mkv", "old bytes")
        val temp = File(temporary.newFolder(), "video.mkv.part").apply { writeText("new bytes") }

        val uri = DownloadLocationManager.finalizeDownload(temp.toURI().toString(), "video.mkv")

        assertEquals(
            "new bytes",
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().decodeToString() },
        )
    }

    @Test
    fun resolveLocalFileUriUsesStoredFileAndFallsBackToLocation() {
        val context = initializedApplication()
        val stored = File(temporary.newFolder(), "stored.mkv").apply { writeText("stored") }

        assertEquals(
            stored.toURI().toString(),
            DownloadLocationManager.resolveLocalFileUri(stored.toURI().toString(), "stored.mkv"),
        )

        val downloadsDirectory = File(context.filesDir, "downloads").apply { mkdirs() }
        val fallback = File(downloadsDirectory, "fallback.mkv").apply { writeText("fallback") }
        assertEquals(
            fallback.toURI().toString(),
            DownloadLocationManager.resolveLocalFileUri(null, "fallback.mkv"),
        )

        assertNull(DownloadLocationManager.resolveLocalFileUri(null, "missing.mkv"))
        assertNull(DownloadLocationManager.resolveLocalFileUri(null, ""))
    }

    @Test
    fun removeFileDeletesLegacyFile() {
        val context = initializedApplication()
        val file = File(temporary.newFolder(), "remove-me.mkv").apply { writeText("bytes") }

        assertTrue(DownloadLocationManager.removeFile(file.toURI().toString()))
        assertFalse(file.exists())
        assertFalse(DownloadLocationManager.removeFile(null))
    }

    @Test
    fun removeFileDeletesSafDocument() {
        initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider(SAF_MOVIES_URI)
        provider.createDocument("primary:Movies/video.mkv")
        val documentUri = safDocumentUri("primary:Movies/video.mkv")

        assertTrue(DownloadLocationManager.removeFile(documentUri.toString()))

        assertFalse(provider.documentExists("primary:Movies/video.mkv"))
    }

    @Test
    fun ensureLocationSelectedOrPromptReturnsTrueWhenLocationAlreadySet() = runBlocking {
        initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)

        assertTrue(DownloadLocationManager.ensureLocationSelectedOrPrompt())
    }

    @Test
    fun ensureLocationSelectedOrPromptContinuesAfterFolderPicked() = runBlocking {
        initializedApplication()
        val launched = CompletableDeferred<Unit>()
        DownloadLocationManager.bindFolderPicker { launched.complete(Unit) }

        val selection = async { DownloadLocationManager.ensureLocationSelectedOrPrompt() }
        launched.await()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)

        assertTrue(selection.await())
        assertEquals("Movies", DownloadLocationManager.currentLocationLabel())
    }

    @Test
    fun ensureLocationSelectedOrPromptAbortsWhenPickerIsCancelled() = runBlocking {
        initializedApplication()
        val launched = CompletableDeferred<Unit>()
        DownloadLocationManager.bindFolderPicker { launched.complete(Unit) }

        val selection = async { DownloadLocationManager.ensureLocationSelectedOrPrompt() }
        launched.await()
        DownloadLocationManager.onFolderPicked(null)

        assertFalse(selection.await())
        assertFalse(DownloadLocationManager.ensureLocationSet())
    }

    @Test
    fun ensureLocationSelectedOrPromptAbortsWhenPickerCannotBeOpened() = runBlocking {
        initializedApplication()
        DownloadLocationManager.bindFolderPicker(null)

        assertFalse(DownloadLocationManager.ensureLocationSelectedOrPrompt())
    }

    @Test
    fun folderPickerPersistsSafLocationAndUpdatesLabel() {
        val context = initializedApplication()

        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)

        assertEquals("Movies", DownloadLocationManager.currentLocationLabel())
        assertEquals("Movies", DownloadLocationManager.locationLabel.value)

        DownloadLocationManager.initialize(context)
        assertEquals("Movies", DownloadLocationManager.currentLocationLabel())
    }

    @Test
    fun changingFolderReplacesStoredLocation() {
        initializedApplication()

        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        DownloadLocationManager.onFolderPicked(SAF_NESTED_URI)

        assertEquals("Download/Nuvio", DownloadLocationManager.currentLocationLabel())
    }

    @Test
    fun cancellingFolderPickerKeepsExistingLocation() {
        val context = initializedApplication()
        val defaultLabel = DownloadLocationManager.currentLocationLabel()

        DownloadLocationManager.onFolderPicked(null)

        assertEquals(defaultLabel, DownloadLocationManager.currentLocationLabel())
    }

    @Test
    fun openDownloadLocationUsesPersistedTreeDocumentUri() {
        val application = initializedApplication()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)

        assertTrue(DownloadLocationManager.openDownloadLocation())

        val started = shadowOf(application).nextStartedActivity
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(
                SAF_MOVIES_URI,
                DocumentsContract.getTreeDocumentId(SAF_MOVIES_URI),
            ),
            started?.data,
        )
    }

    @Test
    fun requestFolderPickerInvokesBoundLauncher() {
        initializedApplication()
        var launched = false
        DownloadLocationManager.bindFolderPicker { launched = true }

        assertTrue(DownloadLocationManager.requestFolderPicker())
        assertTrue(launched)
    }

    private fun initializedApplication(): Application =
        RuntimeEnvironment.getApplication().also(DownloadLocationManager::initialize)

    private fun revokePersistedPermission(context: Application, uri: Uri) {
        context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

}
