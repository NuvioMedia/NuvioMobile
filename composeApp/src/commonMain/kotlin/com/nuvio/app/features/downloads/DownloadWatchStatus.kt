package com.nuvio.app.features.downloads

import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState

internal data class DownloadWatchStatus(
    val isWatched: Boolean = false,
    val progress: Float = 0f,
    val remainingMs: Long = 0L,
)

internal fun DownloadItem.watchStatus(progressState: WatchProgressUiState): DownloadWatchStatus {
    val entry = progressState.progressForVideo(
        videoId = videoId,
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    if (entry != null && !entry.isEffectivelyCompleted && entry.progressFraction > 0f) {
        return DownloadWatchStatus(
            progress = entry.progressFraction,
            remainingMs = if (entry.durationMs > 0L) {
                (entry.durationMs - entry.lastPositionMs).coerceAtLeast(0L)
            } else {
                0L
            },
        )
    }
    return DownloadWatchStatus(
        isWatched = entry?.isEffectivelyCompleted == true || WatchedRepository.isWatched(
            id = parentMetaId,
            type = parentMetaType,
            season = seasonNumber,
            episode = episodeNumber,
        ),
    )
}
