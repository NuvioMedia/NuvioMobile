package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val TRAILER_EXTRACTOR_TAG = "InAppYouTubeExtractor"
internal const val TRAILER_REQUEST_TIMEOUT_MS = 20_000L

private const val EXTRACTOR_TIMEOUT_MS = 30_000L

// YouTube gates the ANDROID and IOS InnerTube clients behind a PO token: their
// googlevideo URLs serve the first chunk and then reject every further range
// with 403, which surfaces as mid-open playback failures. VISIONOS URLs (and
// its HLS manifest) are still ungated, so only those are used for playback.
// The other clients stay in CLIENTS as a last-resort fallback in case VISIONOS
// gets gated too - unreachable candidates are then dropped by the probes.
private val PLAYBACK_CLIENT_ALLOWLIST = setOf("visionos", "android_vr")

// The InnerTube key is a public constant shipped in every YouTube page and the
// player endpoint even accepts requests without it. Scraping it from the watch
// page (over a megabyte per video) is what breaks first when the device gets
// rate limited, so it is only used as a fallback.
private const val DEFAULT_INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

// Probing is not free, so only the top few candidates of each kind are tried.
internal const val MAX_CANDIDATE_ATTEMPTS = 3

// The watch page is over a megabyte and only exists to scrape the InnerTube key
// and visitor id, which are not per-video. Re-fetching it for every trailer is
// what gets the device rate limited, so it is cached process-wide.
private val WatchConfigTtl = 3.hours

// A 429 is device wide: every further request makes it worse, so extraction
// stops entirely until the cooldown expires. Per client bot checks are handled
// separately and only skip the client that was challenged.
private val RateLimitCooldown = 15.minutes

private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

private data class YouTubeClient(
    val key: String,
    val id: String,
    val version: String,
    val userAgent: String,
    val context: JsonObject,
    val priority: Int,
)

// A verdict YouTube will keep repeating no matter how often we ask: geo blocks,
// removed videos, private videos. Retrying only burns request budget.
private class UnplayableException(val playabilityStatus: String, val playabilityReason: String) :
    IllegalStateException("unplayable ($playabilityStatus): $playabilityReason")

private class BotCheckException(clientKey: String, playabilityStatus: String) :
    IllegalStateException("bot check for $clientKey ($playabilityStatus)")

private data class WatchConfig(
    val apiKey: String?,
    val visitorData: String?,
)

internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val height: Int,
    val fps: Int,
    val ext: String,
    // Only meaningful for audio candidates: false means this format is an
    // alternate-language dub track, not the video's original/default audio.
    // Always true for video/progressive candidates, so it never affects them.
    val isDefaultAudioTrack: Boolean = true,
)

private data class ManifestBestVariant(
    val url: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
)

internal data class ManifestCandidate(
    val client: String,
    val priority: Int,
    val manifestUrl: String,
    val selectedVariantUrl: String,
    val height: Int,
    val bandwidth: Long,
)

internal data class TrailerRequestResponse(
    val ok: Boolean,
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
)

private val JSON = Json { ignoreUnknownKeys = true }

