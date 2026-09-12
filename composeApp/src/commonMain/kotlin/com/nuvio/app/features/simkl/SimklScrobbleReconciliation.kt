package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingExternalIds

internal fun SimklSyncSnapshot.applyScrobbleResult(
    result: SimklScrobbleResult,
    committedAtEpochMs: Long,
): SimklSyncSnapshot {
    val localCommittedAt = committedAtEpochMs.epochMsToUtcIso()
        ?: return this
    return when (result.outcome) {
        SimklScrobbleOutcome.START -> this
        SimklScrobbleOutcome.PAUSE -> withPausedScrobble(result, localCommittedAt)
        SimklScrobbleOutcome.SCROBBLE -> withCompletedScrobble(
            result = result,
            committedAt = result.watchedAt
                ?.takeIf { value -> parseSimklUtcEpochMs(value) != null }
                ?: localCommittedAt,
        )
    }.reconcileWatchedPlayback()
}

private fun SimklSyncSnapshot.withPausedScrobble(
    result: SimklScrobbleResult,
    committedAt: String,
): SimklSyncSnapshot {
    val existingEntry = entries.matchingEntry(result)
    val existingSession = playback.firstOrNull { session ->
        session.media?.matchesTarget(result.media) == true
    }
    val media = result.media
        .mergeMissing(existingEntry?.media)
        .mergeMissing(existingSession?.media)
    val mediaType = existingEntry?.mediaType ?: result.mediaType
    val isAnimeMovie = mediaType == SimklMediaType.ANIME &&
        (existingEntry?.animeType == "movie" || result.episode == null)
    val session = SimklPlaybackSession(
        id = result.playbackId ?: existingSession?.id,
        progress = result.progress,
        pausedAt = committedAt,
        type = if (mediaType == SimklMediaType.MOVIES || isAnimeMovie) "movie" else "episode",
        episode = result.episode?.mergeMissing(existingSession?.episode),
        show = media.takeIf { mediaType == SimklMediaType.SHOWS },
        anime = media.takeIf { mediaType == SimklMediaType.ANIME && !isAnimeMovie },
        movie = media.takeIf { mediaType == SimklMediaType.MOVIES || isAnimeMovie },
    )
    return copy(
        playback = playback.filterNot { candidate ->
            candidate.media?.matchesTarget(result.media) == true
        } + session,
    )
}

private fun SimklSyncSnapshot.withCompletedScrobble(
    result: SimklScrobbleResult,
    committedAt: String,
): SimklSyncSnapshot {
    val updatedEntries = entries.toMutableList()
    val index = updatedEntries.indexOfMatchingEntry(result)
    val existing = updatedEntries.getOrNull(index)
    val updated = when {
        result.mediaType == SimklMediaType.MOVIES ||
            (result.mediaType == SimklMediaType.ANIME &&
                (existing?.animeType == "movie" || result.episode == null)) -> existing
            ?.withWatchedMovie(result, committedAt)
            ?: result.toWatchedMovieEntry(committedAt)
        else -> existing
            ?.withWatchedEpisode(result, committedAt)
            ?: result.toWatchedSeriesEntry(committedAt)
    }
    if (index >= 0) {
        updatedEntries[index] = updated
    } else {
        updatedEntries += updated
    }
    return copy(
        entries = updatedEntries,
        playback = playback.filterNot { session ->
            session.media?.matchesTarget(result.media) == true
        },
    )
}

private fun SimklLibraryEntry.withWatchedMovie(
    result: SimklScrobbleResult,
    committedAt: String,
): SimklLibraryEntry = copy(
    mediaType = result.mediaType,
    animeType = if (result.mediaType == SimklMediaType.ANIME) "movie" else animeType,
    lastWatchedAt = committedAt,
    status = SimklListStatus.COMPLETED,
    movie = result.media.mergeMissing(media),
    show = null,
)

