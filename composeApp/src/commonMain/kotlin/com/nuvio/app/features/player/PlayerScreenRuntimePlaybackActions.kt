package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.contentEndPercent
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun PlayerScreenRuntime.finishTimelineScrub(positionMs: Long) {
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to positionMs
    isScrubbingTimeline = false
    scrubbingPositionMs = positionMs.takeIf { playbackSnapshot.isLoading }
}

internal fun PlayerScreenRuntime.updatePlaybackSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    playbackKey: PlaybackKey = activePlaybackKey,
): Boolean {
    if (playbackKey != activePlaybackKey) return false
    playbackSnapshot = snapshot
    playbackSnapshotKey = playbackKey
    val targetPositionMs = scrubbingPositionMs ?: return true
    if (!isScrubbingTimeline && (
            !snapshot.isLoading || snapshot.isEnded ||
                abs(snapshot.positionMs - targetPositionMs) <= 1_000L
            )
    ) {
        scrubbingPositionMs = null
    }
    return true
}

internal val PlayerScreenRuntime.activePlaybackIdentity: String
    get() = activeTorrentInfoHash
        ?.let { hash -> "torrent:$hash:${activeTorrentFileIdx ?: -1}" }
        ?: activeSourceUrl

internal val PlayerScreenRuntime.activePlaybackKey: PlaybackKey
    get() = PlaybackKey(
        sourceIdentity = activePlaybackIdentity,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
    )

internal val PlayerScreenRuntime.playbackSession: WatchProgressPlaybackSession
    get() = WatchProgressPlaybackSession(
        profileId = profileId,
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            fallbackVideoId = activeVideoId,
        ),
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        lastStreamTitle = activeStreamTitle,
        lastStreamSubtitle = activeStreamSubtitle,
        pauseDescription = activePauseDescription,
        lastSourceUrl = activeSourceUrl,
    )

internal fun PlayerScreenRuntime.resetIdentityStateIfNeeded() {
    val identity = activePlaybackKey
    if (lastResetPlaybackIdentity != identity) {
        lastResetPlaybackIdentity = identity
        shouldPlay = true
        initialLoadCompleted = false
        speedBoostRestoreSpeed = null
        isHoldToSpeedGestureActive = false
        initialSeekApplied = activeInitialPositionMs <= 0L &&
            (activeInitialProgressFraction == null || activeInitialProgressFraction!! <= 0f)
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        autoFetchedAddonSubtitlesForKey = null
        trackPreferenceRestoreApplied = false
        preferredAudioSelectionApplied = false
        appliedAudioPreferences = null
        preferredSubtitleSelectionApplied = false
        isUserExplicitAudioSelection = false
        isUserExplicitSubtitleSelection = false
        hasScannedTextTracksOnce = false
    }

    val videoIdentity = "$identity:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (lastResetVideoIdentity != videoIdentity) {
        lastResetVideoIdentity = videoIdentity
        hasRequestedScrobbleStartForCurrentItem = false
        scrobbleStartRequestGeneration = 0L
        pendingSeekScrobbleRestart = false
        hasSentCompletionScrobbleForCurrentItem = false
        currentTrackingMedia = null
    }
}

internal fun PlayerScreenRuntime.currentPlaybackProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float {
    val duration = snapshot.durationMs.takeIf { it > 0L } ?: return 0f
    return ((snapshot.positionMs.toFloat() / duration.toFloat()) * 100f)
        .coerceIn(0f, 100f)
}

internal data class TrackingScrobbleItemInputs(
    val contentType: String,
    val parentMetaId: String,
    val videoId: String?,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
)

internal fun PlayerScreenRuntime.snapshotTrackingScrobbleItemInputs() = TrackingScrobbleItemInputs(
    contentType = contentType ?: parentMetaType,
    parentMetaId = parentMetaId,
    videoId = activeVideoId,
    title = title,
    seasonNumber = activeSeasonNumber,
    episodeNumber = activeEpisodeNumber,
    episodeTitle = activeEpisodeTitle,
)

