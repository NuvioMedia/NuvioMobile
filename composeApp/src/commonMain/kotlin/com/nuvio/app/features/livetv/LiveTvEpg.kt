package com.nuvio.app.features.livetv

private const val XMLTV_PROGRAMME_OPEN = "<programme"
private const val XMLTV_PROGRAMME_CLOSE = "</programme>"
private const val XMLTV_CHANNEL_OPEN = "<channel"
private const val XMLTV_CHANNEL_CLOSE = "</channel>"
private const val XMLTV_TITLE_OPEN = "<title"
private const val XMLTV_TITLE_CLOSE = "</title>"
private const val MAX_XMLTV_CHANNEL_HEADER_CHARS = 16 * 1024 * 1024
private const val MAX_XMLTV_CHANNELS = 100_000
private const val MAX_XMLTV_ALIASES_PER_CHANNEL = 32
private const val MAX_XMLTV_CHANNEL_BLOCK_CHARS = 64 * 1024
private const val MAX_RETAINED_PROGRAMMES_PER_CHANNEL = 512
private const val MAX_FUTURE_SCHEDULE_DAYS = 7L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

internal data class XmlTvChannelAliases(
    val id: String,
    val displayNames: Set<String>,
)

internal fun extractXmlTvChannelAliases(
    content: String,
    onProgress: () -> Unit = {},
): List<XmlTvChannelAliases> {
    val boundedHeaderEnd = minOf(content.length, MAX_XMLTV_CHANNEL_HEADER_CHARS)
    val programmeStart = content.findXmlTvOpeningTag("programme", 0, boundedHeaderEnd)
    val headerEnd = programmeStart.takeIf { it >= 0 } ?: boundedHeaderEnd
    val channels = mutableListOf<XmlTvChannelAliases>()
    var searchIndex = 0

    while (searchIndex < headerEnd && channels.size < MAX_XMLTV_CHANNELS) {
        if (channels.size % 64 == 0) onProgress()
        val channelStart = content.findXmlTvOpeningTag("channel", searchIndex, headerEnd)
        if (channelStart < 0) break
        val tagEnd = content.indexOf('>', channelStart + XMLTV_CHANNEL_OPEN.length)
        if (tagEnd < 0 || tagEnd >= headerEnd) break
        val channelEnd = content.indexOf(XMLTV_CHANNEL_CLOSE, tagEnd + 1, ignoreCase = true)
        if (channelEnd < 0 || channelEnd > headerEnd) break
        searchIndex = channelEnd + XMLTV_CHANNEL_CLOSE.length
        if (channelEnd - channelStart > MAX_XMLTV_CHANNEL_BLOCK_CHARS) continue

        val id = content.substring(channelStart + XMLTV_CHANNEL_OPEN.length, tagEnd)
            .xmlTvAttribute("id")
            ?.decodeFullXmlTvEntities()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: continue
        val displayNames = content.substring(tagEnd + 1, channelEnd)
            .xmlTvElementTexts("display-name", 0, channelEnd - tagEnd - 1)
            .take(MAX_XMLTV_ALIASES_PER_CHANNEL)
            .toSet()
        channels += XmlTvChannelAliases(id = id, displayNames = displayNames)
    }

    return channels
}

internal fun resolveXmlTvChannelIds(
    channels: List<LiveTvChannel>,
    providerChannels: List<XmlTvChannelAliases>,
): List<LiveTvChannel> {
    val exactIds = providerChannels.map(XmlTvChannelAliases::id).toSet()
    val caseInsensitiveIds = providerChannels.uniqueIdsBy { it.id.lowercase() }
    val exactAliases = providerChannels.uniqueIdsByAliases { alias -> alias.normalizedXmlTvName() }
    val conservativeAliases = providerChannels.uniqueIdsByAliases { alias -> alias.conservativeXmlTvName() }

    return channels.map { channel ->
        val playlistId = channel.tvgId?.trim().orEmpty()
        val providerId = playlistId.takeIf(exactIds::contains)
            ?: caseInsensitiveIds[playlistId.lowercase()]
            ?: exactAliases[channel.name.normalizedXmlTvName()]
            ?: conservativeAliases[channel.name.conservativeXmlTvName()]
        if (providerId == null || providerId == channel.tvgId) channel else channel.copy(tvgId = providerId)
    }
}

