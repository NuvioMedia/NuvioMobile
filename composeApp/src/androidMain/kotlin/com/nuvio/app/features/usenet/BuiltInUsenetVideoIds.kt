package com.nuvio.app.features.usenet

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import java.util.Locale

/** The content types the built-in Usenet search covers. */
internal val BUILT_IN_USENET_TYPES = setOf("movie", "series", "tv", "show")

/** Season and episode from an episode video id (tt123:1:2, tmdb:10:1:2); nulls when it has none. */
internal fun episodeNumbers(videoId: String): Pair<Int?, Int?> {
    val parts = videoId.split(':')
    if (parts.size < if (videoId.startsWith("tmdb:")) 4 else 3) return null to null
    return parts[parts.lastIndex - 1].toIntOrNull() to parts.last().toIntOrNull()
}

/**
 * Whether the built-in search can look up [videoId]: a movie, or an episode that names its season
 * and episode. A series id alone is not searched, so it must not count as playable.
 */
internal fun builtInUsenetCanSearch(type: String, videoId: String): Boolean {
    val kind = type.lowercase()
    if (kind !in BUILT_IN_USENET_TYPES) return false
    if (kind == "movie") return true
    val (season, episode) = episodeNumbers(videoId)
    return season != null && season >= 0 && episode != null && episode >= 1
}

/** The anime catalogs whose ids indexers do not know. */
internal val ANIME_ID_PREFIXES = setOf("kitsu", "mal", "anilist", "anidb")

/**
 * The absolute episode number of an anime catalog's episode id (kitsu:1:5 is episode 5); null for
 * any other id. These catalogs have no seasons: an episode id has exactly three parts.
 */
internal fun absoluteAnimeEpisodeNumber(videoId: String): Int? {
    val parts = videoId.trim().split(':')
    if (parts.size != 3 || parts[0].lowercase(Locale.ROOT) !in ANIME_ID_PREFIXES) return null
    if (parts[1].isBlank() || parts[1].toLongOrNull() == null) return null
    return parts[2].toIntOrNull()
}

/** An anime catalog's episode or film as IMDb knows it; [season] and [episode] are null for a film. */
internal data class ImdbEquivalent(val imdbId: String, val season: Int?, val episode: Int?)

private val IMDB_ID = Regex("tt\\d+")

/**
 * The id of the meta that lists [videoId] when it is an anime catalog's id (kitsu:, mal:), which
 * indexers do not know: an episode's show (kitsu:1 for kitsu:1:5), or the film itself. Null for
 * any other id.
 */
internal fun animeMetaId(videoId: String, series: Boolean): String? {
    val id = videoId.trim()
    if (series) return id.takeIf { absoluteAnimeEpisodeNumber(it) != null }?.substringBeforeLast(':')
    val parts = id.split(':')
    return id.takeIf {
        parts.size == 2 && parts[0].lowercase(Locale.ROOT) in ANIME_ID_PREFIXES && parts[1].toLongOrNull() != null
    }
}

/**
 * [videoId] on IMDb when the title it belongs to has an IMDb id of its own ([contentId]): an
 * episode then keeps the title's numbering ([season], [episode]), as on any IMDb title, unless its
 * [video] gives its IMDb numbering. Null when [contentId] is not an IMDb id or an episode has no
 * numbering.
 */
internal fun contentImdbEquivalent(contentId: String?, video: MetaVideo?, series: Boolean,
    season: Int?, episode: Int?): ImdbEquivalent? {
    val imdb = contentId?.trim()?.takeIf { IMDB_ID.matches(it) } ?: return null
    if (!series) return ImdbEquivalent(imdb, null, null)
    val imdbSeason = video?.imdbSeason?.takeIf { it >= 0 }
    val imdbEpisode = video?.imdbEpisode?.takeIf { it >= 1 }
    if (imdbSeason != null && imdbEpisode != null) return ImdbEquivalent(imdb, imdbSeason, imdbEpisode)
    val ownSeason = season?.takeIf { it >= 0 } ?: return null
    val ownEpisode = episode?.takeIf { it >= 1 } ?: return null
    return ImdbEquivalent(imdb, ownSeason, ownEpisode)
}

/**
 * [videoId] on IMDb, as its anime catalog's [meta] gives it. An episode needs its IMDb season and
 * episode too: the catalog's own numbers restart with each season's entry or run past a thousand,
 * and would name another episode. Null when the meta does not say.
 */
internal fun imdbEquivalent(meta: MetaDetails, videoId: String, series: Boolean): ImdbEquivalent? {
    if (!series) return meta.imdbId?.trim()?.takeIf { IMDB_ID.matches(it) }?.let { ImdbEquivalent(it, null, null) }
    val video = meta.videos.firstOrNull { it.id == videoId.trim() } ?: return null
    val imdb = (video.imdbId ?: meta.imdbId?.trim())?.takeIf { IMDB_ID.matches(it) } ?: return null
    val season = video.imdbSeason?.takeIf { it >= 0 } ?: return null
    val episode = video.imdbEpisode?.takeIf { it >= 1 } ?: return null
    return ImdbEquivalent(imdb, season, episode)
}

/**
 * The season and episode the engine picks a pack's file by: those the release was searched by
 * when they differ from the episode's own, the episode's otherwise.
 */
internal fun engineEpisode(stream: StreamItem, season: Int?, episode: Int?): Pair<Int?, Int?> {
    val searchedSeason = stream.usenetSeason
    val searchedEpisode = stream.usenetEpisode
    return if (searchedSeason != null && searchedEpisode != null) searchedSeason to searchedEpisode else season to episode
}
