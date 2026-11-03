package com.nuvio.app.features.livetv

internal data class ParsedM3uPlaylist(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>,
)

internal fun parseM3uPlaylist(content: String): List<LiveTvChannel> =
    parseM3uPlaylistData(content).channels

internal fun parseM3uPlaylistData(content: String): ParsedM3uPlaylist {
    val channels = mutableListOf<LiveTvChannel>()
    val epgUrls = linkedSetOf<String>()
    var metadata: ParsedM3uMetadata? = null
    var pendingHeaders = emptyMap<String, String>()

    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trim().trimStart('\uFEFF').trimStart()
        when {
            line.isBlank() -> Unit

            line.isM3uDirective("#EXTM3U") -> {
                parseM3uAttributes(line.removeM3uDirective("#EXTM3U"))
                    .filter { (name) -> name in EPG_URL_ATTRIBUTES }
                    .flatMap { (_, value) -> splitXmlTvUrls(value) }
                    .forEach(epgUrls::add)
            }

            line.isM3uDirective("#EXTINF") -> {
                metadata = parseExtInf(line.removeM3uDirective("#EXTINF"))
            }

            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("User-Agent" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("Referer" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + parseExtHttpHeaders(line.substringAfter(':'))
            }

            line.startsWith('#') -> Unit

            else -> {
                val parsedUrl = parseStreamUrl(line)
                if (parsedUrl == null) {
                    metadata = null
                    pendingHeaders = emptyMap()
                    return@forEach
                }
                val channelNumber = channels.size + 1
                val current = metadata ?: ParsedM3uMetadata(name = "Kanal $channelNumber")
                channels += LiveTvChannel(
                    id = parsedUrl.url,
                    name = current.name.ifBlank { "Kanal $channelNumber" },
                    streamUrl = parsedUrl.url,
                    tvgId = current.tvgId,
                    logoUrl = current.logoUrl,
                    group = current.group,
                    headers = defaultM3uStreamHeaders(parsedUrl.url) + pendingHeaders + parsedUrl.headers,
                    streamType = parsedUrl.url.inferM3uStreamType(),
                )
                metadata = null
                pendingHeaders = emptyMap()
            }
        }
    }

    return ParsedM3uPlaylist(
        channels = channels
            .distinctBy(LiveTvChannel::streamUrl)
            .filterNot { isLikelyCategoryHeading(it.name) },
        epgUrls = epgUrls.toList(),
    )
}

private data class ParsedM3uMetadata(
    val name: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String = "",
)

private data class ParsedStreamUrl(
    val url: String,
    val headers: Map<String, String>,
)

private data class M3uAttribute(
    val name: String,
    val value: String,
)

private val EPG_URL_ATTRIBUTES = setOf("x-tvg-url", "url-tvg", "tvg-url")
private val STREAM_URL_REGEX = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:\S+$""")
private val HTTP_URL_REGEX = Regex("""(?i)^https?://[^\s,;]+$""")

private fun parseExtInf(value: String): ParsedM3uMetadata {
    val separatorIndex = value.indexOfUnquoted(',')
    val attributeText = if (separatorIndex >= 0) value.substring(0, separatorIndex) else value
    val attributes = parseM3uAttributes(attributeText).associate { it.name to it.value }
    val displayName = if (separatorIndex >= 0) {
        value.substring(separatorIndex + 1).trim().unquoteM3uValue()
    } else {
        ""
    }

    return ParsedM3uMetadata(
        name = displayName.ifBlank { attributes["tvg-name"].orEmpty() },
        tvgId = attributes["tvg-id"]?.takeIf(String::isNotBlank),
        logoUrl = attributes["tvg-logo"]?.takeIf(String::isNotBlank),
        group = attributes["group-title"].orEmpty(),
    )
}

