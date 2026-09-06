package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.resolveDebridForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onResolved: (StreamItem) -> Unit,
    onStale: () -> Unit,
): Boolean {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return false
    scope.launch {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = season,
            episode = episode,
        )
        when (resolved) {
            is DirectDebridPlayableResult.Success -> onResolved(resolved.stream)
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                if (resolved == DirectDebridPlayableResult.Stale) {
                    onStale()
                }
            }
        }
    }
    return true
}

internal fun PlayerScreenRuntime.p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

internal fun PlayerScreenRuntime.isP2pStream(stream: StreamItem): Boolean =
    stream.needsLocalDebridResolve && stream.p2pInfoHash != null

internal fun PlayerScreenRuntime.openExternalSourceUrl(stream: StreamItem): Boolean {
    if (!stream.shouldOpenExternally) return false
    val url = stream.externalOpenUrl ?: return false
    val openExternalUrl = args.onOpenExternalUrl ?: return false
    openExternalUrl(url)
    showSourcesPanel = false
    showEpisodesPanel = false
    controlsVisible = true
    return true
}

internal fun StreamItem.playerSourceIdentityKey(): String? {
    p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "torrent:$hash:${p2pFileIdx ?: -1}"
    }

    clientResolve?.let { resolve ->
        val raw = resolve.stream?.raw
        val keyParts = listOf(
            addonId,
            resolve.service,
            resolve.serviceIndex?.toString(),
            resolve.infoHash?.trim()?.lowercase(),
            resolve.fileIdx?.toString(),
            resolve.magnetUri,
            resolve.torrentName,
            resolve.filename,
            raw?.torrentName,
            raw?.filename,
            raw?.size?.toString(),
            behaviorHints.filename,
            behaviorHints.videoSize?.toString(),
            streamLabel,
            streamSubtitle,
        ).map { it.orEmpty().trim() }
        if (keyParts.any { it.isNotBlank() }) {
            return "resolve:${keyParts.joinToString("|")}"
        }
    }

    behaviorHints.videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "hash:$addonId:$hash:${behaviorHints.videoSize ?: ""}:${behaviorHints.filename.orEmpty()}"
    }

    playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
        return "url:$url"
    }

    val fallbackParts = listOf(
        addonId,
        addonName,
        streamLabel,
        streamSubtitle.orEmpty(),
        behaviorHints.filename.orEmpty(),
        behaviorHints.videoSize?.toString().orEmpty(),
        sourceName.orEmpty(),
        sources.joinToString(","),
    ).map { it.trim() }
    return fallbackParts
        .takeIf { parts -> parts.any { it.isNotBlank() } }
        ?.joinToString(separator = "|", prefix = "meta:")
}

internal fun PlayerScreenRuntime.stopActiveP2pStream() {
    if (activeTorrentInfoHash != null || p2pResolvedSourceUrl != null) {
        P2pStreamingEngine.stopStream()
    }
    activeTorrentInfoHash = null
    activeTorrentFileIdx = null
    activeTorrentFilename = null
    activeTorrentTrackers = emptyList()
    p2pResolvedSourceUrl = null
}

internal fun PlayerScreenRuntime.saveP2pStreamForReuse(
    stream: StreamItem,
    videoId: String?,
    season: Int?,
    episode: Int?,
) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled || videoId == null) return
    val infoHash = stream.p2pInfoHash ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = "",
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        infoHash = infoHash,
        fileIdx = stream.p2pFileIdx,
        sources = stream.sources,
        bingeGroup = stream.behaviorHints.bingeGroup,
    )
}

