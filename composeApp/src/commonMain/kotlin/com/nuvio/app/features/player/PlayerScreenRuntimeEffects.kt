package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        playerMeta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        playerMetaVideos = playerMeta?.videos.orEmpty()
        if (playerMetaVideos.isEmpty()) {
            MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.let { meta ->
                playerMeta = meta
                playerMetaVideos = meta.videos
            }
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMeta = currentMeta
            playerMetaVideos = currentMeta.videos
        }
    }

    LaunchedEffect(currentStreamBingeGroup, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg)
        }
    }

    LaunchedEffect(activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        credentialRefreshAttemptedSourceUrl = null
        initialLoadCompleted = false
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        speedBoostRestoreSpeed = null
        preferredAudioSelectionApplied = false
        appliedAudioPreferences = null
        preferredSubtitleSelectionApplied = false
        isUserExplicitAudioSelection = false
        isUserExplicitSubtitleSelection = false
        hasScannedTextTracksOnce = false
        selectedSubtitleIndex = -1
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
        showSourcesPanel = false
        showEpisodesPanel = false
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
        SubtitleRepository.clear()
        WatchProgressRepository.ensureLoaded()
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        initialLoadCompleted = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            p2pResolvedSourceUrl = null
            playerController = null
            playerControllerSourceUrl = null
            playbackSnapshot = PlayerPlaybackSnapshot()
            initialLoadCompleted = true
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDelayMs = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle) {
        playerController?.applySubtitleStyle(subtitleStyle)
    }

    val subtitlePreferenceKey = listOf(
        playerSettingsUiState.preferredSubtitleLanguage,
        playerSettingsUiState.secondaryPreferredSubtitleLanguage.orEmpty(),
        subtitleStyle.useForcedSubtitles,
    ).joinToString("|")
    var lastSubtitlePreferenceKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        playerController,
        subtitlePreferenceKey,
        preferredSubtitleSelectionApplied,
        selectedSubtitleIndex,
        selectedAddonSubtitleId,
        useCustomSubtitles,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        val preferenceChanged = lastSubtitlePreferenceKey != null &&
            lastSubtitlePreferenceKey != subtitlePreferenceKey
        lastSubtitlePreferenceKey = subtitlePreferenceKey

        controller.applySubtitlePreferences(
            preferredLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            useForcedSubtitles = subtitleStyle.useForcedSubtitles,
            autoSelectionApplied = preferredSubtitleSelectionApplied,
            hasActiveSubtitle = selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null,
            useCustomSubtitles = useCustomSubtitles,
        )

        if (preferenceChanged) {
            preferredSubtitleSelectionApplied = false
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        activeSourceUrl,
        title,
        activeStreamTitle,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        poster,
        background,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        controller.updateNowPlayingMetadata(buildNowPlayingInfo())
    }

    LaunchedEffect(
        activeSourceUrl,
        addonSubtitleFetchKey,
        playerController,
        playerControllerSourceUrl,
    ) {
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        if (autoFetchedAddonSubtitlesForKey == fetchKey) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = fetchKey
        fetchAddonSubtitlesForActiveItem()
    }

    LaunchedEffect(playerController, playerControllerSourceUrl, activeSourceUrl, preferredAudioLanguageTargets) {
        if (playerControllerSourceUrl == activeSourceUrl) {
            applyPreferredAudioTrack(preferredAudioLanguageTargets)
        }
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController, preferredAudioLanguageTargets) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
        addonSubtitles,
        isLoadingAddonSubtitles,
    ) {
        if (playerController == null || playbackSnapshot.isLoading) {
            return@LaunchedEffect
        }
        if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
            return@LaunchedEffect
        }

        repeat(10) {
            refreshTracks()
            if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }
            delay(300)
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect

        val progressFraction = activeInitialProgressFraction
            ?.takeIf { it > 0f }
            ?.coerceIn(0f, 1f)
        val targetPositionMs = when {
            activeInitialPositionMs > 0L -> activeInitialPositionMs
            progressFraction != null && playbackSnapshot.durationMs > 0L -> {
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong()
            }
            progressFraction != null -> return@LaunchedEffect
            else -> 0L
        }
        if (targetPositionMs <= 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        onDispose {
            if (
                playbackSession.videoId == effectVideoId &&
                activeSourceUrl == effectSourceUrl &&
                activeSourceAudioUrl == effectSourceAudioUrl
            ) {
                flushWatchProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerController?.clearNowPlayingInfo()
            P2pStreamingEngine.shutdown()
            PlayerStreamsRepository.clearAll()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    LaunchedEffect(
        controlsVisible,
        isScrubbingTimeline,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        showParentalGuide,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            isScrubbingTimeline ||
            !playbackSnapshot.isPlaying ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        delay(3500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.durationMs, errorMessage) {
        pausedOverlayVisible = false
        if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
    ) {
        if (playbackSnapshot.isEnded) {
            flushWatchProgress(TrackingScrobbleAction.STOP)
            previousIsPlaying = false
            pendingSeekScrobbleRestart = false
            return@LaunchedEffect
        }

        if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            pendingSeekScrobbleRestart = false
            flushWatchProgress(TrackingScrobbleAction.PAUSE)
        }

        if (playbackSnapshot.isPlaying && pendingSeekScrobbleRestart) {
            pendingSeekScrobbleRestart = false
            if (hasRequestedScrobbleStartForCurrentItem) {
                emitTrackingSeekScrobbleStart()
            } else {
                emitTrackingScrobbleStart()
            }
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTrackingScrobbleStart()
        }

        if (!playbackSnapshot.isLoading) {
            previousIsPlaying = playbackSnapshot.isPlaying
        }
        if (playbackSnapshot.isPlaying) {
            persistPlaybackProgressTick()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber) {
        skipIntervals = emptyList()
        activeSkipInterval = null
        skipIntervalDismissed = false
        showNextEpisodeCard = false
        nextEpisodeCardDismissed = false
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlaySearching = false

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId
        if (season == null || episode == null || vid == null) return@LaunchedEffect

        launch {
            val imdbFromContent = parentMetaId.takeIf { it.startsWith("tt") }
            val intervals = when {
                vid.startsWith("mal:") -> {
                    val malId = vid.removePrefix("mal:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForMal(malId = malId, episode = episode, imdbId = imdbFromContent, imdbSeason = season, imdbEpisode = episode)
                }
                vid.startsWith("kitsu:") -> {
                    val kitsuId = vid.removePrefix("kitsu:").substringBefore(':')
                    SkipIntroRepository.getSkipIntervalsForKitsu(kitsuId = kitsuId, episode = episode, imdbId = imdbFromContent, imdbSeason = season, imdbEpisode = episode)
                }
                else -> SkipIntroRepository.getSkipIntervals(
                    imdbId = vid.substringBefore(':').takeIf { it.startsWith("tt") },
                    season = season,
                    episode = episode,
                )
            }
            skipIntervals = intervals
        }
    }

    LaunchedEffect(playbackSnapshot.positionMs, skipIntervals) {
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        val positionSec = playbackSnapshot.positionMs / 1000.0
        val current = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime
        }
        if (current != activeSkipInterval) {
            activeSkipInterval = current
            if (current != null) skipIntervalDismissed = false
        }
    }

    LaunchedEffect(
        playerMetaVideos,
        activeSeasonNumber,
        activeEpisodeNumber,
        watchProgressUiState.entries,
        watchedUiState.watchedKeys,
    ) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber ?: return@LaunchedEffect
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = playerMetaVideos,
            currentSeason = curSeason,
            currentEpisode = curEpisode,
        )
        val nextSeason = nextVideo?.season
        val nextEpisode = nextVideo?.episode
        nextEpisodeInfo = if (nextVideo != null && nextSeason != null && nextEpisode != null) {
            val playbackVideoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
                fallbackVideoId = nextVideo.id,
            )
            val isWatched = watchProgressUiState.progressForVideo(
                videoId = playbackVideoId,
                parentMetaId = parentMetaId,
                seasonNumber = nextSeason,
                episodeNumber = nextEpisode,
            )?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = nextVideo,
            )
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextSeason,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                isWatched = isWatched,
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    PlayerNextEpisodeRules.formatUnairedEpisodeMessage(
                        released = nextVideo.released,
                        airsPrefix = airsPrefix,
                        tbaLabel = tbaLabel,
                    )
                } else null,
            )
        } else null
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.durationMs,
        nextEpisodeInfo,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        nextEpisodeCardDismissed,
    ) {
        if (nextEpisodeInfo == null || playbackSnapshot.durationMs <= 0L) {
            showNextEpisodeCard = false
            return@LaunchedEffect
        }
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        if (shouldShow && !showNextEpisodeCard && !nextEpisodeCardDismissed) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        } else if (!shouldShow) {
            showNextEpisodeCard = false
        }
    }

    LaunchedEffect(playbackSnapshot.isEnded, nextEpisodeInfo, nextEpisodeCardDismissed) {
        if (
            playbackSnapshot.isEnded &&
            nextEpisodeInfo != null &&
            !showNextEpisodeCard &&
            !nextEpisodeCardDismissed
        ) {
            showNextEpisodeCard = true
            if (playerSettingsUiState.streamAutoPlayNextEpisodeEnabled && nextEpisodeInfo?.hasAired == true) {
                playNextEpisode()
            }
        }
    }
}

