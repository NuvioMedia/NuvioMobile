package com.nuvio.app.features.player

import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.player.skip.ContentEndToleranceSeconds
import com.nuvio.app.features.player.skip.contentEndPercent
import com.nuvio.app.features.player.skip.contentEndWithTolerance
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_engine_switching_automatic_message
import nuvio.composeapp.generated.resources.player_engine_switching_manual_message
import kotlin.math.abs
import kotlin.time.TimeSource

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
    val reported = if (partyRateNudge != null) snapshot.copy(playbackSpeed = partyBaseRate()) else snapshot
    playbackSnapshot = if (reported.durationMs <= 0L && playbackSnapshotKey == playbackKey) {
        reported.copy(durationMs = playbackSnapshot.durationMs)
    } else {
        reported
    }
    playbackSnapshotKey = playbackKey
    playbackSnapshotMark = TimeSource.Monotonic.markNow()
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
        playbackEngine = playbackEngineOverride,
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
        lastSourceUrl = activeSourceUrl.takeUnless { ServerStreams.isServerSourceId(activeProviderAddonId) },
    )

internal fun PlayerScreenRuntime.currentLaunch(launch: PlayerLaunch): PlayerLaunch {
    val positionMs = playbackSnapshot.positionMs.takeIf {
        it > 0L && initialSeekApplied && playbackSnapshotKey == activePlaybackKey
    }
    return launch.copy(
        sourceUrl = activeSourceUrl,
        sourceAudioUrl = activeSourceAudioUrl,
        sourceHeaders = activeSourceHeaders,
        sourceResponseHeaders = activeSourceResponseHeaders,
        externalSubtitles = externalSubtitles,
        streamType = activeStreamType,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        streamTitle = activeStreamTitle,
        streamSubtitle = activeStreamSubtitle,
        bingeGroup = currentStreamBingeGroup,
        pauseDescription = activePauseDescription,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        videoId = activeVideoId,
        torrentInfoHash = activeTorrentInfoHash,
        torrentFileIdx = activeTorrentFileIdx,
        torrentFilename = activeTorrentFilename,
        torrentTrackers = activeTorrentTrackers,
        sourceFilename = activeSourceFilename,
        initialPositionMs = positionMs ?: activeInitialPositionMs,
        initialProgressFraction = activeInitialProgressFraction.takeIf { positionMs == null },
    )
}

private var lastIssuedScrobblePlaybackId = 0L

/**
 * A run id that a tracker can use to collapse the completions of one playback without touching a
 * later, separate viewing. The clock keeps it unique across player runtimes; the guard keeps two
 * runs started in the same millisecond apart.
 */
internal fun nextScrobblePlaybackId(): Long {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    lastIssuedScrobblePlaybackId =
        if (now > lastIssuedScrobblePlaybackId) now else lastIssuedScrobblePlaybackId + 1L
    return lastIssuedScrobblePlaybackId
}

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
        hasSentStartAboveCompletionBarForCurrentItem = false
        scrobbleStartRequestGeneration = 0L
        pendingSeekScrobbleRestart = false
        hasSentCompletionScrobbleForCurrentItem = false
        hasOpenedScrobbleSessionForCurrentItem = false
        currentTrackingMedia = null
        scrobblePlaybackId = nextScrobblePlaybackId()
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

internal fun PlayerScreenRuntime.snapshotTrackingScrobbleItemInputs(): TrackingScrobbleItemInputs {
    val inputs = TrackingScrobbleItemInputs(
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        videoId = activeVideoId,
        title = title,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
    )
    if (!ServerItemRef.isServerId(parentMetaId)) return inputs
    val imdbId = (metaUiState.meta ?: playerMeta)
        ?.takeIf { it.id == parentMetaId }
        ?.imdbId
        ?.takeIf { it.startsWith("tt") }
    return if (imdbId != null) inputs.copy(parentMetaId = imdbId, videoId = null) else inputs.copy(title = "")
}

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
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val currentProgress = currentPlaybackProgressPercent()
    /*
     * A playback that begins above the completion bar gets its start too, so the trackers that show
     * the user as watching can report it. That start opens nothing here, and Trakt skips it on its
     * own side, where a start and the stop that follows would record the same viewing twice.
     */
    if (currentProgress >= TRACKING_COMPLETION_BAR_PERCENT.toFloat()) {
        emitTrackingScrobbleStartAboveCompletionBar(currentProgress)
        return
    }
    // Under the bar again (a fresh playback, or one seeked back): the session below owns the item,
    // and a start already sent above the bar does not stand in its way.
    hasSentStartAboveCompletionBarForCurrentItem = false
    // The run has a session from here on, even if a pause later closes the one that is open.
    hasOpenedScrobbleSessionForCurrentItem = true
    if (hasRequestedScrobbleStartForCurrentItem) return
    // A new session is a new viewing: the completion latch of the run that ended before this one
    // must not suppress this run's own end.
    hasSentCompletionScrobbleForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = scrobbleStartRequestGeneration + 1L
    scrobbleStartRequestGeneration = requestGeneration

    scope.launch {
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
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
                hasPlaybackSession = true,
                playbackId = scrobblePlaybackId,
            ),
        )
    }
}

