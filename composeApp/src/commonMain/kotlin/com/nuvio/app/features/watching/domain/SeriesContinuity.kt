package com.nuvio.app.features.watching.domain

import com.nuvio.app.core.i18n.localizedPlayLabel
import com.nuvio.app.core.i18n.localizedResumeLabel
import com.nuvio.app.core.i18n.localizedUpNextLabel

const val DefaultContinueWatchingLimit = 20

private val watchingProgressRecencyComparator =
    compareBy<WatchingProgressRecord> { record -> record.lastUpdatedEpochMs }
        .thenBy { record -> record.seasonNumber ?: 0 }
        .thenBy { record -> record.episodeNumber ?: 0 }
        .thenBy(WatchingProgressRecord::lastPositionMs)
        .thenBy(WatchingProgressRecord::isCompleted)
        .thenBy(WatchingProgressRecord::identityKey)
        .thenBy(WatchingProgressRecord::videoId)

internal fun String?.isSeriesLikeWatchingContentType(): Boolean {
    val type = this?.trim() ?: return false
    return type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("show", ignoreCase = true) ||
        type.equals("tvshow", ignoreCase = true)
}

private fun WatchingContentRef.matchesWatchingContent(content: WatchingContentRef): Boolean {
    val bothSeriesLike = type.isSeriesLikeWatchingContentType() &&
        content.type.isSeriesLikeWatchingContentType()
    return if (bothSeriesLike) {
        id.trim() == content.id.trim()
    } else {
        this == content
    }
}

fun resumeProgressForSeries(
    content: WatchingContentRef,
    progressRecords: List<WatchingProgressRecord>,
): WatchingProgressRecord? = progressRecords
    .filter { record -> record.content.matchesWatchingContent(content) }
    .maxWithOrNull(watchingProgressRecencyComparator)
    ?.takeUnless { record -> record.isCompleted }

fun continueWatchingProgressEntries(
    progressRecords: List<WatchingProgressRecord>,
    limit: Int = DefaultContinueWatchingLimit,
): List<WatchingProgressRecord> {
    val (seriesEntries, nonSeriesEntries) = progressRecords.partition { record ->
        record.content.type.isSeriesLikeWatchingContentType() ||
            (record.seasonNumber != null && record.episodeNumber != null)
    }
    val latestPerSeries = seriesEntries
        .groupBy { record -> record.content.id.trim() }
        .values
        .mapNotNull { entries -> entries.maxWithOrNull(watchingProgressRecencyComparator) }

    return (nonSeriesEntries + latestPerSeries)
        .filterNot { record -> record.isCompleted }
        .sortedByDescending { record -> record.lastUpdatedEpochMs }
        .take(limit)
}

fun shouldPreferResume(
    resumeRecord: WatchingProgressRecord?,
    latestCompletedEpisode: WatchingCompletedEpisode?,
): Boolean = resumeRecord != null &&
    (latestCompletedEpisode == null || resumeRecord.lastUpdatedEpochMs > latestCompletedEpisode.markedAtEpochMs)

fun nextReleasedEpisodeAfter(
    content: WatchingContentRef,
    episodes: List<WatchingReleasedEpisode>,
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
    showUnairedNextUp: Boolean = false,
): WatchingReleasedEpisode? {
    val sortedEpisodes = episodes.sortedWith(
        compareBy<WatchingReleasedEpisode>({ normalizeSeasonNumber(it.seasonNumber) }, { it.episodeNumber ?: 0 }),
    )
    val watchedVideoId = buildPlaybackVideoId(content, seasonNumber, episodeNumber)
    var watchedIndex = sortedEpisodes.indexOfFirst { episode ->
        buildPlaybackVideoId(content, episode.seasonNumber, episode.episodeNumber, episode.videoId) == watchedVideoId
    }

    // Fallback: if the seed wasn't found by season+episode (anime with absolute
    // numbering on Trakt vs multi-season on addon), try global index matching.
    if (watchedIndex < 0 && seasonNumber != null && episodeNumber != null) {
        val mainEpisodes = sortedEpisodes.filter { episode -> normalizeSeasonNumber(episode.seasonNumber) > 0 }
        val addonSeasons = mainEpisodes.mapTo(mutableSetOf()) { episode ->
            normalizeSeasonNumber(episode.seasonNumber)
        }
        if (seasonNumber == 1 && addonSeasons.size > 1 && episodeNumber > 0) {
            val globalIndex = episodeNumber - 1
            if (globalIndex in mainEpisodes.indices) {
                watchedIndex = sortedEpisodes.indexOf(mainEpisodes[globalIndex])
            }
        }
    }

    if (watchedIndex < 0) return null

    val watchedEpisodeSeason = sortedEpisodes[watchedIndex].seasonNumber
    val candidates = sortedEpisodes
        .drop(watchedIndex + 1)
        .filter { episode ->
            shouldSurfaceNextEpisode(
                watchedSeasonNumber = watchedEpisodeSeason,
                candidateSeasonNumber = episode.seasonNumber,
                todayIsoDate = todayIsoDate,
                releasedDate = episode.releasedDate,
                showUnairedNextUp = showUnairedNextUp,
                available = episode.available,
            )
        }
    return candidates.firstOrNull { normalizeSeasonNumber(it.seasonNumber) > 0 }
}

