package com.nuvio.app.features.usenet

import java.util.Locale

/**
 * What a release's name says for its result's text, beyond its ranking facts: the streaming
 * service of a web release, its main audio track, the languages it names and its group. Tags are
 * read from the year, episode or resolution on, so words of the title (It, Max, Now) are not taken
 * for tags.
 */
internal data class UsenetReleaseLabels(
    val service: String?,
    /** The best audio track it names, with its channels and Atmos: "TrueHD 7.1 Atmos". */
    val audio: String?,
    /** Language codes, upper case: EN, DE, MULTI. */
    val languages: List<String>,
    /** The release group, without what a repost or an obfuscated upload adds after it. */
    val group: String?
) {
    companion object {
        fun parse(title: String): UsenetReleaseLabels {
            val tags = tags(title.take(2048))
            // Case matters for iTunes: "iT" is its tag, "IT" or "ITA" Italian.
            val service = ITUNES.find(tags)?.let { "iTunes" }
                ?: SERVICES.firstNotNullOfOrNull { (name, pattern) -> name.takeIf { pattern.containsMatchIn(tags) } }
            return UsenetReleaseLabels(service, audio(tags),
                StreamRankingFacts.languagesOf(ITUNES.replace(tags, " ")).map { it.uppercase(Locale.ROOT) },
                group(title.take(2048)))
        }

        /** ROEN for Movie.2024.BDRip.x264-ROEN-xpost; null when the name ends in no group. */
        fun group(title: String): String? {
            var name = EXTENSION.replace(title.trim(), "").trimEnd('-', ' ')
            while (true) {
                val shorter = REPOST.replace(name, "").trimEnd('-', ' ')
                if (shorter == name) break
                name = shorter
            }
            // A name ending in WEB-DL has no group.
            return GROUP.find(name)?.groupValues?.get(1)?.takeUnless { name.endsWith("WEB-DL", ignoreCase = true) }
        }

        /** The name from its year, episode or resolution on; the whole name when it has none of them. */
        fun tags(title: String): String {
            val start = listOfNotNull(
                YEAR.findAll(title).firstOrNull { it.range.first > 0 }?.range?.first,
                EPISODE.find(title)?.range?.first,
                RESOLUTION.find(title)?.range?.first
            ).minOrNull() ?: return title
            return title.substring(start)
        }

        /** The quality as release names write it; null when the name does not say. */
        fun quality(quality: StreamReleaseQuality): String? = when (quality) {
            StreamReleaseQuality.REMUX -> "Remux"
            StreamReleaseQuality.BLURAY -> "BluRay"
            StreamReleaseQuality.WEB_DL -> "WEB-DL"
            StreamReleaseQuality.WEBRIP -> "WEBRip"
            StreamReleaseQuality.HDTV -> "HDTV"
            StreamReleaseQuality.DVD -> "DVD"
            StreamReleaseQuality.CAM -> "CAM"
            StreamReleaseQuality.UNKNOWN -> null
        }

        /** Dolby Vision and the best HDR format named: "DV HDR10+". */
        fun visual(visual: Set<String>): String? = listOfNotNull(
            "DV".takeIf { "DV" in visual },
            when { "HDR10_PLUS" in visual -> "HDR10+"; "HDR" in visual -> "HDR"; else -> null }
        ).joinToString(" ").ifEmpty { null }

        fun codec(codec: String): String? = codec.takeIf { it != "UNKNOWN" }

        /**
         * The language codes joined, at most two: "EN+DE", or "EN+JA +5" for a release of seven.
         * The release name, when shown, lists them all; null for none.
         */
        fun languages(codes: List<String>): String? = when {
            codes.isEmpty() -> null
            codes.size <= MAX_LISTED_LANGUAGES -> codes.joinToString("+")
            else -> codes.take(MAX_LISTED_LANGUAGES).joinToString("+") + " +${codes.size - MAX_LISTED_LANGUAGES}"
        }
        private const val MAX_LISTED_LANGUAGES = 2

        private fun audio(tags: String): String? {
            val found = CODECS.firstNotNullOfOrNull { (name, pattern) -> pattern.find(tags)?.let { name to it } }
            val channels = found?.second?.let { match -> match.groupValues[1].takeIf { it.isNotEmpty() }?.let { "$it.${match.groupValues[2]}" } }
                ?: CHANNELS.findAll(tags).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.firstOrNull { it in KNOWN_CHANNELS }
                ?: CHANNEL_COUNT.find(tags)?.let { SPEAKERS[it.groupValues[1]] }
            return listOfNotNull(found?.first, channels, "Atmos".takeIf { ATMOS.containsMatchIn(tags) })
                .joinToString(" ").ifEmpty { null }
        }

        private fun token(value: String) = Regex("(?i)(?<![a-z0-9])(?:$value)(?![a-z0-9])")
        private val YEAR = Regex("(?<![0-9a-zA-Z])(?:19|20)[0-9]{2}(?![0-9a-zA-Z])")
        private val EPISODE = Regex("(?i)(?<![a-z0-9])s[0-9]{1,3}(?:[ ._-]?e[0-9]{1,4})?(?![a-z0-9])")
        private val RESOLUTION = Regex("(?i)(?<![0-9a-z])(?:2160|1440|1080|720|576|480|360)[pi](?![a-z0-9])|(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])")
        private val ITUNES = Regex("(?<![A-Za-z0-9])iT(?![A-Za-z0-9])")
        private val EXTENSION = Regex("(?i)\\.(?:mkv|mp4|m2ts|ts|nzb)$")
        /** What reposts and obfuscated uploads add after the group, and a bracketed tag such as [TGx]. */
        private val REPOST = Regex("(?i)(?:-(?:xpost|repost|postbot|xsp|obfuscated|scrambled|asrequested|" +
            "alternativetorequested|rakuv[a-z0-9]*|buymore|4p|4planet|chamele0n|whiterev)|\\[[^\\]]{1,20}\\])$")
        private val GROUP = Regex("(?i)-((?:D-Z0N3)|[a-z0-9][a-z0-9._]{1,31})$")
        private val SERVICES = listOf(
            "Prime" to token("amzn"), "Netflix" to token("nf"), "Apple TV+" to token("atvp"),
            "Disney+" to token("dsnp"), "Max" to token("hmax|max"), "Hulu" to token("hulu"),
            "Peacock" to token("pcok"), "Paramount+" to token("pmtp"), "Crunchyroll" to token("cr"),
            "Stan" to token("stan")
        )
        /** Best first; the channels may follow without a separator, as in DDP5.1. */
        private fun audioCodec(value: String) =
            Regex("(?i)(?<![a-z0-9])(?:$value)(?:[ ._-]?([1-8])[ ._]([0-2]))?(?![a-z0-9])")
        private val CODECS = listOf(
            "TrueHD" to audioCodec("true[ ._-]?hd"),
            "DTS:X" to audioCodec("dts[ ._:-]?x"),
            "DTS-HD MA" to audioCodec("dts[ ._-]?hd[ ._-]?ma|dts[ ._-]?ma"),
            "DTS-HD" to audioCodec("dts[ ._-]?hd(?:[ ._-]?hra)?"),
            "DD+" to audioCodec("ddp|dd\\+|e[ ._-]?ac[ ._-]?3"),
            "DD" to audioCodec("dd|ac[ ._-]?3"),
            "DTS" to audioCodec("dts"),
            "FLAC" to audioCodec("flac"),
            "LPCM" to audioCodec("l?pcm"),
            "AAC" to audioCodec("aac"),
            "Opus" to audioCodec("opus")
        )
        /** Channels apart from their codec, as in TrueHD.Atmos.7.1; not part of a longer number. */
        private val CHANNELS = Regex("(?<![0-9])(?<![0-9][ ._])([1-8])[ ._]([0-2])(?![0-9])(?![ ._][0-9])")
        private val KNOWN_CHANNELS = setOf("1.0", "2.0", "2.1", "5.1", "6.1", "7.1")
        /** A channel count, as in 6CH: the speaker layout it stands for. */
        private val CHANNEL_COUNT = token("([2678])ch")
        private val SPEAKERS = mapOf("2" to "2.0", "6" to "5.1", "7" to "6.1", "8" to "7.1")
        private val ATMOS = token("atmos")
    }
}
