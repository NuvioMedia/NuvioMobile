package com.nuvio.app.features.servers.silo

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.servers.ServerCandidate
import com.nuvio.app.features.servers.ServerIndexEntry
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerTitle
import com.nuvio.app.features.servers.ServerUserState
import com.nuvio.app.features.tracking.TrackingExternalIds
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Turns Silo's catalog answers into the app's titles, with links to [address]'s artwork. */
internal class SiloMapper(address: String, private val connectionId: String) {
    private val origin = runCatching {
        URLBuilder(Url(address)).apply {
            pathSegments = emptyList()
            parameters.clear()
            fragment = ""
        }.buildString().trimEnd('/')
    }.getOrDefault(address.trimEnd('/'))

    fun ref(contentId: String): String = ServerItemRef(connectionId, contentId).encode()

    fun artwork(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("/") -> origin + value
            else -> null
        }
    }

    /** Silo signs artwork for a few hours; the link names its title so it can be renewed later. */
    fun artwork(url: String?, contentId: String, kind: SiloArtworkKind): String? =
        artwork(url)?.let { SiloArtworkLink.mark(it, connectionId, contentId, kind) }

    fun preview(item: CatalogItemWire): MetaPreview? {
        val kind = siloKind(item.type) ?: return null
        if (item.contentId.isBlank()) return null
        return MetaPreview(
            id = ref(item.contentId),
            type = kind.contentType,
            name = item.title,
            poster = artwork(item.posterUrl, item.contentId, SiloArtworkKind.POSTER),
            banner = artwork(item.backdropUrl, item.contentId, SiloArtworkKind.BACKGROUND),
            logo = artwork(item.logoUrl, item.contentId, SiloArtworkKind.LOGO),
            posterShape = PosterShape.Poster,
            description = item.overview,
            releaseInfo = item.year?.toString(),
            rawReleaseDate = (item.releaseDate ?: item.firstAirDate)?.take(10),
            imdbRating = item.ratingImdb?.oneDecimal(),
            genres = item.genres,
        )
    }

    fun title(item: CatalogItemWire): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    fun details(item: CatalogItemWire, episodes: List<EpisodeWire>): MetaDetails {
        val kind = siloKind(item.type) ?: ServerMediaKind.MOVIE
        return MetaDetails(
            id = ref(item.contentId),
            type = kind.contentType,
            name = item.title,
            imdbId = item.externalIds().imdb,
            poster = artwork(item.posterUrl, item.contentId, SiloArtworkKind.POSTER),
            background = artwork(item.backdropUrl, item.contentId, SiloArtworkKind.BACKGROUND),
            logo = artwork(item.logoUrl, item.contentId, SiloArtworkKind.LOGO),
            description = item.overview,
            releaseInfo = releaseInfo(item, kind),
            lastAirDate = item.lastAirDate,
            status = item.showStatus,
            imdbRating = item.ratingImdb?.oneDecimal(),
            ageRating = item.contentRating,
            runtime = item.runtime?.takeIf { it > 0 }?.let { "$it min" },
            genres = item.genres,
            director = item.crew.filter { it.job.equals("Director", ignoreCase = true) }.map { it.name },
            writer = item.crew.filter { it.job?.contains("Writ", ignoreCase = true) == true }.map { it.name }.distinct(),
            cast = item.cast.filter { it.name.isNotBlank() }.map { person ->
                MetaPerson(name = person.name, role = person.character, photo = artwork(person.photoUrl))
            },
            country = item.countries.firstOrNull(),
            videos = episodes.map(::video),
        )
    }

    fun video(episode: EpisodeWire): MetaVideo = MetaVideo(
        id = ref(episode.contentId),
        title = episode.title,
        released = episode.airDate,
        thumbnail = artwork(episode.stillUrl),
        season = episode.seasonNumber,
        episode = episode.episodeNumber,
        overview = episode.overview,
        runtime = episode.runtime?.takeIf { it > 0 },
    )

    fun userState(item: CatalogItemWire): ServerUserState? {
        val data = item.userData
        val played = data?.played ?: item.userState?.played ?: if (item.positionSeconds != null) false else return null
        val runtimeMs = item.runtime?.let { it * 60_000L }
        return ServerUserState(
            videoId = ref(item.contentId),
            positionMs = seconds(data?.positionSeconds ?: item.positionSeconds),
            durationMs = seconds(data?.durationSeconds ?: item.durationSeconds).takeIf { it > 0 } ?: runtimeMs ?: 0L,
            played = played,
            lastPlayedEpochMs = parseInstant(item.progressUpdatedAt),
            season = item.seasonNumber,
            episode = item.episodeNumber,
            title = item.title,
        )
    }

    fun userState(episode: EpisodeWire): ServerUserState? {
        val data = episode.userData ?: return null
        return ServerUserState(
            videoId = ref(episode.contentId),
            positionMs = seconds(data.positionSeconds),
            durationMs = seconds(data.durationSeconds).takeIf { it > 0 } ?: episode.runtime?.let { it * 60_000L } ?: 0L,
            played = data.played,
            lastPlayedEpochMs = null,
            season = episode.seasonNumber,
            episode = episode.episodeNumber,
            title = episode.title,
        )
    }

    /** A continue watching card: an episode (or season) shows its series, a movie itself. */
    fun resumeTitle(item: CatalogItemWire): ServerTitle? {
        val partOfSeries = item.type.equals("episode", ignoreCase = true) || item.type.equals("season", ignoreCase = true)
        if (!partOfSeries) return title(item)
        val seriesId = item.seriesId?.takeIf { it.isNotBlank() } ?: return null
        val preview = MetaPreview(
            id = ref(seriesId),
            type = ServerMediaKind.SERIES.contentType,
            name = item.seriesTitle ?: item.title,
            poster = artwork(item.seriesPosterUrl ?: item.posterUrl, seriesId, SiloArtworkKind.POSTER),
            banner = artwork(item.seriesBackdropUrl ?: item.backdropUrl, seriesId, SiloArtworkKind.BACKGROUND),
            logo = artwork(item.logoUrl, seriesId, SiloArtworkKind.LOGO),
            posterShape = PosterShape.Poster,
            description = item.overview,
        )
        return ServerTitle(preview, contentIdExternalIds(seriesId))
    }

    fun candidates(contentId: String, versions: List<WatchVersionWire>): List<ServerCandidate> {
        val ref = ServerItemRef(connectionId, contentId)
        val usable = versions.filter { it.fileId.isNotBlank() }
        return usable.map { version ->
            val edition = version.editionRaw?.trim()?.takeIf { it.isNotEmpty() }
            ServerCandidate(
                target = ServerPlaybackTarget(ref, version.fileId),
                title = edition?.takeIf { usable.size > 1 } ?: version.resolution.ifBlank { version.container.uppercase() },
                description = listOfNotNull(
                    version.videoLabel(),
                    (version.audioTracks.firstOrNull { it.default } ?: version.audioTracks.firstOrNull())?.label(),
                    version.container.takeIf { it.isNotBlank() }?.uppercase()?.let { if (it == "MATROSKA") "MKV" else it },
                ).joinToString(" • ").ifBlank { null },
                filename = version.fileName,
                sizeBytes = version.fileSize?.takeIf { it > 0 },
            )
        }
    }

    fun indexEntry(item: CatalogItemWire): ServerIndexEntry =
        ServerIndexEntry(itemId = item.contentId, ids = item.externalIds(), year = item.year)

    private fun releaseInfo(item: CatalogItemWire, kind: ServerMediaKind): String? {
        val start = item.year ?: (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull() ?: return null
        if (kind != ServerMediaKind.SERIES) return start.toString()
        val end = item.lastAirDate?.take(4)?.toIntOrNull()
        val running = item.showStatus?.let { it.equals("Continuing", true) || it.equals("Returning Series", true) } == true
        return when {
            running -> "$start–"
            end != null && end != start -> "$start–$end"
            else -> start.toString()
        }
    }

    private fun seconds(value: Double?): Long = value?.takeIf { it > 0 }?.let { (it * 1_000).toLong() } ?: 0L

    private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()
}