/*
 * The start of a playback that begins above the completion bar.
 *
 * The trackers that keep a "watching now" state of their own want to hear about the playback whatever
 * position it starts from, so the start goes out here instead of being dropped. It opens nothing
 * locally: it never arms the pause and stop policy, which is what keeps every other event exactly as
 * it is for a playback that was already past the bar. The provider that cannot take such a start,
 * Trakt, skips it in its own scrobbler.
 *
 * One start per playback stretch: the pause that ends it clears the mark, so the resume after the
 * pause reports the watching state again, and a seek back under the bar hands the item to the path
 * above.
 */
private fun PlayerScreenRuntime.emitTrackingScrobbleStartAboveCompletionBar(progressPercent: Float) {
    if (!shouldSendStartAboveCompletionBar(hasSentStartAboveCompletionBarForCurrentItem, progressPercent)) {
        return
    }
    hasSentStartAboveCompletionBarForCurrentItem = true
    // A new stretch of the playback has its own end, so a completion of the stretch before it does
    // not suppress this one. It still opens no session: the stop above the bar must not ask for a
    // rewatch for a viewing that was already past the bar when it began.
    hasSentCompletionScrobbleForCurrentItem = false
    val media = currentTrackingMedia()
    if (!media.hasResolvableIdentity) {
        hasSentStartAboveCompletionBarForCurrentItem = false
        return
    }
    currentTrackingMedia = media
    scope.launch {
        TrackingScrobbleCoordinator.scrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = progressPercent.toDouble(),
                sessionless = true,
                hasPlaybackSession = false,
                playbackId = scrobblePlaybackId,
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
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val percent = progressPercent ?: currentPlaybackProgressPercent()
    // A pause stands on a session or on the start above the bar; a stop also stands on the bar itself,
    // which is where a viewing ends and its finish has to reach the trackers.
    val allowed = if (action == TrackingScrobbleAction.PAUSE) {
        shouldSendPauseScrobble(
            hasActiveScrobble = hasRequestedScrobbleStartForCurrentItem,
            progressPercent = percent,
            hasStartAboveCompletionBar = hasSentStartAboveCompletionBarForCurrentItem,
        )
    } else {
        shouldSendStopScrobble(
            hasActiveScrobble = hasRequestedScrobbleStartForCurrentItem,
            progressPercent = percent,
            hasStartAboveCompletionBar = hasSentStartAboveCompletionBarForCurrentItem,
        )
    }
    if (!allowed) {
        return
    }
    /*
     * A pause or a stop below the bar for a playback no session owns: the trackers that take such
     * reports hear where the user left it, and the ones that would read it as a finished watch never
     * see it. Above the bar the stop stays as it is for everyone, because there it is the finish of a
     * viewing and the trackers that record it have to hear about it.
     */
    val sessionless = !hasRequestedScrobbleStartForCurrentItem &&
        hasSentStartAboveCompletionBarForCurrentItem &&
        (action == TrackingScrobbleAction.PAUSE || percent < TRACKING_COMPLETION_BAR_PERCENT.toFloat())
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    // IntroDB knows where the credits start, and a playback that reached them is over for the tracker
    // even when it stopped below the percentage the user set. Snapshot here, before the write leaves,
    // because the marker and the duration belong to this playback, not to the request.
    val contentEndPercent = currentContentEndPercent()
    val hasPlaybackSession = hasOpenedScrobbleSessionForCurrentItem
    val playbackId = scrobblePlaybackId
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
                sessionless = sessionless,
                hasPlaybackSession = hasPlaybackSession,
                playbackId = playbackId,
            ),
        )
    }
    currentTrackingMedia = null
    hasRequestedScrobbleStartForCurrentItem = false
    // The pause that ends this stretch clears the mark, so the resume after the pause reports the
    // watching state again; the stop ends the item for good.
    hasSentStartAboveCompletionBarForCurrentItem = false
    pendingSeekScrobbleRestart = false
    scrobbleStartRequestGeneration += 1L
}

