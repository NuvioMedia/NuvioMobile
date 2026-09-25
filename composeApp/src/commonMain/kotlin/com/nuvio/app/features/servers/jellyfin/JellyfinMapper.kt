package com.nuvio.app.features.servers.jellyfin

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.servers.ServerCandidate
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerPlaybackTarget
import com.nuvio.app.features.servers.ServerUserState
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlin.math.roundToInt
import kotlin.time.Instant

internal class JellyfinMapper(
    private val baseUrl: String,
    private val connectionId: String,
) {
    fun ref(itemId: String): String = ServerItemRef(connectionId, itemId).encode()

    fun preview(item: JellyfinItem): MetaPreview? {
        val kind = item.mediaKind() ?: return null
        return MetaPreview(
            id = ref(item.id),
            type = kind.contentType,
            name = item.name.orEmpty(),
            poster = item.primaryImage(),
            banner = item.backdropImage(),
            logo = item.logoImage(),
            posterShape = PosterShape.Poster,
            description = item.overview,
            releaseInfo = item.productionYear?.toString(),
            rawReleaseDate = item.premiereDate?.take(10),
            imdbRating = item.communityRating?.formatRating(),
            genres = item.genres,
        )
    }

    fun resumePreview(item: JellyfinItem): MetaPreview? {
        if (!item.type.equals("Episode", ignoreCase = true)) return preview(item)
        val seriesId = item.seriesId ?: return null
        return MetaPreview(
            id = ref(seriesId),
            type = ServerMediaKind.SERIES.contentType,
            name = item.seriesName ?: item.name.orEmpty(),
            poster = item.seriesPrimaryImageTag?.let { image(seriesId, "Primary", it, maxHeight = 600) },
            banner = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
        )
    }

    fun details(item: JellyfinItem, episodes: List<JellyfinItem>): MetaDetails {
        val kind = item.mediaKind() ?: ServerMediaKind.MOVIE
        return MetaDetails(
            id = ref(item.id),
            type = kind.contentType,
            name = item.name.orEmpty(),
            imdbId = item.externalIds().imdb,
            poster = item.primaryImage(),
            background = item.backdropImage(),
            logo = item.logoImage(),
            description = item.overview,
            releaseInfo = item.releaseInfo(kind),
            status = item.status,
            imdbRating = item.communityRating?.formatRating(),
            ageRating = item.officialRating,
            runtime = item.runTimeTicks?.let { "${ticksToMinutes(it)} min" },
            genres = item.genres,
            director = item.people.filter { it.type.equals("Director", ignoreCase = true) }.mapNotNull { it.name },
            writer = item.people.filter { it.type.equals("Writer", ignoreCase = true) }.mapNotNull { it.name },
            cast = item.people
                .filter { it.type.equals("Actor", ignoreCase = true) && !it.name.isNullOrBlank() }
                .map { person ->
                    MetaPerson(
                        name = person.name.orEmpty(),
                        role = person.role,
                        photo = person.id?.let { id -> person.primaryImageTag?.let { image(id, "Primary", it, maxHeight = 300) } },
                    )
                },
            videos = episodes.map(::video),
        )
    }

    fun userState(item: JellyfinItem): ServerUserState? {
        val data = item.userData ?: return null
        return ServerUserState(
            videoId = ref(item.id),
            positionMs = (data.playbackPositionTicks ?: 0L) / TICKS_PER_MS,
            durationMs = (item.runTimeTicks ?: 0L) / TICKS_PER_MS,
            played = data.played,
            lastPlayedEpochMs = data.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
            season = item.parentIndexNumber,
            episode = item.indexNumber,
            title = item.name,
        )
    }

    fun video(item: JellyfinItem): MetaVideo = MetaVideo(
        id = ref(item.id),
        title = item.name.orEmpty(),
        released = item.premiereDate,
        available = !item.isMissing,
        thumbnail = item.imageTags["Primary"]?.let { image(item.id, "Primary", it, maxWidth = 640) },
        season = item.parentIndexNumber,
        episode = item.indexNumber,
        overview = item.overview,
        runtime = item.runTimeTicks?.let(::ticksToMinutes),
        rating = item.communityRating,
    )

    fun candidates(item: JellyfinItem): List<ServerCandidate> {
        if (item.isMissing) return emptyList()
        val itemRef = ServerItemRef(connectionId, item.id)
        return item.mediaSources.map { source ->
            val video = source.mediaStreams.firstOrNull { it.type.equals("Video", ignoreCase = true) }
            val audio = source.mediaStreams.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = source.id),
                title = source.name?.takeIf { it.isNotBlank() && item.mediaSources.size > 1 }
                    ?: video?.let(::resolutionLabel)
                    ?: source.name.orEmpty(),
                description = listOfNotNull(
                    video?.displayTitle,
                    audio?.displayTitle,
                    source.container?.uppercase(),
                ).joinToString(" • ").ifBlank { null },
                filename = source.path?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = source.size,
            )
        }
    }

    fun image(itemId: String, type: String, tag: String, maxWidth: Int? = null, maxHeight: Int? = null): String =
        buildUrl(
            baseUrl,
            "/Items/${pathSegment(itemId)}/Images/$type",
            mapOf(
                "tag" to tag,
                "maxWidth" to maxWidth?.toString(),
                "maxHeight" to maxHeight?.toString(),
                "quality" to "90",
            ),
        )

    private fun JellyfinItem.primaryImage(): String? =
        imageTags["Primary"]?.let { image(id, "Primary", it, maxHeight = 600) }

    private fun JellyfinItem.backdropImage(): String? =
        backdropImageTags.firstOrNull()?.let { image(id, "Backdrop/0", it, maxWidth = 1920) }
            ?: parentBackdropItemId?.let { parentId ->
                parentBackdropImageTags.firstOrNull()?.let { image(parentId, "Backdrop/0", it, maxWidth = 1920) }
            }

    private fun JellyfinItem.logoImage(): String? =
        imageTags["Logo"]?.let { image(id, "Logo", it, maxWidth = 800) }
            ?: parentLogoItemId?.let { parentId -> parentLogoImageTag?.let { image(parentId, "Logo", it, maxWidth = 800) } }

    private fun JellyfinItem.releaseInfo(kind: ServerMediaKind): String? {
        val start = productionYear ?: return null
        if (kind != ServerMediaKind.SERIES) return start.toString()
        val end = endDate?.take(4)?.toIntOrNull()
        return when {
            status.equals("Continuing", ignoreCase = true) -> "$start–"
            end != null && end != start -> "$start–$end"
            else -> start.toString()
        }
    }
}

