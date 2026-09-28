package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListLibrarySorterTest {
    private val page = """[
        {"id":1,"mediatype":"movie","title":"Zulu","rank":0,"added_at":null},
        {"id":1,"mediatype":"show","title":"Alpha","rank":1,"added_at":null},
        {"id":2,"mediatype":"movie","title":"Bravo","rank":2,"added_at":null}
    ]"""
    private val items get() = decodeMdbListLibraryPage(page).items
    private val order get() = items.map { MdbListLibraryOrderItem(it.type, it.media.ids) }

    @Test
    fun `zero based provider ranks survive decoding and projection`() {
        val snapshot = mdbListLibrarySnapshot(0).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to items))
        assertEquals(listOf(0, 1, 2), MdbListLibraryProjection(snapshot).entries.map { it.listRanks[MDBLIST_TEST_LIST_KEY] })
    }

    @Test
    fun `older cached entries missing the first rank retain provider sequence`() {
        val cached = items.mapIndexed { index, item -> if (index == 0) item.copy(rank = null) else item }
        val snapshot = mdbListLibrarySnapshot(0).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to cached))
        assertEquals(listOf(0, 1, 2), MdbListLibraryProjection(snapshot).entries.map { it.listRanks[MDBLIST_TEST_LIST_KEY] })
    }

    @Test
    fun `each added direction is fetched once and survives a cache reload`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to items)))
        h.repository.ensureLoaded()
        val sorter = h.libraryService(backgroundScope).listSorter
        h.http.reply(body = page)
        h.http.reply(body = """[{"id":2,"mediatype":"movie"},{"id":1,"mediatype":"show"},{"id":1,"mediatype":"movie"}]""")
        val expected = listOf("movie:tmdb:1", "series:tmdb:1", "movie:tmdb:2")
        assertEquals(expected, sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, false).first())
        assertEquals(expected.reversed(), sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first())
        repeat(3) {
            assertEquals(expected, sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, false).first())
            assertEquals(expected.reversed(), sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first())
        }
        assertEquals(listOf("asc", "desc"), h.http.engine.requests.map { it.query["order"] })
        assertTrue(h.http.engine.requests.all { it.query["sort"] == "added" && it.query["unified"] == "true" })
        val restored = MdbListSyncTestHarness(backgroundScope)
        restored.seed(Json.decodeFromString<MdbListSyncSnapshot>(h.storage.profiles.getValue(1)))
        restored.repository.ensureLoaded()
        assertEquals(expected.reversed(), restored.libraryService(backgroundScope).listSorter
            .observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first())
        assertTrue(restored.http.engine.requests.isEmpty())
    }

    @Test
    fun `unified order follows header cursors across mixed media pages`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.repository.ensureLoaded()
        h.http.reply(body = """[{"id":1,"mediatype":"show"}]""",
            headers = mapOf("X-Has-More" to "true", "X-Next-Cursor" to "next"))
        h.http.reply(body = """[{"id":1,"mediatype":"movie"}]""", headers = mapOf("X-Has-More" to "false"))
        val keys = h.libraryService(backgroundScope).listSorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first()
        assertEquals(listOf("series:tmdb:1", "movie:tmdb:1"), keys)
        assertEquals("next", h.http.engine.requests.last().query["cursor"])
        assertTrue(h.http.engine.requests.all { it.query["sort"] == "added" && it.query["order"] == "desc" })
    }

    @Test
    fun `order identifiers resolve to the same aliases used by library cards`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val aliased = items.map { it.copy(media = it.media.copy(ids = it.media.ids.copy(imdb = "tt${it.media.ids.tmdb}"))) }
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to aliased)))
        h.repository.ensureLoaded()
        h.http.reply(body = page)
        assertEquals(listOf("movie:tt1", "series:tt1", "movie:tt2"), h.libraryService(backgroundScope).listSorter
            .observeAddedOrder(MDBLIST_TEST_LIST_KEY, false).first())
    }

    @Test
    fun `additional identifiers in sort responses do not change existing card keys`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to items)))
        h.repository.ensureLoaded()
        h.http.reply(body = """[{"id":1,"imdb_id":"tt1","mediatype":"movie"}]""")
        assertEquals(listOf("movie:tmdb:1"), h.libraryService(backgroundScope).listSorter
            .observeAddedOrder(MDBLIST_TEST_LIST_KEY, false).first())
    }

    @Test
    fun `failed later page never caches a partial order`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val snapshot = mdbListLibrarySnapshot(h.http.now).copy(addedOrders = mapOf(MDBLIST_TEST_LIST_KEY to mapOf("asc" to order)))
        h.seedLibrary(snapshot)
        h.repository.ensureLoaded()
        h.http.reply(body = page, headers = mapOf("X-Next-Cursor" to "next"))
        h.http.reply(body = "{}")
        expectMdbListFailure<MdbListDecodingException> {
            h.libraryService(backgroundScope).listSorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first()
        }
        assertEquals(snapshot, h.repository.currentSnapshot()!!.library)
    }

    @Test
    fun `concurrent requests for the same order share the cache fill`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.repository.ensureLoaded()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.http.engine.intercept = { entered.complete(Unit); release.await() }
        h.http.reply(body = page)
        val sorter = h.libraryService(backgroundScope).listSorter
        val first = async { sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first() }
        entered.await()
        val second = async { sorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first() }
        runCurrent()
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, h.http.engine.requests.size)
    }

    @Test
    fun `refresh keeps unchanged static orders and invalidates watchlist and changed lists`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val orders = mapOf("asc" to order, "desc" to order.reversed())
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(addedOrders = mapOf(
            MDBLIST_WATCHLIST_KEY to orders, MDBLIST_TEST_LIST_KEY to orders
        )))
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(mapOf(MDBLIST_TEST_LIST_KEY to orders), h.repository.currentSnapshot()!!.library!!.addedOrders)
        h.http.reply(body = mdbListLibraryListsBody("v2"))
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = page)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertTrue(h.repository.currentSnapshot()!!.library!!.addedOrders.isEmpty())
    }

    @Test
    fun `membership writes invalidate only the changed list order`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val orders = mapOf("asc" to order)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(addedOrders = mapOf(
            MDBLIST_WATCHLIST_KEY to orders, MDBLIST_TEST_LIST_KEY to orders
        )))
        h.http.reply(body = """{"added":{"movies":1}}""")
        h.libraryService(backgroundScope).applyMembershipChanges(LibraryItem("tmdb:1", "movie", "Movie", savedAtEpochMs = 0),
            mapOf(MDBLIST_TEST_LIST_KEY to true))
        assertEquals(mapOf(MDBLIST_WATCHLIST_KEY to orders), h.repository.currentSnapshot()!!.library!!.addedOrders)
    }

    @Test
    fun `active order reloads automatically after a list version changes`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(
            itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to items),
            addedOrders = mapOf(MDBLIST_TEST_LIST_KEY to mapOf("desc" to order))
        ))
        h.repository.ensureLoaded()
        val service = h.libraryService(backgroundScope)
        val observed = mutableListOf<List<String>?>()
        backgroundScope.launch { service.listSorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).collect { observed += it } }
        runCurrent()
        assertEquals(listOf("movie:tmdb:1", "series:tmdb:1", "movie:tmdb:2"), observed.last())
        h.http.reply(body = mdbListLibraryListsBody("v2"))
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = page)
        h.http.reply(body = """[{"id":2,"mediatype":"movie"},{"id":1,"mediatype":"show"},{"id":1,"mediatype":"movie"}]""")
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        runCurrent()
        assertEquals(listOf("movie:tmdb:2", "series:tmdb:1", "movie:tmdb:1"), observed.last())
        assertEquals(4, h.http.engine.requests.size)
        assertEquals("added", h.http.engine.requests.last().query["sort"])
    }

    @Test
    fun `profile switch during an order request cannot save or publish it to another account`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.repository.ensureLoaded()
        h.http.reply(body = page)
        h.http.engine.intercept = { h.switch(2, 84) }
        expectMdbListFailure<CancellationException> {
            h.libraryService(backgroundScope).listSorter.observeAddedOrder(MDBLIST_TEST_LIST_KEY, true).first()
        }
        val old = Json.decodeFromString<MdbListSyncSnapshot>(h.storage.profiles.getValue(1))
        assertTrue(old.library!!.addedOrders.isEmpty())
        assertFalse(h.storage.profiles.containsKey(2))
    }
}