private fun TrackingScrobbleItemInputs.buildMedia(): TrackingMediaReference =
    buildTrackingMediaReference(
        contentType = contentType,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
    )

internal fun PlayerScreenRuntime.currentTrackingMedia(): TrackingMediaReference =
    snapshotTrackingScrobbleItemInputs().buildMedia()

internal fun PlayerScreenRuntime.emitTrackingScrobbleStart() {
    if (hasRequestedScrobbleStartForCurrentItem) return
    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = scrobbleStartRequestGeneration + 1L
    scrobbleStartRequestGeneration = requestGeneration

    scope.launch {
        val media = currentTrackingMedia()
        if (!media.hasResolvableIdentity) {
            hasRequestedScrobbleStartForCurrentItem = false
            return@launch
        }
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            return@launch
        }
        currentTrackingMedia = media
        TrackingScrobbleCoordinator.scrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = currentPlaybackProgressPercent().toDouble(),
            ),
        )
    }
}

internal fun PlayerScreenRuntime.emitTrackingScrobblePause(progressPercent: Float? = null) {
    emitTrackingScrobbleTerminal(
        action = TrackingScrobbleAction.PAUSE,
        progressPercent = progressPercent,
    )
}

internal fun PlayerScreenRuntime.emitTrackingScrobbleStop(progressPercent: Float? = null) {
    emitTrackingScrobbleTerminal(
        action = TrackingScrobbleAction.STOP,
        progressPercent = progressPercent,
    )
}

private fun PlayerScreenRuntime.emitTrackingScrobbleTerminal(
    action: TrackingScrobbleAction,
    progressPercent: Float?,
) {
    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    val percent = provided ?: currentPlaybackProgressPercent()
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    // IntroDB knows where the credits start, and a playback that reached them is over for the tracker
    // even when it stopped below the percentage the user set. Snapshot here, before the write leaves,
    // because the marker and the duration belong to this playback, not to the request.
    val contentEndPercent = currentContentEndPercent()
    scope.launch(NonCancellable) {
        val media = mediaSnapshot ?: inputsSnapshot.buildMedia()
        if (!media.hasResolvableIdentity) return@launch
        TrackingScrobbleCoordinator.scrobble(
            profileId = profileId,
            action = action,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = percent.toDouble(),
                contentEndPercent = contentEndPercent,
            ),
        )
    }
    currentTrackingMedia = null
    hasRequestedScrobbleStartForCurrentItem = false
    pendingSeekScrobbleRestart = false
    scrobbleStartRequestGeneration += 1L
}

internal fun PlayerScreenRuntime.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    if (!shouldSendStopScrobble(hasRequestedScrobbleStartForCurrentItem, progressPercent)) {
        return
    }
    if (progressPercent < 80f) {
        emitTrackingScrobbleStop(progressPercent)
        return
    }

    if (progressPercent >= 80f && !hasSentCompletionScrobbleForCurrentItem) {
        hasSentCompletionScrobbleForCurrentItem = true
        emitTrackingScrobbleStop(progressPercent)
    }
}

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
): Boolean = hasActiveScrobble || progressPercent >= 80f

internal fun shouldUpdateTrackingScrobbleAfterSeek(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
): Boolean = hasActiveScrobble && progressPercent >= 1f && progressPercent < 80f

internal fun PlayerScreenRuntime.emitTrackingSeekScrobbleStart() {
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    scope.launch {
        val media = mediaSnapshot ?: inputsSnapshot.buildMedia()
        if (!media.hasResolvableIdentity) return@launch
        TrackingScrobbleCoordinator.scrobbleSeek(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = currentPlaybackProgressPercent().toDouble(),
            ),
        )
    }
}

