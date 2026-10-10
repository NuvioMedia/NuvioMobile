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
internal const val MDBLIST_EXTERNAL_LIST_KEY_PREFIX = "mdblist:external:"

/** Bumped when list items are requested in a different order, so cached lists are downloaded again. */
internal const val MDBLIST_ITEMS_ORDER = 1

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
data class MdbListExternalList(
    val id: Long,
    val name: String,
    val source: String? = null,
    val mediaType: MdbListItemType? = null,
    val updatedAt: String? = null
) {
    val key: String get() = "$MDBLIST_EXTERNAL_LIST_KEY_PREFIX$id"

    // External lists mirror another service, so MDBList only allows reading them.
    fun tab() = TrackingLibraryTab(
        key = key,
        title = name,
        kind = TrackingLibraryTabKind.EXTERNAL,
        description = source,
        providerId = TrackingProviderId.MDBLIST,
        supportedContentTypes = when (mediaType) {
            MdbListItemType.MOVIE -> movieContentTypes
            MdbListItemType.SHOW -> showContentTypes
            else -> movieContentTypes + showContentTypes
        },
        isMembershipDestination = false
    )
}

@Serializable
data class MdbListLibraryItem(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val listedAt: Long = 0,
    val rank: Int? = null,
    val releaseDate: String? = null
) {
    val key: String get() = "$type:${media.ids.key}"
    fun matches(other: MdbListLibraryItem): Boolean = type == other.type && media.ids.matches(other.media.ids)
}

@Serializable
data class MdbListLibraryOrderItem(
    val type: MdbListItemType,
    val ids: MdbListIds
)

@Serializable
data class MdbListLibrarySnapshot(
    val lists: List<MdbListLibraryList> = emptyList(),
    val itemsByList: Map<String, List<MdbListLibraryItem>> = emptyMap(),
    val checkedAtEpochMs: Long? = null,
    val invalidated: Boolean = false,
    val addedOrders: Map<String, Map<String, List<MdbListLibraryOrderItem>>> = emptyMap(),
    val externalLists: List<MdbListExternalList> = emptyList(),
    val hiddenListKeys: Set<String> = emptySet(),
    val itemsOrder: Int = 0
) {
    /** Tabs shown in Nuvio: lists the user hid in MDBList settings are left out. */
    fun visibleTabs(): List<TrackingLibraryTab> = tabs().filterNot { it.key in hiddenListKeys }

    /** Items of the visible lists only. */
    fun visible(): MdbListLibrarySnapshot = if (hiddenListKeys.isEmpty()) this else copy(itemsByList = itemsByList - hiddenListKeys)

    /** Every list except the Watchlist can be hidden. */
    fun listOptions(): List<MdbListLibraryListOption> = tabs()
        .filter { it.kind != TrackingLibraryTabKind.WATCHLIST }
        .map { MdbListLibraryListOption(it.key, it.title, it.key !in hiddenListKeys) }

    fun tabs(): List<TrackingLibraryTab> = listOf(
        TrackingLibraryTab(
            MDBLIST_WATCHLIST_KEY, "Watchlist", TrackingProviderId.MDBLIST, TrackingLibraryTabKind.WATCHLIST, supportedContentTypes = movieContentTypes + showContentTypes
        )
    ) + lists.map(MdbListLibraryList::tab) + externalLists.map(MdbListExternalList::tab)
}

data class MdbListLibraryListOption(
    val key: String,
    val name: String,
    val visible: Boolean
)
