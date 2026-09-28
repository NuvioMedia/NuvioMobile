package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingLibrarySorter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class MdbListLibrarySorter(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    auth: MdbListAuthStore,
    activeProfileId: StateFlow<Int>
) : TrackingLibrarySorter {
    private val snapshots = combine(sync.state, auth.state, activeProfileId) { state, authorization, profileId ->
        state.snapshot?.library?.takeIf {
            authorization.isAuthenticated && state.scope == authorization.scope && state.scope.profileId == profileId
        }?.let { state.scope to it }
    }.distinctUntilChanged()

    override fun observeAddedOrder(listKey: String, descending: Boolean) = snapshots.map { scoped ->
        val (scope, library) = scoped ?: return@map null
        if (listKey !in library.itemsByList) return@map null
        val direction = if (descending) "desc" else "asc"
        val cached = library.addedOrders[listKey]?.get(direction)
        if (cached != null && !library.invalidated) return@map library.orderKeys(cached)
        if (library.invalidated) return@map null
        sync.mutate(requireNotNull(scope)) { previous ->
            val current = previous.library ?: throw MdbListDecodingException()
            if (listKey !in current.itemsByList || current.invalidated) return@mutate previous to null
            val order = current.addedOrders[listKey]?.get(direction)
                ?: MdbListLibraryRemote(api, scope).items(listKey, direction)
                    .map { MdbListLibraryOrderItem(it.type, it.media.ids) }
            val updated = current.copy(addedOrders = current.addedOrders +
                (listKey to (current.addedOrders[listKey].orEmpty() + (direction to order))))
            previous.copy(library = updated) to updated.orderKeys(order)
        }
    }.distinctUntilChanged()

    private fun MdbListLibrarySnapshot.orderKeys(order: List<MdbListLibraryOrderItem>): List<String> {
        val index = MdbListMediaIndex(MdbListSyncSnapshot(0))
        itemsByList.values.flatten().forEach { index.add(it.type, it.media) }
        val keys = buildMap {
            itemsByList.values.flatten().forEach { item ->
                val media = index.resolve(item.type, item.media.ids)
                val type = if (item.type == MdbListItemType.MOVIE) "movie" else "series"
                media.ids.aliases().forEach { put(item.type to it, "$type:${media.ids.contentId}") }
            }
        }
        return order.map { item ->
            val type = if (item.type == MdbListItemType.MOVIE) "movie" else "series"
            item.ids.aliases().firstNotNullOfOrNull { keys[item.type to it] } ?: "$type:${item.ids.contentId}"
        }
    }
}
