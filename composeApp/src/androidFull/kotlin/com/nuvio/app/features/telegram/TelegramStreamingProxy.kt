package com.nuvio.app.features.telegram

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/**
 * Local loopback HTTP bridge for TDLib files.
 *
 * It deliberately serves bounded byte ranges instead of exposing TDLib's temporary
 * file path. This lets both Media3 and libmpv seek without eagerly downloading a movie.
 */
internal class TelegramStreamingProxy(
    context: Context,
    private val client: TelegramTdClient,
) {
    companion object {
        private const val TAG = "NuvioTelegramProxy"
        private const val CHUNK_BYTES = 1024 * 1024
        private const val DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val DOWNLOAD_PRIORITY = 32
    }

    private data class ActiveRequests(
        var count: Int = 0,
    )

    private val appContext = context.applicationContext
    private val fileRegistry = File(appContext.filesDir, "telegram/proxy_file_ids.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requestStates = mutableMapOf<Int, ActiveRequests>()
    private val registeredFileIds = mutableSetOf<Int>().also { ids ->
        runCatching {
            if (fileRegistry.exists()) {
                fileRegistry.forEachLine { line ->
                    line.trim().toIntOrNull()?.takeIf { it > 0 }?.let(ids::add)
                }
            }
        }.onFailure { error -> Log.w(TAG, "Could not restore Telegram proxy file registry", error) }
    }
    private val mimeTypes = mutableMapOf<Int, String>()

    @Volatile private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    @Volatile private var port: Int = 0
    @Volatile private var accessToken: String = UUID.randomUUID().toString().replace("-", "")

    @Synchronized
    fun start() {
        if (server != null) return
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/file/{fileId}") {
                    val fileId = call.parameters["fileId"]?.toIntOrNull()
                    if (fileId == null || fileId <= 0) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (call.request.queryParameters["key"] != accessToken || !client.isAuthenticated()) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    beginRequest(fileId)
                    var responseStarted = false
                    try {
                        val file = client.sendRequest(TdApi.GetFile(fileId), REQUEST_TIMEOUT_MS) as? TdApi.File
                        val totalSize = file?.size?.takeIf { it > 0L }
                            ?: file?.expectedSize?.takeIf { it > 0L }
                        if (totalSize == null || totalSize <= 0L) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        val byteRange = TelegramByteRangeParser.parse(rangeHeader, totalSize)
                        if (byteRange == null) {
                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
                            call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                            call.respond(HttpStatusCode(416, "Range Not Satisfiable"))
                            return@get
                        }

                        val isPartial = rangeHeader != null
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentLength, byteRange.length.toString())
                        if (isPartial) {
                            call.response.header(
                                HttpHeaders.ContentRange,
                                "bytes ${byteRange.start}-${byteRange.end}/$totalSize",
                            )
                        }

                        val mime = mimeTypes[fileId]?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                            ?: ContentType.Application.OctetStream
                        responseStarted = true
                        call.respondBytesWriter(
                            contentType = mime,
                            status = if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                        ) {
                            var offset = byteRange.start
                            while (offset <= byteRange.end) {
                                val requested = minOf(CHUNK_BYTES.toLong(), byteRange.end - offset + 1L).toInt()
                                val bytes = downloadChunk(fileId, offset, requested, totalSize)
                                    ?: throw IllegalStateException("Telegram did not provide bytes at offset $offset")
                                if (bytes.isEmpty()) {
                                    throw IllegalStateException("Telegram returned an empty media chunk at offset $offset")
                                }
                                writeFully(bytes)
                                offset += bytes.size
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Telegram range request failed for file $fileId", error)
                        if (!responseStarted) {
                            call.respond(HttpStatusCode.BadGateway)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            endRequest(fileId)
                        }
                    }
                }
            }
        }.start(wait = false)
        Log.i(TAG, "Telegram loopback server listening at 127.0.0.1:$port")
    }

    fun streamUrl(fileId: Int, mimeType: String?): String {
        start()
        if (!mimeType.isNullOrBlank()) synchronized(mimeTypes) { mimeTypes[fileId] = mimeType }
        synchronized(registeredFileIds) {
            registeredFileIds += fileId
            persistFileRegistryLocked()
        }
        return "http://127.0.0.1:$port/file/$fileId?key=$accessToken"
    }

    suspend fun clearCache() {
        val activeIds = mutex.withLock { requestStates.filterValues { it.count > 0 }.keys.toSet() }
        val deletable = synchronized(registeredFileIds) {
            registeredFileIds.filterNot { it in activeIds }.toList()
        }
        deletable.forEach { fileId ->
            deleteFile(fileId)
            synchronized(registeredFileIds) {
                registeredFileIds.remove(fileId)
                persistFileRegistryLocked()
            }
            synchronized(mimeTypes) { mimeTypes.remove(fileId) }
        }
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        accessToken = UUID.randomUUID().toString().replace("-", "")
        current.stop(250, 1_000)
    }

    suspend fun forgetRegisteredFiles() {
        mutex.withLock { requestStates.clear() }
        synchronized(registeredFileIds) {
            registeredFileIds.clear()
            persistFileRegistryLocked()
        }
        synchronized(mimeTypes) { mimeTypes.clear() }
    }

    private suspend fun beginRequest(fileId: Int) {
        mutex.withLock {
            val state = requestStates.getOrPut(fileId) { ActiveRequests() }
            state.count += 1
        }
    }

    private suspend fun endRequest(fileId: Int) {
        mutex.withLock {
            val state = requestStates[fileId] ?: return@withLock
            state.count = (state.count - 1).coerceAtLeast(0)
            // Keep the TDLib file alive after the last range request: players may pause for
            // several minutes before issuing the next seek/range request. Cache eviction is
            // explicit in Settings or performed on logout, never based on request inactivity.
            if (state.count == 0) requestStates.remove(fileId)
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        offset: Long,
        length: Int,
        totalSize: Long,
    ): ByteArray? = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
        val usableSpace = runCatching { appContext.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val prefetch = when {
            usableSpace < 500L * 1024L * 1024L -> 2L * 1024L * 1024L
            else -> (length * 8L).coerceIn(4L * 1024L * 1024L, 16L * 1024L * 1024L)
        }.coerceAtMost(totalSize - offset)

        client.sendRequest(
            TdApi.DownloadFile().also { request ->
                request.fileId = fileId
                request.priority = DOWNLOAD_PRIORITY
                request.offset = offset
                request.limit = prefetch.coerceAtLeast(length.toLong())
                request.synchronous = false
            },
            REQUEST_TIMEOUT_MS,
        )

        var attempts = 0
        while (attempts < 450) {
            try {
                val data = client.sendRequest(
                    TdApi.ReadFilePart(fileId, offset, length.toLong()),
                    REQUEST_TIMEOUT_MS,
                ) as? TdApi.Data
                if (data?.data?.isNotEmpty() == true) {
                    if (data.data.size >= length || offset + data.data.size >= totalSize) {
                        return@withTimeoutOrNull data.data
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TelegramTdApiException) {
                // The requested range may still be downloading; retry the non-blocking read.
            }

            val file = client.sendRequest(TdApi.GetFile(fileId), REQUEST_TIMEOUT_MS) as? TdApi.File
            if (file?.local?.isDownloadingCompleted == true && offset >= file.size) return@withTimeoutOrNull null
            delay(100L)
            attempts += 1
        }
        null
    }

    private fun persistFileRegistryLocked() {
        runCatching {
            fileRegistry.parentFile?.mkdirs()
            fileRegistry.writeText(registeredFileIds.sorted().joinToString("\n"))
        }.onFailure { error ->
            Log.w(TAG, "Could not persist Telegram proxy file registry", error)
        }
    }

    private suspend fun deleteFile(fileId: Int) {
        runCatching {
            client.sendRequest(
                TdApi.CancelDownloadFile().also {
                    it.fileId = fileId
                    it.onlyIfPending = false
                },
                REQUEST_TIMEOUT_MS,
            )
        }
        runCatching { client.sendRequest(TdApi.DeleteFile().also { it.fileId = fileId }, REQUEST_TIMEOUT_MS) }
    }
}
