package com.nuvio.app.features.downloads

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.nuvio.app.core.build.AppFeaturePolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class DownloadsTransferWorkerTest {
    @Test
    fun completesDownloadsWithTheDistributionForegroundPolicy(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val id = UUID.randomUUID().toString()
            val item = downloadItem(server.url("/video").toString(), id).copy(fileName = "$id.mkv")
            val transfer = scheduler.store.begin(item)
            val promotions = mutableListOf<ForegroundInfo>()
            val worker = TestListenableWorkerBuilder<DownloadsTransferWorker>(context)
                .setInputData(workDataOf(
                    AndroidDownloadScheduler.FILE_NAME to item.fileName,
                    AndroidDownloadScheduler.GENERATION to transfer.generation,
                ))
                .setForegroundUpdater { _, _, info ->
                    promotions += info
                    SettableFuture.create<Void>().apply { set(null) }
                }
                .build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())

            val completed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, completed.status)
            assertEquals("complete video", File(scheduler.directory, item.fileName).readText())
            assertEquals(14L, completed.downloadedBytes)
            assertEquals("Bearer test", server.takeRequest().getHeader("Authorization"))
            assertEquals(if (AppFeaturePolicy.downloadForegroundServiceEnabled) 1 else 0, promotions.size)
            promotions.singleOrNull()?.let { info ->
                assertEquals(DownloadsLiveStatusPlatform.notificationId(item.id), info.notificationId)
                assertEquals(
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                    info.foregroundServiceType,
                )
            }
        }
    }
}
