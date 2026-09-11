package com.nuvio.app.features.streams

/**
 * Deterministic quality-aware ordering for automatic stream selection.
 * Manual stream selection is intentionally unaffected.
 *
 * Explicit preferences are applied before the general quality score. This keeps
 * automatic ranking from overriding a user's configured stream preference.
 */
object SmartStreamSelector {
    data class Context(
        val estimatedBandwidthKbps: Int? = null,
        val displayWidth: Int? = null,
        val displayHeight: Int? = null,
        /**
         * Null means the device capability is not known. Unknown capability must
         * not be treated as an explicit lack of HDR support.
         */
        val supportsHdr: Boolean? = null,
        val supportedHdrTypes: Set<String> = emptySet(),
        val dataSaver: Boolean = false,
        val preferredVideoCodec: String? = null,
        val preferredAudioLanguage: String? = null,
        val preferredStreamTerms: List<String> = emptyList(),
    )

    private val resolutionPattern =
        Regex("""(?:^|\D)(4320|2160|1440|1080|720|576|540|480|360)p?(?:\D|$)""")
    private val dolbyVisionPattern =
        Regex("""(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)""")
    private val hdrPattern =
        Regex("""(^|[^a-z0-9])(hdr|hdr10|hdr10\+|hdr10plus|hlg)([^a-z0-9]|$)""")
    private val hevcPattern =
        Regex("""(^|[^a-z0-9])(hevc|h[ ._-]?265|x265)([^a-z0-9]|$)""")
    private val h264Pattern =
        Regex("""(^|[^a-z0-9])(h[ ._-]?264|avc|x264)([^a-z0-9]|$)""")
    private val av1Pattern =
        Regex("""(^|[^a-z0-9])av1([^a-z0-9]|$)""")

    private var platformContextProvider: (() -> Context)? = null

    @Synchronized
    fun setPlatformContextProvider(provider: (() -> Context)?) {
        platformContextProvider = provider
    }

    fun currentContext(): Context = platformContextProvider?.invoke() ?: Context()

