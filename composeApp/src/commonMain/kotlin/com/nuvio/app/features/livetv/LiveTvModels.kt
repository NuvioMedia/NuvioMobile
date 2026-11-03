package com.nuvio.app.features.livetv

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
)

data class LiveTvRecentChannel(
    val streamUrl: String,
    val name: String,
    val logoUrl: String? = null,
    val group: String = "",
    val tvgId: String? = null,
)

data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val stopEpochMs: Long,
    val timeLabel: String,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val episode: String? = null,
    val iconUrl: String? = null,
    val rating: String? = null,
    val credits: List<LiveTvProgrammeCredit> = emptyList(),
    val date: String? = null,
    val country: String? = null,
    val language: String? = null,
    val isNew: Boolean = false,
    val isPremiere: Boolean = false,
    val isPreviouslyShown: Boolean = false,
)

data class LiveTvProgrammeCredit(
    val role: String,
    val name: String,
)

data class LiveTvEpgCacheEntry(
    val url: String,
    val content: String,
    val savedAtEpochMs: Long,
)

data class LiveTvUiState(
    val sourceType: LiveTvSourceType = LiveTvSourceType.M3u,
    val sourceUrl: String = "",
    val stalkerSettings: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtreamSettings: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val channels: List<LiveTvChannel> = emptyList(),
    val currentProgrammes: Map<String, LiveTvProgramme> = emptyMap(),
    val programmesByChannel: Map<String, List<LiveTvProgramme>> = emptyMap(),
    val recentChannel: LiveTvRecentChannel? = null,
    val favoriteUrls: Set<String> = emptySet(),
    val isEpgLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val errorMessage: String? = null,
)

enum class LiveTvSourceType {
    M3u,
    Stalker,
    Xtream,
}

data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

sealed interface LiveTvIncomingSource {
    data class SourceUrl(val url: String) : LiveTvIncomingSource

    data class PlaylistData(
        val fileName: String,
        val data: String,
    ) : LiveTvIncomingSource

    data class DirectStream(
        val url: String,
        val title: String = "Shared stream",
        val headers: Map<String, String> = emptyMap(),
    ) : LiveTvIncomingSource

    data class Magnet(
        val magnetUri: String,
        val infoHash: String,
        val trackers: List<String> = emptyList(),
    ) : LiveTvIncomingSource
}

object LiveTvIncomingSourceRepository {
    private val requestChannel = Channel<LiveTvIncomingSource>(capacity = Channel.BUFFERED)
    val requests: Flow<LiveTvIncomingSource> = requestChannel.receiveAsFlow()

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) {
            requestChannel.trySend(LiveTvIncomingSource.PlaylistData(fileName = "Shared M3U playlist", data = trimmed))
            return
        }
        val url = firstSharedUrl(trimmed) ?: return
        when {
            url.startsWith("magnet:", ignoreCase = true) -> parseMagnet(url)?.let(requestChannel::trySend)
            url.looksLikePlaylistUrl() -> requestChannel.trySend(LiveTvIncomingSource.SourceUrl(url))
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                requestChannel.trySend(
                    LiveTvIncomingSource.DirectStream(
                        url = url,
                        title = url.substringBefore('?').substringAfterLast('/').ifBlank { "Shared stream" },
                    ),
                )
            }
        }
    }

    fun submitPlaylistData(fileName: String, data: String) {
        val trimmed = data.trim()
        if (trimmed.isBlank()) return
        requestChannel.trySend(
            LiveTvIncomingSource.PlaylistData(
                fileName = fileName.trim().ifBlank { "Shared M3U playlist" },
                data = trimmed,
            ),
        )
    }
}

private val sharedUrlRegex = Regex("""(?i)(magnet:\?xt=urn:btih:[^\s"'<>]+|https?://[^\s"'<>]+)""")

private fun firstSharedUrl(value: String): String? =
    sharedUrlRegex.find(value)?.value?.trim()?.trimEnd(',', '.', ')', ']')

private fun String.looksLikePlaylistUrl(): Boolean {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    return normalized.endsWith(".m3u") || normalized.endsWith(".m3u8")
}

private fun parseMagnet(url: String): LiveTvIncomingSource.Magnet? {
    val infoHash = Regex("""(?i)(?:xt=urn:btih:)([a-f0-9]{32,40})""")
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()
        ?: return null
    val trackers = Regex("""(?:^|[?&])tr=([^&]+)""")
        .findAll(url)
        .map { it.groupValues[1].replace("+", "%20") }
        .toList()
    return LiveTvIncomingSource.Magnet(
        magnetUri = url,
        infoHash = infoHash,
        trackers = trackers,
    )
}
