package com.nuvio.app.features.livetv

internal object XmlTvParser {
    fun parse(content: String, channelIdByEpgId: Map<String, String>): Map<String, List<EpgProgram>> {
        val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()
        val programmeOpen = "<programme"
        var searchIndex = 0
        while (true) {
            val start = content.indexOf(programmeOpen, searchIndex, ignoreCase = true)
            if (start < 0) break
            val tagEnd = content.indexOf('>', start)
            if (tagEnd < 0) break
            val close = content.indexOf("</programme>", tagEnd, ignoreCase = true)
            if (close < 0) break
            val attributes = content.substring(start, tagEnd)
            val body = content.substring(tagEnd + 1, close)
            val attributeMap = parseAttributes(attributes)
            val epgChannelId = attributeMap["channel"]?.trim().orEmpty()
            val channelId = channelIdByEpgId[epgChannelId] ?: run {
                searchIndex = close + "</programme>".length
                continue
            }
            val startMs = parseXmlTvDate(attributeMap["start"]) ?: run {
                searchIndex = close + "</programme>".length
                continue
            }
            val endMs = parseXmlTvDate(attributeMap["stop"]) ?: run {
                searchIndex = close + "</programme>".length
                continue
            }
            val title = extractTag(body, "title")?.trim().orEmpty().ifBlank { "Program" }
            val description = extractTag(body, "desc")?.trim()
            programsByChannel.getOrPut(channelId) { mutableListOf() } += EpgProgram(
                id = "$channelId-$startMs",
                channelId = channelId,
                title = title,
                description = description,
                startMs = startMs,
                endMs = endMs,
            )
            searchIndex = close + "</programme>".length
        }
        return programsByChannel.mapValues { (_, programs) ->
            programs.sortedBy { it.startMs }
        }
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val regex = Regex("""(\w+)="([^"]*)"""")
        return regex.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun extractTag(body: String, tag: String): String? {
        val open = body.indexOf("<$tag", ignoreCase = true)
        if (open < 0) return null
        val contentStart = body.indexOf('>', open)
        if (contentStart < 0) return null
        val close = body.indexOf("</$tag>", contentStart, ignoreCase = true)
        if (close < 0) return null
        return decodeXmlEntities(body.substring(contentStart + 1, close))
    }

    private fun decodeXmlEntities(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    private fun parseXmlTvDate(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.length < 14) return null
        val year = raw.substring(0, 4).toIntOrNull() ?: return null
        val month = raw.substring(4, 6).toIntOrNull() ?: return null
        val day = raw.substring(6, 8).toIntOrNull() ?: return null
        val hour = raw.substring(8, 10).toIntOrNull() ?: return null
        val minute = raw.substring(10, 12).toIntOrNull() ?: return null
        val second = raw.substring(12, 14).toIntOrNull() ?: 0
        return LiveTvTime.epochMs(year, month, day, hour, minute, second)
    }
}
