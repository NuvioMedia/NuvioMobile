package com.nuvio.app.features.streams

import kotlin.concurrent.Volatile

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

    private object ScoreWeights {
        const val RES_8K = 150
        const val RES_4K = 130
        const val RES_2K = 120
        const val RES_1080 = 110
        const val RES_720 = 90
        const val RES_480 = 70
        const val RES_DEFAULT = 50

        const val DATA_SAVER_480 = 70
        const val DATA_SAVER_720 = 90
        const val DATA_SAVER_1080 = 80
        const val DATA_SAVER_HIGH = 45

        const val BASE_DISPLAY_MATCH = 100

        const val BW_SAFE = 35
        const val BW_FAIR = 20
        const val BW_TIGHT = 5
        const val BW_EXCEEDS = 0
        const val BW_RISKY = -15
        const val BW_DANGEROUS = -60

        const val HDR_SUPPORTED = 20
        const val HDR_UNSUPPORTED = -25
        const val HDR_FALLBACK = 5

        const val CODEC_PREFERRED = 15
        const val CODEC_HEVC_AV1 = 5
        const val CODEC_H264 = 3

        const val AUDIO_LANG_PREFERRED = 12
        const val DEBRID_CACHED = 35
        const val DIRECT_PLAY = 20
        const val TORRENT_NOT_CACHED = -10
        const val NOT_WEB_READY = -15
    }

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

    @Volatile
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
                    resolution <= 480 -> ScoreWeights.DATA_SAVER_480
                    resolution <= 720 -> ScoreWeights.DATA_SAVER_720
                    resolution <= 1080 -> ScoreWeights.DATA_SAVER_1080
                    else -> ScoreWeights.DATA_SAVER_HIGH
                }
                context.displayHeight?.takeIf { it > 0 } != null -> {
                    val displayHeight = context.displayHeight!!.coerceAtLeast(1)
                    when {
                        resolution <= displayHeight -> ScoreWeights.BASE_DISPLAY_MATCH + resolution / 100
                        else -> maxOf(0, ScoreWeights.BASE_DISPLAY_MATCH - (resolution - displayHeight) / 20)
                    }
                }
                else -> when (resolution) {
                    4320 -> ScoreWeights.RES_8K
                    2160 -> ScoreWeights.RES_4K
                    1440 -> ScoreWeights.RES_2K
                    1080 -> ScoreWeights.RES_1080
                    720 -> ScoreWeights.RES_720
                    480 -> ScoreWeights.RES_480
                    else -> ScoreWeights.RES_DEFAULT
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
                requiredKbps.toLong() * 100 <= bandwidth * 60 -> ScoreWeights.BW_SAFE
                requiredKbps.toLong() * 100 <= bandwidth * 75 -> ScoreWeights.BW_FAIR
                requiredKbps.toLong() * 100 <= bandwidth * 90 -> ScoreWeights.BW_TIGHT
                requiredKbps.toLong() * 100 <= bandwidth * 100 -> ScoreWeights.BW_EXCEEDS
                requiredKbps.toLong() * 100 <= bandwidth * 125 -> ScoreWeights.BW_RISKY
                else -> ScoreWeights.BW_DANGEROUS
            }
        }

        val hdrTypes = hdrTypes(parsed?.hdr.orEmpty(), text)
        val isHdr = hdrTypes.isNotEmpty() || hasHdrToken(parsed?.hdr.orEmpty(), text)
        val supportedHdrTypes = context.supportedHdrTypes.map { it.lowercase() }.toSet()
        score += when {
            isHdr && hdrTypes.any { it in supportedHdrTypes } -> ScoreWeights.HDR_SUPPORTED
            isHdr && context.supportsHdr == true -> ScoreWeights.HDR_SUPPORTED
            isHdr && context.supportsHdr == false -> ScoreWeights.HDR_UNSUPPORTED
            !isHdr && context.supportsHdr != null -> ScoreWeights.HDR_FALLBACK
            else -> 0
        }

        val codec = normalizeCodec(parsed?.codec).takeIf { it.isNotEmpty() } ?: codecFromText(text)
        if (normalizeCodec(context.preferredVideoCodec) == codec && codec.isNotEmpty()) score += ScoreWeights.CODEC_PREFERRED
        score += when (codec) {
            "av1", "hevc" -> ScoreWeights.CODEC_HEVC_AV1
            "h264" -> ScoreWeights.CODEC_H264
            else -> 0
        }

        if (context.preferredAudioLanguage != null && parsed?.languages.orEmpty().any {
                it.equals(context.preferredAudioLanguage, ignoreCase = true)
            }) score += ScoreWeights.AUDIO_LANG_PREFERRED

        if (stream.isDirectDebridStream || stream.isCachedDebridTorrentStream) score += ScoreWeights.DEBRID_CACHED
        if (stream.clientResolve?.isCached == true) score += ScoreWeights.DEBRID_CACHED
        if (stream.playableDirectUrl != null) score += ScoreWeights.DIRECT_PLAY
        if (stream.isTorrentStream && !stream.isCachedDebridTorrentStream) score += ScoreWeights.TORRENT_NOT_CACHED
        if (stream.behaviorHints.notWebReady) score += ScoreWeights.NOT_WEB_READY

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
