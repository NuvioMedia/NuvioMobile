package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val scheduler = AndroidDownloadScheduler(context)
            val item = downloadItem(server.url("/video").toString(), "background").copy(fileName = "background.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val recreated = AndroidDownloadScheduler(context)
            val restored = recreated.restore(item)
            assertEquals(DownloadStatus.Completed, restored.status)
            assertEquals("complete video", File(recreated.directory, item.fileName).readText())
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
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "finalized.mkv"))
        scheduler.directory.mkdirs()
        File(scheduler.directory, transfer.item.fileName).writeText("final data")

        assertFalse(scheduler.execute(transfer) { })

        assertEquals(DownloadStatus.Completed, scheduler.store.get(transfer.item.fileName)?.item?.status)
        assertEquals(10L, scheduler.store.get(transfer.item.fileName)?.item?.downloadedBytes)
    }
}
