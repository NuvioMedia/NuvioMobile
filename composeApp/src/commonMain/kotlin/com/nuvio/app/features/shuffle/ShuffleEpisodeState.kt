package com.nuvio.app.features.shuffle

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SeriesPrimaryAction
import com.nuvio.app.features.details.playLabel
import com.nuvio.app.features.details.resumeLabel
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressEntry

internal fun watchedShuffleEpisodes(
    contentId: String,
    contentType: String,
    videos: List<MetaVideo>,
    watchedKeys: Set<String>,
): Set<Pair<Int, Int>> = videos.mapNotNull { video ->
    val season = video.season ?: return@mapNotNull null
    val episode = video.episode ?: return@mapNotNull null
    (season to episode).takeIf {
        WatchingState.isEpisodeWatched(watchedKeys, contentType, contentId, video)
    }
}.toSet()

internal fun shuffleEpisodeProgress(
    contentId: String,
    entries: Collection<WatchProgressEntry>,
): Map<Pair<Int, Int>, WatchProgressEntry> = entries
    .filter { it.parentMetaId == contentId && it.seasonNumber != null && it.episodeNumber != null }
    .groupBy { it.seasonNumber!! to it.episodeNumber!! }
    .mapValues { (_, progress) -> progress.maxBy { it.lastUpdatedEpochMs } }

internal fun MetaDetails.shufflePrimaryAction(
    profileId: Int,
    settings: EpisodeShuffleSettings,
    entries: List<WatchProgressEntry>,
    watchedKeys: Set<String>,
    visit: Long,
    shuffle: EpisodeShuffle = EpisodeShuffleRepository.shuffle,
    surface: ShuffleSurface = ShuffleSurface.DETAIL,
): SeriesPrimaryAction? {
    val resume = entries.filter {
        it.parentMetaId == id && it.seasonNumber != null && it.episodeNumber != null &&
            it.videoId.isNotBlank() && !it.isEffectivelyCompleted &&
            (it.lastPositionMs > 0 || it.progressFraction > 0)
    }.maxByOrNull { it.lastUpdatedEpochMs }
    if (resume != null) return SeriesPrimaryAction(
        label = resume.resumeLabel(), videoId = resume.videoId,
        seasonNumber = resume.seasonNumber, episodeNumber = resume.episodeNumber,
        episodeTitle = resume.episodeTitle, episodeThumbnail = resume.episodeThumbnail,
        resumePositionMs = resume.lastPositionMs,
    )
    val selected = shuffle.select(
        profileId, id, videos, settings.includeWatched,
        watchedShuffleEpisodes(id, type, videos, watchedKeys), shuffleEpisodeProgress(id, entries),
        surface, visit = visit,
    ) ?: return null
    return SeriesPrimaryAction(
        label = selected.playLabel(), videoId = selected.id,
        seasonNumber = selected.season, episodeNumber = selected.episode,
        episodeTitle = selected.title, episodeThumbnail = selected.thumbnail,
        resumePositionMs = null,
    )
}
