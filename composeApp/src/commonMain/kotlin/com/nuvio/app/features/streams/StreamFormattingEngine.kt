package com.nuvio.app.features.streams

import kotlinx.serialization.Serializable

/**
 * Pure presentation-layer stream formatting.
 *
 * This deliberately returns display data instead of mutating [StreamItem]. Playback URLs,
 * headers, provider identifiers and the original provider response must remain untouched.
 */
@Serializable
enum class StreamFormatPreset {
    ORIGINAL,
    NUVIOEX_MODERN,
    MINIMAL,
    MEDIAFUSION,
    ESSENTIALS,
}

@Serializable
enum class StreamProviderFamily {
    NUVIO_ADDON,
    CLOUDSTREAM_PLUGIN,
    OTHER,
}

@Serializable
data class StreamFormattingScope(
    val includeNuvioAddons: Boolean = true,
    val includeCloudstreamPlugins: Boolean = true,
    val includeOtherProviders: Boolean = false,
    /** Explicit provider IDs excluded even when their family is enabled. */
    val excludedProviderIds: Set<String> = emptySet(),
    /** When non-empty, only these provider IDs are formatted within enabled families. */
    val includedProviderIds: Set<String> = emptySet(),
) {
    fun includes(providerId: String, family: StreamProviderFamily, providerName: String = ""): Boolean {
        val keys = setOf(providerId.trim().lowercase(), providerName.trim().lowercase()).filter { it.isNotBlank() }.toSet()
        val excluded = excludedProviderIds.map { it.trim().lowercase() }.toSet()
        val included = includedProviderIds.map { it.trim().lowercase() }.toSet()
        if (keys.any { it in excluded }) return false
        if (included.isNotEmpty() && keys.none { it in included }) return false
        return when (family) {
            StreamProviderFamily.NUVIO_ADDON -> includeNuvioAddons
            StreamProviderFamily.CLOUDSTREAM_PLUGIN -> includeCloudstreamPlugins
            StreamProviderFamily.OTHER -> includeOtherProviders
        }
    }
}

@Serializable
data class StreamFormattingSettings(
    val enabled: Boolean = true,
    val preset: StreamFormatPreset = StreamFormatPreset.NUVIOEX_MODERN,
    val scope: StreamFormattingScope = StreamFormattingScope(),
    val showProvider: Boolean = true,
    val showAudio: Boolean = true,
    val showSubtitles: Boolean = true,
)

data class FormattedStreamPresentation(
    val title: String,
    val summary: String?,
    val technicalDetails: List<String>,
    val originalTitle: String,
)

/** Extracts only explicit, recognizable facts from provider text and filename metadata. */
object StreamFormattingEngine {
    private val whitespace = Regex("\\s+")
    private val resolution = Regex("(?i)(2160p|1440p|1080p|720p|576p|480p|4k|uhd)")
    private val source = Regex("(?i)(WEB[- .]?DL|WEB[- .]?Rip|Blu[- .]?Ray|BDRip|BRRip|HDRip|HDTV|DVDRip|CAM)")
    private val videoCodec = Regex("(?i)(HEVC|H\\.265|x265|AVC|H\\.264|x264|AV1|VP9)")
    private val audioCodec = Regex("(?i)(EAC3|DDP(?:[ .]?5[ .]?1)?|DD\\+|AC3|DTS(?:-HD)?|TRUEHD|ATMOS|AAC|FLAC|OPUS)")
    private val channels = Regex("(?i)(7[ .]?1|5[ .]?1|2[ .]?0)")
    private val hdr = Regex("(?i)(Dolby[ .]?Vision|HDR10\\+?|HDR|HLG)")
    private val size = Regex("(?i)(\\d+(?:[.,]\\d+)?\\s*(?:GB|GiB|MB|MiB))")
    private val language = Regex("(?i)(Hindi|English|Tamil|Telugu|Malayalam|Kannada|Bengali|Japanese|Korean|French|German|Spanish|Multi(?:ple)?)")
    private val subtitles = Regex("(?i)(ESub|ESubs|ESubtitle|SUBBED|MULTI[- .]?SUB|\\bSUBS?\\b)")

