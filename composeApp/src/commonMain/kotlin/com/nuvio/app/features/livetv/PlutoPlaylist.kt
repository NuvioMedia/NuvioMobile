package com.nuvio.app.features.livetv

internal object PlutoPlaylist {
    private data class PlutoChannelDefinition(
        val id: String,
        val name: String,
        val group: String,
    )

    private val channels = listOf(
        PlutoChannelDefinition("5f8f1a0a7f23ff000621e3f2", "Pluto TV Movies", "Movies"),
        PlutoChannelDefinition("5f8f1a1b7f23ff000621e3f4", "Pluto TV Spotlight", "Featured"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e0b", "Pluto TV Comedy", "Comedy"),
        PlutoChannelDefinition("5f8f1f99d9b39700071f6e08", "Pluto TV Drama", "Drama"),
        PlutoChannelDefinition("5f8f1f99d9b39700071f6e07", "Pluto TV Action", "Action"),
        PlutoChannelDefinition("5f8f1f99d9b39700071f6e09", "Pluto TV Thrillers", "Thriller"),
        PlutoChannelDefinition("5f8f1f99d9b39700071f6e0a", "Pluto TV Horror", "Horror"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e0c", "Pluto TV Sci-Fi", "Sci-Fi"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e0d", "Pluto TV Documentaries", "Documentary"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e0e", "Pluto TV Crime", "Crime"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e0f", "Pluto TV Reality", "Reality"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e10", "Pluto TV Kids", "Kids"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e11", "Nick Pluto TV", "Kids"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e12", "Pluto TV News", "News"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e13", "CBS News", "News"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e14", "Pluto TV Sports", "Sports"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e15", "Pluto TV Classic TV", "Classic TV"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e16", "Pluto TV Westerns", "Westerns"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e17", "Pluto TV Anime", "Anime"),
        PlutoChannelDefinition("5f8f2149d9b39700071f6e18", "Pluto TV Music", "Music"),
    )

    fun channels(nowMs: Long): List<LiveTvChannel> =
        channels.map { definition ->
            LiveTvChannel(
                id = "pluto:${definition.id}",
                name = definition.name,
                streamUrl = streamUrl(definition.id),
                logoUrl = "https://images.pluto.tv/channels/${definition.id}/colorLogoPNG.png",
                group = definition.group,
                epgChannelId = definition.id,
                source = LiveTvSource.Pluto,
                sourceStreamId = definition.id,
            )
        }

    fun placeholderEpg(
        channels: List<LiveTvChannel>,
        windowStartMs: Long,
        windowEndMs: Long,
        nowMs: Long,
    ): Map<String, List<EpgProgram>> =
        channels.associate { channel ->
            val startMs = maxOf(windowStartMs, nowMs - 30 * 60_000L)
            val endMs = minOf(windowEndMs, startMs + 2 * 60 * 60_000L)
            channel.id to listOf(
                EpgProgram(
                    id = "${channel.id}-live",
                    channelId = channel.id,
                    title = "Live on ${channel.name}",
                    description = "Streaming now on Pluto TV",
                    startMs = startMs,
                    endMs = endMs,
                ),
            )
        }

    private fun streamUrl(channelId: String): String =
        "https://service-stitcher.clusters.pluto.tv/stitch/hls/channel/$channelId/master.m3u8" +
            "?deviceType=web&deviceMake=Chrome&deviceModel=Chrome&appName=web&appVersion=unknown"
}