private fun PlayerScreenRuntime.buildNowPlayingInfo(): PlayerNowPlayingInfo {
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    return PlayerNowPlayingInfo(
        title = title.ifBlank { activeStreamTitle },
        subtitle = buildNowPlayingSubtitle(
            isEpisode = isEpisode,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        ),
        artworkUrl = firstNonBlankUrl(poster, background),
    )
}

private fun buildNowPlayingSubtitle(
    isEpisode: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    if (!isEpisode) return null

    val episodeParts = buildList {
        if (seasonNumber != null && episodeNumber != null) {
            add("S${seasonNumber}E${episodeNumber}")
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    return when (episodeParts.size) {
        0 -> null
        1 -> episodeParts.first()
        else -> "${episodeParts[0]} - ${episodeParts[1]}"
    }
}

private fun firstNonBlankUrl(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

internal fun PlayerScreenRuntime.removeFailedStreamFromCache() {
    val currentVideoId = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = currentVideoId,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.remove(cacheKey)
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    val failedUrl = activeSourceUrl
    if (!failedUrl.hasLikelyExpiringPlaybackCredentials()) return false
    if (credentialRefreshJob?.isActive == true) return true
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false

    val currentVideoId = activeVideoId ?: return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    removeFailedStreamFromCache()

    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        PlayerStreamsRepository.loadSources(
            type = type,
            videoId = currentVideoId,
            season = season,
            episode = episode,
            forceRefresh = true,
        )

        var refreshedStream: StreamItem? = null
        var pollCount = 0
        while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
            val state = PlayerStreamsRepository.sourceState.value
            refreshedStream = findCredentialRefreshCandidate(
                streams = state.groups.flatMap { it.streams },
                failedUrl = failedUrl,
                expectedProviderAddonId = expectedProviderAddonId,
                expectedProviderName = expectedProviderName,
                expectedStreamTitle = expectedStreamTitle,
                expectedBingeGroup = expectedBingeGroup,
            )
            if (
                refreshedStream != null ||
                state.emptyStateReason != null ||
                (!state.isAnyLoading && state.groups.isNotEmpty())
            ) {
                break
            }
            delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
            pollCount++
        }

        val stream = refreshedStream
        if (stream == null) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }

        val refreshedUrl = stream.playableDirectUrl
        if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }

        flushWatchProgress()
        stopActiveP2pStream()
        activeSourceUrl = refreshedUrl
        activeSourceAudioUrl = null
        activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
        activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
        activeStreamType = stream.streamType
        activeStreamTitle = stream.streamLabel
        activeStreamSubtitle = stream.streamSubtitle
        activeProviderName = stream.addonName
        activeProviderAddonId = stream.addonId
        currentStreamBingeGroup = stream.behaviorHints.bingeGroup
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        showSourcesPanel = false
        controlsVisible = true
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L