private fun List<XmlTvChannelAliases>.uniqueIdsBy(
    keySelector: (XmlTvChannelAliases) -> String,
): Map<String, String> {
    val ids = mutableMapOf<String, MutableSet<String>>()
    forEach { channel ->
        keySelector(channel).takeIf(String::isNotBlank)?.let { key ->
            ids.getOrPut(key) { mutableSetOf() }.add(channel.id)
        }
    }
    return ids.mapNotNull { (key, values) -> values.singleOrNull()?.let { key to it } }.toMap()
}

private fun List<XmlTvChannelAliases>.uniqueIdsByAliases(
    keySelector: (String) -> String,
): Map<String, String> {
    val aliases = mutableMapOf<String, MutableSet<String>>()
    forEach { channel ->
        channel.displayNames.forEach { alias ->
            keySelector(alias).takeIf(String::isNotBlank)?.let { key ->
                aliases.getOrPut(key) { mutableSetOf() }.add(channel.id)
            }
        }
    }
    return aliases.mapNotNull { (key, values) -> values.singleOrNull()?.let { key to it } }.toMap()
}

private fun String.normalizedXmlTvName(): String =
    decodeFullXmlTvEntities()
        .lowercase()
        .replace(XmlTvWhitespaceRegex, " ")
        .trim()

private fun String.conservativeXmlTvName(): String {
    val tokens = normalizedXmlTvName()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)
        .toMutableList()
    while (tokens.lastOrNull() in XMLTV_QUALITY_SUFFIXES) tokens.removeAt(tokens.lastIndex)
    return tokens.joinToString(separator = "")
}

private val XMLTV_QUALITY_SUFFIXES = setOf("sd", "hd", "fhd", "uhd", "2k", "4k", "8k")

/**
 * Keeps current programmes for playlist channels and future programmes only for favorites.
 */
