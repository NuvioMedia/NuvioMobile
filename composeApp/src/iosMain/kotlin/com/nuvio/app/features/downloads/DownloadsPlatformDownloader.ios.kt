package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.downloads_error_open_partial_file_failed
import nuvio.composeapp.generated.resources.downloads_error_partial_file_not_open
import nuvio.composeapp.generated.resources.downloads_error_write_partial_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

private const val DOWNLOAD_REQUEST_TIMEOUT_SECONDS = 60.0
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0
private const val PROGRESS_MIN_INTERVAL_SECONDS = 0.5
private const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
}

fun pauseDownloadsForAppBackground() {
    DownloadsRepository.pauseActiveDownloads()
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val handle = IosDownloadsTaskHandle(job)

        scope.launch {
            val downloadsDirectory = downloadsDirectoryPath()
            val destinationPath = "$downloadsDirectory/${request.destinationFileName}"
            val tempPath = "$downloadsDirectory/${request.destinationFileName}.part"

            try {
                DownloadSubtitles.prepare(request.item, NSURL.fileURLWithPath(destinationPath).absoluteString!!)
                var resumeFromBytes = fileSizeOrNull(tempPath)?.coerceAtLeast(0L) ?: 0L

                var attemptedRangeRequest = resumeFromBytes > 0L
                var result = performDownloadRequest(
                    request = request,
                    rangeStart = if (attemptedRangeRequest) resumeFromBytes else null,
                    resumeFromBytes = resumeFromBytes,
                    tempPath = tempPath,
                    handle = handle,
                    onProgress = onProgress,
                )

                if (attemptedRangeRequest && result.statusCode == 416) {
                    removePathIfExists(tempPath)
                    resumeFromBytes = 0L
                    attemptedRangeRequest = false
                    result = performDownloadRequest(
                        request = request,
                        rangeStart = null,
                        resumeFromBytes = 0L,
                        tempPath = tempPath,
                        handle = handle,
                        onProgress = onProgress,
                    )
                }

                if (result.statusCode !in 200..299) {
                    error(runBlocking { getString(Res.string.network_request_failed_http, result.statusCode) })
                }

                val isPartialResume = attemptedRangeRequest && result.statusCode == 206 && resumeFromBytes > 0L
                val startingBytes = if (isPartialResume) resumeFromBytes else 0L
                val totalBytes = resolveTotalBytes(
                    startingBytes = startingBytes,
                    isPartialResume = isPartialResume,
                    contentRangeHeader = result.contentRange,
                    contentLength = result.contentLength,
                )

                removePathIfExists(destinationPath)
                val moved = NSFileManager.defaultManager.moveItemAtPath(
                    srcPath = tempPath,
                    toPath = destinationPath,
                    error = null,
                )
                if (!moved) {
                    error(runBlocking { getString(Res.string.downloads_error_finalize_file_failed) })
                }

                val localFileUri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
                val finalSize = fileSizeOrNull(destinationPath)
                onSuccess(localFileUri, totalBytes ?: finalSize)
            } catch (_: CancellationException) {
                handle.cancelNativeTask()
            } catch (error: Throwable) {
                onFailure(error.message ?: runBlocking { getString(Res.string.download_failed) })
            }
        }

        return handle
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val destinationPath = "${downloadsDirectoryPath()}/$destinationFileName"
        DownloadSubtitleStorage(NSURL.fileURLWithPath(destinationPath).absoluteString!!).remove()
        return removePathIfExists("$destinationPath.part")
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path ->
                return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
            }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
            NSURL.fileURLWithPath(currentPath).absoluteString ?: "file://$currentPath"
        } else {
            null
        }
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(downloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }
}

private class IosDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    private var task: NSURLSessionTask? = null
    private var session: NSURLSession? = null

    fun attach(task: NSURLSessionTask, session: NSURLSession) {
        this.task = task
        this.session = session
    }

    override fun cancel() {
        cancelNativeTask()
        job.cancel()
    }

    fun cancelNativeTask() {
        task?.cancel()
        session?.invalidateAndCancel()
        task = null
        session = null
    }
}

private data class IosDownloadResult(
    val statusCode: Int,
    val contentRange: String?,
    val contentLength: Long?,
)

