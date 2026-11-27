package com.nuvio.app.features.player

import android.util.Log
import androidx.media3.common.MimeTypes
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * that: two or three small requests. Transient failures are retried; anything else gives no chapters.
 */
internal object MatroskaChapterLoader {
    private const val TAG = "MatroskaChapterLoader"
    private const val ATTEMPT_TIMEOUT_MS = 12_000L
    private const val INITIAL_PROBE_BYTES = 256 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val MAX_SEEK_HEAD_BYTES = 256 * 1024
    private const val MAX_CHAPTERS_BYTES = 1024 * 1024

    /**
     * The read starts once the source is ready, while playback opens its own connections, so a host
     * that limits connections can refuse it once. Waits before the second and third attempt.
     */
    private val DEFAULT_RETRY_DELAYS_MS = listOf(1_500L, 3_000L)

    internal sealed interface RangeFetchResult {
        class Success(val bytes: ByteArray, val resolvedUrl: String) : RangeFetchResult
        data class PermanentFailure(val reason: String) : RangeFetchResult
        data class TransientFailure(val reason: String) : RangeFetchResult
    }

    private sealed interface ChapterLoadOutcome {
        data class Loaded(val chapters: List<PlayerChapter>) : ChapterLoadOutcome
        data object NoChapters : ChapterLoadOutcome
        data class PermanentFailure(val reason: String) : ChapterLoadOutcome
        data class TransientFailure(val reason: String) : ChapterLoadOutcome
    }

    private sealed interface ElementFetchResult {
        class Found(val bytes: ByteArray) : ElementFetchResult
        data object NotFound : ElementFetchResult
        data class PermanentFailure(val reason: String) : ElementFetchResult
        data class TransientFailure(val reason: String) : ElementFetchResult
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun load(url: String, headers: Map<String, String>): List<PlayerChapter> {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            loadWithFetcher(url, headers) { requestUrl, requestHeaders, start, length ->
                fetchRange(requestUrl, requestHeaders, start, length)
            }
        }
    }

