package com.nuvio.app.features.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.CancellationException

class DownloadsTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scheduler = DownloadsPlatformDownloader.scheduler(applicationContext)
        val fileName = inputData.getString(AndroidDownloadScheduler.FILE_NAME) ?: return Result.failure()
        val generation = inputData.getString(AndroidDownloadScheduler.GENERATION) ?: return Result.failure()
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return Result.success()
        if (!scheduler.isActive(transfer)) return Result.success()
        DownloadsLiveStatusPlatform.initialize(applicationContext)
        try {
            if (AppFeaturePolicy.downloadForegroundServiceEnabled) {
                setForeground(ForegroundInfo(
                    DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
                    DownloadsLiveStatusPlatform.buildNotification(applicationContext, transfer.item),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                ))
            }
            val retry = scheduler.execute(transfer) { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
            scheduler.notifyCurrent(fileName)
            return if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            scheduler.fail(transfer, error)
            scheduler.notifyCurrent(fileName)
            return Result.failure()
        }
    }
}