internal fun PlayerScreenRuntime.tryShowParentalGuide() {
    if (!playerSettingsUiState.showParentalGuide) return
    if (!parentalGuideHasShown && parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        showParentalGuide = true
        parentalGuideHasShown = true
    }
}

internal suspend fun PlayerScreenRuntime.resolveParentalGuideImdbId(): String? {
    val candidates = listOf(parentMetaId, activeVideoId)
    candidates.firstNotNullOfOrNull(::extractParentalGuideImdbId)?.let { return it }
    // Fallback: use imdb_id from addon meta response
    val metaImdbId = (metaUiState.meta ?: playerMeta)
        ?.takeIf { it.id == parentMetaId }
        ?.imdbId
        ?.takeIf { it.startsWith("tt") }
    if (metaImdbId != null) return metaImdbId
    val tmdbId = candidates.firstNotNullOfOrNull(::extractParentalGuideTmdbId) ?: return null
    return TmdbService.tmdbToImdb(
        tmdbId = tmdbId,
        mediaType = contentType ?: parentMetaType,
    )
}

internal fun PlayerScreenRuntime.flushWatchProgress(
    scrobbleAction: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
) {
    when (scrobbleAction) {
        TrackingScrobbleAction.PAUSE -> emitTrackingScrobblePause()
        TrackingScrobbleAction.STOP -> emitStopScrobbleForCurrentProgress()
        TrackingScrobbleAction.START -> Unit
    }
    WatchProgressRepository.flushPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        contentEndPercent = currentContentEndPercent(),
    )
}

/**
 * Where the content really ends for the playback on screen, as a percentage, from the credits marker of
 * the release being played. The tracker and the local progress row both decide with this number, so a
 * playback that stopped before the credits is not read as finished by either of them.
 */
internal fun PlayerScreenRuntime.currentContentEndPercent(): Double? =
    contentEndIntervals.contentEndPercent(playbackSnapshot.durationMs)

internal fun PlayerScreenRuntime.scheduleProgressSyncAfterSeek() {
    val shouldRestartScrobbleAfterSeek = shouldPlay || playbackSnapshot.isPlaying
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(PlayerSeekProgressSyncDebounceMs)
        WatchProgressRepository.upsertPlaybackProgress(
            session = playbackSession,
            snapshot = playbackSnapshot,
            contentEndPercent = currentContentEndPercent(),
        )

        val progressPercent = currentPlaybackProgressPercent()
        if (
            !shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = hasRequestedScrobbleStartForCurrentItem,
                progressPercent = progressPercent,
            )
        ) {
            return@launch
        }

        val media = currentTrackingMedia ?: currentTrackingMedia()
        if (!media.hasResolvableIdentity) return@launch
        val stopEvent = TrackingScrobbleEvent(
            media = media,
            progressPercent = progressPercent.toDouble(),
        )
        scope.launch {
            TrackingScrobbleCoordinator.scrobbleSeek(
                profileId = profileId,
                action = TrackingScrobbleAction.STOP,
                event = stopEvent,
            )
            if (!shouldRestartScrobbleAfterSeek || !shouldPlay || playbackSnapshot.isEnded) return@launch
            if (playbackSnapshot.isPlaying) {
                pendingSeekScrobbleRestart = false
                TrackingScrobbleCoordinator.scrobbleSeek(
                    profileId = profileId,
                    action = TrackingScrobbleAction.START,
                    event = stopEvent.copy(
                        progressPercent = currentPlaybackProgressPercent().toDouble(),
                    ),
                )
            } else {
                pendingSeekScrobbleRestart = true
            }
        }
    }
}

internal fun PlayerScreenRuntime.persistPlaybackProgressTick() {
    val now = WatchProgressClock.nowEpochMs()
    if (now - lastProgressPersistEpochMs < PlaybackProgressPersistIntervalMs) return
    lastProgressPersistEpochMs = now
    WatchProgressRepository.upsertPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        syncRemote = false,
        contentEndPercent = currentContentEndPercent(),
    )
}