private fun SimklScrobbleResult.toWatchedMovieEntry(committedAt: String): SimklLibraryEntry =
    SimklLibraryEntry(
        mediaType = mediaType,
        animeType = if (mediaType == SimklMediaType.ANIME) "movie" else null,
        lastWatchedAt = committedAt,
        status = SimklListStatus.COMPLETED,
        movie = media,
    )

private fun SimklLibraryEntry.withWatchedEpisode(
    result: SimklScrobbleResult,
    committedAt: String,
): SimklLibraryEntry {
    val target = result.episode ?: return this
    val update = seasons.withWatchedEpisode(target, committedAt)
    return copy(
        lastWatchedAt = committedAt,
        watchedEpisodesCount = watchedEpisodesCount + if (update.wasAlreadyWatched) 0 else 1,
        show = result.media.mergeMissing(media),
        movie = null,
        seasons = update.seasons,
    )
}

private fun SimklScrobbleResult.toWatchedSeriesEntry(committedAt: String): SimklLibraryEntry {
    val target = episode
    val update = if (target == null) {
        EpisodeUpdate(emptyList(), wasAlreadyWatched = false)
    } else {
        emptyList<SimklSeason>().withWatchedEpisode(target, committedAt)
    }
    return SimklLibraryEntry(
        mediaType = mediaType,
        lastWatchedAt = committedAt,
        status = SimklListStatus.WATCHING,
        watchedEpisodesCount = if (target == null) 0 else 1,
        show = media,
        seasons = update.seasons,
    )
}

private data class EpisodeUpdate(
    val seasons: List<SimklSeason>,
    val wasAlreadyWatched: Boolean,
)

private fun List<SimklSeason>.withWatchedEpisode(
    target: SimklPlaybackEpisode,
    committedAt: String,
): EpisodeUpdate {
    val targetNumber = target.number ?: return EpisodeUpdate(this, wasAlreadyWatched = false)
    val targetSeason = target.season ?: 1
    val targetMapping = if (target.tvdbSeason != null && target.tvdbNumber != null) {
        SimklEpisodeMapping(target.tvdbSeason, target.tvdbNumber)
    } else {
        null
    }
    var matched = false
    var wasAlreadyWatched = false
    val updated = map { season ->
        val updatedEpisodes = season.episodes.map { episode ->
            if (!episode.matches(targetSeason, targetNumber, targetMapping, season.number)) {
                episode
            } else {
                matched = true
                wasAlreadyWatched = episode.watchedAt != null
                episode.copy(
                    watchedAt = committedAt,
                    tvdb = targetMapping ?: episode.tvdb,
                )
            }
        }
        if (matched || season.number != targetSeason) {
            season.copy(episodes = updatedEpisodes)
        } else {
            matched = true
            season.copy(
                episodes = updatedEpisodes + SimklEpisode(
                    number = targetNumber,
                    watchedAt = committedAt,
                    tvdb = targetMapping,
                ),
            )
        }
    }.toMutableList()
    if (!matched) {
        updated += SimklSeason(
            number = targetSeason,
            episodes = listOf(
                SimklEpisode(
                    number = targetNumber,
                    watchedAt = committedAt,
                    tvdb = targetMapping,
                ),
            ),
        )
    }
    return EpisodeUpdate(updated, wasAlreadyWatched)
}

private fun SimklEpisode.matches(
    targetSeason: Int,
    targetNumber: Int,
    targetMapping: SimklEpisodeMapping?,
    seasonNumber: Int?,
): Boolean {
    if (
        targetMapping?.season != null &&
        targetMapping.episode != null &&
        tvdb?.season == targetMapping.season &&
        tvdb.episode == targetMapping.episode
    ) {
        return true
    }
    return seasonNumber == targetSeason && number == targetNumber
}