private val CLIENTS = listOf(
    YouTubeClient(
        key = "visionos",
        id = "101",
        version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        context = jsonObjectOf(
            "clientName" to "VISIONOS",
            "clientVersion" to "1.02",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 0,
    ),
    // Oculus client: no PO token required, so its URLs stay playable. It gets
    // bot challenged sooner than ANDROID/IOS, hence second rather than first.
    YouTubeClient(
        key = "android_vr",
        id = "28",
        version = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
            "eureka-user Build/SQ3A.220605.009.A1) gzip",
        context = jsonObjectOf(
            "clientName" to "ANDROID_VR",
            "clientVersion" to "1.65.10",
            "deviceMake" to "Oculus",
            "deviceModel" to "Quest 3",
            "osName" to "Android",
            "osVersion" to "12L",
            "androidSdkVersion" to 32,
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 1,
    ),
    // ANDROID and IOS survive bot checks the longest but their adaptive formats
    // are PO token gated; only their progressive format stays playable.
    YouTubeClient(
        key = "android",
        id = "3",
        version = "21.26.364",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        context = jsonObjectOf(
            "clientName" to "ANDROID",
            "clientVersion" to "21.26.364",
            "osName" to "Android",
            "osVersion" to "11",
            "platform" to "MOBILE",
            "androidSdkVersion" to 30,
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 2,
    ),
    YouTubeClient(
        key = "ios",
        id = "5",
        version = "21.26.4",
        userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        context = jsonObjectOf(
            "clientName" to "IOS",
            "clientVersion" to "21.26.4",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82",
            "platform" to "MOBILE",
            "hl" to "en",
            "gl" to "US",
        ),
        priority = 3,
    ),
)

// Shared across every extractor instance: the InnerTube key and visitor id are
// account-agnostic and stay valid for hours.
private object WatchConfigCache {
    private val mutex = Mutex()
    private var cached: WatchConfig? = null
    private var cachedAt: TimeMark? = null

    suspend fun get(fetch: suspend () -> WatchConfig): WatchConfig = mutex.withLock {
        val current = cached
        val age = cachedAt
        if (current != null && age != null && age.elapsedNow() < WatchConfigTtl) {
            return@withLock current
        }
        val fresh = fetch()
        if (fresh.apiKey != null) {
            cached = fresh
            cachedAt = TimeSource.Monotonic.markNow()
        }
        fresh
    }

    suspend fun invalidate() = mutex.withLock {
        cached = null
        cachedAt = null
    }
}

// Global backoff so a rate limited device stops issuing requests instead of
// retrying per trailer, per hover and per hero.
private object RateLimitGate {
    private val mutex = Mutex()
    private var trippedAt: TimeMark? = null

    suspend fun remaining(): Duration? = mutex.withLock {
        val tripped = trippedAt ?: return@withLock null
        val elapsed = tripped.elapsedNow()
        if (elapsed >= RateLimitCooldown) {
            trippedAt = null
            null
        } else {
            RateLimitCooldown - elapsed
        }
    }

    suspend fun trip() = mutex.withLock {
        trippedAt = TimeSource.Monotonic.markNow()
    }
}

class InAppYouTubeExtractor {
    private val log = Logger.withTag(TRAILER_EXTRACTOR_TAG)

    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerPlaybackSource? = withContext(Dispatchers.Default) {
        if (youtubeUrl.isBlank()) return@withContext null

        runCatching {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                extractPlaybackSourceInternal(youtubeUrl)
            }
        }.onFailure {
            log.w { "Trailer extractor failed for $youtubeUrl: ${it.message}" }
        }.getOrNull()
    }

    private suspend fun extractPlaybackSourceInternal(youtubeUrl: String): TrailerPlaybackSource? {
        val videoId = extractVideoId(youtubeUrl) ?: return null

        RateLimitGate.remaining()?.let { remaining ->
            return null
        }

        // The watch page is deliberately NOT fetched up front: it is the first
        // thing YouTube rate limits, and the player endpoint works fine with the
        // public key. It is only consulted if every client came back empty.
        var watchConfig: WatchConfig? = null

        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val manifestUrls = mutableListOf<Triple<String, Int, String>>()
        var definitiveVerdict: UnplayableException? = null

        suspend fun queryClients() {
            for (client in CLIENTS) {
                // The allowlisted client is tried first and, when it yields anything
                // playable, the remaining clients are skipped: their URLs are gated
                // anyway and every extra call brings the device closer to a 429.
                if (client.key !in PLAYBACK_CLIENT_ALLOWLIST &&
                    (manifestUrls.isNotEmpty() || progressive.isNotEmpty() || adaptiveVideo.isNotEmpty())
                ) {
                    continue
                }
                runCatching {
                    val playerResponse = fetchPlayerResponse(
                        apiKey = watchConfig?.apiKey ?: DEFAULT_INNERTUBE_API_KEY,
                        videoId = videoId,
                        client = client,
                        visitorData = watchConfig?.visitorData,
                    )

                    val streamingData = playerResponse.objectValue("streamingData")
                        ?: throw IllegalStateException("missing streamingData")
                    val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                    if (!hlsManifestUrl.isNullOrBlank()) {
                        manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                    }

                    for (format in streamingData.listObjectValue("formats")) {
                        val url = format.stringValue("url") ?: continue
                        val mimeType = format.stringValue("mimeType").orEmpty()
                        if (!mimeType.contains("video/") && mimeType.isNotBlank()) continue

                        val height = (
                            format.numberValue("height")
                                ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                                ?: 0.0
                            ).toInt()
                        val fps = (format.numberValue("fps") ?: 0.0).toInt()
                        val bitrate = format.numberValue("bitrate")
                            ?: format.numberValue("averageBitrate")
                            ?: 0.0

                        progressive += StreamCandidate(
                            client = client.key,
                            priority = client.priority,
                            url = url,
                            score = videoScore(height, fps, bitrate),
                            hasN = hasNParam(url),
                            height = height,
                            fps = fps,
                            ext = if (mimeType.contains("webm")) "webm" else "mp4",
                        )
                    }

                    for (format in streamingData.listObjectValue("adaptiveFormats")) {
                        val url = format.stringValue("url") ?: continue
                        val mimeType = format.stringValue("mimeType").orEmpty()
                        val hasVideo = mimeType.contains("video/")
                        val hasAudio = mimeType.contains("audio/") || mimeType.startsWith("audio/")

                        if (hasVideo) {
                            val height = (
                                format.numberValue("height")
                                    ?: parseQualityLabel(format.stringValue("qualityLabel"))?.toDouble()
                                    ?: 0.0
                                ).toInt()
                            val fps = (format.numberValue("fps") ?: 0.0).toInt()
                            val bitrate = format.numberValue("bitrate")
                                ?: format.numberValue("averageBitrate")
                                ?: 0.0

                            adaptiveVideo += StreamCandidate(
                                client = client.key,
                                priority = client.priority,
                                url = url,
                                score = videoScore(height, fps, bitrate),
                                hasN = hasNParam(url),
                                height = height,
                                fps = fps,
                                ext = if (mimeType.contains("webm")) "webm" else "mp4",
                            )
                        } else if (hasAudio) {
                            val bitrate = format.numberValue("bitrate")
                                ?: format.numberValue("averageBitrate")
                                ?: 0.0
                            val audioSampleRate = format.numberValue("audioSampleRate") ?: 0.0
                            // Multi-language uploads (common for major-studio trailers)
                            // expose each dub as a separate adaptiveFormats entry with an
                            // audioTrack.audioIsDefault flag. Formats with no audioTrack
                            // are the only audio for that video, so treat them as default.
                            val isDefaultAudioTrack = format.objectValue("audioTrack")
                                ?.booleanValue("audioIsDefault") ?: true

                            adaptiveAudio += StreamCandidate(
                                client = client.key,
                                priority = client.priority,
                                url = url,
                                score = audioScore(bitrate, audioSampleRate),
                                hasN = hasNParam(url),
                                height = 0,
                                fps = 0,
                                ext = if (mimeType.contains("webm")) "webm" else "m4a",
                                isDefaultAudioTrack = isDefaultAudioTrack,
                            )
                        }
                    }
                }.onFailure {
                    if (it is UnplayableException) {
                        definitiveVerdict = it
                    }
                }
                }
        }

        queryClients()

        // Everything came back empty: the constant key or a stale visitor id may
        // be the reason, so the watch page is tried once as a last resort.
        definitiveVerdict?.let { verdict ->
            return null
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            val fetched = runCatching { fetchWatchConfig(videoId) }.getOrNull()
            if (fetched?.apiKey != null) {
                watchConfig = fetched
                queryClients()
            }
        }

        if (manifestUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
        }

        val playbackManifestUrls = manifestUrls.preferPlaybackClients { it.first }
        val playbackProgressive = progressive.preferPlaybackClients { it.client }
        val playbackVideo = adaptiveVideo.preferPlaybackClients { it.client }
        val playbackAudio = adaptiveAudio.preferPlaybackClients { it.client }

        val manifestCandidates = mutableListOf<ManifestCandidate>()
        for ((clientKey, priority, manifestUrl) in playbackManifestUrls) {
            runCatching {
                val variant = parseHlsManifest(manifestUrl) ?: return@runCatching
                manifestCandidates += ManifestCandidate(
                    client = clientKey,
                    priority = priority,
                    manifestUrl = manifestUrl,
                    selectedVariantUrl = variant.url,
                    height = variant.height,
                    bandwidth = variant.bandwidth,
                )
            }
        }
        manifestCandidates.sortWith(
            compareByDescending<ManifestCandidate> { it.height }
                .thenByDescending { it.bandwidth }
                .thenBy { it.priority },
        )

        val progressiveCandidates = sortCandidates(playbackProgressive)
        val videoCandidates = sortCandidates(playbackVideo)
        val audioCandidates = sortCandidates(playbackAudio)

        return TrailerExtractionPlatform.buildPlaybackSource(
            manifestCandidates = manifestCandidates,
            progressiveCandidates = progressiveCandidates,
            videoCandidates = videoCandidates,
            audioCandidates = audioCandidates,
        )
    }

    // Keeps only candidates minted by a playback-safe client, falling back to
    // the untrusted ones when that leaves nothing to play.
    private fun <T> List<T>.preferPlaybackClients(clientOf: (T) -> String): List<T> {
        val allowed = filter { clientOf(it) in PLAYBACK_CLIENT_ALLOWLIST }
        if (allowed.isNotEmpty()) return allowed
        if (isNotEmpty()) {
        }
        return this
    }

    private suspend fun fetchWatchConfig(videoId: String): WatchConfig = WatchConfigCache.get {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId&hl=en"
        val watchResponse = TrailerExtractionPlatform.performRequest(
            url = watchUrl,
            method = "GET",
            headers = TrailerExtractionPlatform.defaultHeaders,
            body = null,
            timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
        )
        if (watchResponse.status == 429) {
            RateLimitGate.trip()
            throw IllegalStateException("Rate limited by YouTube (429)")
        }
        if (!watchResponse.ok) {
            throw IllegalStateException("Failed to fetch watch page (${watchResponse.status})")
        }
        getWatchConfig(watchResponse.body)
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
    ): JsonObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${encodeUrlComponent(apiKey)}"

        val headers = buildMap {
            putAll(TrailerExtractionPlatform.defaultHeaders)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) put("x-goog-visitor-id", visitorData)
        }

        val payload = jsonObjectOf(
            "videoId" to videoId,
            "contentCheckOk" to true,
            "racyCheckOk" to true,
            "context" to jsonObjectOf("client" to client.context),
            "playbackContext" to jsonObjectOf(
                "contentPlaybackContext" to jsonObjectOf("html5Preference" to "HTML5_PREF_WANTS"),
            ),
        )

        val response = TrailerExtractionPlatform.performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payload.toString(),
            timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
        )

        if (response.status == 429) {
            RateLimitGate.trip()
            throw IllegalStateException("Rate limited by YouTube (429)")
        }
        if (!response.ok) {
            val preview = response.body.take(200)
            throw IllegalStateException("player API ${client.key} failed (${response.status}): $preview")
        }

        val parsed = JSON.parseToJsonElement(response.body)
        val playerResponse = parsed as? JsonObject ?: JsonObject(emptyMap())

        val playability = playerResponse.objectValue("playabilityStatus")
        val playabilityStatus = playability?.stringValue("status")
        val playabilityReason = playability?.stringValue("reason").orEmpty()
        if (playabilityStatus != null && playabilityStatus != "OK") {
            // "Sign in to confirm you're not a bot" is per client: the rare clients
            // (visionos, android_vr, tvhtml5) get challenged long before ANDROID
            // and IOS do. Only this client is abandoned - the loop still falls
            // through to the others, whose progressive stream stays playable.
            if (playabilityStatus == "LOGIN_REQUIRED" || playabilityReason.contains("bot", ignoreCase = true)) {
                throw BotCheckException(client.key, playabilityStatus)
            }
            // UNPLAYABLE / ERROR are final: no client and no fresh visitor id will
            // produce streams for a video that is geo blocked or gone.
            if (playabilityStatus == "UNPLAYABLE" || playabilityStatus == "ERROR") {
                throw UnplayableException(playabilityStatus, playabilityReason)
            }
        }

        return playerResponse
    }

    private suspend fun parseHlsManifest(manifestUrl: String): ManifestBestVariant? {
        val response = TrailerExtractionPlatform.performRequest(
            url = manifestUrl,
            method = "GET",
            headers = TrailerExtractionPlatform.defaultHeaders,
            body = null,
            timeoutMillis = TRAILER_REQUEST_TIMEOUT_MS,
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        var bestVariant: ManifestBestVariant? = null
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseHlsAttributeList(line)
            val nextLine = lines.getOrNull(index + 1) ?: continue
            if (nextLine.startsWith("#")) continue

            val resolution = attrs["RESOLUTION"].orEmpty()
            val (width, height) = parseResolution(resolution)
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L

            val candidate = ManifestBestVariant(
                url = absolutizeUrl(manifestUrl, nextLine),
                width = width,
                height = height,
                bandwidth = bandwidth,
            )

            if (
                bestVariant == null ||
                candidate.height > bestVariant.height ||
                (candidate.height == bestVariant.height && candidate.bandwidth > bestVariant.bandwidth) ||
                (
                    candidate.height == bestVariant.height &&
                        candidate.bandwidth == bestVariant.bandwidth &&
                        candidate.width > bestVariant.width
                    )
            ) {
                bestVariant = candidate
            }
        }

        return bestVariant
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val parsed = parseUrl(trimmed) ?: return null

        if (parsed.host.endsWith("youtu.be")) {
            val id = parsed.pathSegments.firstOrNull()
            if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) {
                return id
            }
        }

        val queryId = parsed.query["v"]?.firstOrNull()
        if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
            return queryId
        }

        if (parsed.pathSegments.size >= 2) {
            val first = parsed.pathSegments[0]
            val second = parsed.pathSegments[1]
            if ((first == "embed" || first == "shorts" || first == "live") && VIDEO_ID_REGEX.matches(second)) {
                return second
            }
        }

        return null
    }

    private fun getWatchConfig(html: String): WatchConfig {
        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1)
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
        return WatchConfig(apiKey = apiKey, visitorData = visitorData)
    }

    private fun parseHlsAttributeList(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') {
                    inKey = false
                } else {
                    key.append(ch)
                }
                continue
            }

            if (ch == '"') {
                inQuote = !inQuote
                continue
            }

            if (ch == ',' && !inQuote) {
                val parsedKey = key.toString().trim()
                if (parsedKey.isNotEmpty()) {
                    out[parsedKey] = value.toString().trim()
                }
                key.clear()
                value.clear()
                inKey = true
                continue
            }

            value.append(ch)
        }

        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) {
            out[lastKey] = value.toString().trim()
        }

        return out
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x')
        if (parts.size != 2) return 0 to 0
        val width = parts[0].toIntOrNull() ?: 0
        val height = parts[1].toIntOrNull() ?: 0
        return width to height
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return QUALITY_LABEL_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean {
        return parseUrl(url)?.query?.get("n")?.firstOrNull()?.isNotBlank() == true
    }

    private fun videoScore(height: Int, fps: Int, bitrate: Double): Double {
        return height * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
    }

    private fun audioScore(bitrate: Double, audioSampleRate: Double): Double {
        return bitrate * 1_000_000.0 + audioSampleRate
    }

    internal fun sortCandidates(items: List<StreamCandidate>): List<StreamCandidate> {
        return items.sortedWith(
            compareBy<StreamCandidate> { if (it.isDefaultAudioTrack) 0 else 1 }
                .thenByDescending { it.score }
                .thenBy { if (it.hasN) 1 else 0 }
                .thenBy { containerPreference(it.ext) }
                .thenBy { it.priority },
        )
    }

    private fun containerPreference(ext: String): Int {
        return when (ext.lowercase()) {
            "mp4", "m4a" -> 0
            "webm" -> 1
            else -> 2
        }
    }

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative
        }
        if (maybeRelative.startsWith('/')) {
            val scheme = baseUrl.substringBefore("://", "https")
            val host = baseUrl.substringAfter("://", "").substringBefore('/')
            return if (host.isNotBlank()) "$scheme://$host$maybeRelative" else maybeRelative
        }
        val baseDir = baseUrl.substringBeforeLast('/', missingDelimiterValue = baseUrl)
        return "$baseDir/$maybeRelative"
    }

    private fun encodeUrlComponent(value: String): String {
        return value
            .replace("%", "%25")
            .replace("+", "%2B")
            .replace(" ", "%20")
            .replace("&", "%26")
            .replace("=", "%3D")
    }
}