internal fun siloKind(type: String): ServerMediaKind? = when (type.trim().lowercase()) {
    "movie" -> ServerMediaKind.MOVIE
    "series" -> ServerMediaKind.SERIES
    else -> null
}

internal fun parseInstant(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal fun CatalogItemWire.externalIds(): TrackingExternalIds {
    val fromDetail = TrackingExternalIds(
        imdb = imdbId?.trim()?.lowercase()?.takeIf { IMDB_ID.matches(it) },
        tmdb = tmdbId?.let { LEADING_DIGITS.find(it)?.value?.toLongOrNull() },
        tvdb = tvdbId?.let { LEADING_DIGITS.find(it)?.value },
    )
    return fromDetail.mergeMissing(contentIdExternalIds(contentId))
}

/** Silo names items after the id they were first matched with, e.g. movie-tmdb-603 or series-tvdb-121361. */
internal fun contentIdExternalIds(contentId: String): TrackingExternalIds {
    val match = CONTENT_ID.matchEntire(contentId.trim()) ?: return TrackingExternalIds()
    val id = match.groupValues[3]
    return when (match.groupValues[2]) {
        "tmdb" -> TrackingExternalIds(tmdb = id.toLongOrNull())
        "tvdb" -> TrackingExternalIds(tvdb = id)
        "imdb" -> TrackingExternalIds(imdb = id.takeIf { IMDB_ID.matches(it) })
        else -> TrackingExternalIds()
    }
}

/** The content ids Silo would have given a title with [ids], most likely first. */
internal fun contentIdCandidates(kind: ServerMediaKind, ids: TrackingExternalIds): List<String> {
    val tmdb = ids.tmdb?.let { "tmdb-$it" }
    val imdb = ids.imdb?.trim()?.lowercase()?.takeIf { IMDB_ID.matches(it) }?.let { "imdb-$it" }
    val tvdb = ids.tvdb?.trim()?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let { "tvdb-$it" }
    return when (kind) {
        ServerMediaKind.MOVIE -> listOfNotNull(tmdb, imdb, tvdb).map { "movie-$it" }
        ServerMediaKind.SERIES -> listOfNotNull(tvdb, tmdb, imdb).map { "series-$it" }
        ServerMediaKind.COLLECTION, ServerMediaKind.MIXED -> emptyList()
    }
}

/** An episode's content id under a series id such as series-tvdb-121361. */
internal fun episodeContentId(seriesId: String, season: Int, episode: Int): String? {
    if (!seriesId.startsWith("series-") || CONTENT_ID.matchEntire(seriesId) == null) return null
    return "episode-${seriesId.removePrefix("series-")}-$season-$episode"
}

private val CONTENT_ID = Regex("""(movie|series)-(tmdb|tvdb|imdb)-([A-Za-z0-9]+)""")
private val IMDB_ID = Regex("""tt\d+""")
private val LEADING_DIGITS = Regex("""^\d+""")