internal fun PlayerScreenRuntime.emitStopScrobbleForCurrentProgress() {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val progressPercent = currentPlaybackProgressPercent()
    if (
        !shouldSendStopScrobble(
            hasActiveScrobble = hasRequestedScrobbleStartForCurrentItem,
            hasStartAboveCompletionBar = hasSentStartAboveCompletionBarForCurrentItem,
            progressPercent = progressPercent,
        )
    ) {
        return
    }
    if (progressPercent < TRACKING_COMPLETION_BAR_PERCENT.toFloat()) {
        emitTrackingScrobbleStop(progressPercent)
        return
    }

    if (!hasSentCompletionScrobbleForCurrentItem) {
        hasSentCompletionScrobbleForCurrentItem = true
        emitTrackingScrobbleStop(progressPercent)
    }
}

/**
 * The stop of a playback that reached its natural end.
 *
 * The end is 100% by definition: a position the engine reports just short of the duration would be a
 * pause to the tracker at a 100% threshold, and a viewing that reached the end has to complete. The
 * latch keeps the repeated snapshots of the ended state from sending it twice.
 */
internal fun PlayerScreenRuntime.emitCompletionScrobbleStop(progressPercent: Float) {
    // A short clip is a placeholder, not a viewing: the terminal call below would drop the event,
    // and the latch must not be set for one that never leaves.
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    if (progressPercent < TRACKING_COMPLETION_BAR_PERCENT.toFloat() ||
        hasSentCompletionScrobbleForCurrentItem
    ) {
        return
    }
    hasSentCompletionScrobbleForCurrentItem = true
    emitTrackingScrobbleStop(progressPercent)
}

/**
 * Whether a pause is worth reporting.
 *
 * An active session owns its playback below and above the bar, and a playback that only ever got its
 * start above the bar is reported by the tracker that took that start, so its pause goes out too and
 * the tracker learns where the user stopped.
 */
internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
    hasStartAboveCompletionBar: Boolean = false,
): Boolean = progressPercent in 0f..100f && (hasActiveScrobble || hasStartAboveCompletionBar)

/**
 * Whether a stop is worth reporting.
 *
 * Same as the pause, plus the rule the completion bar already had: a stop above the bar stands on its
 * own, because that is where a viewing ends and the trackers have to hear about the finish.
 */
internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
    hasStartAboveCompletionBar: Boolean = false,
): Boolean = hasActiveScrobble || hasStartAboveCompletionBar ||
    progressPercent >= TRACKING_COMPLETION_BAR_PERCENT.toFloat()

/**
 * Whether a playback that began above the completion bar still needs its start sent.
 *
 * Below the bar the start is the session the rest of the playback is reported through, and that path
 * owns it. Above the bar the start is only a "watching now" report, sent once per playback stretch:
 * a pause clears the mark, so the resume after the pause sends one again and the tracker sees the
 * playback continue.
 */
internal fun shouldSendStartAboveCompletionBar(
    hasSentAboveCompletionBar: Boolean,
    progressPercent: Float,
): Boolean =
    progressPercent.toDouble() >= TRACKING_COMPLETION_BAR_PERCENT && !hasSentAboveCompletionBar

internal fun shouldUpdateTrackingScrobbleAfterSeek(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
): Boolean = hasActiveScrobble && progressPercent >= 1f &&
    progressPercent < TRACKING_COMPLETION_BAR_PERCENT.toFloat()

