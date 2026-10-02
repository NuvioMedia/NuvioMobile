package com.nuvio.app.features.trailer

import android.net.Uri
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val PROBE_TIMEOUT_SECONDS = 5L
private const val PROBE_RACE_TIMEOUT_MS = 8_000L

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    )

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TRAILER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(buildHeaders(headers))

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(requestBuilder.build())
            .execute().use { response ->
                TrailerRequestResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    statusText = response.message,
                    url = response.request.url.toString(),
                    body = response.body?.string().orEmpty(),
                )
            }
    }

    suspend fun buildPlaybackSource(
        manifestCandidates: List<ManifestCandidate>,
        progressiveCandidates: List<StreamCandidate>,
        videoCandidates: List<StreamCandidate>,
        audioCandidates: List<StreamCandidate>,
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        // A candidate can look fine and still be unplayable: PO token gated URLs
        // answer the first range and 403 everything after it. Every candidate is
        // probed and the first one that survives wins.
        var videoOnlyFallback: String? = null
        // One rejection condemns a whole client: the gate applies to all of its
        // adaptive formats, so there is no point probing its siblings.
        val gatedClients = mutableSetOf<String>()

        suspend fun fromManifests(): TrailerPlaybackSource? {
            for (candidate in manifestCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                val url = resolveReachableUrlOrNull(candidate.selectedVariantUrl) ?: continue
                return TrailerPlaybackSource(videoUrl = url, audioUrl = null)
            }
            return null
        }

        suspend fun fromSeparateStreams(): TrailerPlaybackSource? {
            for (video in videoCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                if (video.client in gatedClients) continue
                val videoUrl = resolveReachableUrlOrNull(video.url)
                if (videoUrl == null) {
                    gatedClients += video.client
                    continue
                }
                for (audio in audioCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                    val audioUrl = resolveReachableUrlOrNull(audio.url) ?: continue
                    return TrailerPlaybackSource(videoUrl = videoUrl, audioUrl = audioUrl)
                }
                // Video is reachable but no audio track survived: remember it and
                // let the other strategies try to produce a source with sound.
                if (videoOnlyFallback == null) {
                    videoOnlyFallback = videoUrl
                }
                break
            }
            return null
        }

        suspend fun fromProgressive(): TrailerPlaybackSource? {
            for (candidate in progressiveCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                val url = resolveReachableUrlOrNull(candidate.url) ?: continue
                return TrailerPlaybackSource(videoUrl = url, audioUrl = null)
            }
            return null
        }

        val manifestHeight = manifestCandidates.firstOrNull()?.height ?: -1
        val separateHeight = videoCandidates.firstOrNull()?.height ?: -1
        val strategies: List<suspend () -> TrailerPlaybackSource?> = if (manifestHeight >= separateHeight) {
            listOf({ fromManifests() }, { fromSeparateStreams() }, { fromProgressive() })
        } else {
            listOf({ fromSeparateStreams() }, { fromManifests() }, { fromProgressive() })
        }

        for (strategy in strategies) {
            strategy()?.let { return@withContext it }
        }

        videoOnlyFallback?.let { videoUrl ->
            return@withContext TrailerPlaybackSource(videoUrl = videoUrl, audioUrl = null)
        }

        null
    }

    private suspend fun resolveReachableUrlOrNull(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val servers = uri.getQueryParameter("mn")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val host = uri.host

        val candidates = buildList {
            add(url)
            if (host != null) {
                servers.forEachIndexed { index, server ->
                    val altHost = host
                        .replaceFirst(Regex("^rr\\d+---"), "rr${index + 1}---")
                        .replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
                    if (altHost != host) {
                        add(url.replace(host, altHost))
                    }
                }
            }
        }.distinct()

        if (candidates.size == 1) {
            return candidates.first().takeIf { isUrlReachable(it) }
        }

        val result = CompletableDeferred<String>()
        val probeScope = CoroutineScope(Dispatchers.IO)
        candidates.forEach { candidate ->
            probeScope.launch {
                if (isUrlReachable(candidate)) {
                    result.complete(candidate)
                }
            }
        }

        return try {
            withTimeoutOrNull(PROBE_RACE_TIMEOUT_MS) { result.await() }
        } finally {
            probeScope.cancel()
        }
    }

    // A single head range probe is not enough: gated URLs answer the first chunk
    // and 403 the rest, so the tail is probed too whenever the length is known.
    private fun isUrlReachable(url: String): Boolean = runCatching {
        val sourceSize = Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        val ranges = sourceSize?.let { size ->
            listOf(
                0L to 65_535L.coerceAtMost(size - 1L),
                (size - 65_536L).coerceAtLeast(0L) to size - 1L,
            ).distinct()
        } ?: listOf(0L to 0L)

        ranges.all { (rangeStart, rangeEnd) ->
            val request = Request.Builder()
                .url(url)
                .headers(buildHeaders(defaultHeaders))
                .header("Range", "bytes=$rangeStart-$rangeEnd")
                .get()
                .build()

            probeClient.newCall(request).execute().use { response ->
                response.code == 206 ||
                    (sourceSize == null && rangeStart == 0L && response.code in 200..299)
            }
        }
    }.getOrDefault(false)

    private fun buildHeaders(source: Map<String, String>): Headers {
        val headers = Headers.Builder()
        source.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                headers.add(name, value)
            }
        }
        if (source.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent", defaultHeaders.getValue("user-agent"))
        }
        return headers.build()
    }
}