package com.nuvio.app.features.simkl

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.tracking.RewatchRunPosition
import com.nuvio.app.features.tracking.TrackingMediaReference

/**
 * Reads the rewatch sessions of an account and keeps the ones that look like a run.
 *
 * Simkl keeps a rewatch in its own session and never moves the canonical watch position, so a client
 * that reads only the canonical row offers the episode the user finished months ago, or nothing at
 * all. The sessions are the only place that knows where the run is, and they live on the account, so
 * a run read from here shows up on every device the user signs in on.
 *
 * Sessions are merged per series first, because Simkl splits a running rewatch into a new session
 * once the same episode is rewatched 48 hours later; the run itself continues across the split.
 * Episodes then have to form a chain of consecutive numbers inside one season, and the chain holding
 * the most recently rewatched episode is the run. How long that chain has to be is the user's
 * choice; see [SimklRewatchNextUpMode].
 */
internal fun deriveSimklRewatchRuns(
    entries: List<SimklLibraryEntry>,
    animeIdPreference: SimklAnimeIdPreference,
    minimumRunEpisodes: Int?,
): List<RewatchRunPosition> {
    val requiredEpisodes = minimumRunEpisodes ?: return emptyList()
    val sessions = entries.filter { entry -> entry.isRewatch && entry.media != null }
    if (sessions.isEmpty()) return emptyList()
    return sessions
        .groupBy { entry -> entry.media?.canonicalContentId(animeIdPreference).orEmpty() }
        .filterKeys { contentId -> contentId.isNotEmpty() }
        .mapNotNull { (contentId, rows) ->
            buildRewatchRun(contentId, rows, animeIdPreference, requiredEpisodes)
        }
        .sortedByDescending(RewatchRunPosition::markedAtEpochMs)
}

/**
 * What one read of the rewatch sessions produced: the runs the app offers and the sessions they were
 * derived from.
 *
 * The sessions are kept on the snapshot next to the runs, so a change of [SimklRewatchNextUpMode] can
 * re-derive the runs straight away instead of waiting for the next read of the account.
 */
internal data class SimklRewatchRead(
    val runs: List<RewatchRunPosition>,
    val sessions: List<SimklLibraryEntry>,
)

private fun buildRewatchRun(
    contentId: String,
    rows: List<SimklLibraryEntry>,
    animeIdPreference: SimklAnimeIdPreference,
    minimumRunEpisodes: Int,
): RewatchRunPosition? {
    val episodes = rows
        .flatMap { row -> row.rewatchedEpisodes() }
        .newestPerEpisode()
    if (episodes.isEmpty()) return null
    val newest = episodes.maxWith(
        compareBy(
            { episode -> episode.watchedAtEpochMs ?: Long.MIN_VALUE },
            { episode -> episode.seasonNumber },
            { episode -> episode.episodeNumber },
        ),
    )
    val chain = consecutiveChains(episodes)
        .firstOrNull { candidate -> candidate.any { it.isSameEpisodeAs(newest) } }
        ?: return null
    if (chain.size < minimumRunEpisodes) return null
    val position = chain.maxBy { episode -> episode.episodeNumber }
    return RewatchRunPosition(
        contentId = contentId,
        matchKeys = rows.firstNotNullOfOrNull(SimklLibraryEntry::media)?.rewatchMatchKeys(contentId).orEmpty(),
        seasonNumber = position.seasonNumber,
        episodeNumber = position.episodeNumber,
        markedAtEpochMs = newest.watchedAtEpochMs ?: position.watchedAtEpochMs ?: 0L,
    )
}

private data class RewatchedEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val watchedAtEpochMs: Long?,
) {
    fun isSameEpisodeAs(other: RewatchedEpisode): Boolean =
        seasonNumber == other.seasonNumber && episodeNumber == other.episodeNumber
}

/** Every episode the session rows carry, falling back to the row date when the episode has none. */
private fun SimklLibraryEntry.rewatchedEpisodes(): List<RewatchedEpisode> {
    val rowWatchedAt = lastWatchedAt?.let(::parseZonedIsoDateTimeToEpochMs)
    return seasons.flatMap { season ->
        val seasonNumber = season.number ?: 0
        season.episodes.mapNotNull { episode ->
            val episodeNumber = episode.number?.takeIf { number -> number > 0 } ?: return@mapNotNull null
            RewatchedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                watchedAtEpochMs = episode.watchedAt?.let(::parseZonedIsoDateTimeToEpochMs) ?: rowWatchedAt,
            )
        }
    }
}

/** Keeps one row per episode, dated with the latest time it was rewatched across all sessions. */
private fun List<RewatchedEpisode>.newestPerEpisode(): List<RewatchedEpisode> =
    groupBy { episode -> episode.seasonNumber to episode.episodeNumber }
        .values
        .map { sameEpisode -> sameEpisode.maxBy { episode -> episode.watchedAtEpochMs ?: Long.MIN_VALUE } }

/** Splits episodes into chains of consecutive numbers, per season. */
private fun consecutiveChains(episodes: List<RewatchedEpisode>): List<List<RewatchedEpisode>> =
    episodes
        .groupBy(RewatchedEpisode::seasonNumber)
        .values
        .flatMap { seasonEpisodes ->
            seasonEpisodes
                .sortedBy(RewatchedEpisode::episodeNumber)
                .fold(mutableListOf<MutableList<RewatchedEpisode>>()) { chains, episode ->
                    val running = chains.lastOrNull()
                    if (running != null && episode.episodeNumber == running.last().episodeNumber + 1) {
                        running.add(episode)
                    } else {
                        chains.add(mutableListOf(episode))
                    }
                    chains
                }
        }

/**
 * True when the account's rewatch sessions hold this exact episode.
 *
 * A write to `/sync/history` answers `not_found` for an episode that is already in the history, even
 * when Simkl opened a rewatch session for it, so the write receipt says "nothing was added" for a
 * rewatch that landed. The sessions are the only honest answer, and they are what Continue Watching
 * reads as well.
 */
internal fun List<SimklLibraryEntry>.holdsRewatchEpisode(media: TrackingMediaReference): Boolean {
    val episode = media.episode ?: return false
    val target = media.toSimklMedia()
    return any { entry ->
        entry.isRewatch &&
            entry.media?.matchesTarget(target) == true &&
            entry.rewatchedEpisodes().any { rewatched ->
                rewatched.seasonNumber == episode.season && rewatched.episodeNumber == episode.number
            }
    }
}

/**
 * True when any session holds a rewatch at this season and episode, whoever the show belongs to.
 *
 * Used only to tell a write that errored but landed from one that did not. Identifying the show is
 * besides the point there: the coordinates come from the episode just written, and the account only
 * holds what the user put there.
 */
internal fun List<SimklLibraryEntry>.holdsRewatchAt(
    seasonNumber: Int,
    episodeNumber: Int,
): Boolean = any { entry ->
    entry.isRewatch &&
        entry.rewatchedEpisodes().any { rewatched ->
            rewatched.seasonNumber == seasonNumber && rewatched.episodeNumber == episodeNumber
        }
}

internal fun SimklMedia.rewatchMatchKeys(contentId: String): List<String> = buildList {
    add(contentId)
    ids.idValue("imdb")?.let { imdb -> add(imdb); add("imdb:$imdb") }
    ids.idValue("tmdb")?.let { tmdb -> add("tmdb:$tmdb") }
    ids.idValue("tvdb")?.let { tvdb -> add("tvdb:$tvdb") }
    ids.simklIdValue()?.let { simkl -> add("simkl:$simkl") }
}.distinct()
