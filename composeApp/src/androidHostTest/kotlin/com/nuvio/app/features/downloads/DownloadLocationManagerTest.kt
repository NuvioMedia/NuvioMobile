package com.nuvio.app.features.downloads

import java.io.File
import java.net.URI
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)

        assertTrue(DownloadLocationManager.ensureLocationSet())
        assertEquals(
            File(context.filesDir, "downloads").absolutePath,
            DownloadLocationManager.currentLocationLabel(),
        )
    }

    @Test
    fun finalizeDownloadMovesTempFileIntoInternalLocation() {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
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
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
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
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val file = File(temporary.newFolder(), "remove-me.mkv").apply { writeText("bytes") }

        assertTrue(DownloadLocationManager.removeFile(file.toURI().toString()))
        assertFalse(file.exists())
        assertFalse(DownloadLocationManager.removeFile(null))
    }
}
