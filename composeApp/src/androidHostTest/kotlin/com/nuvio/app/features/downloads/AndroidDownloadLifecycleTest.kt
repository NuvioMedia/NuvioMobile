package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class AndroidDownloadLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun scheduledDownloadsArePersistedUserInitiatedTransfers() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context)
        val transfer = AndroidDownloadStore(temporary.newFolder()).begin(downloadItem())

        val job = scheduler.buildJob(transfer)

        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertNotNull(job.requiredNetwork)
        assertEquals(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), job.estimatedNetworkDownloadBytes)
        assertEquals(transfer.generation, job.extras.getString(AndroidDownloadScheduler.GENERATION))
        assertEquals(transfer.item.fileName, job.extras.getString(AndroidDownloadScheduler.FILE_NAME))
        assertEquals(DownloadsTransferJobService::class.java.name, job.service.className)
        assertEquals(2, job.extras.size())
    }

    @Test
    fun transferStateAndCredentialsSurviveProcessRecreation() {
        val directory = temporary.newFolder()
        val store = AndroidDownloadStore(directory)
        val transfer = store.begin(downloadItem())
        store.update(transfer.item.fileName, transfer.generation) {
            it.copy(validator = "\"v1\"", item = it.item.copy(downloadedBytes = 12L, totalBytes = 100L))
        }

        val restored = assertNotNull(AndroidDownloadStore(directory).get(transfer.item.fileName))

        assertEquals(DownloadStatus.Downloading, restored.item.status)
        assertEquals(12L, restored.item.downloadedBytes)
        assertEquals(transfer.item.sourceHeaders, restored.item.sourceHeaders)
        assertEquals("\"v1\"", restored.validator)
        assertEquals(transfer.generation, restored.generation)
        assertEquals(transfer.jobId, restored.jobId)
    }

    @Test
    fun missingSystemJobIsPausedInsteadOfSilentlyRescheduling() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "stopped.mkv"))

        assertEquals(DownloadStatus.Paused, scheduler.restore(transfer.item).status)
    }

    @Test
    fun staleWorkerCannotOverwriteAResumedTransfer() {
        val store = AndroidDownloadStore(temporary.newFolder())
        val first = store.begin(downloadItem())
        store.update(first.item.fileName, first.generation) { it.copy(item = it.item.copy(status = DownloadStatus.Paused)) }
        val resumed = store.begin(first.item)

        store.update(first.item.fileName, first.generation) { it.copy(item = it.item.copy(status = DownloadStatus.Failed)) }

        assertNotEquals(first.generation, resumed.generation)
        assertEquals(resumed, store.get(first.item.fileName))
    }

    @Test
    fun backgroundExecutionDoesNotNeedRepositoryOrActivityCallbacks(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "background").copy(fileName = "background.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val recreated = AndroidDownloadScheduler(context)
            val restored = recreated.restore(item)
            assertEquals(DownloadStatus.Completed, restored.status)
            assertEquals("complete video", provider.readDocument("primary:Movies/${item.fileName}"))
            assertEquals(14L, restored.downloadedBytes)
            assertNotNull(restored.localFileUri)
        }
    }

    @Test
    fun pausedTransferIsNotRestartedBySystemRedelivery(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context)
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "paused.mkv"))
        scheduler.store.update(transfer.item.fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused))
        }

        assertFalse(scheduler.execute(transfer) { error("Paused download must not transfer data") })
        assertEquals(DownloadStatus.Paused, scheduler.restore(transfer.item).status)
    }

    @Test
    fun completedRenameIsRecoveredAfterProcessDeathBeforeStateCommit(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val provider = registerFakeDocumentsProvider()
        val scheduler = AndroidDownloadScheduler(context)
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "finalized.mkv"))
        scheduler.store.update(transfer.item.fileName, transfer.generation) {
            it.copy(
                destinationTreeUri = SAF_MOVIES_URI.toString(),
                destinationDocumentUri = safDocumentUri("primary:Movies/finalized.mkv.part").toString(),
            )
        }
        provider.createDocument("primary:Movies/finalized.mkv", "final data")

        assertFalse(scheduler.execute(assertNotNull(scheduler.store.get(transfer.item.fileName))) { })

        assertEquals(DownloadStatus.Completed, scheduler.store.get(transfer.item.fileName)?.item?.status)
        assertEquals(10L, scheduler.store.get(transfer.item.fileName)?.item?.downloadedBytes)
    }

    @Test
    fun completedSafDownloadStoresContentUriAndRemovesInternalTemp(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        registerFakeDocumentsProvider()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "saf").copy(fileName = "saf.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status)
            val storedUri = assertNotNull(completed.localFileUri)
            assertTrue(storedUri.startsWith("content://"))
            assertEquals(
                "complete video",
                context.contentResolver.openInputStream(Uri.parse(storedUri))
                    ?.use { it.readBytes().decodeToString() },
            )
            assertFalse(File(scheduler.directory, item.fileName).exists())
        }
    }

    @Test
    fun noLocationFailsOnceWithChooseAFolder(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val scheduler = AndroidDownloadScheduler(context)
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "no-location.mkv"))

        assertFalse(scheduler.execute(transfer) { })

        val stored = assertNotNull(scheduler.store.get(transfer.item.fileName))
        assertEquals(DownloadStatus.Failed, stored.item.status)
        assertEquals(MISSING_LOCATION_MESSAGE, stored.item.errorMessage)
        assertEquals(0, stored.retryCount)
    }

    @Test
    fun changingTheLocationOnlyAffectsNewDownloads(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val provider = registerFakeDocumentsProvider()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val scheduler = AndroidDownloadScheduler(context)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val item = downloadItem(server.url("/video").toString(), "move").copy(fileName = "move.mkv")
            val target = assertNotNull(DownloadLocationManager.createDownloadTarget(item.fileName))
            val transfer = scheduler.store.begin(item)
            scheduler.store.update(item.fileName, transfer.generation) {
                it.copy(
                    destinationTreeUri = target.destinationTreeUri,
                    destinationDocumentUri = target.destinationDocumentUri,
                )
            }

            DownloadLocationManager.onFolderPicked(SAF_NESTED_URI)

            assertFalse(scheduler.execute(assertNotNull(scheduler.store.get(item.fileName))) { })

            assertEquals("complete video", provider.readDocument("primary:Movies/move.mkv"))
            assertFalse(provider.documentExists("primary:Download/Nuvio/move.mkv"))
        }
    }

    @Test
    fun appendRejectedProviderFailsOnceAndDiscardsThePartial(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val provider = registerFakeDocumentsProvider()
        provider.appendSupported = false
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11").setBody(" world"),
            )
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "append").copy(fileName = "append.mkv")
            provider.createDocument("primary:Movies/append.mkv.part", "hello")
            val transfer = scheduler.store.begin(item)
            scheduler.store.update(item.fileName, transfer.generation) { it.copy(validator = "\"v1\"") }

            assertFalse(scheduler.execute(assertNotNull(scheduler.store.get(item.fileName))) { })

            val stored = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Failed, stored.item.status)
            assertEquals(APPEND_NOT_SUPPORTED_MESSAGE, stored.item.errorMessage)
            assertFalse(provider.documentExists("primary:Movies/append.mkv.part"))
            assertNull(stored.destinationDocumentUri)
        }
    }

    @Test
    fun retryableFailureKeepsThePartialForResume(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val provider = registerFakeDocumentsProvider()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "keep").copy(fileName = "keep.mkv")
            val transfer = scheduler.store.begin(item)

            assertTrue(scheduler.execute(transfer) { })

            assertEquals(DownloadStatus.Downloading, assertNotNull(scheduler.store.get(item.fileName)).item.status)
            assertTrue(provider.documentExists("primary:Movies/keep.mkv.part"))
        }
    }

    @Test
    fun removingADownloadDiscardsThePartialAndFinalFile(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val provider = registerFakeDocumentsProvider()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val scheduler = AndroidDownloadScheduler(context)
        val item = downloadItem().copy(fileName = "remove.mkv")
        val target = assertNotNull(DownloadLocationManager.createDownloadTarget(item.fileName))
        provider.createDocument("primary:Movies/remove.mkv", "final bytes")
        val transfer = scheduler.store.begin(item)
        scheduler.store.update(item.fileName, transfer.generation) {
            it.copy(
                destinationTreeUri = target.destinationTreeUri,
                destinationDocumentUri = target.destinationDocumentUri,
                item = it.item.copy(localFileUri = safDocumentUri("primary:Movies/remove.mkv").toString()),
            )
        }

        scheduler.remove(item.fileName)

        withTimeout(5_000) {
            while (
                provider.documentExists("primary:Movies/remove.mkv") ||
                provider.documentExists("primary:Movies/remove.mkv.part")
            ) {
                delay(10)
            }
        }
        assertFalse(provider.documentExists("primary:Movies/remove.mkv"))
        assertFalse(provider.documentExists("primary:Movies/remove.mkv.part"))
    }

    @Test
    fun removingADownloadCleansUpAFinalizedFileThatWasNotCommitted(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        DownloadLocationManager.initialize(context)
        val provider = registerFakeDocumentsProvider()
        DownloadLocationManager.onFolderPicked(SAF_MOVIES_URI)
        val scheduler = AndroidDownloadScheduler(context)
        val item = downloadItem().copy(fileName = "uncommitted.mkv")
        val target = assertNotNull(DownloadLocationManager.createDownloadTarget(item.fileName))
        provider.createDocument("primary:Movies/uncommitted.mkv", "final bytes")
        val transfer = scheduler.store.begin(item)
        scheduler.store.update(item.fileName, transfer.generation) {
            it.copy(
                destinationTreeUri = target.destinationTreeUri,
                destinationDocumentUri = target.destinationDocumentUri,
            )
        }

        scheduler.remove(item.fileName)

        withTimeout(5_000) {
            while (provider.documentExists("primary:Movies/uncommitted.mkv")) delay(10)
        }
        assertFalse(provider.documentExists("primary:Movies/uncommitted.mkv"))
        assertFalse(provider.documentExists("primary:Movies/uncommitted.mkv.part"))
    }
}