internal fun JellyfinItem.mediaKind(): ServerMediaKind? = when {
    type.equals("Movie", ignoreCase = true) -> ServerMediaKind.MOVIE
    type.equals("Series", ignoreCase = true) -> ServerMediaKind.SERIES
    else -> null
}

internal fun JellyfinItem.externalIds(): TrackingExternalIds {
    fun id(name: String): String? = providerIds.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return TrackingExternalIds(
        imdb = id("Imdb")?.takeIf { it.startsWith("tt") },
        tmdb = id("Tmdb")?.toLongOrNull(),
        tvdb = id("Tvdb"),
    )
}

internal fun libraryKind(collectionType: String?): ServerMediaKind? = when (collectionType?.lowercase()) {
    "movies" -> ServerMediaKind.MOVIE
    "tvshows" -> ServerMediaKind.SERIES
    else -> null
}

internal const val TICKS_PER_MS = 10_000L

private fun ticksToMinutes(ticks: Long): Int = (ticks / TICKS_PER_MS / 60_000L).toInt()

private fun Double.formatRating(): String = ((this * 10).roundToInt() / 10.0).toString()

private fun resolutionLabel(stream: JellyfinMediaStream): String? {
    val width = stream.width ?: 0
    val height = stream.height ?: 0
    return when {
        width >= 3200 || height >= 2000 -> "4K"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }
}
