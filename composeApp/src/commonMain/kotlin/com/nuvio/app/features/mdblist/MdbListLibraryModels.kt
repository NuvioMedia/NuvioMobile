package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.serialization.Serializable

private val movieContentTypes = setOf("movie")
private val showContentTypes = setOf("series", "show", "tv", "anime")

internal const val MDBLIST_WATCHLIST_KEY = "mdblist:watchlist"
internal const val MDBLIST_LIST_KEY_PREFIX = "mdblist:list:"

@Serializable
data class MdbListLibraryList(
    val id: Long,
    val name: String,
    val private: Boolean,
    val description: String? = null,
    val mediaType: MdbListItemType? = null,
    val updatedAt: String? = null
) {
    val key: String get() = "$MDBLIST_LIST_KEY_PREFIX$id"

    fun tab() = TrackingLibraryTab(
        key = key,
        title = name,
        kind = TrackingLibraryTabKind.PERSONAL,
        privacy = if (private) LibraryListPrivacy.PRIVATE else LibraryListPrivacy.PUBLIC,
        description = description,
        providerId = TrackingProviderId.MDBLIST,
        supportedContentTypes = when (mediaType) {
            MdbListItemType.MOVIE -> movieContentTypes
            MdbListItemType.SHOW -> showContentTypes
            else -> movieContentTypes + showContentTypes
        }
    )
}

@Serializable
data class MdbListLibraryItem(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: Long = 0,
    val rank: Int? = null
) {
    val key: String get() = "$type:${media.ids.key}"
    fun matches(other: MdbListLibraryItem): Boolean = type == other.type && media.ids.matches(other.media.ids)
}

@Serializable
data class MdbListLibrarySnapshot(
    val lists: List<MdbListLibraryList> = emptyList(),
    val itemsByList: Map<String, List<MdbListLibraryItem>> = emptyMap(),
    val checkedAtEpochMs: Long? = null,
    val invalidated: Boolean = false
) {
    fun tabs(): List<TrackingLibraryTab> = listOf(
        TrackingLibraryTab(
            MDBLIST_WATCHLIST_KEY, "Watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST, supportedContentTypes = movieContentTypes + showContentTypes
        )
    ) + lists.map(MdbListLibraryList::tab)
}
