package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

internal class MdbListLibraryRemote(private val api: MdbListApiClient, private val scope: MdbListAuthScope) {
    suspend fun synchronize(previous: MdbListLibrarySnapshot?, accountId: Long, now: Long): MdbListLibrarySnapshot {
        val lists = decodeMdbListLibraryLists(api.get("/lists/user", mapOf("unified" to "false", "sort" to "ranked"), scope).body, accountId)
        val items = linkedMapOf(MDBLIST_WATCHLIST_KEY to items(MDBLIST_WATCHLIST_KEY))
        val previousLists = previous?.lists.orEmpty().associateBy { it.id }
        val addedOrders = previous?.addedOrders.orEmpty().toMutableMap().apply { remove(MDBLIST_WATCHLIST_KEY) }
        for (list in lists) {
            items[list.key] = cachedOrFetched(previous, list.key, list.updatedAt, previousLists[list.id]?.updatedAt, addedOrders)
        }
        val externalLists = synchronizeExternal(previous, items, addedOrders)
        return MdbListLibrarySnapshot(lists, items, now, addedOrders = addedOrders.filterKeys { it in items },
            externalLists = externalLists)
    }

    // External lists are optional extras: if MDBList cannot serve them, keep the cached copy
    // instead of blocking the watchlist and static lists.
    private suspend fun synchronizeExternal(
        previous: MdbListLibrarySnapshot?,
        items: MutableMap<String, List<MdbListLibraryItem>>,
        addedOrders: MutableMap<String, Map<String, List<MdbListLibraryOrderItem>>>
    ): List<MdbListExternalList> {
        val fetched = linkedMapOf<String, List<MdbListLibraryItem>>()
        val externalLists = try {
            val lists = decodeMdbListExternalLists(api.get("/external/lists/user", emptyMap(), scope).body)
            val previousLists = previous?.externalLists.orEmpty().associateBy { it.id }
            for (list in lists) {
                fetched[list.key] = cachedOrFetched(previous, list.key, list.updatedAt, previousLists[list.id]?.updatedAt, addedOrders)
            }
            lists
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if ((error as? MdbListApiException)?.retryAtEpochMs != null) throw error
            log.w(error) { "External lists sync failed" }
            val cachedItems = previous?.itemsByList.orEmpty()
            previous?.externalLists.orEmpty().filter { it.key in cachedItems }.onEach { fetched[it.key] = cachedItems.getValue(it.key) }
        }
        items += fetched
        return externalLists
    }

    private suspend fun cachedOrFetched(
        previous: MdbListLibrarySnapshot?,
        key: String,
        updatedAt: String?,
        previousUpdatedAt: String?,
        addedOrders: MutableMap<String, Map<String, List<MdbListLibraryOrderItem>>>
    ): List<MdbListLibraryItem> {
        val cached = previous?.itemsByList?.get(key)
        val unchanged = previous?.invalidated != true && updatedAt != null && previousUpdatedAt == updatedAt
        if (unchanged && cached != null) return cached
        addedOrders.remove(key)
        return items(key)
    }

    suspend fun items(key: String, addedOrder: String? = null): List<MdbListLibraryItem> {
        val path = mdbListLibraryItemsPath(key)
        val initial = mapOf("limit" to "1000", "sort" to if (addedOrder == null) "rank" else "added",
            "order" to (addedOrder ?: "asc"), "unified" to "true") +
            if (addedOrder == null) mapOf("append_to_response" to "poster,description,genres") else emptyMap()
        var query = initial
        val visited = mutableSetOf<Map<String, String>>()
        val items = mutableListOf<MdbListLibraryItem>()
        repeat(1_000) {
            if (!visited.add(query)) throw MdbListDecodingException()
            val response = api.get(path, query, scope)
            val page = decodeMdbListLibraryPage(response.body)
            items += page.items
            val nextCursor = page.nextCursor ?: response.header("X-Next-Cursor")?.takeIf { it.isNotBlank() }
            query = when {
                nextCursor != null -> initial + ("cursor" to nextCursor)
                page.nextOffset != null -> initial + ("offset" to page.nextOffset.toString())
                response.header("X-Has-More").equals("true", ignoreCase = true) -> throw MdbListDecodingException()
                else -> return items.distinctBy { it.key }
            }
        }
        throw MdbListDecodingException()
    }
}

private val log = Logger.withTag("MdbListLibraryRemote")

internal fun mdbListLibraryItemsPath(key: String): String = when {
    key == MDBLIST_WATCHLIST_KEY -> "/watchlist/items"
    key.startsWith(MDBLIST_EXTERNAL_LIST_KEY_PREFIX) -> "/external/lists/${mdbListExternalListId(key)}/items"
    else -> "/lists/${mdbListPersonalListId(key)}/items"
}

internal fun mdbListExternalListId(key: String): Long =
    requireNotNull(key.removePrefix(MDBLIST_EXTERNAL_LIST_KEY_PREFIX).toLongOrNull()?.takeIf { it > 0 }) { "Invalid MDBList list" }

internal fun mdbListPersonalListId(key: String): Long {
    require(key.startsWith(MDBLIST_LIST_KEY_PREFIX)) { "Invalid MDBList list" }
    return requireNotNull(key.removePrefix(MDBLIST_LIST_KEY_PREFIX).toLongOrNull()?.takeIf { it > 0 }) { "Invalid MDBList list" }
}