internal fun parseXmlTvProgrammeSchedule(
    content: String,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
    relevantChannelIds: Set<String>? = null,
    retainedScheduleChannelIds: Set<String>? = null,
    onProgress: () -> Unit = {},
): Map<String, List<LiveTvProgramme>> {
    val liveState = LiveTvRepository.uiState.value
    val loadedChannelIds = liveState.channels
        .mapNotNull(LiveTvChannel::tvgId)
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    val inferredRelevantChannelIds = if (liveState.channels.isEmpty()) null else loadedChannelIds
    val favoriteChannelIds = liveState.channels
        .asSequence()
        .filter { channel -> channel.streamUrl in liveState.favoriteUrls }
        .mapNotNull(LiveTvChannel::tvgId)
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    val relevantIds = relevantChannelIds ?: inferredRelevantChannelIds
    val futureIds = retainedScheduleChannelIds ?: favoriteChannelIds
    val futureLimitEpochMs = nowEpochMs + MAX_FUTURE_SCHEDULE_DAYS * MILLIS_PER_DAY
    val programmes = mutableMapOf<String, MutableList<LiveTvProgramme>>()
    var searchIndex = 0
    var parsedEntries = 0

    while (true) {
        if (parsedEntries % 64 == 0) onProgress()
        val programmeStart = content.nextXmlTvProgrammeStart(searchIndex)
        if (programmeStart < 0) break
        val tagEnd = content.indexOf('>', startIndex = programmeStart + XMLTV_PROGRAMME_OPEN.length)
        if (tagEnd < 0) break
        val programmeEnd = content.indexOf(
            string = XMLTV_PROGRAMME_CLOSE,
            startIndex = tagEnd + 1,
            ignoreCase = true,
        )
        if (programmeEnd < 0) break
        searchIndex = programmeEnd + XMLTV_PROGRAMME_CLOSE.length
        parsedEntries += 1

        val attributes = content.substring(programmeStart + XMLTV_PROGRAMME_OPEN.length, tagEnd)
        val channelId = attributes.xmlTvAttribute("channel")
            ?.decodeFullXmlTvEntities()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: continue
        if (relevantIds != null && channelId !in relevantIds) continue

        val rawStart = attributes.xmlTvAttribute("start").orEmpty()
        val rawStop = attributes.xmlTvAttribute("stop").orEmpty()
        val startEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStart) ?: continue
        val stopEpochMs = LiveTvClock.parseXmlTvTimestamp(rawStop) ?: continue
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) continue

        val isCurrent = nowEpochMs in startEpochMs until stopEpochMs
        if (!isCurrent && (channelId !in futureIds || startEpochMs > futureLimitEpochMs)) continue

        val channelProgrammes = programmes.getOrPut(channelId) { mutableListOf() }
        if (channelProgrammes.size >= MAX_RETAINED_PROGRAMMES_PER_CHANNEL) continue

        val titleStart = content.indexOf(
            string = XMLTV_TITLE_OPEN,
            startIndex = tagEnd + 1,
            ignoreCase = true,
        )
        if (titleStart < 0 || titleStart >= programmeEnd) continue
        val titleTagEnd = content.indexOf('>', startIndex = titleStart + XMLTV_TITLE_OPEN.length)
        if (titleTagEnd < 0 || titleTagEnd >= programmeEnd) continue
        val titleEnd = content.indexOf(
            string = XMLTV_TITLE_CLOSE,
            startIndex = titleTagEnd + 1,
            ignoreCase = true,
        )
        if (titleEnd < 0 || titleEnd > programmeEnd) continue
        val title = content.substring(titleTagEnd + 1, titleEnd)
            .toXmlTvPlainText()
            .takeIf(String::isNotBlank)
            ?: continue

        val contentStart = tagEnd + 1
        val programmeContent = content.substring(contentStart, programmeEnd)
        val programmeContentEnd = programmeContent.length
        val subtitle = programmeContent.xmlTvElementText("sub-title", 0, programmeContentEnd)
        val description = programmeContent.xmlTvElementText("desc", 0, programmeContentEnd)
        val categories = programmeContent.xmlTvElementTexts("category", 0, programmeContentEnd)
            .distinct()
        val episodeElement = programmeContent.xmlTvElement("episode-num", 0, programmeContentEnd)
        val episode = episodeElement?.content
            ?.toXmlTvPlainText()
            ?.takeIf(String::isNotBlank)
            ?.let { value -> formatXmlTvEpisode(value, episodeElement.attributes.xmlTvAttribute("system")) }
        val iconUrl = programmeContent.xmlTvOpeningTagAttributes("icon", 0, programmeContentEnd)
            ?.xmlTvAttribute("src")
            ?.decodeFullXmlTvEntities()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val ratingElement = programmeContent.xmlTvElement("rating", 0, programmeContentEnd)
        val rating = ratingElement?.content
            ?.xmlTvElementText("value", 0, ratingElement.content.length)
            ?.let { value ->
                ratingElement.attributes.xmlTvAttribute("system")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { system -> "$value · $system" }
                    ?: value
            }
        val credits = programmeContent.xmlTvCredits(0, programmeContentEnd)
        val date = programmeContent.xmlTvElementText("date", 0, programmeContentEnd)
            ?.let(::formatXmlTvDate)
        val country = programmeContent.xmlTvElementText("country", 0, programmeContentEnd)
        val language = programmeContent.xmlTvElementText("language", 0, programmeContentEnd)

        channelProgrammes += LiveTvProgramme(
            title = title,
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            timeLabel = "${rawStart.fullXmlTvTimePart()} - ${rawStop.fullXmlTvTimePart()}",
            subtitle = subtitle,
            description = description,
            categories = categories,
            episode = episode,
            iconUrl = iconUrl,
            rating = rating,
            credits = credits,
            date = date,
            country = country,
            language = language,
            isNew = programmeContent.hasXmlTvOpeningTag("new", 0, programmeContentEnd),
            isPremiere = programmeContent.hasXmlTvOpeningTag("premiere", 0, programmeContentEnd),
            isPreviouslyShown = programmeContent.hasXmlTvOpeningTag("previously-shown", 0, programmeContentEnd),
        )
    }

    return programmes.mapValues { (_, entries) ->
        entries
            .distinctBy { programme ->
                Triple(programme.startEpochMs, programme.stopEpochMs, programme.title)
            }
            .sortedBy(LiveTvProgramme::startEpochMs)
    }
}

