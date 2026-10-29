package com.nuvio.app.features.player

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository

internal fun PlayerLaunch.externalPlaybackSession(): WatchProgressPlaybackSession = WatchProgressPlaybackSession(
    profileId = profileId,
    contentType = contentType ?: parentMetaType,
    parentMetaId = parentMetaId,
    parentMetaType = parentMetaType,
    videoId = videoId ?: parentMetaId,
    title = title,
    logo = logo,
    poster = poster,
    background = background,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
    episodeThumbnail = episodeThumbnail,
    providerName = providerName,
    providerAddonId = providerAddonId,
    lastStreamTitle = streamTitle,
    lastStreamSubtitle = streamSubtitle,
    pauseDescription = pauseDescription,
    lastSourceUrl = sourceUrl,
)

internal suspend fun recordExternalPlaybackProgress(
    result: ExternalPlaybackResult,
    fallbackSession: WatchProgressPlaybackSession?,
) {
    val session = result.playbackSession ?: fallbackSession ?: return
    // A completed playback may carry no position at all (MX Player), so only drop position-less
    // results that were not reported as played to the end.
    if (result.positionMs <= 0L && result.endedByUser) return
    val durationMs = result.durationMs
    if (durationMs != null && isShortPlaceholderDuration(durationMs)) return
    WatchProgressRepository.upsertPlaybackProgress(
        session = session,
        snapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            isEnded = !result.endedByUser,
            durationMs = durationMs ?: 0L,
            positionMs = result.positionMs,
        ),
    )
    val media = buildTrackingMediaReference(
        contentType = session.parentMetaType,
        parentMetaId = session.parentMetaId,
        videoId = session.videoId,
        title = session.title,
        seasonNumber = session.seasonNumber,
        episodeNumber = session.episodeNumber,
        episodeTitle = session.episodeTitle,
    )
    if (media.hasResolvableIdentity) {
        val completed = !result.endedByUser
        val knownDurationMs = durationMs ?: 0L
        // A result the user ended without a duration has no percentage to report, and a completion
        // without one is the end by definition. A start is not sent: this result arrives after the
        // external player closed, so a watching-now report would be stale by the time it lands.
        if (knownDurationMs > 0L || completed) {
            val progressPercent = if (completed || knownDurationMs <= 0L) {
                100.0
            } else {
                (result.positionMs.toDouble() / knownDurationMs.toDouble() * 100).coerceIn(0.0, 100.0)
            }
            runCatching {
                TrackingScrobbleCoordinator.scrobble(
                    profileId = session.profileId,
                    action = TrackingScrobbleAction.STOP,
                    event = TrackingScrobbleEvent(
                        media = media,
                        progressPercent = progressPercent,
                        hasPlaybackSession = true,
                        playbackId = nextScrobblePlaybackId(),
                    ),
                )
            }
        }
    }
}
