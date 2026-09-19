package com.nuvio.app.features.details

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watching.domain.WatchingCompletedEpisode
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.WatchingReleasedEpisode
import com.nuvio.app.features.watching.domain.WatchingSeriesPrimaryAction
import com.nuvio.app.features.watching.domain.WatchingWatchedRecord
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import com.nuvio.app.features.watching.domain.decideSeriesPrimaryAction
import com.nuvio.app.features.watching.domain.isReleasedBy
import com.nuvio.app.features.watching.domain.latestCompletedSeriesEpisode
import com.nuvio.app.features.watching.domain.playLabel
import com.nuvio.app.features.watching.domain.resumeLabel
import com.nuvio.app.features.watching.domain.shouldSurfaceNextEpisode
import com.nuvio.app.features.watching.domain.upNextLabel

internal fun MetaDetails.sortedPlayableEpisodes(): List<MetaVideo> =
    videos
        .filter { it.season != null || it.episode != null }
        .sortedWith(metaVideoSeasonEpisodeComparator)

internal fun List<MetaVideo>.filterUnavailableFutureSeasons(
    todayIsoDate: String,
): List<MetaVideo> {
    val unavailableSeasons = groupBy { episode -> normalizeSeasonNumber(episode.season) }
        .filter { (seasonNumber, episodes) ->
            if (seasonNumber <= 0) return@filter false
            val firstEpisode = episodes.minWithOrNull(
                compareBy<MetaVideo>({ it.episode ?: Int.MAX_VALUE }, { it.released.orEmpty() }),
            ) ?: return@filter false
            !firstEpisode.isReleasedBy(todayIsoDate)
        }
        .keys

    return if (unavailableSeasons.isEmpty()) {
        this
    } else {
        filter { episode -> normalizeSeasonNumber(episode.season) !in unavailableSeasons }
    }
}

internal fun MetaDetails.firstPlayableEpisode(): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull()

internal fun MetaDetails.firstReleasedPlayableEpisode(todayIsoDate: String): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull { video ->
        video.isReleasedBy(todayIsoDate)
    }

internal fun MetaDetails.nextReleasedEpisodeAfter(
    completedEntry: WatchProgressEntry,
    todayIsoDate: String,
): MetaVideo? =
    nextReleasedEpisodeAfter(
        seasonNumber = completedEntry.seasonNumber,
        episodeNumber = completedEntry.episodeNumber,
        todayIsoDate = todayIsoDate,
    )

internal fun MetaDetails.nextReleasedEpisodeAfter(
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
): MetaVideo? {
    return nextReleasedEpisodeAfter(
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        todayIsoDate = todayIsoDate,
        showUnairedNextUp = false,
    )
}