fun decideSeriesPrimaryAction(
    content: WatchingContentRef,
    episodes: List<WatchingReleasedEpisode>,
    progressRecords: List<WatchingProgressRecord>,
    watchedRecords: List<WatchingWatchedRecord>,
    todayIsoDate: String,
    preferFurthestEpisode: Boolean = true,
    showUnairedNextUp: Boolean = false,
    defaultVideoId: String? = null,
    allowRewatch: Boolean = false,
): WatchingSeriesPrimaryAction? {
    val resumeRecord = resumeProgressForSeries(
        content = content,
        progressRecords = progressRecords,
    )
    val latestCompletedEpisode = latestCompletedSeriesEpisode(
        content = content,
        progressRecords = progressRecords,
        watchedRecords = watchedRecords,
        preferFurthestEpisode = preferFurthestEpisode,
    )

    if (shouldPreferResume(resumeRecord = resumeRecord, latestCompletedEpisode = latestCompletedEpisode)) {
        return resumeRecord?.toResumeAction()
    }

    val nextEpisode = if (latestCompletedEpisode != null) {
        nextReleasedEpisodeAfter(
            content = content,
            episodes = episodes,
            seasonNumber = latestCompletedEpisode.seasonNumber,
            episodeNumber = latestCompletedEpisode.episodeNumber,
            todayIsoDate = todayIsoDate,
            showUnairedNextUp = showUnairedNextUp,
        )
    } else {
        null
    }

    if (latestCompletedEpisode != null && nextEpisode == null && !allowRewatch) return null

    val playbackEpisode = nextEpisode ?: run {
        val sorted = episodes
            .sortedWith(compareBy<WatchingReleasedEpisode>({ normalizeSeasonNumber(it.seasonNumber) }, { it.episodeNumber ?: 0 }))
        val released = sorted.filter { episode ->
            isReleasedBy(
                todayIsoDate = todayIsoDate,
                releasedDate = episode.releasedDate,
                available = episode.available,
            )
        }
        defaultVideoId?.takeIf { latestCompletedEpisode == null }
            ?.let { videoId -> released.firstOrNull { it.videoId == videoId } }
            ?: released.firstOrNull { normalizeSeasonNumber(it.seasonNumber) > 0 }
            ?: released.firstOrNull()
    }

    return playbackEpisode?.let { episode ->
        WatchingSeriesPrimaryAction(
            label = if (nextEpisode != null) {
                upNextLabel(episode.seasonNumber, episode.episodeNumber)
            } else {
                playLabel(episode.seasonNumber, episode.episodeNumber)
            },
            videoId = buildPlaybackVideoId(
                content = content,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                fallbackVideoId = episode.videoId,
            ),
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            resumePositionMs = null,
        )
    }
}

fun buildPlaybackVideoId(
    content: WatchingContentRef,
    seasonNumber: Int?,
    episodeNumber: Int?,
    fallbackVideoId: String? = null,
): String =
    if (seasonNumber != null && episodeNumber != null) {
        "${content.id}:$seasonNumber:$episodeNumber"
    } else {
        fallbackVideoId?.takeIf { it.isNotBlank() } ?: content.id
    }

fun playLabel(seasonNumber: Int?, episodeNumber: Int?): String =
    localizedPlayLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber)

fun upNextLabel(seasonNumber: Int?, episodeNumber: Int?): String =
    localizedUpNextLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber)

fun resumeLabel(seasonNumber: Int?, episodeNumber: Int?): String =
    localizedResumeLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber)

private fun WatchingProgressRecord.toResumeAction(): WatchingSeriesPrimaryAction =
    WatchingSeriesPrimaryAction(
        label = resumeLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber),
        videoId = videoId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        resumePositionMs = lastPositionMs,
    )
