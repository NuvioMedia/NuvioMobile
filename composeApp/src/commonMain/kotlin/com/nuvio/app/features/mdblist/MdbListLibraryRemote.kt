package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

internal class MdbListLibraryRemote(private val api: MdbListApiClient, private val scope: MdbListAuthScope) {
    /**
     * [reloadItems] downloads unchanged lists too: changing only a list's saved sort order on
     * mdblist.com does not change its update time.
     */
    suspend fun synchronize(
        previous: MdbListLibrarySnapshot?,
        accountId: Long,
        now: Long,
        reloadItems: Boolean = false
    ): MdbListLibrarySnapshot {
        val userLists = api.get("/lists/user", mapOf("unified" to "false", "sort" to "ranked"), scope).body
        val lists = decodeMdbListLibraryLists(userLists, accountId)
        val dynamicLists = decodeMdbListDynamicLists(userLists, accountId)
        val items = linkedMapOf(MDBLIST_WATCHLIST_KEY to items(MDBLIST_WATCHLIST_KEY))
        val reuseItems = !reloadItems && previous?.itemsOrder == MDBLIST_ITEMS_ORDER
        val previousUpdatedAt = previous?.lists.orEmpty().associate { it.key to it.updatedAt } +
            previous?.dynamicLists.orEmpty().associate { it.key to it.updatedAt }
        val addedOrders = previous?.addedOrders.orEmpty().toMutableMap().apply { remove(MDBLIST_WATCHLIST_KEY) }
        val hidden = previous?.hiddenListKeys.orEmpty()
        for ((key, updatedAt) in lists.map { it.key to it.updatedAt } + dynamicLists.map { it.key to it.updatedAt }) {
            // Hidden lists are not shown anywhere, so skip downloading their items.
            if (key in hidden) {
                addedOrders.remove(key)
                continue
            }
            items[key] = cachedOrFetched(previous, key, updatedAt, previousUpdatedAt[key], reuseItems, addedOrders)
        }
        val externalLists = synchronizeOptional("External", previous, previous?.externalLists.orEmpty(), hidden, reuseItems,
            items, addedOrders) { decodeMdbListExternalLists(api.get("/external/lists/user", emptyMap(), scope).body) }
        val likedLists = synchronizeOptional("Liked", previous, previous?.likedLists.orEmpty(), hidden, reuseItems,
            items, addedOrders) { likedLists(accountId) }
        val snapshot = MdbListLibrarySnapshot(lists, items, now, addedOrders = addedOrders.filterKeys { it in items },
            externalLists = externalLists, itemsOrder = MDBLIST_ITEMS_ORDER, dynamicLists = dynamicLists,
            likedLists = likedLists)
        // Drop hidden keys of lists that were deleted in MDBList.
        return snapshot.copy(hiddenListKeys = hidden intersect snapshot.tabs().mapTo(mutableSetOf()) { it.key })
    }

    // External and liked lists are optional extras: if MDBList cannot serve them, keep the cached
    // copy instead of blocking the watchlist and the user's own lists.
    private suspend fun <T : MdbListReadOnlyList> synchronizeOptional(
        label: String,
        previous: MdbListLibrarySnapshot?,
        previousLists: List<T>,
        hidden: Set<String>,
        reuseItems: Boolean,
        items: MutableMap<String, List<MdbListLibraryItem>>,
        addedOrders: MutableMap<String, Map<String, List<MdbListLibraryOrderItem>>>,
        fetchLists: suspend () -> List<T>
    ): List<T> {
        val fetched = linkedMapOf<String, List<MdbListLibraryItem>>()
        val synchronized = try {
            val lists = fetchLists()
            val previousUpdatedAt = previousLists.associate { it.key to it.updatedAt }
            for (list in lists) {
                if (list.key in hidden) {
                    addedOrders.remove(list.key)
                    continue
                }
                fetched[list.key] = cachedOrFetched(previous, list.key, list.updatedAt, previousUpdatedAt[list.key],
                    reuseItems, addedOrders)
            }
            lists
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if ((error as? MdbListApiException)?.retryAtEpochMs != null) throw error
            log.w(error) { "$label lists sync failed" }
            val cachedItems = previous?.itemsByList.orEmpty()
            // Hidden lists have no cached items but must stay listed so they remain hidden.
            previousLists.filter { it.key in cachedItems || it.key in hidden }
                .onEach { list -> cachedItems[list.key]?.let { fetched[list.key] = it } }
        }
        items += fetched
        return synchronized
    }