private fun parseM3uAttributes(value: String): List<M3uAttribute> {
    val attributes = mutableListOf<M3uAttribute>()
    var index = 0
    while (index < value.length) {
        while (index < value.length && value[index].isWhitespace()) index++
        val nameStart = index
        while (index < value.length && (value[index].isLetterOrDigit() || value[index] == '-' || value[index] == '_')) {
            index++
        }
        if (nameStart == index) {
            index++
            continue
        }

        val name = value.substring(nameStart, index)
        val nameEnd = index
        while (index < value.length && value[index].isWhitespace()) index++
        if (index >= value.length || value[index] != '=') {
            index = nameEnd
            while (index < value.length && !value[index].isWhitespace()) index++
            continue
        }

        index++
        while (index < value.length && value[index].isWhitespace()) index++
        val attributeValue = if (index < value.length && (value[index] == '"' || value[index] == '\'')) {
            val quote = value[index++]
            val valueStart = index
            while (index < value.length && (value[index] != quote || value.isEscapedAt(index))) index++
            value.substring(valueStart, index).also {
                if (index < value.length) index++
            }
        } else {
            val valueStart = index
            while (index < value.length) {
                if (value[index].isWhitespace()) {
                    val nextToken = value.nextNonWhitespaceIndex(index)
                    if (nextToken >= value.length || value.startsWithM3uAttributeAt(nextToken)) break
                }
                index++
            }
            value.substring(valueStart, index).trim()
        }
        attributes += M3uAttribute(name.lowercase(), attributeValue.trim())
    }
    return attributes
}

private fun splitXmlTvUrls(value: String): List<String> =
    value.split(Regex("""[,;\s]+"""))
        .map(String::trim)
        .filter(String::isValidXmlTvUrl)

private fun parseStreamUrl(line: String): ParsedStreamUrl? {
    val url = line.substringBefore('|').trim()
    if (!STREAM_URL_REGEX.matches(url)) return null
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        if (!url.hasHttpAuthority()) return null
    }
    val headers = line.substringAfter('|', "")
        .split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
    return ParsedStreamUrl(url = url, headers = headers)
}

private fun String.isValidXmlTvUrl(): Boolean =
    HTTP_URL_REGEX.matches(this) && hasHttpAuthority()

private fun String.hasHttpAuthority(): Boolean =
    substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').isNotBlank()

private fun parseExtHttpHeaders(value: String): Map<String, String> {
    val trimmed = value.trim().removePrefix("{").removeSuffix("}")
    return trimmed.split(',')
        .mapNotNull { entry ->
            val key = entry.substringBefore(':').trim().trim('"')
            val headerValue = entry.substringAfter(':', "").trim().trim('"')
            if (key.isBlank() || headerValue.isBlank()) null else key to headerValue
        }
        .toMap()
}

private fun String.isM3uDirective(directive: String): Boolean =
    startsWith(directive, ignoreCase = true) &&
        (length == directive.length || this[directive.length].isWhitespace() || this[directive.length] == ':')

private fun String.removeM3uDirective(directive: String): String =
    substring(directive.length).trimStart().removePrefix(":").trimStart()

private fun String.indexOfUnquoted(target: Char): Int {
    var quote: Char? = null
    forEachIndexed { index, character ->
        if ((character == '"' || character == '\'') && !isEscapedAt(index)) {
            quote = if (quote == character) null else if (quote == null) character else quote
        } else if (character == target && quote == null) {
            return index
        }
    }
    return -1
}

private fun String.isEscapedAt(index: Int): Boolean {
    var backslashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        backslashCount++
        cursor--
    }
    return backslashCount % 2 == 1
}

private fun String.nextNonWhitespaceIndex(startIndex: Int): Int {
    var index = startIndex
    while (index < length && this[index].isWhitespace()) index++
    return index
}

private fun String.startsWithM3uAttributeAt(startIndex: Int): Boolean {
    var index = startIndex
    while (index < length && (this[index].isLetterOrDigit() || this[index] == '-' || this[index] == '_')) index++
    if (index == startIndex) return false
    index = nextNonWhitespaceIndex(index)
    return index < length && this[index] == '='
}

private fun String.unquoteM3uValue(): String =
    if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
        substring(1, lastIndex).trim()
    } else {
        this
    }
