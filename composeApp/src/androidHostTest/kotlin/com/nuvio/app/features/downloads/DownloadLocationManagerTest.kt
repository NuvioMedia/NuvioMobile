package com.nuvio.app.features.downloads

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.net.URI
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
    fun androidDefaultsToInternalStorageLocation() {
        val context = initializedApplication()

        assertTrue(DownloadLocationManager.ensureLocationSet())
        assertEquals(
            File(context.filesDir, "downloads").absolutePath,
            DownloadLocationManager.currentLocationLabel(),
        )
    }

    @Test
    fun finalizeDownloadMovesTempFileIntoInternalLocation() {
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

    private companion object {
        val SAF_MOVIES_URI: Uri =
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")
        val SAF_NESTED_URI: Uri =
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FNuvio")
    }
}