private fun String.nextXmlTvProgrammeStart(startIndex: Int): Int {
    var searchIndex = startIndex
    while (searchIndex < length) {
        val matchIndex = indexOf(XMLTV_PROGRAMME_OPEN, searchIndex, ignoreCase = true)
        if (matchIndex < 0) return -1
        val boundaryIndex = matchIndex + XMLTV_PROGRAMME_OPEN.length
        if (boundaryIndex >= length || this[boundaryIndex].isWhitespace() || this[boundaryIndex] == '>') {
            return matchIndex
        }
        searchIndex = boundaryIndex
    }
    return -1
}

private fun String.xmlTvAttribute(name: String): String? {
    var searchIndex = 0
    while (searchIndex < length) {
        val nameIndex = indexOf(name, searchIndex, ignoreCase = true)
        if (nameIndex < 0) return null
        val nameEnd = nameIndex + name.length
        val startsAtBoundary = nameIndex == 0 || !this[nameIndex - 1].isLetterOrDigit()
        val endsAtBoundary = nameEnd >= length || !this[nameEnd].isLetterOrDigit()
        if (!startsAtBoundary || !endsAtBoundary) {
            searchIndex = nameEnd
            continue
        }

        var cursor = nameEnd
        while (cursor < length && this[cursor].isWhitespace()) cursor += 1
        if (cursor >= length || this[cursor] != '=') {
            searchIndex = nameEnd
            continue
        }
        cursor += 1
        while (cursor < length && this[cursor].isWhitespace()) cursor += 1
        if (cursor >= length || (this[cursor] != '"' && this[cursor] != '\'')) return null
        val quote = this[cursor]
        val valueStart = cursor + 1
        val valueEnd = indexOf(quote, startIndex = valueStart)
        return if (valueEnd >= 0) substring(valueStart, valueEnd) else null
    }
    return null
}

private data class XmlTvElement(
    val attributes: String,
    val content: String,
    val nextIndex: Int,
)

private fun String.xmlTvElement(name: String, startIndex: Int, endIndex: Int): XmlTvElement? {
    val elementStart = findXmlTvOpeningTag(name, startIndex, endIndex)
    if (elementStart < 0) return null
    val tagEnd = indexOf('>', startIndex = elementStart + name.length + 1)
    if (tagEnd < 0 || tagEnd >= endIndex) return null
    val closeTag = "</$name>"
    val contentEnd = indexOf(closeTag, startIndex = tagEnd + 1, ignoreCase = true)
    if (contentEnd < 0 || contentEnd > endIndex) return null
    return XmlTvElement(
        attributes = substring(elementStart + name.length + 1, tagEnd),
        content = substring(tagEnd + 1, contentEnd),
        nextIndex = contentEnd + closeTag.length,
    )
}

private fun String.xmlTvElementText(name: String, startIndex: Int, endIndex: Int): String? =
    xmlTvElement(name, startIndex, endIndex)
        ?.content
        ?.toXmlTvPlainText()
        ?.takeIf(String::isNotBlank)

private fun String.xmlTvElementTexts(name: String, startIndex: Int, endIndex: Int): List<String> {
    val values = mutableListOf<String>()
    var searchIndex = startIndex
    while (searchIndex < endIndex) {
        val element = xmlTvElement(name, searchIndex, endIndex) ?: break
        element.content.toXmlTvPlainText().takeIf(String::isNotBlank)?.let(values::add)
        searchIndex = element.nextIndex
    }
    return values
}

private fun String.xmlTvOpeningTagAttributes(name: String, startIndex: Int, endIndex: Int): String? {
    val elementStart = findXmlTvOpeningTag(name, startIndex, endIndex)
    if (elementStart < 0) return null
    val tagEnd = indexOf('>', startIndex = elementStart + name.length + 1)
    if (tagEnd < 0 || tagEnd >= endIndex) return null
    return substring(elementStart + name.length + 1, tagEnd).removeSuffix("/")
}