    /**
     * Reads the chapters through [fetchRange], reading again after a transient failure (429, 408,
     * 5xx, I/O error, timeout). A file without chapters and a permanent failure (another HTTP error,
     * a parse failure) end it at once.
     */
    internal suspend fun loadWithFetcher(
        url: String,
        headers: Map<String, String>,
        retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
        attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult,
    ): List<PlayerChapter> {
        val totalAttempts = retryDelaysMs.size + 1
        for (attempt in 0 until totalAttempts) {
            val outcome = try {
                withTimeoutOrNull(attemptTimeoutMs) {
                    loadChapters(url, headers, fetchRange)
                } ?: ChapterLoadOutcome.TransientFailure("timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ChapterLoadOutcome.TransientFailure("io:${e.javaClass.simpleName}")
            } catch (e: Exception) {
                // Range fetches report their failures as results, so what is thrown here comes from
                // parsing the file or building the request: reading again would fail the same way.
                ChapterLoadOutcome.PermanentFailure("exception:${e.javaClass.simpleName}")
            }

            when (outcome) {
                is ChapterLoadOutcome.Loaded -> return outcome.chapters
                ChapterLoadOutcome.NoChapters -> return emptyList()
                is ChapterLoadOutcome.PermanentFailure -> {
                    Log.w(TAG, "Chapter read stopped (${outcome.reason})")
                    return emptyList()
                }
                is ChapterLoadOutcome.TransientFailure -> {
                    Log.w(TAG, "Chapter read attempt ${attempt + 1}/$totalAttempts failed (${outcome.reason})")
                    if (attempt >= retryDelaysMs.size) return emptyList()
                    delay(retryDelaysMs[attempt])
                }
            }
        }
        return emptyList()
    }

    private suspend fun loadChapters(
        url: String,
        headers: Map<String, String>,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult,
    ): ChapterLoadOutcome {
        val initialResult = when (val fetched = fetchRange(url, headers, 0L, INITIAL_PROBE_BYTES)) {
            is RangeFetchResult.Success -> fetched
            is RangeFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(fetched.reason)
            is RangeFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(fetched.reason)
        }
        val initial = initialResult.bytes
        val layout = MatroskaChapterParser.topLevelLayout(initial) ?: return ChapterLoadOutcome.NoChapters
        val followUpUrl = followUpRangeUrl(url, initialResult.resolvedUrl, headers)

        val chaptersPosition = layout.chaptersPosition ?: run {
            val seekHeadPosition = layout.seekHeadPosition ?: return ChapterLoadOutcome.NoChapters
            val seekHead = when (
                val element = elementAt(followUpUrl, headers, initial, seekHeadPosition, MAX_SEEK_HEAD_BYTES, fetchRange)
            ) {
                is ElementFetchResult.Found -> element.bytes
                ElementFetchResult.NotFound -> return ChapterLoadOutcome.NoChapters
                is ElementFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(element.reason)
                is ElementFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(element.reason)
            }
            val relativeChaptersPosition =
                MatroskaChapterParser.seekHeadPositions(seekHead)[MatroskaChapterParser.ID_CHAPTERS]
                    ?: return ChapterLoadOutcome.NoChapters
            layout.segmentDataStart + relativeChaptersPosition
        }

        val chaptersBytes = when (
            val element = elementAt(followUpUrl, headers, initial, chaptersPosition, MAX_CHAPTERS_BYTES, fetchRange)
        ) {
            is ElementFetchResult.Found -> element.bytes
            ElementFetchResult.NotFound -> return ChapterLoadOutcome.NoChapters
            is ElementFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(element.reason)
            is ElementFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(element.reason)
        }
        val parsed = PlayerChapters.normalize(MatroskaChapterParser.parseChapters(chaptersBytes))
        return if (parsed.isEmpty()) ChapterLoadOutcome.NoChapters else ChapterLoadOutcome.Loaded(parsed)
    }

    /** The whole element at [position], from [initial] when it is already there. */
    private suspend fun elementAt(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray,
        position: Long,
        maxBytes: Int,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult,
    ): ElementFetchResult {
        val header = MatroskaChapterParser.readElement(initial, position) ?: run {
            val probe = when (val fetched = fetchRange(url, headers, position, HEADER_PROBE_BYTES)) {
                is RangeFetchResult.Success -> fetched.bytes
                is RangeFetchResult.PermanentFailure -> return ElementFetchResult.PermanentFailure(fetched.reason)
                is RangeFetchResult.TransientFailure -> return ElementFetchResult.TransientFailure(fetched.reason)
            }
            MatroskaChapterParser.readElement(probe, 0L) ?: return ElementFetchResult.NotFound
        }
        if (header.unknownSize) return ElementFetchResult.NotFound
        val totalSize = header.headerSize + header.dataSize
        if (totalSize <= 0L || totalSize > maxBytes) return ElementFetchResult.NotFound
        val end = position + totalSize
        if (end <= initial.size) {
            return ElementFetchResult.Found(initial.copyOfRange(position.toInt(), end.toInt()))
        }
        return when (val fetched = fetchRange(url, headers, position, totalSize.toInt())) {
            is RangeFetchResult.Success ->
                if (fetched.bytes.size.toLong() == totalSize) {
                    ElementFetchResult.Found(fetched.bytes)
                } else {
                    ElementFetchResult.TransientFailure("truncated_element")
                }
            is RangeFetchResult.PermanentFailure -> ElementFetchResult.PermanentFailure(fetched.reason)
            is RangeFetchResult.TransientFailure -> ElementFetchResult.TransientFailure(fetched.reason)
        }
    }

    /**
     * Where the follow-up range requests go: the redirect target of the first one, so they do not
     * go through the redirect again, unless that would send Authorization or Cookie headers to
     * another host.
     */
    internal fun followUpRangeUrl(
        originalUrl: String,
        resolvedUrl: String,
        headers: Map<String, String>,
    ): String {
        if (resolvedUrl.isBlank() || resolvedUrl == originalUrl) return originalUrl
        val originalHost = originalUrl.toHttpUrlOrNull()?.host ?: return originalUrl
        val resolvedHost = resolvedUrl.toHttpUrlOrNull()?.host ?: return originalUrl
        if (originalHost.equals(resolvedHost, ignoreCase = true)) return resolvedUrl
        val sendsCredentials = headers.keys.any { key ->
            key.equals("Authorization", ignoreCase = true) || key.equals("Cookie", ignoreCase = true)
        }
        return if (sendsCredentials) originalUrl else resolvedUrl
    }

    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        length: Int,
    ): RangeFetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply {
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", PlayerPlaybackNetworking.DEFAULT_USER_AGENT)
                }
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
                    if (continuation.isActive) {
                        continuation.resume(RangeFetchResult.TransientFailure("io:${e.javaClass.simpleName}"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { res ->
                            when {
                                // A server ignoring Range answers 200 with the whole file: only usable from 0.
                                res.code == 206 || (res.code == 200 && start == 0L) -> {
                                    val bytes = res.body?.byteStream()?.use { input -> input.readUpTo(length) }
                                    if (bytes == null) {
                                        RangeFetchResult.TransientFailure("empty_body")
                                    } else {
                                        RangeFetchResult.Success(bytes, res.request.url.toString())
                                    }
                                }
                                res.code == 429 || res.code == 408 || res.code in 500..599 ->
                                    RangeFetchResult.TransientFailure("http_${res.code}")
                                else -> RangeFetchResult.PermanentFailure("http_${res.code}")
                            }
                        }
                    } catch (e: IOException) {
                        RangeFetchResult.TransientFailure("io:${e.javaClass.simpleName}")
                    } catch (e: Exception) {
                        // Anything else must still resume the caller, not leave it to the attempt timeout.
                        RangeFetchResult.PermanentFailure("exception:${e.javaClass.simpleName}")
                    }
                    if (continuation.isActive) continuation.resume(result)
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

/**
 * Whether ExoPlayer should read the file's chapters: for the chapter UI or for skip intro, and only
 * for Matroska/WebM (or before the container is known), as in NuvioTV.
 */
internal fun shouldReadExoChapters(
    chaptersEnabled: Boolean,
    skipIntroEnabled: Boolean,
    containerMimeType: String?,
): Boolean {
    if (!chaptersEnabled && !skipIntroEnabled) return false
    return containerMimeType == null ||
        containerMimeType == MimeTypes.VIDEO_MATROSKA ||
        containerMimeType == MimeTypes.VIDEO_WEBM
}
