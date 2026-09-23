package com.nuvio.app.features.library

import com.nuvio.app.features.tracking.TrackingLibrarySorter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryProviderOrdersTest {
    private val firstKey = "mdblist:list:7"
    private val secondKey = "mdblist:list:8"
    private val items = listOf(
        LibraryItem("1", "movie", "Zulu", savedAtEpochMs = 0),
        LibraryItem("1", "series", "Alpha", savedAtEpochMs = 0),
        LibraryItem("2", "movie", "Bravo", savedAtEpochMs = 0),
    )
    private val keys = items.map(::libraryDisplayItemKey)

    @Test
    fun `title and provider sorts do not request remote added orders`() = runTest {
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = error("Unexpected request")
        }
        for (option in listOf(LibrarySortOption.DEFAULT, LibrarySortOption.TITLE_ASC, LibrarySortOption.TITLE_DESC)) {
            assertEquals(LibraryProviderOrders(), observeLibraryProviderOrders(sorter, listOf(firstKey), option).first())
        }
        assertEquals(LibraryProviderOrders(), observeLibraryProviderOrders(sorter, emptyList(), LibrarySortOption.ADDED_DESC).first())
    }

    @Test
    fun `horizontal lists each use their own server order without added timestamps`() = runTest {
        val requested = mutableListOf<Pair<String, Boolean>>()
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = flowOf(
                if (listKey == firstKey) keys else keys.reversed()
            ).also { requested += listKey to descending }
        }
        val orders = observeLibraryProviderOrders(sorter, listOf(firstKey, secondKey), LibrarySortOption.ADDED_DESC)
            .first { it.ranks.size == 2 }
        val sections = listOf(LibrarySection(firstKey, "First", items), LibrarySection(secondKey, "Second", items))
        val sorted = sortLibrarySections(sections, LibrarySortOption.ADDED_DESC, LibrarySourceMode.MDBLIST, orders.ranks)
        assertEquals(listOf("Zulu", "Alpha", "Bravo"), sorted[0].items.map { it.name })
        assertEquals(listOf("Bravo", "Alpha", "Zulu"), sorted[1].items.map { it.name })
        assertEquals(listOf(firstKey to true, secondKey to true), requested)
    }

    @Test
    fun `vertical filtering preserves server positions and title sorting stays local`() {
        val orders = mapOf(firstKey to keys.withIndex().associate { (index, key) -> key to index })
        val sections = listOf(LibrarySection(firstKey, "First", items))
        val projection = buildLibraryVerticalProjection(sections, LibrarySourceMode.MDBLIST, firstKey, "movie",
            LibrarySortOption.ADDED_ASC, orders)
        assertEquals(listOf("Zulu", "Bravo"), projection.entries.map { it.item.name })
        assertEquals(listOf("Alpha", "Bravo", "Zulu"),
            sortLibraryItems(items, LibrarySortOption.TITLE_ASC, LibrarySourceMode.MDBLIST, firstKey, orders[firstKey]).map { it.name })
        assertEquals(listOf("Zulu", "Bravo", "Alpha"),
            sortLibraryItems(items, LibrarySortOption.TITLE_DESC, LibrarySourceMode.MDBLIST, firstKey, orders[firstKey]).map { it.name })
    }

    @Test
    fun `active orders update when the provider refreshes its cache`() = runTest {
        val keysFlow = MutableStateFlow<List<String>?>(keys)
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = keysFlow
        }
        val seen = mutableListOf<LibraryProviderOrders>()
        backgroundScope.launch {
            observeLibraryProviderOrders(sorter, listOf(firstKey), LibrarySortOption.ADDED_ASC).collect { seen += it }
        }
        runCurrent()
        assertEquals(0, seen.last().ranks[firstKey]?.get(keys.first()))
        keysFlow.value = keys.reversed()
        runCurrent()
        assertEquals(2, seen.last().ranks[firstKey]?.get(keys.first()))
        keysFlow.value = null
        runCurrent()
        assertTrue(seen.last().ranks.isEmpty())
    }

    @Test
    fun `failed requests clear cached display ranks and signal fallback`() = runTest {
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = flow {
                emit(keys)
                throw IllegalStateException("Unavailable")
            }
        }
        val failed = observeLibraryProviderOrders(sorter, listOf(firstKey), LibrarySortOption.ADDED_DESC).first { it.failed }
        assertTrue(failed.ranks.isEmpty())
    }

    @Test
    fun `changing sort cancels pending requests without reporting failure`() = runTest {
        var cancelled = false
        val sorter = object : TrackingLibrarySorter {
            override fun observeAddedOrder(listKey: String, descending: Boolean) = flow<List<String>?> {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val option = MutableStateFlow(LibrarySortOption.ADDED_DESC)
        val seen = mutableListOf<LibraryProviderOrders>()
        backgroundScope.launch {
            option.flatMapLatest { observeLibraryProviderOrders(sorter, listOf(firstKey), it) }.collect { seen += it }
        }
        runCurrent()
        option.value = LibrarySortOption.TITLE_ASC
        runCurrent()
        assertTrue(cancelled)
        assertFalse(seen.any { it.failed })
        assertEquals(LibraryProviderOrders(), seen.last())
    }
}