    fun rank(
        streams: List<StreamItem>,
        context: Context = currentContext(),
    ): List<StreamItem> = streams
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<StreamItem>> { preferenceScore(it.value, context) }
                .thenByDescending { score(it.value, context) }
                .thenBy { it.index }
        )
        .map { it.value }

    private fun preferenceScore(stream: StreamItem, context: Context): Int {
        if (context.preferredStreamTerms.isEmpty()) return 0
        val text = streamSearchText(stream)

        context.preferredStreamTerms.forEachIndexed { index, term ->
            if (term.isNotBlank() && matchesPreferenceTerm(text, term)) {
                return (context.preferredStreamTerms.size - index) * 1_000
            }
        }
        return 0
    }

    private fun score(stream: StreamItem, context: Context): Int {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val text = streamSearchText(stream)

        var score = 0
        val resolution = resolutionHeight(parsed?.resolution.orEmpty())
            .takeIf { it > 0 }
            ?: resolutionHeight(text)
        if (resolution > 0) {
            score += when {
                context.dataSaver -> when {
                    resolution <= 480 -> 70
                    resolution <= 720 -> 90
                    resolution <= 1080 -> 80
                    else -> 45
                }
                context.displayHeight?.takeIf { it > 0 } != null -> {
                    val displayHeight = context.displayHeight!!.coerceAtLeast(1)
                    when {
                        resolution <= displayHeight -> 100 + resolution / 100
                        else -> maxOf(0, 100 - (resolution - displayHeight) / 20)
                    }
                }
                else -> when (resolution) {
                    4320 -> 150
                    2160 -> 130
                    1440 -> 120
                    1080 -> 110
                    720 -> 90
                    480 -> 70
                    else -> 50
                }
            }
        }

        val sizeBytes = (
            stream.behaviorHints.videoSize
                ?: stream.clientResolve?.stream?.raw?.size
                ?: stream.debridCacheStatus?.cachedSize
            )?.takeIf { it > 0 }
        val bandwidthKbps = context.estimatedBandwidthKbps
        val durationSeconds = parsed?.duration?.takeIf { it > 0 }
        if (bandwidthKbps != null && bandwidthKbps > 0 && sizeBytes != null && durationSeconds != null) {
            val requiredKbps = (sizeBytes * 8L / 1000L / durationSeconds)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val bandwidth = bandwidthKbps.toLong()
            score += when {
                requiredKbps.toLong() * 100 <= bandwidth * 60 -> 35
                requiredKbps.toLong() * 100 <= bandwidth * 75 -> 20
                requiredKbps.toLong() * 100 <= bandwidth * 90 -> 5
                requiredKbps.toLong() * 100 <= bandwidth * 100 -> 0
                requiredKbps.toLong() * 100 <= bandwidth * 125 -> -15
                else -> -60
            }
        }

        val hdrTypes = hdrTypes(parsed?.hdr.orEmpty(), text)
        val isHdr = hdrTypes.isNotEmpty() || hasHdrToken(parsed?.hdr.orEmpty(), text)
        val supportedHdrTypes = context.supportedHdrTypes.map { it.lowercase() }.toSet()
        score += when {
            isHdr && hdrTypes.any { it in supportedHdrTypes } -> 20
            isHdr && context.supportsHdr == true -> 20
            isHdr && context.supportsHdr == false -> -25
            !isHdr && context.supportsHdr != null -> 5
            else -> 0
        }

        val codec = normalizeCodec(parsed?.codec).takeIf { it.isNotEmpty() } ?: codecFromText(text)
        if (normalizeCodec(context.preferredVideoCodec) == codec && codec.isNotEmpty()) score += 15
        score += when (codec) {
            "av1", "hevc" -> 5
            "h264" -> 3
            else -> 0
        }

        if (context.preferredAudioLanguage != null && parsed?.languages.orEmpty().any {
                it.equals(context.preferredAudioLanguage, ignoreCase = true)
            }) score += 12

        if (stream.isDirectDebridStream || stream.isCachedDebridTorrentStream) score += 35
        if (stream.clientResolve?.isCached == true) score += 35
        if (stream.playableDirectUrl != null) score += 20
        if (stream.isTorrentStream && !stream.isCachedDebridTorrentStream) score -= 10
        if (stream.behaviorHints.notWebReady) score -= 15

        return score
    }

    private fun streamSearchText(stream: StreamItem): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            resolve?.filename,
            resolve?.torrentName,
            raw?.filename,
            raw?.torrentName,
            parsed?.rawTitle,
            parsed?.parsedTitle,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
        ).joinToString(" ").lowercase()
    }

    private fun hdrTypes(parsedHdr: List<String>, text: String): Set<String> = buildSet {
        (parsedHdr + text).forEach { value ->
            val normalized = value.lowercase()
            when {
                dolbyVisionPattern.containsMatchIn(normalized) -> add("dolbyvision")
                normalized.contains("hdr10+") || normalized.contains("hdr10plus") -> add("hdr10+")
                hasToken(normalized, "hdr10") -> add("hdr10")
                hasToken(normalized, "hlg") -> add("hlg")
            }
        }
    }

    private fun hasHdrToken(parsedHdr: List<String>, text: String): Boolean =
        (parsedHdr + text).any { hdrPattern.containsMatchIn(it.lowercase()) }

    private fun matchesPreferenceTerm(text: String, term: String): Boolean {
        val normalized = term.trim().lowercase()
        return if (normalized.all { it.isLetterOrDigit() }) {
            hasToken(text, normalized)
        } else {
            normalized in text
        }
    }

    private fun normalizeCodec(codec: String?): String {
        val normalized = codec
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()
        return when (normalized) {
            "hevc", "h265", "x265" -> "hevc"
            "h264", "avc", "x264" -> "h264"
            "av1" -> "av1"
            else -> ""
        }
    }

    private fun codecFromText(text: String): String = when {
        av1Pattern.containsMatchIn(text) -> "av1"
        hevcPattern.containsMatchIn(text) -> "hevc"
        h264Pattern.containsMatchIn(text) -> "h264"
        else -> ""
    }

    private fun resolutionHeight(value: String): Int {
        val normalized = value.lowercase()
        val match = resolutionPattern.find(normalized)
        if (match != null) return match.groupValues[1].toInt()
        return when {
            hasToken(normalized, "8k") -> 4320
            hasToken(normalized, "4k") || hasToken(normalized, "uhd") -> 2160
            hasToken(normalized, "2k") || hasToken(normalized, "qhd") -> 1440
            hasToken(normalized, "fhd") -> 1080
            hasToken(normalized, "hd") -> 720
            hasToken(normalized, "sd") -> 480
            else -> 0
        }
    }

    private fun hasToken(value: String, token: String): Boolean =
        Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|$)")
            .containsMatchIn(value.lowercase())
}
