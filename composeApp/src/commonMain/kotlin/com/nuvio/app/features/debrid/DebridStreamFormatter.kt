package com.nuvio.app.features.debrid

import com.nuvio.app.features.debrid.DebridStreamPresentation.isManagedDebridStream
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamItem

class DebridStreamFormatter(
    private val engine: DebridStreamTemplateEngine = DebridStreamTemplateEngine(),
) {
    private val whitespaceRegex = Regex("\\s+")

    fun format(
        stream: StreamItem,
        settings: DebridSettings,
    ): StreamItem = format(stream, settings, facts = null)

    /** [facts] are the ones computed while filtering; without them they are computed here. */
    internal fun format(
        stream: StreamItem,
        settings: DebridSettings,
        facts: DebridStreamFacts?,
    ): StreamItem {
        if (!stream.isManagedDebridStream) return stream
        val matchedBadges = stream.badges
        val values = buildValues(
            stream = stream,
            matchedBadges = matchedBadges,
            facts = facts ?: DebridStreamMetadata.facts(
                stream = stream,
                preferences = DebridStreamMetadata.effectivePreferences(settings),
            ),
        )
        val formattedName = engine.render(settings.streamNameTemplate, values)
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(whitespaceRegex, " ")
            .trim()
        val formattedDescription = engine.render(settings.streamDescriptionTemplate, values)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

        return stream.copy(
            name = formattedName.ifBlank { stream.name ?: DebridProviders.displayName(serviceId(stream)) },
            description = formattedDescription.ifBlank { stream.description ?: stream.title },
            badges = stream.badges,
        )
    }

    private fun buildValues(
        stream: StreamItem,
        matchedBadges: List<StreamBadge>,
        facts: DebridStreamFacts,
    ): Map<String, Any?> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val seasons = parsed?.seasons.orEmpty()
        val episodes = parsed?.episodes.orEmpty()
        val season = resolve?.season ?: seasons.singleOrFirstOrNull()
        val episode = resolve?.episode ?: episodes.singleOrFirstOrNull()
        val visualTags = facts.visualTags.mapNotUnknown { it.label }
        val audioTags = facts.audioTags.mapNotUnknown { it.label }
        val audioChannels = facts.audioChannels.mapNotUnknown { it.label }
        val edition = parsed?.edition ?: buildEdition(parsed)
        val matchedBadgeNames = matchedBadges.map { it.name }

        return linkedMapOf(
            "stream.title" to (parsed?.parsedTitle ?: resolve?.title ?: stream.title),
            "stream.year" to parsed?.year,
            "stream.season" to season,
            "stream.episode" to episode,
            "stream.seasons" to seasons,
            "stream.episodes" to episodes,
            "stream.seasonEpisode" to buildSeasonEpisodeList(season, episode, seasons, episodes),
            "stream.formattedEpisodes" to formatEpisodes(episodes),
            "stream.formattedSeasons" to formatSeasons(seasons),
            "stream.resolution" to facts.resolution.labelUnlessUnknown(),
            "stream.library" to false,
            "stream.quality" to facts.quality.labelUnlessUnknown(),
            "stream.visualTags" to visualTags,
            "stream.audioTags" to audioTags,
            "stream.audioChannels" to audioChannels,
            "stream.languages" to languageValues(parsed, facts),
            "stream.languageEmojis" to languageValues(parsed, facts).map { languageEmoji(it) },
            "stream.size" to facts.size?.let(::DebridTemplateBytes),
            "stream.folderSize" to raw?.folderSize?.let(::DebridTemplateBytes),
            "stream.encode" to facts.encode.labelUnlessUnknown(),
            "stream.indexer" to (raw?.indexer ?: raw?.tracker ?: stream.sourceName),
            "stream.network" to (parsed?.network ?: raw?.network),
            "stream.releaseGroup" to facts.releaseGroup.takeIf { it.isNotBlank() },
            "stream.duration" to parsed?.duration,
            "stream.edition" to edition,
            "stream.filename" to (raw?.filename ?: resolve?.filename ?: stream.behaviorHints.filename ?: stream.debridCacheStatus?.cachedName),
            "stream.regexMatched" to matchedBadgeNames,
            "stream.rseMatched" to matchedBadgeNames,
            "stream.type" to streamType(stream, resolve),
            "service.cached" to serviceCached(stream, resolve),
            "service.shortName" to DebridProviders.shortName(serviceId(stream)),
            "service.name" to DebridProviders.displayName(serviceId(stream)),
            "addon.name" to stream.addonName,
        )
    }

    private fun serviceId(stream: StreamItem): String? =
        stream.debridCacheStatus?.providerId ?: stream.clientResolve?.service

    private fun serviceCached(stream: StreamItem, resolve: StreamClientResolve?): Boolean? =
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> true
            StreamDebridCacheState.NOT_CACHED -> false
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.UNKNOWN,
            null -> resolve?.isCached
        }

    private fun streamType(stream: StreamItem, resolve: StreamClientResolve?): String =
        when {
            stream.debridCacheStatus != null -> "Debrid"
            resolve?.type.equals("debrid", ignoreCase = true) -> "Debrid"
            resolve?.type.equals("torrent", ignoreCase = true) -> "p2p"
            else -> resolve?.type.orEmpty()
        }

    private fun buildEdition(parsed: StreamClientResolveParsed?): String? {
        if (parsed == null) return null
        return buildList {
            if (parsed.extended == true) add("extended")
            if (parsed.theatrical == true) add("theatrical")
            if (parsed.remastered == true) add("remastered")
            if (parsed.unrated == true) add("unrated")
        }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun buildSeasonEpisodeList(
        season: Int?,
        episode: Int?,
        seasons: List<Int>,
        episodes: List<Int>,
    ): List<String> {
        if (season != null && episode != null) return listOf("S${season.twoDigits()}E${episode.twoDigits()}")
        if (seasons.isEmpty() || episodes.isEmpty()) return emptyList()
        return seasons.flatMap { s -> episodes.map { e -> "S${s.twoDigits()}E${e.twoDigits()}" } }
    }

    private fun formatEpisodes(episodes: List<Int>): String =
        episodes.joinToString(" | ") { "E${it.twoDigits()}" }

    private fun formatSeasons(seasons: List<Int>): String =
        seasons.joinToString(" | ") { "S${it.twoDigits()}" }

    private fun List<Int>.singleOrFirstOrNull(): Int? =
        singleOrNull() ?: firstOrNull()

    private fun Int.twoDigits(): String = toString().padStart(2, '0')

    private fun languageValues(parsed: StreamClientResolveParsed?, facts: DebridStreamFacts): List<String> =
        parsed?.languages.orEmpty().ifEmpty { facts.languages.map { it.code } }

    private fun languageEmoji(language: String): String =
        when (language.lowercase()) {
            "en", "eng", "english" -> "GB"
            "hi", "hin", "hindi" -> "IN"
            "ml", "mal", "malayalam" -> "IN"
            "ta", "tam", "tamil" -> "IN"
            "te", "tel", "telugu" -> "IN"
            "ja", "jpn", "japanese" -> "JP"
            "ko", "kor", "korean" -> "KR"
            "fr", "fre", "fra", "french" -> "FR"
            "es", "spa", "spanish" -> "ES"
            "de", "ger", "deu", "german" -> "DE"
            "it", "ita", "italian" -> "IT"
            "multi" -> "Multi"
            else -> language
        }

    private inline fun <T> List<T>.mapNotUnknown(label: (T) -> String): List<String> =
        map(label).filterNot { it.equals("Unknown", ignoreCase = true) }

    private fun DebridStreamResolution.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamResolution.UNKNOWN }

    private fun DebridStreamQuality.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamQuality.UNKNOWN }

    private fun DebridStreamEncode.labelUnlessUnknown(): String? =
        label.takeUnless { this == DebridStreamEncode.UNKNOWN }
}
