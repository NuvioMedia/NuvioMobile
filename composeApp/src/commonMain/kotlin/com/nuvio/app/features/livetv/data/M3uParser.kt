package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.DrmInfo
import io.ktor.http.decodeURLQueryComponent

/** Raw channel entry as read from a playlist, before numbering and keying. */
data class M3uEntry(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val chno: Int?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?,
    val drm: DrmInfo? = null
)

data class M3uPlaylist(
    val epgUrls: List<String>,
    val entries: List<M3uEntry>
)

/**
 * Streaming M3U / M3U8 (extended) parser. Handles the attributes IPTV providers
 * actually use: tvg-id, tvg-name, tvg-logo, group-title, tvg-chno, catchup*,
 * #EXTGRP, #EXTVLCOPT user agent / referrer and Kodi style "url|User-Agent=..".
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
    private val attrRegexUnquoted = Regex("""([A-Za-z0-9_\-]+)\s*=\s*([^\s",]+)""")

    /** Parses a whole playlist held in memory. */
    fun parse(text: String): M3uPlaylist = parse(text.lineSequence())

    /** Parses a playlist line by line, so a big file needn't be split up front. */
    fun parse(lines: Sequence<String>): M3uPlaylist {
        val epgUrls = mutableListOf<String>()
        val entries = ArrayList<M3uEntry>(4096)

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()
        // Kodi / TiviMate DRM lines ("#KODIPROP:inputstream.adaptive.license_type=…").
        val pendingProps = mutableMapOf<String, String>()
        // Catch-up set once for the whole playlist on its #EXTM3U line (TiviMate supports this):
        // used for every channel that doesn't set its own.
        val headerCatchup = mutableMapOf<String, String>()

        lines.forEach { rawLine ->
            val line = rawLine.trim().removePrefix("﻿")
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    listOf("catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "tvg-rec", "timeshift")
                        .forEach { k -> attrs[k]?.takeIf { it.isNotBlank() }?.let { headerCatchup[k] = it } }
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }
                            ?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    // Header lines (#EXTVLCOPT, #EXTHTTP) can come before or after #EXTINF; DRM
                    // playlists usually put them before. They're cleared once the entry's link
                    // is read, not here.
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val prop = line.substringAfter(':')
                    val key = prop.substringBefore('=').trim().lowercase()
                        .removePrefix("inputstream.adaptive.").removePrefix("inputstream.")
                    pendingProps[key] = prop.substringAfter('=', "").trim()
                }
                // TiviMate-style request headers: #EXTHTTP:{"User-Agent":"…","Referer":"…"}
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> runCatching {
                    val o = parseObject(line.substringAfter(':'))
                    o.keys.forEach { k -> pendingHeaders[normalizeHeader(k)] = o.optString(k) }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        // Request headers from Kodi lines: stream_headers, manifest_headers and
                        // common_headers ("User-Agent=…&Referer=…").
                        listOf("stream_headers", "manifest_headers", "common_headers").forEach { key ->
                            val v = pendingProps[key]
                            if (!v.isNullOrBlank()) v.split('&').forEach { part ->
                                val k = part.substringBefore('=').trim()
                                if (k.isNotEmpty()) pendingHeaders[normalizeHeader(k)] = urlDecode(part.substringAfter('=', ""))
                            }
                        }
                        entries.add(buildEntry(info, pendingGroup, line, pendingHeaders.toMap(), headerCatchup).copy(drm = drmFrom(pendingProps)))
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingHeaders.clear()
                    pendingProps.clear()
                }
            }
        }
        return M3uPlaylist(epgUrls.distinct(), entries)
    }

    /** Like java.net.URLDecoder: "+" is a space; a broken escape leaves the text as it was. */
    private fun urlDecode(s: String): String =
        runCatching { s.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(s)

    /**
     * DRM from "#KODIPROP" lines: license_type (clearkey / com.widevine.alpha /
     * com.microsoft.playready), license_key (URL, possibly "url|Header=…", or ClearKey
     * "kid:key" pairs / JSON), manifest_type.
     */
    private fun drmFrom(props: Map<String, String>): DrmInfo? {
        val type = (props["license_type"] ?: props["drm"])?.lowercase() ?: return null
        val scheme = when {
            "clearkey" in type || "org.w3" in type -> "clearkey"
            "widevine" in type -> "widevine"
            "playready" in type -> "playready"
            else -> return null
        }
        val raw = props["license_key"].orEmpty()
        // "url|Header=value&Header2=value|R{SSM}|" (Kodi): the URL, then optional headers.
        val parts = raw.split('|')
        val license = parts.firstOrNull().orEmpty().trim()
        val headers = LinkedHashMap<String, String>()
        parts.getOrNull(1)?.takeIf { it.contains('=') }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] = urlDecode(h.substringAfter('=', ""))
        }
        // Newer Kodi style: license request headers on their own line.
        props["license_headers"]?.takeIf { it.isNotBlank() }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] = urlDecode(h.substringAfter('=', ""))
        }
        if (license.isBlank() && scheme != "clearkey") return null
        return DrmInfo(
            scheme = scheme,
            license = if (scheme == "clearkey") raw.trim() else license,
            licenseHeaders = headers,
            manifestType = props["manifest_type"]?.lowercase()
        )
    }

    private fun buildEntry(
        info: String,
        extGroup: String?,
        urlLine: String,
        vlcHeaders: Map<String, String>,
        playlistCatchup: Map<String, String> = emptyMap()
    ): M3uEntry {
        // The channel's own attributes win over the playlist-wide catch-up defaults.
        val attrs = playlistCatchup + attributes(info)
        // Display name follows the first comma that is outside quotes.
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel" }

        val headers = LinkedHashMap(vlcHeaders)
        var url = urlLine
        val pipe = urlLine.indexOf('|')
        if (pipe > 0) {
            url = urlLine.substring(0, pipe)
            urlLine.substring(pipe + 1).split('&').forEach { part ->
                val k = part.substringBefore('=').trim()
                val v = urlDecode(part.substringAfter('=', ""))
                if (k.isNotEmpty()) headers[normalizeHeader(k)] = v
            }
        }
        attrs["user-agent"]?.let { headers["User-Agent"] = it }
        attrs["http-user-agent"]?.let { headers["User-Agent"] = it }
        attrs["http-referrer"]?.let { headers["Referer"] = it }

        val catchupType = attrs["catchup"] ?: attrs["catchup-type"]
        val catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()
        val catchup = if (catchupType != null || (catchupDays ?: 0) > 0) {
            CatchupInfo(
                type = (catchupType ?: "default").lowercase(),
                source = attrs["catchup-source"],
                days = catchupDays ?: 1,
                correctionHours = attrs["catchup-correction"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            )
        } else null

        return M3uEntry(
            name = name.trim(),
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            tvgName = attrs["tvg-name"]?.trim()?.ifEmpty { null },
            logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.ifEmpty { null },
            group = (attrs["group-title"]?.trim()?.ifEmpty { null } ?: extGroup)
                ?.split(';')?.firstOrNull()?.trim(),
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull(),
            url = url.trim(),
            headers = headers,
            catchup = catchup
        )
    }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> key
    }

    internal fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        attrRegex.findAll(line).forEach { m -> map[m.groupValues[1].lowercase()] = m.groupValues[2] }
        attrRegexUnquoted.findAll(line).forEach { m ->
            val key = m.groupValues[1].lowercase()
            if (key !in map && key != "extinf") map[key] = m.groupValues[2]
        }
        return map
    }

    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}
