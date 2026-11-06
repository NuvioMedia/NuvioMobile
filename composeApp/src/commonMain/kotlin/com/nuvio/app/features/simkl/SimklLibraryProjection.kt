package com.nuvio.app.features.simkl

import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_status_completed
import nuvio.composeapp.generated.resources.library_status_dropped
import nuvio.composeapp.generated.resources.library_status_on_hold
import nuvio.composeapp.generated.resources.library_status_plan_to_watch
import nuvio.composeapp.generated.resources.library_status_watching
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal const val SIMKL_STATUS_SELECTION_GROUP = "simkl:status"

internal data class SimklLibraryStatusDefinition(
    val status: SimklListStatus,
    val key: String,
    val title: String,
    val titleResource: StringResource,
    val trackingStatus: TrackingListStatus,
    val supportedContentTypes: Set<String>,
    val isMembershipDestination: Boolean = true,
)

internal fun SimklLibraryStatusDefinition.localizedTitle(): String =
    runCatching { runBlocking { getString(titleResource) } }.getOrDefault(title)

internal data class SimklLibraryProjection(
    val items: List<LibraryItem>,
    val sections: List<LibrarySection>,
)

internal val simklLibraryStatusDefinitions = listOf(
    SimklLibraryStatusDefinition(
        status = SimklListStatus.WATCHING,
        key = "simkl:status:watching",
        title = "Watching",
        titleResource = Res.string.library_status_watching,
        trackingStatus = TrackingListStatus.WATCHING,
        supportedContentTypes = setOf("series", "anime"),
    ),
    SimklLibraryStatusDefinition(
        status = SimklListStatus.PLAN_TO_WATCH,
        key = "simkl:status:plantowatch",
        title = "Plan to Watch",
        titleResource = Res.string.library_status_plan_to_watch,
        trackingStatus = TrackingListStatus.PLAN_TO_WATCH,
        supportedContentTypes = setOf("movie", "series", "anime"),
    ),
    SimklLibraryStatusDefinition(
        status = SimklListStatus.ON_HOLD,
        key = "simkl:status:hold",
        title = "On Hold",
        titleResource = Res.string.library_status_on_hold,
        trackingStatus = TrackingListStatus.ON_HOLD,
        supportedContentTypes = setOf("series", "anime"),
    ),
    SimklLibraryStatusDefinition(
        status = SimklListStatus.COMPLETED,
        key = "simkl:status:completed",
        title = "Completed",
        titleResource = Res.string.library_status_completed,
        trackingStatus = TrackingListStatus.COMPLETED,
        supportedContentTypes = setOf("movie", "series", "anime"),
        isMembershipDestination = false,
    ),
    SimklLibraryStatusDefinition(
        status = SimklListStatus.DROPPED,
        key = "simkl:status:dropped",
        title = "Dropped",
        titleResource = Res.string.library_status_dropped,
        trackingStatus = TrackingListStatus.DROPPED,
        supportedContentTypes = setOf("movie", "series", "anime"),
    ),
)

internal fun SimklSyncSnapshot.toSimklLibraryProjection(): SimklLibraryProjection {
    val sections = simklLibraryStatusDefinitions.mapNotNull { definition ->
        val items = entries
            .asSequence()
            .filter { entry -> entry.status == definition.status }
            .mapNotNull { entry -> entry.toLibraryItem(definition.key, lastSyncedAtEpochMs) }
            .distinctBy { item -> "${item.type}:${item.id}" }
            .sortedByDescending(LibraryItem::savedAtEpochMs)
            .toList()
        items.takeIf { projectedItems -> projectedItems.isNotEmpty() }?.let {
            LibrarySection(
                type = definition.key,
                displayTitle = definition.title,
                items = items,
            )
        }
    }
    return SimklLibraryProjection(
        items = sections
            .flatMap(LibrarySection::items)
            .distinctBy { item -> "${item.type}:${item.id}" }
            .sortedByDescending(LibraryItem::savedAtEpochMs),
        sections = sections,
    )
}

internal fun simklLibraryStatusDefinition(key: String): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.key == key }

internal fun simklLibraryStatusDefinition(status: TrackingListStatus): SimklLibraryStatusDefinition? =
    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.trackingStatus == status }

private fun SimklLibraryEntry.toLibraryItem(
    listKey: String,
    lastSyncedAtEpochMs: Long?,
): LibraryItem? {
    val media = media ?: return null
    val contentId = media.canonicalContentId() ?: return null
    val simklId = media.ids.simklIdValue()?.toLongOrNull()
    val entryType = when (mediaType) {
        SimklMediaType.MOVIES -> "movie"
        SimklMediaType.ANIME -> if (animeType == "movie") "movie" else "series"
        SimklMediaType.SHOWS -> "series"
    }
    return LibraryItem(
        id = contentId,
        type = entryType,
        name = media.title?.takeIf(String::isNotBlank) ?: contentId,
        poster = resolvedPosterUrl(),
        releaseInfo = media.year?.toString(),
        posterShape = PosterShape.Poster,
        listKeys = setOf(listKey),
        imdbId = media.ids.idValue("imdb"),
        tmdbId = media.ids.idValue("tmdb")?.toIntOrNull(),
        mediaCategory = if (mediaType == SimklMediaType.ANIME) "anime" else null,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = simklId?.let { "simkl:$it" },
        trackingSourceUrl = buildSimklSourceUrl(mediaType, media),
        savedAtEpochMs = parseSimklUtcEpochMs(addedToWatchlistAt)
            ?: parseSimklUtcEpochMs(lastWatchedAt)
            ?: lastSyncedAtEpochMs
            ?: 0L,
    )
}