private fun String.hasXmlTvOpeningTag(name: String, startIndex: Int, endIndex: Int): Boolean =
    findXmlTvOpeningTag(name, startIndex, endIndex) >= 0

private fun String.findXmlTvOpeningTag(name: String, startIndex: Int, endIndex: Int): Int {
    var searchIndex = startIndex
    val prefix = "<$name"
    while (searchIndex < endIndex) {
        val matchIndex = indexOf(prefix, searchIndex, ignoreCase = true)
        if (matchIndex < 0 || matchIndex >= endIndex) return -1
        val boundaryIndex = matchIndex + prefix.length
        if (
            boundaryIndex < endIndex &&
            (this[boundaryIndex].isWhitespace() || this[boundaryIndex] == '>' || this[boundaryIndex] == '/')
        ) {
            return matchIndex
        }
        searchIndex = boundaryIndex
    }
    return -1
}

private fun String.xmlTvCredits(startIndex: Int, endIndex: Int): List<LiveTvProgrammeCredit> {
    val creditsElement = xmlTvElement("credits", startIndex, endIndex) ?: return emptyList()
    return listOf("director", "actor", "writer", "presenter", "producer", "guest")
        .flatMap { role ->
            creditsElement.content.xmlTvElementTexts(role, 0, creditsElement.content.length)
                .map { name -> LiveTvProgrammeCredit(role = role, name = name) }
        }
}

private fun formatXmlTvEpisode(value: String, system: String?): String {
    if (!system.equals("xmltv_ns", ignoreCase = true)) return value
    val parts = value.split('.')
    val season = parts.getOrNull(0)?.substringBefore('/')?.toIntOrNull()?.plus(1)
    val episode = parts.getOrNull(1)?.substringBefore('/')?.toIntOrNull()?.plus(1)
    return when {
        season != null && episode != null -> "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        season != null -> "Season $season"
        else -> value
    }
}

private fun formatXmlTvDate(value: String): String =
    if (value.length == 8 && value.all(Char::isDigit)) {
        "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
    } else {
        value
    }

internal fun mergeXmlTvProgrammeSchedules(
    schedules: Iterable<Map<String, List<LiveTvProgramme>>>,
): Map<String, List<LiveTvProgramme>> {
    val merged = mutableMapOf<String, MutableList<LiveTvProgramme>>()
    schedules.forEach { schedule ->
        schedule.forEach { (channelId, programmes) ->
            merged.getOrPut(channelId) { mutableListOf() }.addAll(programmes)
        }
    }
    return merged.mapValues { (_, programmes) ->
        programmes
            .distinctBy { programme ->
                Triple(programme.startEpochMs, programme.stopEpochMs, programme.title)
            }
            .sortedBy(LiveTvProgramme::startEpochMs)
    }
}

internal fun currentXmlTvProgrammes(
    programmesByChannel: Map<String, List<LiveTvProgramme>>,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): Map<String, LiveTvProgramme> =
    programmesByChannel.entries.mapNotNull { (channelId, programmes) ->
        programmes
            .firstOrNull { programme -> nowEpochMs in programme.startEpochMs until programme.stopEpochMs }
            ?.let { programme -> channelId to programme }
    }.toMap()

private fun String.fullXmlTvTimePart(): String {
    val digits = takeWhile(Char::isDigit)
    return if (digits.length >= 12) {
        "${digits.substring(8, 10)}:${digits.substring(10, 12)}"
    } else {
        ""
    }
}

private fun String.decodeFullXmlTvEntities(): String =
    replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

private val XmlTvMarkupRegex = Regex("<[^>]+>")
private val XmlTvWhitespaceRegex = Regex("\\s+")

private fun String.toXmlTvPlainText(): String =
    removePrefix("<![CDATA[")
        .removeSuffix("]]>")
        .replace(XmlTvMarkupRegex, " ")
        .decodeFullXmlTvEntities()
        .replace(XmlTvWhitespaceRegex, " ")
        .trim()
