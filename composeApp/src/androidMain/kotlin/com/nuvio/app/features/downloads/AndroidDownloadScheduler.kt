package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory = internalDownloadsDirectory(context)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        var transfer = store.begin(item)
        if (transfer.destinationTreeUri == null) {
            DownloadLocationManager.currentDownloadTreeUri()?.let { tree ->
                transfer = store.update(item.fileName, transfer.generation) {
                    it.copy(destinationTreeUri = tree)
                } ?: transfer
            }
        }
        val existing = previous?.generation == transfer.generation
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                check(jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        backgroundScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    transfer?.destinationTreeUri?.let { tree ->
                        DownloadLocationManager.findFinalizedDownloadFile(tree, fileName)
                            ?.let(DownloadLocationManager::removeFile)
                    }
                    transfer?.destinationDocumentUri?.let { DownloadLocationManager.removeFile(it) }
                    transfer?.item?.localFileUri?.let { DownloadLocationManager.removeFile(it) }
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                    transfer?.item?.localFileUri?.let { DownloadSubtitleStorage(it).remove() }
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        var target: SafDownloadTarget? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false

            if (transfer.destinationDocumentUri != null) {
                DownloadLocationManager.findFinalizedDownloadFile(transfer.destinationTreeUri, fileName)
                    ?.let { finalized ->
                        complete(transfer, finalized, DownloadLocationManager.fileSize(finalized))
                        prepareSubtitles(fileName, finalized)
                        return@withLock false
                    }
            }

            target = resolveTarget(transfer)
            var lastProgressAt = 0L
            transferAndroidDownload(
                item = transfer.item,
                target = target,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                onProgress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 1_000L || bytes == total) {
                        lastProgressAt = now
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val bytes = target.size()
            val storedFileUri = target.finish()
            complete(transfer, storedFileUri, bytes)
            prepareSubtitles(fileName, storedFileUri)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) {
                updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            } else {
                target?.discard()
                clearDestination(transfer)
                fail(transfer, error)
            }
            retry
        } finally {
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    private fun resolveTarget(transfer: AndroidDownloadTransfer): SafDownloadTarget {
        val tree = transfer.destinationTreeUri
        val document = transfer.destinationDocumentUri
        if (tree != null && document != null) {
            DownloadLocationManager.openDownloadTarget(tree, document, transfer.item.fileName)?.let { return it }
        }
        val created = if (tree != null) {
            DownloadLocationManager.createDownloadTarget(tree, transfer.item.fileName)
        } else {
            DownloadLocationManager.createDownloadTarget(transfer.item.fileName)
        } ?: throw MissingLocationException()
        store.update(transfer.item.fileName, transfer.generation) {
            it.copy(
                destinationTreeUri = created.destinationTreeUri,
                destinationDocumentUri = created.destinationDocumentUri,
            )
        }
        return created
    }

    private fun complete(transfer: AndroidDownloadTransfer, storedFileUri: String, bytes: Long) {
        updateActive(transfer) { current ->
            current.copy(item = current.item.copy(
                status = DownloadStatus.Completed,
                localFileUri = storedFileUri,
                downloadedBytes = bytes,
                totalBytes = bytes,
                errorMessage = null,
            ))
        }
    }

    private fun clearDestination(transfer: AndroidDownloadTransfer) {
        store.update(transfer.item.fileName, transfer.generation) {
            it.copy(destinationTreeUri = null, destinationDocumentUri = null)
        }
    }

    private fun prepareSubtitles(fileName: String, localFileUri: String) {
        backgroundScope.launch {
            lock(fileName).withLock {
                val item = store.get(fileName)?.item
                if (item?.status != DownloadStatus.Completed || item.localFileUri != localFileUri) return@withLock
                try {
                    DownloadSubtitles.prepare(item, localFileUri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = error.message ?: "Download failed"))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is AppendNotSupportedException -> false
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
