package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Reads the chapters of a Matroska/WebM file over HTTP range requests, for ExoPlayer, whose
 * Matroska extractor skips them (libmpv lists them itself).
 *
 * It reads the start of the file, follows the SeekHead to the Chapters element and reads only
 * that: two or three small requests. Best-effort: anything unexpected gives no chapters.
 */
internal object MatroskaChapterLoader {
    private const val TOTAL_TIMEOUT_MS = 8_000L
    private const val INITIAL_PROBE_BYTES = 256 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val MAX_SEEK_HEAD_BYTES = 256 * 1024
    private const val MAX_CHAPTERS_BYTES = 1024 * 1024

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun load(url: String, headers: Map<String, String>): List<PlayerChapter> {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }
        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching { loadChapters(url, headers) }.getOrNull()
            }
        }.orEmpty()
    }

    private suspend fun loadChapters(url: String, headers: Map<String, String>): List<PlayerChapter> {
        val initial = fetchRange(url, headers, 0L, INITIAL_PROBE_BYTES) ?: return emptyList()
        val layout = MatroskaChapterParser.topLevelLayout(initial) ?: return emptyList()

        val chaptersPosition = layout.chaptersPosition ?: layout.seekHeadPosition?.let { seekHeadPosition ->
            val seekHead = elementAt(url, headers, initial, seekHeadPosition, MAX_SEEK_HEAD_BYTES)
                ?: return@let null
            MatroskaChapterParser.seekHeadPositions(seekHead)[MatroskaChapterParser.ID_CHAPTERS]
                ?.let { layout.segmentDataStart + it }
        } ?: return emptyList()

        val chapters = elementAt(url, headers, initial, chaptersPosition, MAX_CHAPTERS_BYTES)
            ?: return emptyList()
        return PlayerChapters.normalize(MatroskaChapterParser.parseChapters(chapters))
    }

    /** The whole element at [position], from [initial] when it is already there. */
    private suspend fun elementAt(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray,
        position: Long,
        maxBytes: Int,
    ): ByteArray? {
        val header = MatroskaChapterParser.readElement(initial, position)
            ?: fetchRange(url, headers, position, HEADER_PROBE_BYTES)
                ?.let { MatroskaChapterParser.readElement(it, 0L) }
            ?: return null
        if (header.unknownSize) return null
        val totalSize = header.headerSize + header.dataSize
        if (totalSize <= 0L || totalSize > maxBytes) return null
        val end = position + totalSize
        if (end <= initial.size) return initial.copyOfRange(position.toInt(), end.toInt())
        return fetchRange(url, headers, position, totalSize.toInt())?.takeIf { it.size.toLong() == totalSize }
    }

    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        length: Int,
    ): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply {
                headers.forEach { (name, value) ->
                    if (!name.equals("Range", ignoreCase = true) && !name.equals("Accept-Encoding", ignoreCase = true)) {
                        header(name, value)
                    }
                }
            }
            .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = runCatching {
                        response.use {
                            // A server ignoring Range answers 200 with the whole file: only usable from 0.
                            if (it.code != 206 && !(it.code == 200 && start == 0L)) return@use null
                            it.body?.byteStream()?.use { input -> input.readUpTo(length) }
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
    }

    private fun InputStream.readUpTo(length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        if (offset == 0) return null
        return if (offset == length) buffer else buffer.copyOf(offset)
    }
}
