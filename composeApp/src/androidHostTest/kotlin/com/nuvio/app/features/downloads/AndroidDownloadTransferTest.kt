package com.nuvio.app.features.downloads

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadTransferTest {
    @Test
    fun resumesPartialFileWithRangeAndValidator(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"version-1\"").setBody(" world"))
            val provider = setupProvider(partial = "hello")

            transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("hello world", provider.readDocument("primary:Movies/video.mkv.part"))
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range"))
            assertEquals("\"version-1\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
        }
    }

    @Test
    fun ignoredRangeRestartsInsteadOfAppending(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new file"))
            val provider = setupProvider(partial = "old prefix")

            transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("new file", provider.readDocument("primary:Movies/video.mkv.part"))
        }
    }

    @Test
    fun unsatisfiedRangeRetriesFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            val provider = setupProvider(partial = "old prefix")

            transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })

            assertEquals("replacement", provider.readDocument("primary:Movies/video.mkv.part"))
            assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test
    fun mismatchedRangeDoesNotCorruptThePartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("bad"))
            val provider = setupProvider(partial = "hello")

            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                    "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }

            assertEquals("hello", provider.readDocument("primary:Movies/video.mkv.part"))
        }
    }

    @Test
    fun truncatedResponseNeverBecomesCompletedFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val provider = setupProvider()

            assertFailsWith<IOException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }

            assertFalse(provider.documentExists("primary:Movies/video.mkv"))
            assertTrue(provider.documentSize("primary:Movies/video.mkv.part") < 10)
        }
    }

    @Test
    fun cancellationClosesBlockedSocketPromptlyAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").throttleBody(1, 10, TimeUnit.SECONDS))
            val provider = setupProvider()
            val task = async(Dispatchers.Default) {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                    null, onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }
            withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            withTimeout(5_000) {
                while (provider.documentSize("primary:Movies/video.mkv.part") == 0L) delay(10)
            }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertEquals(1L, provider.documentSize("primary:Movies/video.mkv.part"))
            assertFalse(provider.documentExists("primary:Movies/video.mkv"))
        }
    }

    @Test
    fun appendRejectedByProviderFailsAsNonRetryable(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11").setBody(" world"))
            val provider = setupProvider(partial = "hello", appendSupported = false)

            assertFailsWith<AppendNotSupportedException> {
                transferAndroidDownload(downloadItem(server.url("/video").toString()), target("video.mkv"),
                    "\"version-1\"", onHeaders = { _, _ -> }, onProgress = { _, _ -> })
            }

            assertEquals("hello", provider.readDocument("primary:Movies/video.mkv.part"))
            assertFalse(shouldRetryAndroidDownload(AppendNotSupportedException(), 0))
        }
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(shouldRetryAndroidDownload(IOException("connection lost"), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(503), 0))
        assertTrue(shouldRetryAndroidDownload(DownloadHttpException(429), 0))
        assertFalse(shouldRetryAndroidDownload(DownloadHttpException(403), 0))
        assertFalse(shouldRetryAndroidDownload(IOException("connection lost"), 4))
        assertFalse(shouldRetryAndroidDownload(AppendNotSupportedException(), 0))
        assertFalse(shouldRetryAndroidDownload(MissingLocationException(), 0))
    }

    private fun setupProvider(partial: String? = null, appendSupported: Boolean = true): FakeDocumentsProvider {
        val provider = setupSafDownloads(appendSupported = appendSupported)
        partial?.let { provider.createDocument("primary:Movies/video.mkv.part", it) }
        return provider
    }

    private fun target(fileName: String): SafDownloadTarget =
        checkNotNull(DownloadLocationManager.createDownloadTarget(fileName))
}

internal fun downloadItem(url: String = "https://example.com/video.mkv", id: String = "test-download") = DownloadItem(
    id = id,
    contentType = "movie",
    parentMetaId = "test-movie",
    parentMetaType = "movie",
    videoId = "test-movie",
    title = "Download test",
    streamTitle = "Test video",
    providerName = "Test",
    sourceUrl = url,
    sourceHeaders = mapOf("Authorization" to "Bearer test"),
    fileName = "video.mkv",
    status = DownloadStatus.Downloading,
    createdAtEpochMs = 1L,
    updatedAtEpochMs = 1L,
)
