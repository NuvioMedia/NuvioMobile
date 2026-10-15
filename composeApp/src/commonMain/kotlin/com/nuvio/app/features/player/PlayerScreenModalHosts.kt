package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry

@Composable
internal fun PlayerScreenModalHosts(
    pendingP2pSwitch: PendingPlayerP2pSwitch?,
    onPendingP2pSwitchChanged: (PendingPlayerP2pSwitch?) -> Unit,
    onP2pEpisodeStreamSelected: (StreamItem, MetaVideo, Boolean) -> Unit,
    onP2pSourceStreamSelected: (StreamItem) -> Unit,
    onNextEpisodeAutoPlaySearchingChanged: (Boolean) -> Unit,
    onNextEpisodeAutoPlayCountdownChanged: (Int?) -> Unit,
    onNextEpisodeAutoPlaySourceNameChanged: (String?) -> Unit,
    showAudioModal: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    onAudioTrackSelected: (Int) -> Unit,
    onAudioModalDismissed: () -> Unit,
    showSubtitleModal: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    isLoadingAddonSubtitles: Boolean,
    subtitleStyle: SubtitleStyleState,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: AddonSubtitle?,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    onBuiltInSubtitleTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onSubtitleStyleChanged: (SubtitleStyleState) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onSubtitleDelayReset: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCueSelected: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    onSubtitleModalDismissed: () -> Unit,
    showPlaybackSpeedModal: Boolean = false,
    currentPlaybackSpeed: Float = 1.0f,
    onSpeedSelected: (Float) -> Unit = {},
    onPlaybackSpeedModalDismissed: () -> Unit = {},
    showVideoSettingsModal: Boolean,
    playerSettings: PlayerSettingsUiState,
    onVideoSettingsChanged: () -> Unit,
    onVideoSettingsModalDismissed: () -> Unit,
    showSourcesPanel: Boolean,
    sourceStreamsState: StreamsUiState,
    contentTitle: String,
    activeEpisodeTitle: String?,
    activeSourceUrl: String,
    activeStreamTitle: String,
    onSourceFilterSelected: (String?) -> Unit,
    onSourceStreamSelected: (StreamItem) -> Unit,
    onReloadSources: () -> Unit,
    onSourcesPanelDismissed: () -> Unit,
    isSeries: Boolean,
    showEpisodesPanel: Boolean,
    allEpisodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    activeSeasonNumber: Int?,
    activeEpisodeNumber: Int?,
    watchProgressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    episodeStreamsPanelState: EpisodeStreamsPanelState,
    episodeStreamsRepoState: StreamsUiState,
    onEpisodeSelectedForDownload: (MetaVideo) -> Boolean,
    onEpisodeStreamsRequested: (MetaVideo) -> Unit,
    onEpisodeStreamFilterSelected: (String?) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onBackToEpisodes: () -> Unit,
    onReloadEpisodeStreams: () -> Unit,
    onEpisodesPanelDismissed: () -> Unit,
    showSubmitIntroModal: Boolean,
    activeVideoId: String?,
    metaUiState: MetaDetailsUiState,
    displayedPositionMs: Long,
    submitIntroSegmentType: String,
    onSubmitIntroSegmentTypeChanged: (String) -> Unit,
    submitIntroStartTimeStr: String,
    onSubmitIntroStartTimeChanged: (String) -> Unit,
    submitIntroEndTimeStr: String,
    onSubmitIntroEndTimeChanged: (String) -> Unit,
    onSubmitIntroDismissed: () -> Unit,
    onSubmitIntroSuccess: () -> Unit,
) {
    if (pendingP2pSwitch != null) {
        P2pConsentDialog(
            onEnableP2p = {
                val pending = pendingP2pSwitch
                onPendingP2pSwitchChanged(null)
                P2pSettingsRepository.setP2pEnabled(true)
                val episode = pending.episode
                if (episode != null) {
                    onP2pEpisodeStreamSelected(pending.stream, episode, pending.isAutoPlay)
                } else {
                    onP2pSourceStreamSelected(pending.stream)
                }
            },
            onDismiss = {
                if (pendingP2pSwitch.isAutoPlay) {
                    onNextEpisodeAutoPlaySearchingChanged(false)
                    onNextEpisodeAutoPlayCountdownChanged(null)
                    onNextEpisodeAutoPlaySourceNameChanged(null)
                }
                onPendingP2pSwitchChanged(null)
            },
        )
    }

    AudioTrackModal(
        visible = showAudioModal,
        audioTracks = audioTracks,
        selectedIndex = selectedAudioIndex,
        onTrackSelected = onAudioTrackSelected,
        onDismiss = onAudioModalDismissed,
    )

    PlaybackSpeedModal(
        visible = showPlaybackSpeedModal,
        currentSpeed = currentPlaybackSpeed,
        onSpeedSelected = onSpeedSelected,
        onDismiss = onPlaybackSpeedModalDismissed,
    )

    SubtitleModal(
        visible = showSubtitleModal,
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        addonSubtitles = addonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage = playerSettings.secondaryPreferredSubtitleLanguage,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onBuiltInTrackSelected = onBuiltInSubtitleTrackSelected,
        onAddonSubtitleSelected = onAddonSubtitleSelected,
        onFetchAddonSubtitles = onFetchAddonSubtitles,
        onStyleChanged = onSubtitleStyleChanged,
        onSubtitleDelayChanged = onSubtitleDelayChanged,
        onSubtitleDelayReset = onSubtitleDelayReset,
        onAutoSyncCapture = onAutoSyncCapture,
        onAutoSyncCueSelected = onAutoSyncCueSelected,
        onAutoSyncReload = onAutoSyncReload,
        onDismiss = onSubtitleModalDismissed,
    )

    IosVideoSettingsModal(
        visible = showVideoSettingsModal,
        settings = playerSettings,
        onSettingsChanged = onVideoSettingsChanged,
        onDismiss = onVideoSettingsModalDismissed,
    )

    PlayerSourcesPanel(
        visible = showSourcesPanel,
        streamsUiState = sourceStreamsState,
        contentTitle = contentTitle,
        currentSeason = activeSeasonNumber,
        currentEpisode = activeEpisodeNumber,
        currentEpisodeTitle = activeEpisodeTitle,
        currentStreamUrl = activeSourceUrl,
        currentStreamName = activeStreamTitle,
        onFilterSelected = onSourceFilterSelected,
        onStreamSelected = onSourceStreamSelected,
        onReload = onReloadSources,
        onDismiss = onSourcesPanelDismissed,
    )

    if (isSeries) {
        PlayerEpisodesPanel(
            visible = showEpisodesPanel,
            episodes = allEpisodes,
            parentMetaType = parentMetaType,
            parentMetaId = parentMetaId,
            currentSeason = activeSeasonNumber,
            currentEpisode = activeEpisodeNumber,
            progressByVideoId = watchProgressByVideoId,
            watchedKeys = watchedKeys,
            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
            episodeStreamsState = episodeStreamsPanelState.copy(
                streamsUiState = episodeStreamsRepoState,
            ),
            onSeasonSelected = { },
            onEpisodeSelected = { episode ->
                if (!onEpisodeSelectedForDownload(episode)) {
                    onEpisodeStreamsRequested(episode)
                }
            },
            onEpisodeStreamFilterSelected = onEpisodeStreamFilterSelected,
            onEpisodeStreamSelected = onEpisodeStreamSelected,
            onBackToEpisodes = onBackToEpisodes,
            onReloadEpisodeStreams = onReloadEpisodeStreams,
            onDismiss = onEpisodesPanelDismissed,
        )
    }

    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val imdbId = activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }

    if (showSubmitIntroModal && season != null && episode != null && !imdbId.isNullOrBlank()) {
        com.nuvio.app.features.player.skip.SubmitIntroDialog(
            imdbId = imdbId,
            season = season,
            episode = episode,
            currentTimeSec = displayedPositionMs / 1000.0,
            segmentType = submitIntroSegmentType,
            onSegmentTypeChange = onSubmitIntroSegmentTypeChanged,
            startTimeStr = submitIntroStartTimeStr,
            onStartTimeChange = onSubmitIntroStartTimeChanged,
            endTimeStr = submitIntroEndTimeStr,
            onEndTimeChange = onSubmitIntroEndTimeChanged,
            onDismiss = onSubmitIntroDismissed,
            onSuccess = onSubmitIntroSuccess,
        )
    }
}

internal fun selectDownloadedEpisodeForPlayback(
    parentMetaId: String,
    episode: MetaVideo,
    onDownloadedEpisodeSelected: (com.nuvio.app.features.downloads.DownloadItem, MetaVideo) -> Unit,
): Boolean {
    val downloadedEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        videoId = episode.id,
    )
    if (downloadedEpisode != null) {
        onDownloadedEpisodeSelected(downloadedEpisode, episode)
        return true
    }
    return false
}