    fun format(
        stream: StreamItem,
        preset: StreamFormatPreset,
        providerId: String,
        providerFamily: StreamProviderFamily,
        settings: StreamFormattingSettings,
    ): FormattedStreamPresentation {
        val original = stream.name?.takeIf { it.isNotBlank() } ?: stream.title?.takeIf { it.isNotBlank() } ?: "Stream"
        val raw = listOfNotNull(
            stream.name, stream.title, stream.description, stream.behaviorHints.filename,
        ).joinToString(" ")
        if (!settings.enabled || preset == StreamFormatPreset.ORIGINAL ||
            !settings.scope.includes(providerId, providerFamily, stream.addonName) || hasExistingFormatting(stream)
        ) {
            return FormattedStreamPresentation(original, stream.description, emptyList(), original)
        }

        val facts = extract(raw)
        val technical = buildList {
            facts.resolution?.let(::add)
            facts.source?.let(::add)
            facts.videoCodec?.let(::add)
            if (preset == StreamFormatPreset.MEDIAFUSION || preset == StreamFormatPreset.NUVIOEX_MODERN) {
                facts.hdr?.let(::add)
                facts.audioCodec?.let(::add)
                facts.channels?.let(::add)
            } else if (preset == StreamFormatPreset.ESSENTIALS) {
                facts.audioCodec?.let(::add)
            }
            if (settings.showAudio) facts.language?.let { add("Audio: $it") }
            if (settings.showSubtitles) facts.subtitles?.let { add("Subtitles: $it") }
            facts.size?.let { add("Size: $it") }
            if (settings.showProvider) stream.addonName.takeIf { it.isNotBlank() }?.let { add("Provider: $it") }
        }.distinct()

        val cleanTitle = cleanTitle(stream.title ?: stream.behaviorHints.filename ?: original)
        val title = when (preset) {
            StreamFormatPreset.MINIMAL -> listOfNotNull(facts.resolution, facts.source, facts.videoCodec, facts.size)
                .joinToString(" · ").ifBlank { cleanTitle }
            StreamFormatPreset.ESSENTIALS -> listOfNotNull(facts.resolution, facts.source, facts.language)
                .joinToString(" · ").ifBlank { cleanTitle }
            else -> cleanTitle
        }
        val summary = when (preset) {
            StreamFormatPreset.MINIMAL -> null
            StreamFormatPreset.ESSENTIALS -> listOfNotNull(facts.audioCodec, facts.channels, facts.hdr)
                .joinToString(" · ").ifBlank { null }
            else -> technical.take(6).joinToString(" · ").ifBlank { null }
        }
        return FormattedStreamPresentation(title, summary, technical, original)
    }

    /**
     * Preserve provider-authored technical summaries to avoid duplicate labels.
     * A quality badge alone is not enough: raw titles may still need normalisation.
     */
    fun hasExistingFormatting(stream: StreamItem): Boolean {
        // Multiple provider-authored badges are already a structured presentation.
        // A lone quality badge (e.g. FHD) is insufficient to skip title normalisation.
        val meaningfulBadgeCount = stream.badges.count { it.name.isNotBlank() || it.imageURL.isNotBlank() }
        if (meaningfulBadgeCount >= 2) return true

        val description = stream.description.orEmpty()
        if (description.isBlank()) return false
        val hasQuality = Regex("(?i)\\b(?:2160p|1440p|1080p|720p|480p|4k|uhd)\\b").containsMatchIn(description)
        val hasLabeledMetadata = Regex("(?i)\\b(?:audio|provider|subtitles?|codec|size)\\s*:").containsMatchIn(description)
        val separators = description.count { it == '·' || it == '|' }
        return (hasQuality && hasLabeledMetadata) || (hasLabeledMetadata && separators >= 2)
    }

    private data class Facts(
        val resolution: String? = null,
        val source: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val channels: String? = null,
        val hdr: String? = null,
        val size: String? = null,
        val language: String? = null,
        val subtitles: String? = null,
    )

    private fun extract(text: String): Facts {
        fun match(regex: Regex): String? = regex.find(text)?.value
            ?.replace(Regex("[._]+"), " ")
            ?.replace(whitespace, " ")
            ?.trim()
        return Facts(
            resolution = match(resolution)?.uppercase()?.let { if (it == "4K") "2160p" else it },
            source = match(source)?.uppercase()?.replace(Regex("[ .]+"), "-"),
            videoCodec = match(videoCodec)?.uppercase()?.let {
                when (it) {
                    "H.265", "X265" -> "HEVC"
                    "H.264", "X264" -> "AVC"
                    else -> it
                }
            },
            audioCodec = match(audioCodec)?.uppercase()?.replace(Regex("[ .]+"), ""),
            channels = match(channels)?.replace(" ", ""),
            hdr = match(hdr)?.uppercase()?.replace(Regex("[ .]+"), " "),
            size = match(size)?.replace(",", ".")?.uppercase(),
            language = match(language)?.replaceFirstChar { it.uppercase() },
            subtitles = match(subtitles)?.uppercase(),
        )
    }

    private fun cleanTitle(value: String): String {
        var result = value.substringAfterLast('/').substringAfterLast('\\')
        result = result.replace(Regex("\\.(mkv|mp4|avi|mov|webm)$", RegexOption.IGNORE_CASE), "")
        result = result.replace(Regex("(?i)[\\s._-]+(2160p|1440p|1080p|720p|576p|480p|4k|uhd|WEB[- .]?DL|WEB[- .]?Rip|Blu[- .]?Ray|BDRip|BRRip|HDRip|HDTV|HEVC|x265|x264|AVC|AV1|HDR10\\+?|HDR|EAC3|DDP|AC3|AAC|DTS|ESub|ESubs).*?$"), "")
        result = result.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '_', '.')
        return result.ifBlank { value.replace(whitespace, " ").trim() }
    }
}
