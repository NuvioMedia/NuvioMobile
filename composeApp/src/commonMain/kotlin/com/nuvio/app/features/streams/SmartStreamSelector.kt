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
        val supportsHdr: Boolean = false,
        val supportedHdrTypes: Set<String> = emptySet(),
        val dataSaver: Boolean = false,
        val preferredVideoCodec: String? = null,
        val preferredAudioLanguage: String? = null,
        val preferredStreamTerms: List<String> = emptyList(),
    )

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
        val text = listOfNotNull(
            stream.name,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.torrentName,
            stream.clientResolve?.stream?.raw?.parsed?.resolution,
            stream.clientResolve?.stream?.raw?.parsed?.quality,
            stream.clientResolve?.stream?.raw?.parsed?.codec,
        ).joinToString(" ").lowercase()

        context.preferredStreamTerms.forEachIndexed { index, term ->
            if (term.isNotBlank() && term.lowercase() in text) {
                return (context.preferredStreamTerms.size - index) * 1_000
            }
        }
        return 0
    }

    private fun score(stream: StreamItem, context: Context): Int {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val text = listOfNotNull(
            stream.name,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.torrentName,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
        ).joinToString(" ").lowercase()

        var score = 0
        val resolution = resolutionHeight(parsed?.resolution ?: text)
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

        val sizeBytes = stream.behaviorHints.videoSize ?: stream.clientResolve?.stream?.raw?.size
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
        val hdr = hdrTypes.isNotEmpty() ||
            listOf("dolby vision", "dolbyvision", "hdr10", "hdr10+", "hlg").any { it in text }
        score += if (hdr) {
            when {
                context.supportedHdrTypes.isNotEmpty() &&
                    hdrTypes.any { it in context.supportedHdrTypes.map(String::lowercase).toSet() } -> 20
                context.supportsHdr -> 20
                else -> -25
            }
        } else 5

        val codec = normalizeCodec(parsed?.codec ?: when {
            "av1" in text -> "av1"
            "hevc" in text || "h265" in text || "x265" in text -> "hevc"
            "h264" in text || "x264" in text -> "h264"
            else -> ""
        })
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

    private fun hdrTypes(parsedHdr: List<String>, text: String): Set<String> = buildSet {
        (parsedHdr + text).forEach { value ->
            val normalized = value.lowercase()
            when {
                "dolby vision" in normalized || "dolbyvision" in normalized || " dv" in " $normalized" -> add("dolbyvision")
                "hdr10+" in normalized || "hdr10plus" in normalized -> add("hdr10+")
                "hdr10" in normalized -> add("hdr10")
                "hlg" in normalized -> add("hlg")
            }
        }
    }

    private fun normalizeCodec(codec: String?): String = when (codec?.trim()?.lowercase()) {
        "hevc", "h265", "x265" -> "hevc"
        "h264", "avc", "x264" -> "h264"
        "av1" -> "av1"
        else -> ""
    }

    private fun resolutionHeight(value: String): Int {
        val normalized = value.lowercase()
        val match = Regex("(?:^|\\D)(4320|2160|1440|1080|720|576|540|480|360)p?(?:\\D|$)").find(normalized)
        if (match != null) return match.groupValues[1].toInt()
        return when {
            "8k" in normalized -> 4320
            "4k" in normalized || "uhd" in normalized -> 2160
            "2k" in normalized || "qhd" in normalized -> 1440
            else -> 0
        }
    }
}
