package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*

@Composable
internal fun PlayerScreenRuntime.RenderPlayerRuntimeUi() {
    val runtime = this
    val isInPip = rememberIsInPictureInPicture()
    val displayedPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
    val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null
    val currentGestureFeedback = liveGestureFeedback ?: gestureFeedback
    val isP2pPlaybackActive = activeTorrentInfoHash != null
    val p2pConnecting = p2pStreamingState as? P2pStreamingState.Connecting
    val p2pStats = p2pStreamingState as? P2pStreamingState.Streaming
    val p2pPeerInfo = p2pStats?.let { stats ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            stats.seeds,
            stats.peers,
        )
    }
    val p2pDownloadSpeed = p2pStats?.let { formatP2pSpeed(it.downloadSpeed) }
    val p2pLoadingBytes = p2pStats?.let { maxOf(it.downloadedBytes, it.deliveredBytes) } ?: 0L
    val connectingPeerInfo = p2pConnecting?.let { state ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            state.seeds,
            state.peers,
        )
    }
    val p2pInitialLoadingMessage = when {
        !isP2pPlaybackActive || initialLoadCompleted -> null
        p2pConnecting != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                p2pConnectingPhaseLabel(p2pConnecting.phase)
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_connecting_status,
                    p2pConnectingPhaseLabel(p2pConnecting.phase),
                    connectingPeerInfo.orEmpty(),
                    formatP2pSpeed(p2pConnecting.downloadSpeed),
                )
            }
        }
        p2pStats != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                null
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_loading_status,
                    formatP2pMegabytes(p2pLoadingBytes),
                    p2pPeerInfo.orEmpty(),
                    p2pDownloadSpeed.orEmpty(),
                )
            }
        }
        else -> org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
        )
    }
    val bufferedAheadMs = (playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs)
        .coerceAtLeast(0L)
    val p2pInitialLoadingProgress = when {
        !isP2pPlaybackActive || initialLoadCompleted || p2pStats == null -> null
        else -> p2pInitialLoadingProgress(
            bufferedAheadMs = bufferedAheadMs,
            downloadedBytes = p2pStats.downloadedBytes,
            deliveredBytes = p2pStats.deliveredBytes,
        )
    }
    val showP2pRebufferStats = isP2pPlaybackActive &&
        initialLoadCompleted &&
        playbackSnapshot.isLoading &&
        p2pStats != null &&
        !p2pSettingsUiState.hideTorrentStats
    val p2pRebufferMessage = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000L)
                .coerceAtLeast(0L)
            "${bufferedSeconds}s buffered · ${p2pPeerInfo.orEmpty()} · ${p2pDownloadSpeed.orEmpty()}"
        }
    }
    val p2pRebufferProgress = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000f)
                .coerceAtLeast(0f)
            (bufferedSeconds / 10f).coerceIn(0f, 1f)
        }
    }
    val gestureCallbacks = rememberSurfaceGestureCallbacks()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .playerSurfaceTapGestures(
                layoutSize = layoutSize,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                onSurfaceTap = gestureCallbacks.onSurfaceTap,
                onSurfaceDoubleTap = gestureCallbacks.onSurfaceDoubleTap,
                activateHoldToSpeedState = gestureCallbacks.activateHoldToSpeed,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
            )
            .playerSurfaceDragGestures(
                gestureController = gestureController,
                layoutSize = layoutSize,
                sideGestureSystemEdgeExclusionPx = sideGestureSystemEdgeExclusionPx,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                touchGesturesEnabledState = gestureCallbacks.touchGesturesEnabled,
                isHoldToSpeedGestureActiveState = gestureCallbacks.isHoldToSpeedGestureActive,
                currentPositionMsState = gestureCallbacks.currentPositionMs,
                currentDurationMsState = gestureCallbacks.currentDurationMs,
                deactivateHoldToSpeedState = gestureCallbacks.deactivateHoldToSpeed,
                showHorizontalSeekPreviewState = gestureCallbacks.showHorizontalSeekPreview,
                showBrightnessFeedbackState = gestureCallbacks.showBrightnessFeedback,
                showVolumeFeedbackState = gestureCallbacks.showVolumeFeedback,
                clearLiveGestureFeedbackState = gestureCallbacks.clearLiveGestureFeedback,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
                commitHorizontalSeekState = gestureCallbacks.commitHorizontalSeek,
            ),
    ) {
        val playerSurfaceSourceUrl = if (isP2pPlaybackActive) p2pResolvedSourceUrl else activeSourceUrl
        val initialPositionRequestKey = currentInitialPositionRequestKey()
        if (playerSurfaceSourceUrl != null) {
            PlatformPlayerSurface(
                sourceUrl = playerSurfaceSourceUrl,
                sourceAudioUrl = activeSourceAudioUrl,
                sourceHeaders = activeSourceHeaders,
                sourceResponseHeaders = activeSourceResponseHeaders,
                externalSubtitles = externalSubtitles,
                streamType = activeStreamType,
                modifier = Modifier.fillMaxSize(),
                playWhenReady = shouldPlay,
                initialPositionMs = activeInitialPositionMs.takeIf { it > 0L },
                initialPositionRequestKey = initialPositionRequestKey,
                resizeMode = resizeMode,
                onInitialPositionHandled = { key, handled ->
                    if (key == currentInitialPositionRequestKey()) {
                        initialSeekApplied = handled
                    }
                },
                onControllerReady = { controller ->
                    playerController = controller
                    playerControllerSourceUrl = activeSourceUrl
                },
                onSnapshot = { snapshot ->
                    playbackSnapshot = snapshot
                    refreshAudioTracksIfChanged()
                    if (!snapshot.isLoading) initialLoadCompleted = true
                    if (snapshot.isEnded) {
                        shouldPlay = false
                        controlsVisible = !playerControlsLocked
                    }
                },
                onError = { message ->
                    if (message != null && tryRefreshCredentialedSourceAfterError(message)) {
                        return@PlatformPlayerSurface
                    }
                    errorMessage = message
                    if (message != null) {
                        controlsVisible = !playerControlsLocked
                        removeFailedStreamFromCache()
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = pausedOverlayVisible && !controlsVisible && !playerControlsLocked,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            PauseMetadataOverlay(
                title = title,
                logo = logo,
                isEpisode = isEpisode,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                pauseDescription = activePauseDescription ?: activeStreamSubtitle,
                providerName = activeProviderName,
                metrics = metrics,
                horizontalSafePadding = horizontalSafePadding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        RenderPlayerControls(displayedPositionMs = displayedPositionMs, isEpisode = isEpisode)
        RenderPlaybackOverlays(
            runtime = runtime,
            displayedPositionMs = displayedPositionMs,
            currentGestureFeedback = currentGestureFeedback,
            p2pInitialLoadingMessage = p2pInitialLoadingMessage,
            p2pInitialLoadingProgress = p2pInitialLoadingProgress,
            showP2pRebufferStats = showP2pRebufferStats,
            p2pRebufferMessage = p2pRebufferMessage,
            p2pRebufferProgress = p2pRebufferProgress,
        )
        RenderPlayerModals(displayedPositionMs = displayedPositionMs)
    }
}

@Composable
private fun p2pConnectingPhaseLabel(phase: String): String = when (phase) {
    "add_magnet" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_fetching_metadata,
    )
    "prepare_stream", "attach_route" -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_preparing_stream,
    )
    else -> org.jetbrains.compose.resources.stringResource(
        nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
    )
}

private fun PlayerScreenRuntime.currentInitialPositionRequestKey(): String? {
    val positionMs = activeInitialPositionMs.takeIf { it > 0L } ?: return null
    return "$activePlaybackIdentity:${activeVideoId.orEmpty()}:$positionMs"
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerControls(displayedPositionMs: Long, isEpisode: Boolean) {
    val isInPip = rememberIsInPictureInPicture()
    AnimatedVisibility(
        visible = (controlsVisible || showParentalGuide) && !playerControlsLocked && !isInPip,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerControlsShell(
            title = title,
            streamTitle = activeStreamTitle,
            providerName = activeProviderName,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            resizeMode = resizeMode,
            isLocked = playerControlsLocked,
            showPlaybackControls = controlsVisible,
            onLockToggle = {
                if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
            },
            onBack = {
                flushWatchProgress()
                args.onBack()
            },
            onTogglePlayback = { togglePlayback() },
            onSeekBack = { seekBy(-10_000L) },
            onSeekForward = { seekBy(10_000L) },
            onResizeModeClick = { cycleResizeMode() },
            onSpeedClick = { showPlaybackSpeedModal = true },
            onSubtitleClick = {
                refreshTracks()
                showSubtitleModal = true
            },
            onAudioClick = {
                refreshTracks()
                showAudioModal = true
            },
            onVideoSettingsClick = if (isIos) {
                {
                    showVideoSettingsModal = true
                    controlsVisible = true
                }
            } else {
                null
            },
            onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
            onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
            onOpenInExternalPlayer = args.onOpenInExternalPlayer?.let { openExternal ->
                {
                    val loadedSubtitles = addonSubtitles
                        .takeIf { it.isNotEmpty() }
                        ?.map { sub ->
                            SubtitleInput(
                                url = sub.url,
                                name = buildString {
                                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                                    append(sub.display)
                                },
                                lang = sub.language,
                            )
                        }
                    openExternal(
                        ExternalPlayerPlaybackRequest(
                            sourceUrl = activeSourceUrl,
                            title = title,
                            streamTitle = activeStreamTitle,
                            sourceHeaders = activeSourceHeaders,
                            resumePositionMs = playbackSnapshot.positionMs,
                            subtitles = loadedSubtitles,
                            season = activeSeasonNumber,
                            episode = activeEpisodeNumber,
                            episodeTitle = activeEpisodeTitle,
                        ),
                    )
                }
            },
            onSubmitIntroClick = if (
                isSeries &&
                playerSettingsUiState.introSubmitEnabled &&
                playerSettingsUiState.introDbApiKey.isNotBlank()
            ) {
                { showSubmitIntroModal = true }
            } else {
                null
            },
            parentalWarnings = parentalWarnings,
            showParentalGuide = showParentalGuide,
            onParentalGuideAnimationComplete = { showParentalGuide = false },
            onScrubChange = { positionMs ->
                isScrubbingTimeline = true
                scrubbingPositionMs = positionMs
            },
            onScrubFinished = { positionMs ->
                isScrubbingTimeline = false
                scrubbingPositionMs = null
                playerController?.seekTo(positionMs)
                scheduleProgressSyncAfterSeek()
            },
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BoxScope.RenderPlaybackOverlays(
    runtime: PlayerScreenRuntime,
    displayedPositionMs: Long,
    currentGestureFeedback: GestureFeedbackState?,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
) {
    runtime.run {
        PlayerPlaybackOverlays(
            playerControlsLocked = playerControlsLocked,
            lockedOverlayVisible = lockedOverlayVisible,
            playbackSnapshot = playbackSnapshot,
        displayedPositionMs = displayedPositionMs,
        metrics = metrics,
        horizontalSafePadding = horizontalSafePadding,
        onUnlock = { unlockPlayerControls() },
        showOpeningOverlay = playerSettingsUiState.showLoadingOverlay && !initialLoadCompleted && errorMessage == null,
        backdropArtwork = background ?: poster,
        logo = logo,
        title = title,
        onBackWithProgress = {
            flushWatchProgress()
            args.onBack()
        },
        p2pInitialLoadingMessage = p2pInitialLoadingMessage,
        p2pInitialLoadingProgress = p2pInitialLoadingProgress,
        showP2pRebufferStats = showP2pRebufferStats,
        p2pRebufferMessage = p2pRebufferMessage,
        p2pRebufferProgress = p2pRebufferProgress,
        currentGestureFeedback = currentGestureFeedback,
        renderedGestureFeedback = renderedGestureFeedback,
        initialLoadCompleted = initialLoadCompleted,
        pausedOverlayVisible = pausedOverlayVisible,
        activeSkipInterval = activeSkipInterval,
        skipIntervalDismissed = skipIntervalDismissed,
        controlsVisible = controlsVisible,
        onSkipInterval = { interval ->
            val rawMs = (interval.endTime * 1000.0).toLong()
            val durationMs = playbackSnapshot.durationMs
            val seekMs = if (durationMs > 0L) rawMs.coerceAtMost(durationMs - 1) else rawMs
            playerController?.seekTo(seekMs)
            scheduleProgressSyncAfterSeek()
            skipIntervalDismissed = true
        },
        onDismissSkipInterval = { skipIntervalDismissed = true },
        sliderEdgePadding = sliderEdgePadding,
        overlayBottomPadding = overlayBottomPadding,
        isSeries = isSeries,
        nextEpisodeInfo = nextEpisodeInfo,
        showNextEpisodeCard = showNextEpisodeCard,
        nextEpisodeAutoPlaySearching = nextEpisodeAutoPlaySearching,
        nextEpisodeAutoPlaySourceName = nextEpisodeAutoPlaySourceName,
        nextEpisodeAutoPlayCountdown = nextEpisodeAutoPlayCountdown,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        onPlayNextEpisode = {
            nextEpisodeAutoPlayJob?.cancel()
            playNextEpisode()
        },
        onDismissNextEpisode = {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeCardDismissed = true
            showNextEpisodeCard = false
            nextEpisodeAutoPlaySearching = false
            nextEpisodeAutoPlaySourceName = null
            nextEpisodeAutoPlayCountdown = null
        },
        errorMessage = errorMessage,
            onDismissError = {
                flushWatchProgress()
                args.onBack()
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerModals(displayedPositionMs: Long) {
    PlayerScreenModalHosts(
        pendingP2pSwitch = pendingP2pSwitch,
        onPendingP2pSwitchChanged = { pendingP2pSwitch = it },
        onP2pEpisodeStreamSelected = { stream, episode, isAutoPlay ->
            switchToP2pEpisodeStream(stream, episode, isAutoPlay)
        },
        onP2pSourceStreamSelected = { stream -> switchToP2pSourceStream(stream) },
        onNextEpisodeAutoPlaySearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onNextEpisodeAutoPlayCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeAutoPlaySourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        showAudioModal = showAudioModal,
        audioTracks = audioTracks,
        selectedAudioIndex = selectedAudioIndex,
        onAudioTrackSelected = { index ->
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
            scope.launch {
                kotlinx.coroutines.delay(200)
                showAudioModal = false
            }
        },
        onAudioModalDismissed = { showAudioModal = false },
        showSubtitleModal = showSubtitleModal,
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        addonSubtitles = visibleAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onBuiltInSubtitleTrackSelected = { index ->
            val wasCustom = useCustomSubtitles
            isUserExplicitSubtitleSelection = true
            preferredSubtitleSelectionApplied = true
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
        },
        onAddonSubtitleSelected = { addon ->
            isUserExplicitSubtitleSelection = true
            selectedAddonSubtitleId = addon.selectionKey
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            preferredSubtitleSelectionApplied = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
        },
        onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
        onSubtitleStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
        onSubtitleDelayChanged = { delayMs -> setSubtitleDelay(delayMs) },
        onSubtitleDelayReset = { setSubtitleDelay(0) },
        onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
        onAutoSyncCueSelected = { cue -> applySubtitleAutoSyncCue(cue) },
        onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
        onSubtitleModalDismissed = { showSubtitleModal = false },
        showPlaybackSpeedModal = showPlaybackSpeedModal,
        currentPlaybackSpeed = playbackSnapshot.playbackSpeed,
        onSpeedSelected = { speed ->
            playerController?.setPlaybackSpeed(speed)
            showGestureMessage(formatPlaybackSpeedLabel(speed))
        },
        onPlaybackSpeedModalDismissed = { showPlaybackSpeedModal = false },
        showVideoSettingsModal = showVideoSettingsModal,
        playerSettings = playerSettingsUiState,
        onVideoSettingsChanged = {
            playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
        },
        onVideoSettingsModalDismissed = { showVideoSettingsModal = false },
        showSourcesPanel = showSourcesPanel,
        sourceStreamsState = sourceStreamsState,
        contentTitle = title,
        activeEpisodeTitle = activeEpisodeTitle,
        activeSourceUrl = activeSourceUrl,
        activeStreamTitle = activeStreamTitle,
        onSourceFilterSelected = PlayerStreamsRepository::selectSourceFilter,
        onSourceStreamSelected = { stream -> switchToSource(stream) },
        onReloadSources = {
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
        onSourcesPanelDismissed = {
            showSourcesPanel = false
            controlsVisible = true
        },
        isSeries = isSeries,
        showEpisodesPanel = showEpisodesPanel,
        allEpisodes = playerMetaVideos,
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        activeSeasonNumber = activeSeasonNumber,
        activeEpisodeNumber = activeEpisodeNumber,
        watchProgressByVideoId = watchProgressUiState.byVideoIdForContent(parentMetaId),
        watchedKeys = watchedUiState.watchedKeys,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        episodeStreamsPanelState = episodeStreamsPanelState,
        episodeStreamsRepoState = episodeStreamsRepoState,
        onEpisodeSelectedForDownload = { episode ->
            selectDownloadedEpisodeForPlayback(
                parentMetaId = parentMetaId,
                episode = episode,
                onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
            )
        },
        onEpisodeStreamsRequested = { episode ->
            PlayerStreamsRepository.loadEpisodeStreams(
                type = contentType ?: parentMetaType,
                videoId = episode.id,
                season = episode.season,
                episode = episode.episode,
            )
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        },
        onEpisodeStreamFilterSelected = PlayerStreamsRepository::selectEpisodeStreamsFilter,
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onBackToEpisodes = {
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        },
        onReloadEpisodeStreams = {
            val episode = episodeStreamsPanelState.selectedEpisode
            if (episode != null) {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            }
        },
        onEpisodesPanelDismissed = {
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        },
        showSubmitIntroModal = showSubmitIntroModal,
        activeVideoId = activeVideoId,
        metaUiState = metaUiState,
        displayedPositionMs = displayedPositionMs,
        submitIntroSegmentType = submitIntroSegmentType,
        onSubmitIntroSegmentTypeChanged = { submitIntroSegmentType = it },
        submitIntroStartTimeStr = submitIntroStartTimeStr,
        onSubmitIntroStartTimeChanged = { submitIntroStartTimeStr = it },
        submitIntroEndTimeStr = submitIntroEndTimeStr,
        onSubmitIntroEndTimeChanged = { submitIntroEndTimeStr = it },
        onSubmitIntroDismissed = { showSubmitIntroModal = false },
        onSubmitIntroSuccess = {
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            showSubmitIntroModal = false
        },
    )
}
