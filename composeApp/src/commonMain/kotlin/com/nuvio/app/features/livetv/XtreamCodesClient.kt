package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpGetText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object XtreamCodesClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class XtreamLiveStream(
        @SerialName("stream_id") val streamId: Int = 0,
        val name: String = "",
        @SerialName("stream_icon") val streamIcon: String? = null,
        @SerialName("epg_channel_id") val epgChannelId: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
    )

    @Serializable
    private data class XtreamEpgResponse(
        @SerialName("epg_listings") val listings: List<XtreamEpgListing> = emptyList(),
    )

    @Serializable
    private data class XtreamEpgListing(
        val id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val start: String? = null,
        val end: String? = null,
        @SerialName("start_timestamp") val startTimestamp: String? = null,
        @SerialName("stop_timestamp") val stopTimestamp: String? = null,
    )

    suspend fun fetchChannels(config: XtreamCodesConfig): List<LiveTvChannel> {
        val baseUrl = normalizeServerUrl(config.serverUrl)
        if (baseUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return emptyList()
        }
        val m3uUrl = buildString {
            append(baseUrl)
            append("/get.php?username=")
            append(config.username.encodeUrl())
            append("&password=")
            append(config.password.encodeUrl())
            append("&type=m3u_plus&output=m3u8")
        }
        val m3u = httpGetText(m3uUrl)
        return M3uParser.parse(
            content = m3u,
            source = LiveTvSource.Xtream,
            idPrefix = "xtream",
        ).map { channel ->
            val streamId = channel.streamUrl
                .substringAfterLast('/')
                .substringBefore('.')
                .takeIf { it.all(Char::isDigit) }
            channel.copy(
                id = "xtream:${streamId ?: channel.id.removePrefix("xtream:")}",
                sourceStreamId = streamId,
            )
        }
    }

    suspend fun fetchEpgForChannel(
        config: XtreamCodesConfig,
        channel: LiveTvChannel,
        limit: Int = 8,
    ): List<EpgProgram> {
        val baseUrl = normalizeServerUrl(config.serverUrl)
        val streamId = channel.sourceStreamId ?: return emptyList()
        val url = buildString {
            append(baseUrl)
            append("/player_api.php?username=")
            append(config.username.encodeUrl())
            append("&password=")
            append(config.password.encodeUrl())
            append("&action=get_short_epg&stream_id=")
            append(streamId)
            append("&limit=")
            append(limit)
        }
        val responseText = httpGetText(url)
        val response = json.decodeFromString<XtreamEpgResponse>(responseText)
        return response.listings.mapNotNull { listing ->
            val startMs = listing.startTimestamp?.toLongOrNull()?.times(1000)
                ?: listing.start?.toLongOrNull()?.times(1000)
            val endMs = listing.stopTimestamp?.toLongOrNull()?.times(1000)
                ?: listing.end?.toLongOrNull()?.times(1000)
            if (startMs == null || endMs == null) return@mapNotNull null
            EpgProgram(
                id = listing.id ?: "${channel.id}-$startMs",
                channelId = channel.id,
                title = decodeBase64IfNeeded(listing.title).ifBlank { "Program" },
                description = decodeBase64IfNeeded(listing.description.orEmpty()).ifBlank { null },
                startMs = startMs,
                endMs = endMs,
            )
        }.sortedBy { it.startMs }
    }

    suspend fun validateConfig(config: XtreamCodesConfig): Boolean {
        val baseUrl = normalizeServerUrl(config.serverUrl)
        if (baseUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return false
        }
        val url = buildString {
            append(baseUrl)
            append("/player_api.php?username=")
            append(config.username.encodeUrl())
            append("&password=")
            append(config.password.encodeUrl())
        }
        return runCatching {
            httpGetText(url).contains("user_info", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun normalizeServerUrl(serverUrl: String): String =
        serverUrl.trim().trimEnd('/')

    private fun String.encodeUrl(): String =
        replace(" ", "%20")

    private fun decodeBase64IfNeeded(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching {
            val decoded = decodeBase64(raw)
            if (decoded.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) raw else decoded
        }.getOrDefault(raw)
    }

    private fun decodeBase64(input: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val cleaned = input.trimEnd('=')
        var buffer = 0
        var bits = 0
        val output = StringBuilder()
        cleaned.forEach { char ->
            val value = alphabet.indexOf(char)
            if (value < 0) return input
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.append(((buffer shr bits) and 0xFF).toChar())
            }
        }
        return output.toString()
    }
}