private data class ParsedUrl(
    val host: String,
    val pathSegments: List<String>,
    val query: Map<String, List<String>>,
)

private fun parseUrl(input: String): ParsedUrl? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }

    val withoutFragment = normalized.substringBefore('#')
    val withoutScheme = withoutFragment.substringAfter("://", withoutFragment)
    val host = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
    if (host.isBlank()) return null

    val pathAndQuery = withoutScheme.removePrefix(host)
    val path = when {
        pathAndQuery.startsWith("/") -> pathAndQuery.substringBefore('?')
        pathAndQuery.startsWith("?") || pathAndQuery.isBlank() -> "/"
        else -> "/${pathAndQuery.substringBefore('?')}"
    }
    val queryString = withoutFragment.substringAfter('?', "")
    val query = LinkedHashMap<String, MutableList<String>>()
    queryString.split('&')
        .filter { it.isNotBlank() }
        .forEach { pair ->
            val key = pair.substringBefore('=').trim()
            if (key.isBlank()) return@forEach
            val value = pair.substringAfter('=', "")
            query.getOrPut(key) { mutableListOf() }.add(value)
        }

    return ParsedUrl(
        host = host,
        pathSegments = path.trim('/').split('/').filter { it.isNotBlank() },
        query = query,
    )
}

private fun JsonObject.objectValue(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.listObjectValue(key: String): List<JsonObject> {
    return (this[key] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
}

private fun JsonObject.stringValue(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.toString().trim('"')
}

private fun JsonObject.numberValue(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.toString().trim('"').toDoubleOrNull()
}

private fun JsonObject.booleanValue(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toBooleanStrictOrNull()
}

private fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val mapped = LinkedHashMap<String, JsonElement>()
    pairs.forEach { (key, value) ->
        value?.let { mapped[key] = toJsonElement(it) }
    }
    return JsonObject(mapped)
}

private fun toJsonElement(value: Any): JsonElement {
    return when (value) {
        is JsonElement -> value
        is JsonObject -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> {
            val map = LinkedHashMap<String, JsonElement>()
            value.forEach { (key, nestedValue) ->
                val parsedKey = key?.toString() ?: return@forEach
                if (nestedValue != null) {
                    map[parsedKey] = toJsonElement(nestedValue)
                }
            }
            JsonObject(map)
        }
        is List<*> -> JsonArray(value.mapNotNull { it?.let(::toJsonElement) })
        else -> JsonPrimitive(value.toString())
    }
}


