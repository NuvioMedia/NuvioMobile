package com.nuvio.app.features.servers.plex

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
import kotlin.math.roundToInt

internal fun plexKind(type: String): ServerMediaKind? = when (type.trim().lowercase()) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    else -> null
}

/** Plex's item type number for a library kind. */
internal fun plexTypeNumber(kind: ServerMediaKind): String? = when (kind) {
    ServerMediaKind.MOVIE -> "1"
    ServerMediaKind.SERIES -> "2"
    else -> null
}

/** Turns Plex Media Server replies into the app's titles. Artwork links carry a mark the image loader signs. */
internal class PlexMapper(private val address: String, private val connectionId: String) {
    fun ref(ratingKey: String): String = ServerItemRef(connectionId, ratingKey).encode()

    private fun artwork(path: String?, width: Int, height: Int): String? {
        val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return PlexArtworkLink.picture(address, connectionId, value, width, height)
    }

    fun preview(item: MetadataWire): MetaPreview? {
        val kind = plexKind(item.type) ?: return null
        if (item.ratingKey.isBlank()) return null
        return MetaPreview(
            id = ref(item.ratingKey),
            type = kind.contentType,
            name = item.title,
            poster = artwork(item.thumb, POSTER_WIDTH, POSTER_HEIGHT),
            banner = artwork(item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            logo = artwork(item.logo(), LOGO_WIDTH, LOGO_HEIGHT),
            posterShape = PosterShape.Poster,
            description = item.summary,
            releaseInfo = item.year?.toString(),
            rawReleaseDate = item.originallyAvailableAt?.take(10),
            imdbRating = item.rating(),
            genres = item.genres.mapNotNull { it.tag },
        )
    }

    fun title(item: MetadataWire): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    /** A Continue watching card: an episode shows its show, a movie itself. */
    fun resumeTitle(item: MetadataWire): ServerTitle? {
        if (item.type != "episode") return title(item)
        val showKey = item.grandparentRatingKey?.takeIf { it.isNotBlank() } ?: return null
        val preview = MetaPreview(
            id = ref(showKey),
            type = ServerMediaKind.SERIES.contentType,
            name = item.grandparentTitle ?: item.title,
            poster = artwork(item.grandparentThumb ?: item.thumb, POSTER_WIDTH, POSTER_HEIGHT),
            banner = artwork(item.grandparentArt ?: item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            posterShape = PosterShape.Poster,
            description = item.summary,
        )
        return ServerTitle(preview)
    }

    fun details(item: MetadataWire, episodes: List<MetadataWire>): MetaDetails {
        val kind = plexKind(item.type) ?: ServerMediaKind.MOVIE
        return MetaDetails(
            id = ref(item.ratingKey),
            type = kind.contentType,
            name = item.title,
            imdbId = item.externalIds().imdb,
            poster = artwork(item.thumb, POSTER_WIDTH, POSTER_HEIGHT),
            background = artwork(item.art, BACKDROP_WIDTH, BACKDROP_HEIGHT),
            logo = artwork(item.logo(), LOGO_WIDTH, LOGO_HEIGHT),
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.rating(),
            ageRating = item.contentRating,
            runtime = item.duration?.takeIf { it > 0 }?.let { "${(it / 60_000L).coerceAtLeast(1)} min" },
            genres = item.genres.mapNotNull { it.tag },
            director = item.directors.mapNotNull { it.tag },
            writer = item.writers.mapNotNull { it.tag },
            cast = item.roles.filter { it.tag.isNotBlank() }.map { role ->
                MetaPerson(name = role.tag, role = role.role, photo = role.thumb?.takeIf { it.startsWith("http") })
            },
            country = item.countries.firstOrNull()?.tag,
            videos = episodes.mapNotNull(::video),
        )
    }

    fun video(episode: MetadataWire): MetaVideo? {
        if (episode.ratingKey.isBlank()) return null
        return MetaVideo(
            id = ref(episode.ratingKey),
            title = episode.title,
            released = episode.originallyAvailableAt,
            thumbnail = artwork(episode.thumb, STILL_WIDTH, STILL_HEIGHT),
            season = episode.parentIndex,
            episode = episode.index,
            overview = episode.summary,
            runtime = episode.duration?.takeIf { it > 0 }?.let { (it / 60_000L).toInt().coerceAtLeast(1) },
        )
    }

    fun userState(item: MetadataWire): ServerUserState {
        val duration = item.duration ?: item.media.firstOrNull()?.duration ?: 0L
        val position = item.viewOffset?.coerceAtLeast(0L) ?: 0L
        return ServerUserState(
            videoId = ref(item.ratingKey),
            positionMs = position,
            durationMs = duration.coerceAtLeast(0L),
            played = (item.viewCount ?: 0) > 0 && position == 0L,
            lastPlayedEpochMs = item.lastViewedAt?.takeIf { it > 0 }?.times(1_000L),
            season = item.parentIndex.takeIf { item.type == "episode" },
            episode = item.index.takeIf { item.type == "episode" },
            title = item.title,
        )
    }

    fun candidates(item: MetadataWire): List<ServerCandidate> {
        val ref = ServerItemRef(connectionId, item.ratingKey)
        val several = item.media.size > 1
        return item.media.mapNotNull { media ->
            val part = media.parts.firstOrNull() ?: return@mapNotNull null
            val version = media.title?.takeIf { it.isNotBlank() } ?: item.editionTitle?.takeIf { it.isNotBlank() }
            ServerCandidate(
                target = ServerPlaybackTarget(ref, part.id.toString()),
                title = version?.takeIf { several } ?: media.label(),
                description = listOfNotNull(
                    part.streams.firstOrNull { it.streamType == STREAM_VIDEO }?.displayTitle?.takeIf { it.isNotBlank() }
                        ?: media.videoCodec?.uppercase(),
                    media.audioCodec?.let(::audioCodecLabel)?.let { codec ->
                        media.audioChannels?.let { "$codec ${channelLabel(it)}" } ?: codec
                    },
                    media.container?.uppercase()?.let { if (it == "MATROSKA") "MKV" else it },
                ).joinToString(" • ").ifBlank { null },
                filename = part.file?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = part.size?.takeIf { it > 0 },
            )
        }
    }

    fun indexEntry(item: MetadataWire): ServerIndexEntry =
        ServerIndexEntry(itemId = item.ratingKey, ids = item.externalIds(), year = item.year)

    private fun MetadataWire.logo(): String? = images.firstOrNull { it.type == "clearLogo" }?.url

    private fun MetadataWire.rating(): String? =
        (audienceRating ?: rating)?.let { ((it * 10).roundToInt() / 10.0).toString() }

    private companion object {
        const val POSTER_WIDTH = 400
        const val POSTER_HEIGHT = 600
        const val BACKDROP_WIDTH = 1920
        const val BACKDROP_HEIGHT = 1080
        const val LOGO_WIDTH = 800
        const val LOGO_HEIGHT = 310
        const val STILL_WIDTH = 640
        const val STILL_HEIGHT = 360
    }
}

private fun MediaWire.label(): String = when {
    !videoResolution.isNullOrBlank() -> videoResolution.uppercase().let { if (it.all(Char::isDigit)) "${it}p" else it }
    !container.isNullOrBlank() -> container.uppercase()
    else -> "Plex"
}

private fun audioCodecLabel(codec: String): String = when (codec.lowercase()) {
    "dca" -> "DTS"
    "dca-ma" -> "DTS-HD MA"
    "truehd" -> "TrueHD"
    "eac3" -> "E-AC-3"
    "ac3" -> "AC-3"
    else -> codec.uppercase()
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

/**
 * imdb://tt0133093, tmdb://603 and tvdb://121361 from the item's Guid list (sent with includeGuids=1). Libraries on the
 * older agents only have the guid field instead: com.plexapp.agents.imdb://tt0133093?lang=en,
 * com.plexapp.agents.themoviedb://603, com.plexapp.agents.thetvdb://121361, or HAMA's anidb-23 / tvdb-81797 for anime.
 */
internal fun MetadataWire.externalIds(): TrackingExternalIds {
    fun id(scheme: String) = guids.firstNotNullOfOrNull { guid ->
        guid.id?.takeIf { it.startsWith("$scheme://", ignoreCase = true) }
            ?.substringAfter("://")?.trim()?.takeIf { it.isNotEmpty() }
    }
    val fromList = TrackingExternalIds(
        imdb = id("imdb")?.lowercase()?.takeIf { IMDB_ID.matches(it) },
        tmdb = id("tmdb")?.let { LEADING_DIGITS.find(it)?.value?.toLongOrNull() },
        tvdb = id("tvdb")?.let { LEADING_DIGITS.find(it)?.value },
    )
    return fromList.mergeMissing(legacyGuidIds(guid))
}

internal fun legacyGuidIds(guid: String?): TrackingExternalIds {
    val match = LEGACY_GUID.find(guid?.trim().orEmpty()) ?: return TrackingExternalIds()
    val agent = match.groupValues[1].lowercase()
    val value = match.groupValues[2]
    fun digits(text: String) = LEADING_DIGITS.find(text)?.value
    return when (agent) {
        "imdb" -> TrackingExternalIds(imdb = value.lowercase().takeIf { IMDB_ID.matches(it) })
        "themoviedb", "tmdb" -> TrackingExternalIds(tmdb = digits(value)?.toLongOrNull())
        "thetvdb", "tvdb", "thetvdbdvdorder" -> TrackingExternalIds(tvdb = digits(value))
        "hama" -> {
            val source = value.substringBefore('-').lowercase()
            val id = value.substringAfter('-', "")
            when {
                source == "anidb" || source == "anidb2" -> TrackingExternalIds(anidb = digits(id)?.toLongOrNull())
                source.startsWith("tvdb") -> TrackingExternalIds(tvdb = digits(id))
                source == "tmdb" -> TrackingExternalIds(tmdb = digits(id)?.toLongOrNull())
                source == "imdb" -> TrackingExternalIds(imdb = id.lowercase().takeIf { IMDB_ID.matches(it) })
                else -> TrackingExternalIds()
            }
        }
        else -> TrackingExternalIds()
    }
}

private val LEGACY_GUID = Regex("""^com\.plexapp\.agents\.([a-z0-9]+)://([^/?]+)""", RegexOption.IGNORE_CASE)
private val IMDB_ID = Regex("""tt\d+""")
private val LEADING_DIGITS = Regex("""^\d+""")