private fun SimklPlaybackEpisode.mergeMissing(
    fallback: SimklPlaybackEpisode?,
): SimklPlaybackEpisode {
    if (fallback == null) return this
    return copy(
        season = season ?: fallback.season,
        number = number ?: fallback.number,
        title = title?.takeIf(String::isNotBlank) ?: fallback.title,
        tvdbSeason = tvdbSeason ?: fallback.tvdbSeason,
        tvdbNumber = tvdbNumber ?: fallback.tvdbNumber,
    )
}

private fun List<SimklLibraryEntry>.matchingEntry(
    result: SimklScrobbleResult,
): SimklLibraryEntry? = getOrNull(indexOfMatchingEntry(result))

private fun List<SimklLibraryEntry>.indexOfMatchingEntry(
    result: SimklScrobbleResult,
): Int {
    val sameType = indexOfFirst { entry ->
        entry.mediaType == result.mediaType &&
            entry.media?.matchesTarget(result.media) == true
    }
    return if (sameType >= 0) {
        sameType
    } else {
        indexOfFirst { entry -> entry.media?.matchesTarget(result.media) == true }
    }
}

internal fun SimklMedia.matchesTarget(target: SimklMedia): Boolean {
    val candidateIds = toTrackingExternalIds()
    val targetIds = target.toTrackingExternalIds()
    candidateIds.comparableMatch(targetIds)?.let { return it }
    return title?.trim()?.equals(target.title?.trim(), ignoreCase = true) == true &&
        (year == null || target.year == null || year == target.year)
}

private fun TrackingExternalIds.comparableMatch(target: TrackingExternalIds): Boolean? {
    if (simkl != null && target.simkl != null) return simkl == target.simkl
    if (mal != null && target.mal != null) return mal == target.mal
    if (anidb != null && target.anidb != null) return anidb == target.anidb
    if (anilist != null && target.anilist != null) return anilist == target.anilist
    if (kitsu != null && target.kitsu != null) return kitsu == target.kitsu
    if (!tvdb.isNullOrBlank() && !target.tvdb.isNullOrBlank()) {
        return tvdb.equals(target.tvdb, ignoreCase = true)
    }
    if (!imdb.isNullOrBlank() && !target.imdb.isNullOrBlank()) {
        return imdb.equals(target.imdb, ignoreCase = true)
    }
    if (tmdb != null && target.tmdb != null) return tmdb == target.tmdb
    return null
}

/**
 * True when the item (or the exact episode) this scrobble just completed was already on the
 * user's Simkl history before this playback, i.e. finishing it again is a genuine rewatch.
 *
 * Mirrors how completed scrobbles are stored in [SimklLibraryEntry.seasons]: the episode must
 * carry a `watchedAt` and match either the Simkl/TVDB coordinates or the plain season/number.
 * Movies and anime movies count as rewatchable once a canonical (non-plan-to-watch) entry exists.
 */
internal fun SimklSyncSnapshot.hasPriorWatch(result: SimklScrobbleResult): Boolean {
    val entry = entries.firstOrNull { candidate ->
        (candidate.mediaType == result.mediaType &&
            candidate.media?.matchesTarget(result.media) == true) ||
            candidate.media?.matchesTarget(result.media) == true
    } ?: return false
    val isMovieLike = result.mediaType == SimklMediaType.MOVIES ||
        (result.mediaType == SimklMediaType.ANIME && result.episode == null)
    if (isMovieLike) {
        return entry.isMovieEntry() && entry.status != SimklListStatus.PLAN_TO_WATCH
    }
    val target = result.episode ?: return false
    val targetNumber = target.number ?: return false
    val targetSeason = target.season ?: 1
    val targetMapping = if (target.tvdbSeason != null && target.tvdbNumber != null) {
        SimklEpisodeMapping(season = target.tvdbSeason, episode = target.tvdbNumber)
    } else {
        null
    }
    return entry.seasons.any { season ->
        season.episodes.any { episode ->
            episode.watchedAt != null &&
                episode.matches(
                    targetSeason = targetSeason,
                    targetNumber = targetNumber,
                    targetMapping = targetMapping,
                    seasonNumber = season.number,
                )
        }
    }
}