internal fun PlayerScreenRuntime.switchToP2pSourceStream(stream: StreamItem) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = null, isAutoPlay = false)
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    saveP2pStreamForReuse(
        stream = stream,
        videoId = activeVideoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.switchToP2pEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    isAutoPlay: Boolean = false,
) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = episode, isAutoPlay = isAutoPlay)
        return
    }
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    saveP2pStreamForReuse(
        stream = stream,
        videoId = epVideoId,
        season = episode.season,
        episode = episode.episode,
    )
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal fun PlayerScreenRuntime.switchToSource(stream: StreamItem) {
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            onResolved = { switchToSource(it) },
            onStale = {
                val vid = activeVideoId
                if (vid != null) {
                    PlayerStreamsRepository.loadSources(
                        type = contentType ?: parentMetaType,
                        videoId = vid,
                        season = activeSeasonNumber,
                        episode = activeEpisodeNumber,
                        forceRefresh = true,
                    )
                }
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pSourceStream(stream)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    val sourceIdentityKey = stream.playerSourceIdentityKey()
    if (url == activeSourceUrl) {
        activeSourceIdentityKey = sourceIdentityKey ?: activeSourceIdentityKey
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    val currentVideoId = activeVideoId
    if (playerSettingsUiState.streamReuseLastLinkEnabled && currentVideoId != null) {
        saveDirectStreamForReuse(stream, url, currentVideoId, activeSeasonNumber, activeEpisodeNumber)
    }
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = sourceIdentityKey
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
}

internal fun PlayerScreenRuntime.switchToEpisodeStream(stream: StreamItem, episode: MetaVideo) {
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = episode.season,
            episode = episode.episode,
            onResolved = { resolvedStream -> switchToEpisodeStream(resolvedStream, episode) },
            onStale = {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pEpisodeStream(stream, episode)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    if (playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, epVideoId, episode.season, episode.episode)
    }
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal fun PlayerScreenRuntime.switchToDownloadedEpisode(downloadItem: DownloadItem, episode: MetaVideo) {
    val localFileUri = DownloadsRepository.playableLocalFileUri(downloadItem) ?: return
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()

    val fallbackVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = episode.id,
    )
    val resolvedVideoId = episode.id.takeIf { it.isNotBlank() } ?: fallbackVideoId
    val epEntry = WatchProgressRepository.progressForVideo(
        videoId = resolvedVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )
        ?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L

    activeSourceUrl = localFileUri
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = null
    activeStreamTitle = downloadItem.streamTitle.ifBlank {
        episode.title.ifBlank { title }
    }
    activeStreamSubtitle = downloadItem.streamSubtitle
    activeProviderName = downloadItem.providerName.ifBlank { downloadedLabel }
    activeProviderAddonId = downloadItem.providerAddonId
    currentStreamBingeGroup = null
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = resolvedVideoId
    activeInitialPositionMs = epResumePositionMs
    activeInitialProgressFraction = epResumeFraction
    controlsVisible = true
}

internal fun PlayerScreenRuntime.playNextEpisode() {
    // If streams for the next episode are already loaded (e.g. autoplay found one
    // during its countdown), re-use them immediately instead of restarting the
    // entire search. This prevents a visible "Searching…" flicker when the user
    // manually taps the Next Episode button while the countdown is in progress.
    val nextVideoId = nextEpisodeInfo?.videoId
    val nextVideo = nextVideoId?.let { id -> playerMetaVideos.firstOrNull { it.id == id } }
    if (nextVideo != null && nextEpisodeInfo?.hasAired == true) {
        val cachedStreams = PlayerStreamsRepository.episodeStreamsState.value.groups
            .flatMap { it.streams }
        if (cachedStreams.isNotEmpty()) {
            nextEpisodeAutoPlayJob?.cancel()
            switchToEpisodeStream(cachedStreams.first(), nextVideo)
            return
        }
    }

    scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = nextEpisodeAutoPlayJob,
        nextEpisodeInfo = nextEpisodeInfo,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onDownloadedEpisodeSelected = { item, episode -> switchToDownloadedEpisode(item, episode) },
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onManualSelectionRequired = { nextVideo ->
            episodeStreamsPanelState = EpisodeStreamsPanelState(
                showStreams = true,
                selectedEpisode = nextVideo,
            )
            showEpisodesPanel = true
        },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = { showNextEpisodeCard = it },
    )?.let { job ->
        nextEpisodeAutoPlayJob = job
    }
}

internal fun PlayerScreenRuntime.openSourcesPanel() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    showSourcesPanel = true
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.openEpisodesPanel() {
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
    showEpisodesPanel = true
    showSourcesPanel = false
    controlsVisible = false
}

private data class EpisodeResume(val positionMs: Long, val fraction: Float?)

private fun PlayerScreenRuntime.resetEpisodePanelAndNextEpisodeState() {
    showNextEpisodeCard = false
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
    PlayerStreamsRepository.clearEpisodeStreams()
}

private fun PlayerScreenRuntime.resolveEpisodeResume(epVideoId: String, episode: MetaVideo): EpisodeResume {
    val epResumeVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = epVideoId,
    )
    val epEntry = WatchProgressRepository.progressForVideo(
        videoId = epVideoId.takeIf { it.isNotBlank() } ?: epResumeVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L
    return EpisodeResume(positionMs = epResumePositionMs, fraction = epResumeFraction)
}

private fun PlayerScreenRuntime.applyEpisodeStreamMetadata(
    stream: StreamItem,
    episode: MetaVideo,
    resume: EpisodeResume,
) {
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = episode.id
    activeInitialPositionMs = resume.positionMs
    activeInitialProgressFraction = resume.fraction
    controlsVisible = true
}

private fun PlayerScreenRuntime.saveDirectStreamForReuse(
    stream: StreamItem,
    url: String,
    videoId: String,
    season: Int?,
    episode: Int?,
) {
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = url,
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
        responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = stream.streamType,
        contentLanguage = contentLanguage,
    )
}
