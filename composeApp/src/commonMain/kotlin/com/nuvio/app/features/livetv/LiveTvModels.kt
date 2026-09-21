package com.nuvio.app.features.livetv

import kotlinx.serialization.Serializable

@Serializable
data class XtreamCodesConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = false,
)

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val epgChannelId: String? = null,
    val source: LiveTvSource,
    val sourceStreamId: String? = null,
)

enum class LiveTvSource {
    Pluto,
    Xtream,
}

data class EpgProgram(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String? = null,
    val startMs: Long,
    val endMs: Long,
)

data class LiveTvUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val channels: List<LiveTvChannel> = emptyList(),
    val epgByChannelId: Map<String, List<EpgProgram>> = emptyMap(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val guideWindowStartMs: Long = 0L,
    val guideWindowEndMs: Long = 0L,
    val nowMs: Long = 0L,
    val plutoEnabled: Boolean = true,
    val xtreamEnabled: Boolean = false,
) {
    val sortedChannels: List<LiveTvChannel>
        get() {
            if (favoriteChannelIds.isEmpty()) return channels
            val favorites = channels.filter { it.id in favoriteChannelIds }
            val others = channels.filter { it.id !in favoriteChannelIds }
            return favorites + others
        }

    val hasFavorites: Boolean
        get() = favoriteChannelIds.isNotEmpty()
}
