package com.nuvio.app.features.watching.domain

import com.nuvio.app.core.i18n.localizedPlayLabel
import com.nuvio.app.core.i18n.localizedResumeLabel
import com.nuvio.app.core.i18n.localizedUpNextLabel
import com.nuvio.app.core.i18n.localizedWatchAgainLabel

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
    rewatchSeasonNumber: Int? = null,
    rewatchEpisodeNumber: Int? = null,
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

    // A rewatch the user is following from its Continue Watching card decides where Play goes: the
    // run is at that episode, so the next step of the run is the right offer even though the series
    // was watched to the end long ago.
    if (rewatchSeasonNumber != null && rewatchEpisodeNumber != null) {
        val runEpisode = nextReleasedEpisodeAfter(
            content = content,
            episodes = episodes,
            seasonNumber = rewatchSeasonNumber,
            episodeNumber = rewatchEpisodeNumber,
            todayIsoDate = todayIsoDate,
            showUnairedNextUp = showUnairedNextUp,
        )
        if (runEpisode != null) {
            return content.actionForEpisode(
                episode = runEpisode,
                label = upNextLabel(runEpisode.seasonNumber, runEpisode.episodeNumber),
            )
        }
    }

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
        firstReleasedEpisode(
            episodes = episodes,
            todayIsoDate = todayIsoDate,
            defaultVideoId = defaultVideoId,
        )
    }

    if (nextEpisode != null) {
        return content.actionForEpisode(
            episode = nextEpisode,
            label = if (latestCompletedEpisode != null) {
                upNextLabel(nextEpisode.seasonNumber, nextEpisode.episodeNumber)
            } else {
                playLabel(nextEpisode.seasonNumber, nextEpisode.episodeNumber)
            },
        )
    }

    // The series has been watched to its last episode, so there is no next one to offer. Watching it
    // again from the first episode keeps the button meaningful and, unlike a launch without an
    // episode, gives progress and tracking something real to record.
    if (latestCompletedEpisode == null) return null
    val firstEpisode = firstReleasedEpisode(
        episodes = episodes,
        todayIsoDate = todayIsoDate,
        defaultVideoId = null,
    )
    return firstEpisode?.let { episode ->
        content.actionForEpisode(
            episode = episode,
            label = watchAgainLabel(episode.seasonNumber, episode.episodeNumber),
            isWatchAgain = true,
        )
    }
}

private fun WatchingContentRef.actionForEpisode(
    episode: WatchingReleasedEpisode,
    label: String,
    isWatchAgain: Boolean = false,
): WatchingSeriesPrimaryAction =
    WatchingSeriesPrimaryAction(
        label = label,
        videoId = buildPlaybackVideoId(
            content = this,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            fallbackVideoId = episode.videoId,
        ),
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        episodeTitle = episode.title,
        episodeThumbnail = episode.thumbnail,
        resumePositionMs = null,
        isWatchAgain = isWatchAgain,
    )

/** The first episode the catalogue can actually play, used when a series starts from scratch. */
private fun firstReleasedEpisode(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
    defaultVideoId: String?,
): WatchingReleasedEpisode? {
    val sorted = episodes.sortedWith(
        compareBy<WatchingReleasedEpisode>(
            { normalizeSeasonNumber(it.seasonNumber) },
            { it.episodeNumber ?: 0 },
        ),
    )
    val released = sorted.filter { episode ->
        isReleasedBy(
            todayIsoDate = todayIsoDate,
            releasedDate = episode.releasedDate,
            available = episode.available,
        )
    }
    return defaultVideoId?.let { videoId -> released.firstOrNull { it.videoId == videoId } }
        ?: released.firstOrNull { normalizeSeasonNumber(it.seasonNumber) > 0 }
        ?: released.firstOrNull()
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

fun watchAgainLabel(seasonNumber: Int?, episodeNumber: Int?): String =
    localizedWatchAgainLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber)

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
