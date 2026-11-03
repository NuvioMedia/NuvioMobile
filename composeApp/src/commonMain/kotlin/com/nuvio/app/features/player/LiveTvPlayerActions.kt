package com.nuvio.app.features.player

import com.nuvio.app.features.livetv.LiveTvChannel

internal fun PlayerScreenRuntime.switchToLiveTvChannel(channel: LiveTvChannel) {
    com.nuvio.app.features.livetv.LiveTvRepository.recordRecentChannel(channel)
    if (channel.streamUrl == activeSourceUrl) {
        showLiveTvChannelsPanel = false
        controlsVisible = true
        return
    }

    activeSourceUrl = channel.streamUrl
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(channel.headers)
    activeSourceResponseHeaders = emptyMap()
    externalSubtitles = emptyList()
    activeStreamType = channel.streamType
    activeSourceIdentityKey = "live-tv:${channel.streamUrl}"
    activeStreamTitle = channel.name
    activeStreamSubtitle = null
    activeStreamFilename = channel.name
    activeSourceFilename = channel.name
    activeStream = null
    currentStreamBingeGroup = null
    currentStreamFilename = null
    currentStreamInfoHash = null
    credentialRefreshAttemptedSourceUrl = null
    errorMessage = null
    activeProviderName = when (com.nuvio.app.features.livetv.LiveTvRepository.uiState.value.sourceType) {
        com.nuvio.app.features.livetv.LiveTvSourceType.M3u -> "M3U"
        com.nuvio.app.features.livetv.LiveTvSourceType.Xtream -> "Xtream"
        com.nuvio.app.features.livetv.LiveTvSourceType.Stalker -> "Stalker"
    }
    activeProviderAddonId = "live-tv"
    activeVideoId = null
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    args = args.copy(
        title = channel.name,
        logo = channel.logoUrl,
        streamTitle = channel.name,
        streamSubtitle = null,
        sourceUrl = channel.streamUrl,
        sourceHeaders = channel.headers,
        streamType = channel.streamType,
    )
    showLiveTvChannelsPanel = false
    controlsVisible = true
}