@OptIn(ExperimentalForeignApi::class)
private class IosDownloadDelegate(
    private val attemptedRangeRequest: Boolean,
    private val resumeFromBytes: Long,
    private val tempPath: String,
    private val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val completion = CompletableDeferred<IosDownloadResult>()
    private var result: IosDownloadResult? = null
    private var fileError: Throwable? = null
    private var outputFile: CPointer<FILE>? = null
    private var startingBytesForResponse = 0L
    private var bytesWrittenForResponse = 0L
    private var totalBytesForResponse: Long? = null
    private var lastProgressBytes = -1L
    private var lastProgressTimestampSeconds = 0.0

    suspend fun awaitCompletion(): IosDownloadResult = completion.await()

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (Long) -> Unit,
    ) {
        val httpResponse = didReceiveResponse as? NSHTTPURLResponse
        val statusCode = httpResponse?.statusCode?.toInt() ?: 200
        val nextResult = IosDownloadResult(
            statusCode = statusCode,
            contentRange = httpResponse?.valueForHTTPHeaderField("Content-Range"),
            contentLength = httpResponse
                ?.valueForHTTPHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L },
        )
        result = nextResult

        if (statusCode in 200..299) {
            val isPartialResume = attemptedRangeRequest && statusCode == 206 && resumeFromBytes > 0L
            startingBytesForResponse = if (isPartialResume) resumeFromBytes else 0L
            bytesWrittenForResponse = 0L
            totalBytesForResponse = resolveTotalBytes(
                startingBytes = startingBytesForResponse,
                isPartialResume = isPartialResume,
                contentRangeHeader = nextResult.contentRange,
                contentLength = nextResult.contentLength,
            )

            outputFile = fopen(tempPath, if (isPartialResume) "ab" else "wb") ?: run {
                fileError = IllegalStateException(runBlocking { getString(Res.string.downloads_error_open_partial_file_failed) })
                null
            }

            reportProgress(startingBytesForResponse, totalBytesForResponse)
        }

        completionHandler(1L)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        if (fileError != null) return

        val file = outputFile ?: run {
            fileError = IllegalStateException(runBlocking { getString(Res.string.downloads_error_partial_file_not_open) })
            return
        }

        val bytesToWrite = didReceiveData.length.toLong()
        val wrote = fwrite(
            didReceiveData.bytes,
            1.convert(),
            bytesToWrite.convert(),
            file,
        ).toLong()
        if (wrote != bytesToWrite) {
            fileError = IllegalStateException(runBlocking { getString(Res.string.downloads_error_write_partial_file_failed) })
            return
        }
        fflush(file)

        bytesWrittenForResponse += bytesToWrite
        reportProgress(
            downloadedBytes = startingBytesForResponse + bytesWrittenForResponse,
            totalBytes = totalBytesForResponse,
        )
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        closeOutputFile()

        if (didCompleteWithError != null) {
            completion.completeExceptionally(
                IllegalStateException(didCompleteWithError.localizedDescription),
            )
            return
        }

        val error = fileError
        if (error != null) {
            completion.completeExceptionally(error)
            return
        }

        completion.complete(result ?: task.response.toDownloadResult())
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
    }

    private fun closeOutputFile() {
        outputFile?.let { file ->
            fflush(file)
            fclose(file)
        }
        outputFile = null
    }

    private fun reportProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        val normalizedDownloadedBytes = downloadedBytes.coerceAtLeast(0L)
        val now = NSDate().timeIntervalSince1970
        val byteDelta = normalizedDownloadedBytes - lastProgressBytes
        val timeDelta = now - lastProgressTimestampSeconds
        val reachedEnd = totalBytes != null && normalizedDownloadedBytes >= totalBytes

        if (
            lastProgressBytes >= 0L &&
            !reachedEnd &&
            byteDelta < PROGRESS_MIN_BYTE_DELTA &&
            timeDelta < PROGRESS_MIN_INTERVAL_SECONDS
        ) {
            return
        }

        lastProgressBytes = normalizedDownloadedBytes
        lastProgressTimestampSeconds = now
        onProgress(normalizedDownloadedBytes, totalBytes)
    }
}

private fun NSURLResponse?.toDownloadResult(): IosDownloadResult {
    val httpResponse = this as? NSHTTPURLResponse
    return IosDownloadResult(
        statusCode = httpResponse?.statusCode?.toInt() ?: 200,
        contentRange = httpResponse?.valueForHTTPHeaderField("Content-Range"),
        contentLength = httpResponse
            ?.valueForHTTPHeaderField("Content-Length")
            ?.toLongOrNull()
            ?.takeIf { it > 0L },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun performDownloadRequest(
    request: DownloadPlatformRequest,
    rangeStart: Long?,
    resumeFromBytes: Long,
    tempPath: String,
    handle: IosDownloadsTaskHandle,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): IosDownloadResult {
    val url = NSURL(string = request.sourceUrl)
    val nativeRequest = NSMutableURLRequest(
        uRL = url,
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = DOWNLOAD_REQUEST_TIMEOUT_SECONDS,
    )
    nativeRequest.setHTTPMethod("GET")
    nativeRequest.setAllowsCellularAccess(true)
    nativeRequest.setAllowsExpensiveNetworkAccess(true)
    nativeRequest.setAllowsConstrainedNetworkAccess(true)
    request.sourceHeaders.forEach { (key, value) ->
        nativeRequest.setValue(value, forHTTPHeaderField = key)
    }
    if (rangeStart != null && rangeStart > 0L) {
        nativeRequest.setValue("bytes=$rangeStart-", forHTTPHeaderField = "Range")
    }

    val delegate = IosDownloadDelegate(
        attemptedRangeRequest = rangeStart != null && rangeStart > 0L,
        resumeFromBytes = resumeFromBytes,
        tempPath = tempPath,
        onProgress = onProgress,
    )
    val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        timeoutIntervalForRequest = DOWNLOAD_REQUEST_TIMEOUT_SECONDS
        timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
        waitsForConnectivity = true
        allowsCellularAccess = true
        allowsExpensiveNetworkAccess = true
        allowsConstrainedNetworkAccess = true
    }
    val session = NSURLSession.sessionWithConfiguration(
        configuration = configuration,
        delegate = delegate,
        delegateQueue = NSOperationQueue().apply {
            maxConcurrentOperationCount = 1
        },
    )
    val task = session.dataTaskWithRequest(nativeRequest)

    handle.attach(task, session)
    onProgress(resumeFromBytes.coerceAtLeast(0L), null)
    task.resume()

    return try {
        delegate.awaitCompletion()
    } finally {
        session.finishTasksAndInvalidate()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