internal fun MetaDetails.nextReleasedEpisodeAfter(
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
    showUnairedNextUp: Boolean,
): MetaVideo? {
    val sortedEpisodes = sortedPlayableEpisodes()
    val watchedVideoId = buildPlaybackVideoId(
        content = WatchingContentRef(type = type, id = id),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    var watchedIndex = sortedEpisodes.indexOfFirst { episode ->
        buildPlaybackVideoId(
            content = WatchingContentRef(type = type, id = id),
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            fallbackVideoId = episode.id,
        ) == watchedVideoId
    }

    // Fallback: if the seed wasn't found by season+episode (anime with absolute
    // numbering on Trakt vs multi-season on addon), try global index matching.
    if (watchedIndex < 0 && seasonNumber != null && episodeNumber != null) {
        val mainEpisodes = sortedEpisodes.filter { episode -> normalizeSeasonNumber(episode.season) > 0 }
        val addonSeasons = mainEpisodes.mapTo(mutableSetOf()) { episode ->
            normalizeSeasonNumber(episode.season)
        }
        if (seasonNumber == 1 && addonSeasons.size > 1 && episodeNumber > 0) {
            val globalIndex = episodeNumber - 1
            if (globalIndex in mainEpisodes.indices) {
                watchedIndex = sortedEpisodes.indexOf(mainEpisodes[globalIndex])
            }
        }
    }

    if (watchedIndex < 0) return null

    val watchedEpisodeSeason = sortedEpisodes[watchedIndex].season
    val candidates = sortedEpisodes
        .drop(watchedIndex + 1)
        .filter { episode ->
            shouldSurfaceNextEpisode(
                watchedSeasonNumber = watchedEpisodeSeason,
                candidateSeasonNumber = episode.season,
                todayIsoDate = todayIsoDate,
                releasedDate = episode.released,
                showUnairedNextUp = showUnairedNextUp,
                available = episode.available,
            )
        }
    return candidates.firstOrNull { normalizeSeasonNumber(it.season) > 0 }
}

internal data class SeriesPrimaryAction(
    val label: String,
    val videoId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val resumePositionMs: Long?,
)

internal fun MetaDetails.seriesPrimaryAction(
    entries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    todayIsoDate: String,
    preferFurthestEpisode: Boolean = true,
    showUnairedNextUp: Boolean = false,
    watchedKeys: Set<String> = emptySet(),
): SeriesPrimaryAction? {
    val content = WatchingContentRef(type = type, id = id)
    val effectiveWatchedItems = buildList {
        addAll(watchedItems.filter { it.type.equals(type, ignoreCase = true) && it.id.equals(id, ignoreCase = true) })
        if (watchedKeys.isNotEmpty()) {
            val existingKeys = mapTo(mutableSetOf()) { watchedItemKey(it.type, it.id, it.season, it.episode) }
            videos.forEach { video ->
                val season = video.season ?: return@forEach
                val episode = video.episode ?: return@forEach
                val key = watchedItemKey(type, id, season, episode)
                if (key in existingKeys) return@forEach
                if (WatchingState.isEpisodeWatched(watchedKeys, type, id, video)) {
                    add(WatchedItem(id = id, type = type, season = season, episode = episode, name = "", markedAtEpochMs = 0L))
                }
            }
        }
    }
    return seriesPrimaryAction(
        content = content,
        entries = entries,
        watchedItems = effectiveWatchedItems,
        todayIsoDate = todayIsoDate,
        preferFurthestEpisode = preferFurthestEpisode,
        showUnairedNextUp = showUnairedNextUp,
    )
}

internal fun MetaDetails.seriesPrimaryAction(
    content: WatchingContentRef,
    entries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    todayIsoDate: String,
    preferFurthestEpisode: Boolean = true,
    showUnairedNextUp: Boolean = false,
): SeriesPrimaryAction? =
    decideSeriesPrimaryAction(
        content = content,
        episodes = videos.filterNot { video ->
            entries.any { entry -> entry.parentMetaId == content.id && entry.parentMetaType.equals(content.type, true) &&
                video.season in entry.excludedNextUpSeasons }
        }.map(MetaVideo::toDomainReleasedEpisode),
        progressRecords = entries.map(WatchProgressEntry::toDomainProgressRecord),
        watchedRecords = watchedItems.map(WatchedItem::toDomainWatchedRecord),
        todayIsoDate = todayIsoDate,
        preferFurthestEpisode = preferFurthestEpisode,
        showUnairedNextUp = showUnairedNextUp,
        defaultVideoId = defaultVideoId,
    )?.toLegacySeriesPrimaryAction()

internal fun MetaVideo.playLabel(): String =
    playLabel(seasonNumber = season, episodeNumber = episode)

internal fun MetaVideo.upNextLabel(): String =
    upNextLabel(seasonNumber = season, episodeNumber = episode)

internal fun WatchProgressEntry.resumeLabel(): String =
    resumeLabel(seasonNumber = seasonNumber, episodeNumber = episodeNumber)

internal fun MetaVideo.isReleasedBy(todayIsoDate: String): Boolean =
    isReleasedBy(
        todayIsoDate = todayIsoDate,
        releasedDate = released,
        available = available,
    )

internal data class CompletedSeriesEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

internal fun latestCompletedSeriesEpisode(
    parentMetaId: String,
    parentMetaType: String,
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
): CompletedSeriesEpisode? =
    latestCompletedSeriesEpisode(
        content = WatchingContentRef(type = parentMetaType, id = parentMetaId),
        progressRecords = progressEntries.map(WatchProgressEntry::toDomainProgressRecord),
        watchedRecords = watchedItems.map(WatchedItem::toDomainWatchedRecord),
    )?.toLegacyCompletedEpisode()

private fun MetaVideo.toDomainReleasedEpisode(): WatchingReleasedEpisode =
    WatchingReleasedEpisode(
        videoId = id,
        seasonNumber = season,
        episodeNumber = episode,
        title = title,
        thumbnail = thumbnail,
        releasedDate = released,
        available = available,
    )

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    WatchingProgressRecord(
        content = WatchingContentRef(type = parentMetaType, id = parentMetaId),
        videoId = videoId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastPositionMs = lastPositionMs,
        isCompleted = isCompleted,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
    )

private fun WatchedItem.toDomainWatchedRecord(): WatchingWatchedRecord =
    WatchingWatchedRecord(
        content = WatchingContentRef(type = type, id = id),
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = normalizeWatchedMarkedAtEpochMs(markedAtEpochMs),
    )

private fun WatchingSeriesPrimaryAction.toLegacySeriesPrimaryAction(): SeriesPrimaryAction =
    SeriesPrimaryAction(
        label = label,
        videoId = videoId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        resumePositionMs = resumePositionMs,
    )

private fun WatchingCompletedEpisode.toLegacyCompletedEpisode(): CompletedSeriesEpisode =
    CompletedSeriesEpisode(
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        markedAtEpochMs = markedAtEpochMs,
    )
