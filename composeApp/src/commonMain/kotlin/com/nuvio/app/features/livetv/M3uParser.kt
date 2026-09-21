package com.nuvio.app.features.livetv

internal object M3uParser {
    fun parse(
        content: String,
        source: LiveTvSource,
        idPrefix: String,
    ): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("#EXTINF:", ignoreCase = true)) {
                index++
                continue
            }
            val attributes = parseExtInfAttributes(line)
            val name = attributes.name ?: line.substringAfterLast(',').trim().ifBlank { "Channel" }
            index++
            val streamUrl = lines.getOrNull(index)?.takeIf { !it.startsWith("#") } ?: run {
                index++
                continue
            }
            val tvgId = attributes.tvgId
            val channelId = buildString {
                append(idPrefix)
                append(':')
                append(tvgId?.takeIf { it.isNotBlank() } ?: streamUrl.hashCode().toString())
            }
            channels += LiveTvChannel(
                id = channelId,
                name = name,
                streamUrl = streamUrl,
                logoUrl = attributes.tvgLogo,
                group = attributes.groupTitle,
                epgChannelId = tvgId,
                source = source,
                sourceStreamId = tvgId,
            )
            index++
        }
        return channels
    }

    private data class ExtInfAttributes(
        val tvgId: String? = null,
        val tvgLogo: String? = null,
        val groupTitle: String? = null,
        val name: String? = null,
    )

    private fun parseExtInfAttributes(line: String): ExtInfAttributes {
        val attributeSection = line.substringBeforeLast(',').substringAfter(":", missingDelimiterValue = line)
        val displayName = line.substringAfterLast(',').trim().takeIf { it.isNotBlank() }
        fun extract(key: String): String? {
            val pattern = Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE)
            return pattern.find(attributeSection)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return ExtInfAttributes(
            tvgId = extract("tvg-id"),
            tvgLogo = extract("tvg-logo"),
            groupTitle = extract("group-title"),
            name = displayName,
        )
    }
}