internal fun PlayerScreenRuntime.emitTrackingSeekScrobbleStart() {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    scope.launch {
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
        val media = mediaSnapshot ?: inputsSnapshot.buildMedia()
        if (!media.hasResolvableIdentity) return@launch
        TrackingScrobbleCoordinator.scrobbleSeek(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = currentPlaybackProgressPercent().toDouble(),
                hasPlaybackSession = true,
                playbackId = scrobblePlaybackId,
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
    completionPercent: Float? = null,
) {
    when (scrobbleAction) {
        TrackingScrobbleAction.PAUSE -> emitTrackingScrobblePause()
        TrackingScrobbleAction.STOP -> if (completionPercent != null) {
            emitCompletionScrobbleStop(completionPercent)
        } else {
            emitStopScrobbleForCurrentProgress()
        }
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
 * the release being played, read [ContentEndToleranceSeconds] early. The tracker and the local progress
 * row both decide with this number, so a playback that stopped before the credits is not read as
 * finished by either of them, and neither of them takes the tolerance off a second time.
 */
internal fun PlayerScreenRuntime.currentContentEndPercent(): Double? =
    contentEndIntervals.contentEndPercent(playbackSnapshot.durationMs)
        .contentEndWithTolerance(playbackSnapshot.durationMs)

internal fun PlayerScreenRuntime.scheduleProgressSyncAfterSeek() {
    val shouldRestartScrobbleAfterSeek = shouldPlay || playbackSnapshot.isPlaying
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(PlayerSeekProgressSyncDebounceMs)
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
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
            hasPlaybackSession = true,
            playbackId = scrobblePlaybackId,
        )
        scope.launch {
            if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
            TrackingScrobbleCoordinator.scrobbleSeek(
                profileId = profileId,
                action = TrackingScrobbleAction.STOP,
                event = stopEvent,
            )
            if (!shouldRestartScrobbleAfterSeek || !shouldPlay || playbackSnapshot.isEnded ||
                isShortPlaceholderDuration(playbackSnapshot.durationMs)
            ) return@launch
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

internal val AndroidPlaybackEngine.switchTarget: AndroidPlaybackEngine
    get() = if (this == AndroidPlaybackEngine.Libmpv) AndroidPlaybackEngine.ExoPlayer else AndroidPlaybackEngine.Libmpv

internal fun PlayerScreenRuntime.switchPlaybackEngine() {
    val target = playerController?.playbackEngine?.switchTarget ?: return
    flushWatchProgress()
    if (initialSeekApplied) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    // Capture the current track selection so it can be restored on the new engine.
    rememberCurrentTrackPreferenceForEngineSwitch(target)
    playbackEngineOverride = target
    // A manual switch is an explicit user decision: never let the automatic engine
    // failover drag the user back to the engine they just switched away from.
    engineFailoverDisarmed = true
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.player_engine_switching_manual_message,
            messageArgs = listOf(target.label),
        ),
    )
}

/**
 * Switches to the opposite playback engine after a playback error, when the
 * "auto-switch engine on playback error" setting is enabled. Applies whichever engine is
 * selected; fires at most once per source, after the engine's own retries, and never after a
 * manual engine switch.
 *
 * @return true when the failover was started and the caller should swallow the error.
 */
internal fun PlayerScreenRuntime.maybeFailoverPlaybackEngine(
    resolvedAutoEngine: AndroidPlaybackEngine?,
): Boolean {
    if (!playerSettingsUiState.autoSwitchPlaybackEngineOnError) return false
    if (engineFailoverDisarmed) return false
    if (engineFailoverTriggered) return false

    val current = playerController?.playbackEngine
        ?: playbackEngineOverride
        ?: resolvedAutoEngine
        ?: playerSettingsUiState.androidPlaybackEngine
    val target = if (current == AndroidPlaybackEngine.Libmpv) {
        AndroidPlaybackEngine.ExoPlayer
    } else {
        AndroidPlaybackEngine.Libmpv
    }

    engineFailoverTriggered = true
    flushWatchProgress()
    if (initialSeekApplied) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    // Capture the current track selection so it can be restored on the new engine.
    rememberCurrentTrackPreferenceForEngineSwitch(target)
    playbackEngineOverride = target
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.player_engine_switching_automatic_message,
            messageArgs = listOf(target.label),
        ),
    )
    return true
}

internal fun PlayerScreenRuntime.openStreamInfo() {
    refreshTracks()
    val controller = playerController
    scope.launch {
        streamMediaInfo = controller?.getMediaInfo() ?: PlayerMediaInfo()
        showStreamInfo = true
    }
}
