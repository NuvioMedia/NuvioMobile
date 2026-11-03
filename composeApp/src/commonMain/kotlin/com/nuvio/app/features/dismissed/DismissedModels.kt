package com.nuvio.app.features.dismissed

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.Serializable

@Serializable
data class DismissedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val dismissedAtEpochMs: Long,
)

data class DismissedUiState(
    val items: List<DismissedItem> = emptyList(),
    val dismissedKeys: Set<String> = emptySet(),
    val isLoaded: Boolean = false,
)

fun MetaPreview.toDismissedItem(
    dismissedAtEpochMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
): DismissedItem = DismissedItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    releaseInfo = releaseInfo,
    dismissedAtEpochMs = dismissedAtEpochMs,
)

fun dismissedItemKey(type: String, id: String): String =
    "${type.trim().lowercase()}:${id.trim()}"

internal fun dismissedItemTypeAliases(type: String): Set<String> = when (type.trim().lowercase()) {
    "movie", "film" -> setOf("movie", "film")
    "series", "show", "tv", "tvshow", "anime" -> setOf("series", "show", "tv", "tvshow", "anime")
    else -> setOf(type.trim().lowercase())
}

fun dismissedItemKeys(type: String, id: String): Set<String> =
    dismissedItemTypeAliases(type).mapTo(linkedSetOf()) { alias ->
        dismissedItemKey(type = alias, id = id)
    }
