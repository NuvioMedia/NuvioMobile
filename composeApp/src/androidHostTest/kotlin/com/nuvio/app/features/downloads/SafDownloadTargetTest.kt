package com.nuvio.app.features.downloads

import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafDownloadTargetTest {
    @Test
    fun createsPartDocumentWithTheVideoMimeType() {
        val provider = setup()

        val target = target("video.mkv")

        assertTrue(provider.documentExists("primary:Movies/video.mkv.part"))
        assertEquals("video/x-matroska", provider.createdMimeTypes.last())
        assertEquals(SAF_MOVIES_URI.toString(), target.destinationTreeUri)
        assertTrue(target.destinationDocumentUri.contains("video.mkv.part"))
    }

    @Test
    fun finishRenamesThePartIntoPlace() {
        val provider = setup()
        val target = target("video.mkv")
        target.openStream(append = false).use { it.write("hello world".toByteArray()) }

        val finished = target.finish()

        assertEquals("hello world", provider.readDocument("primary:Movies/video.mkv"))
        assertFalse(provider.documentExists("primary:Movies/video.mkv.part"))
        assertEquals(
            "hello world",
            RuntimeEnvironment.getApplication().contentResolver
                .openInputStream(Uri.parse(finished))?.use { it.readBytes().decodeToString() },
        )
    }

    @Test
    fun finishCopiesWithinTheTreeWhenRenameIsUnsupported() {
        val provider = setup()
        provider.renameSupported = false
        val target = target("video.mkv")
        target.openStream(append = false).use { it.write("hello world".toByteArray()) }

        val finished = target.finish()

        assertEquals("hello world", provider.readDocument("primary:Movies/video.mkv"))
        assertFalse(provider.documentExists("primary:Movies/video.mkv.part"))
        assertTrue(finished.startsWith("content://"))
    }

    @Test
    fun finishReplacesAnExistingFinalFile() {
        val provider = setup()
        provider.createDocument("primary:Movies/video.mkv", "old bytes")
        val target = target("video.mkv")
        target.openStream(append = false).use { it.write("new bytes".toByteArray()) }

        target.finish()

        assertEquals("new bytes", provider.readDocument("primary:Movies/video.mkv"))
    }

    @Test
    fun discardDeletesThePartDocument() {
        val provider = setup()
        val target = target("video.mkv")

        assertTrue(target.discard())

        assertFalse(provider.documentExists("primary:Movies/video.mkv.part"))
    }

    @Test
    fun openReusesAPersistedPartDocumentForResume() {
        val provider = setup()
        val created = target("video.mkv")
        created.openStream(append = false).use { it.write("partial".toByteArray()) }

        val reopened = assertNotNull(
            DownloadLocationManager.openDownloadTarget(
                created.destinationTreeUri,
                created.destinationDocumentUri,
                "video.mkv",
            ),
        )

        assertEquals(7L, reopened.size())
        assertEquals(created.destinationDocumentUri, reopened.destinationDocumentUri)
    }

    @Test
    fun openIgnoresAMissingPartDocument() {
        setup()

        assertNull(DownloadLocationManager.openDownloadTarget(SAF_MOVIES_URI.toString(), safDocumentUri("primary:Movies/missing.mkv.part").toString(), "missing.mkv"))
    }

    @Test
    fun appendRejectedByProviderThrowsNonRetryable() {
        setup(appendSupported = false)
        val target = target("video.mkv")
        target.openStream(append = false).use { it.write("partial".toByteArray()) }

        assertFailsWith<AppendNotSupportedException> { target.openStream(append = true) }
        assertFalse(shouldRetryAndroidDownload(AppendNotSupportedException(), 0))
    }

    @Test
    fun createWithNoLocationReturnsNull() {
        DownloadLocationManager.initialize(RuntimeEnvironment.getApplication())

        assertNull(DownloadLocationManager.createDownloadTarget("video.mkv"))
    }

    private fun setup(appendSupported: Boolean = true): FakeDocumentsProvider =
        setupSafDownloads(appendSupported = appendSupported)

    private fun target(fileName: String): SafDownloadTarget =
        checkNotNull(DownloadLocationManager.createDownloadTarget(fileName))
}