    private suspend fun likedLists(accountId: Long): List<MdbListLikedList> {
        val lists = mutableListOf<MdbListLikedList>()
        var offset = 0
        repeat(100) {
            val query = mapOf("limit" to "100", "offset" to offset.toString())
            val page = decodeMdbListLikedLists(api.get("/lists/liked", query, scope).body, accountId)
            lists += page.items
            offset = page.nextOffset ?: return lists.distinctBy { it.id }
        }
        throw MdbListDecodingException()
    }

    private suspend fun cachedOrFetched(
        previous: MdbListLibrarySnapshot?,
        key: String,
        updatedAt: String?,
        previousUpdatedAt: String?,
        reuseItems: Boolean,
        addedOrders: MutableMap<String, Map<String, List<MdbListLibraryOrderItem>>>
    ): List<MdbListLibraryItem> {
        val cached = previous?.itemsByList?.get(key)
        val unchanged = previous?.invalidated != true && updatedAt != null && previousUpdatedAt == updatedAt
        if (!unchanged || cached == null) addedOrders.remove(key)
        return if (unchanged && reuseItems && cached != null) cached else items(key)
    }

    // Without a sort, MDBList returns the list in the sort order its owner saved on mdblist.com.
    suspend fun items(key: String, sort: String? = null, order: String = "asc"): List<MdbListLibraryItem> {
        val path = mdbListLibraryItemsPath(key)
        val initial = mapOf("limit" to "1000", "unified" to "true") +
            if (sort == null) mapOf("append_to_response" to "poster,description,genres")
            else mapOf("sort" to sort, "order" to order)
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
    key.startsWith(MDBLIST_DYNAMIC_LIST_KEY_PREFIX) -> "/lists/${mdbListPrefixedListId(key, MDBLIST_DYNAMIC_LIST_KEY_PREFIX)}/items"
    key.startsWith(MDBLIST_LIKED_LIST_KEY_PREFIX) -> "/lists/${mdbListPrefixedListId(key, MDBLIST_LIKED_LIST_KEY_PREFIX)}/items"
    else -> "/lists/${mdbListPersonalListId(key)}/items"
}

internal fun mdbListExternalListId(key: String): Long = mdbListPrefixedListId(key, MDBLIST_EXTERNAL_LIST_KEY_PREFIX)

/** External, dynamic and liked lists are read-only: items are never added to or removed from them. */
internal fun isMdbListReadOnlyListKey(key: String): Boolean =
    key.startsWith(MDBLIST_EXTERNAL_LIST_KEY_PREFIX) || key.startsWith(MDBLIST_DYNAMIC_LIST_KEY_PREFIX) ||
        key.startsWith(MDBLIST_LIKED_LIST_KEY_PREFIX)

private fun mdbListPrefixedListId(key: String, prefix: String): Long =
    requireNotNull(key.removePrefix(prefix).toLongOrNull()?.takeIf { it > 0 }) { "Invalid MDBList list" }

internal fun mdbListPersonalListId(key: String): Long {
    require(key.startsWith(MDBLIST_LIST_KEY_PREFIX)) { "Invalid MDBList list" }
    return requireNotNull(key.removePrefix(MDBLIST_LIST_KEY_PREFIX).toLongOrNull()?.takeIf { it > 0 }) { "Invalid MDBList list" }
}
