package com.nuvio.app.features.trailer

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem

private const val PROBE_TIMEOUT_MS = 5_000L
private const val PROBE_RACE_TIMEOUT_MS = 8_000L

internal object TrailerExtractionPlatform {
    val defaultHeaders: Map<String, String> = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
    )

    private val httpClient = HttpClient(Darwin) {
        install(HttpTimeout)
        followRedirects = true
        expectSuccess = false
    }

    suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
    ): TrailerRequestResponse = withContext(Dispatchers.Default) {
        val response = httpClient.request(url) {
            this.method = when (method.uppercase()) {
                "POST" -> HttpMethod.Post
                "PUT" -> HttpMethod.Put
                "DELETE" -> HttpMethod.Delete
                else -> HttpMethod.Get
            }
            headers.forEach { (name, value) ->
                header(name, value)
            }
            if (body != null) {
                setBody(body)
            }
            timeout {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "" }
        TrailerRequestResponse(
            ok = response.status.isSuccess(),
            status = response.status.value,
            statusText = response.status.description,
            url = response.request.url.toString(),
            body = bodyText,
        )
    }

    suspend fun buildPlaybackSource(
        manifestCandidates: List<ManifestCandidate>,
        progressiveCandidates: List<StreamCandidate>,
        videoCandidates: List<StreamCandidate>,
        audioCandidates: List<StreamCandidate>,
    ): TrailerPlaybackSource? = withContext(Dispatchers.Default) {
        // A candidate can look fine and still be unplayable: PO token gated URLs
        // answer the first range and 403 everything after it. Every candidate is
        // probed and the first one that survives wins.
        var videoOnlyFallback: String? = null
        // One rejection condemns a whole client: the gate applies to all of its
        // adaptive formats, so there is no point probing its siblings.
        val gatedClients = mutableSetOf<String>()

        suspend fun fromManifests(): TrailerPlaybackSource? {
            for (candidate in manifestCandidates.take(MAX_CANDIDATE_ATTEMPTS)) {
                val url = resolveReachableUrlOrNull(candidate.manifestUrl) ?: continue
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

        val servers = getQueryParameter(url, "mn")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val host = getHost(url)

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

        return coroutineScope {
            val probes = candidates.map { candidate ->
                async { if (isUrlReachable(candidate)) candidate else null }
            }
            withTimeoutOrNull(PROBE_RACE_TIMEOUT_MS) {
                probes.awaitAll().firstOrNull { !it.isNullOrBlank() }
            }
        }
    }

    // A single head range probe is not enough: gated URLs answer the first chunk
    // and 403 the rest, so the tail is probed too whenever the length is known.
    private suspend fun isUrlReachable(url: String): Boolean {
        val sourceSize = getQueryParameter(url, "clen")?.toLongOrNull()?.takeIf { it > 0L }
        val ranges = sourceSize?.let { size ->
            listOf(
                0L to 65_535L.coerceAtMost(size - 1L),
                (size - 65_536L).coerceAtLeast(0L) to size - 1L,
            ).distinct()
        } ?: listOf(0L to 0L)

        return ranges.all { (rangeStart, rangeEnd) ->
            val response = runCatching {
                performRequest(
                    url = url,
                    method = "GET",
                    headers = mapOf(
                        "range" to "bytes=$rangeStart-$rangeEnd",
                        "user-agent" to defaultHeaders.getValue("user-agent"),
                    ),
                    body = null,
                    timeoutMillis = PROBE_TIMEOUT_MS,
                )
            }.getOrNull() ?: return false

            response.status == 206 ||
                (sourceSize == null && rangeStart == 0L && response.status in 200..299)
        }
    }

    private fun getHost(url: String): String? {
        val components = NSURLComponents(string = url)
        return components.host
    }

    private fun getQueryParameter(url: String, name: String): String? {
        val components = NSURLComponents(string = url)
        return queryItems(components).firstOrNull { it.name == name }?.value
    }

    private fun queryItems(components: NSURLComponents): List<NSURLQueryItem> {
        return components.queryItems?.mapNotNull { it as? NSURLQueryItem } ?: emptyList()
    }
}