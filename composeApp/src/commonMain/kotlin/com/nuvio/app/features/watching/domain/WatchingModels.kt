package com.nuvio.app.features.watching.domain

data class WatchingContentRef(
    val type: String,
    val id: String,
)

data class WatchingEpisodeRef(
    val content: WatchingContentRef,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class WatchingWatchedRecord(
    val content: WatchingContentRef,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val markedAtEpochMs: Long,
)

data class WatchingProgressRecord(
    val content: WatchingContentRef,
    val videoId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val lastUpdatedEpochMs: Long,
    val lastPositionMs: Long = 0L,
    val isCompleted: Boolean = false,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val identityKey: String = videoId,
)

data class WatchingReleasedEpisode(
    val videoId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val releasedDate: String? = null,
    val available: Boolean = true,
)

data class WatchingCompletedEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

data class WatchingSeriesPrimaryAction(
    val label: String,
    val videoId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val resumePositionMs: Long?,
    /**
     * True when the action restarts a series that has been watched to its end. Continue Watching
     * must not treat that as a next episode: the row would fill up with S01E01 cards for every
     * series the user ever finished.
     */
    val isWatchAgain: Boolean = false,
)
