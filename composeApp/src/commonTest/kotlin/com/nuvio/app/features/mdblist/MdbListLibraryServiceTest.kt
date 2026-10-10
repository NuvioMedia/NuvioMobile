package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
class MdbListLibraryServiceTest {
    @Test
    fun `unresolved provider IDs do not break the shared membership picker`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val membership = h.libraryService(backgroundScope).getMembershipSnapshot(LibraryItem("simkl:123", "anime", "Anime", savedAtEpochMs = 0))
        assertEquals(setOf(MDBLIST_WATCHLIST_KEY, MDBLIST_TEST_LIST_KEY), membership.keys)
        assertTrue(membership.values.none { it })
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `hidden lists are left out of tabs items snapshot and membership and are not synced`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(
            itemsByList = mapOf(MDBLIST_WATCHLIST_KEY to emptyList()),
            hiddenListKeys = setOf(MDBLIST_TEST_LIST_KEY)
        ))
        val service = h.libraryService(backgroundScope)
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY), service.tabs.first().map { it.key })
        assertEquals(listOf(MdbListLibraryListOption(MDBLIST_TEST_LIST_KEY, "Favourites", false)), service.listOptions.first())
        val membership = service.getMembershipSnapshot(LibraryItem("tt0111161", "movie", "Shawshank", savedAtEpochMs = 0))
        assertEquals(setOf(MDBLIST_WATCHLIST_KEY), membership.keys)

        h.http.reply(body = mdbListLibraryListsBody("v2"))
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        h.http.reply(body = "[]")
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/external/lists/user"), h.http.engine.requests.map { it.path })
        assertEquals(setOf(MDBLIST_TEST_LIST_KEY), h.repository.currentSnapshot()!!.library!!.hiddenListKeys)
        assertEquals(listOf(setOf(MDBLIST_WATCHLIST_KEY)), service.items.first().map { it.listKeys })
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY), service.snapshot().tabs.map { it.key })
    }

    @Test
    fun `showing a hidden list loads its items and hiding drops them`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(
            itemsByList = mapOf(MDBLIST_WATCHLIST_KEY to emptyList()),
            hiddenListKeys = setOf(MDBLIST_TEST_LIST_KEY)
        ))
        val service = h.libraryService(backgroundScope)
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        service.setListVisible(MDBLIST_TEST_LIST_KEY, true)
        assertEquals(listOf("/lists/7/items"), h.http.engine.requests.map { it.path })
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY, MDBLIST_TEST_LIST_KEY), service.tabs.first().map { it.key })
        assertEquals(listOf("Shawshank"), service.items.first().map { it.name })

        service.setListVisible(MDBLIST_TEST_LIST_KEY, false)
        assertEquals(1, h.http.engine.requests.size)
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY), service.tabs.first().map { it.key })
        assertTrue(service.items.first().isEmpty())
        assertEquals(null, h.repository.currentSnapshot()!!.library!!.itemsByList[MDBLIST_TEST_LIST_KEY])
        expectMdbListFailure<IllegalArgumentException> { service.setListVisible(MDBLIST_WATCHLIST_KEY, false) }
    }

    @Test
    fun `disk read failure is surfaced by manual refresh and does not crash automatic refresh`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.storage.failLoad = true
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.AUTOMATIC)
        expectMdbListFailure<kotlinx.io.IOException> { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        assertFalse(service.isRefreshing.first())
        assertTrue(service.snapshot().hasLoaded)
        assertEquals("Could not sync with MDBList. Please try again.", service.snapshot().errorMessage)
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `cold library uses four requests for a watchlist one static list and no external lists and persists them`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = "[]")
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/lists/7/items", "/external/lists/user"), h.http.engine.requests.map { it.path })
        assertEquals("false", h.http.engine.requests.first().query["unified"])
        val persisted = Json.decodeFromString<MdbListSyncSnapshot>(h.storage.profiles.getValue(1))
        assertEquals(h.repository.currentSnapshot()!!.library, persisted.library)
        assertEquals(listOf("Shawshank"), service.items.first().map { it.name })
        assertEquals(2, service.tabs.first().size)
        runCurrent()
        assertEquals(4, h.http.engine.requests.size)
        assertTrue(h.remote.calls.isEmpty())
    }

    @Test
    fun `external lists become read-only tabs with their items`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.http.reply(body = "[]")
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = """[{"id":9,"name":"IMDb Top","source":"imdb","items":1}]""")
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/external/lists/user", "/external/lists/9/items"),
            h.http.engine.requests.map { it.path })
        val external = service.tabs.first().single { it.key == "mdblist:external:9" }
        assertEquals(TrackingLibraryTabKind.EXTERNAL, external.kind)
        assertEquals("IMDb Top", external.title)
        assertFalse(external.isMembershipDestination)
        assertEquals(listOf(setOf("mdblist:external:9")), service.items.first().map { it.listKeys })

        service.applyMembershipChanges(LibraryItem("tt0111161", "movie", "Shawshank", savedAtEpochMs = 0),
            mapOf("mdblist:external:9" to false))
        assertEquals(4, h.http.engine.requests.size)
    }

    @Test
    fun `external list failures keep the cached external lists without failing the refresh`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val movie = decodeMdbListLibraryPage(MDBLIST_LIBRARY_MOVIE_PAGE).items
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(
            externalLists = listOf(MdbListExternalList(9, "IMDb Top", "imdb")),
            itemsByList = mapOf(MDBLIST_WATCHLIST_KEY to emptyList(), MDBLIST_TEST_LIST_KEY to emptyList(), "mdblist:external:9" to movie)
        ))
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(404, "{}")
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/lists/7/items", "/external/lists/user"),
            h.http.engine.requests.map { it.path })
        val library = h.repository.currentSnapshot()!!.library!!
        assertEquals(listOf(9L), library.externalLists.map { it.id })
        assertEquals(movie, library.itemsByList["mdblist:external:9"])
        assertTrue(service.tabs.first().any { it.key == "mdblist:external:9" })
    }

    @Test
    fun `cached library membership and repeated browsing make no requests`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val service = h.libraryService(backgroundScope)
        repeat(5) {
            service.refresh(TrackingRefreshIntent.AUTOMATIC)
            service.getMembershipSnapshot(LibraryItem("tt1", "movie", "Movie", savedAtEpochMs = 0))
            service.tabs.first()
            service.items.first()
        }
        runCurrent()
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `unchanged list versions need only metadata and watchlist requests after fifteen minutes`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.AUTOMATIC)
        h.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = "[]")
        service.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/external/lists/user"), h.http.engine.requests.map { it.path })
    }

    @Test
    fun `libraries cached with an older item order download their lists once more`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsOrder = 0))
        val service = h.libraryService(backgroundScope)
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        h.http.reply(body = "[]")
        h.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS
        service.refresh(TrackingRefreshIntent.AUTOMATIC)
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = "[]")
        h.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS
        service.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/lists/7/items", "/external/lists/user",
            "/lists/user", "/watchlist/items", "/external/lists/user"),
            h.http.engine.requests.map { it.path })
        assertEquals(1, h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_TEST_LIST_KEY).size)
    }

    @Test
    fun `manual refresh downloads unchanged lists again to pick up a new saved sort`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(body = mdbListLibraryListsBody())
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        h.http.reply(body = "[]")
        h.libraryService(backgroundScope).refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf("/lists/user", "/watchlist/items", "/lists/7/items", "/external/lists/user"),
            h.http.engine.requests.map { it.path })
        assertEquals(1, h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_TEST_LIST_KEY).size)
    }

    @Test
    fun `changed or missing versions refetch contents even when item counts stay equal`() = runTest {
        for (version in listOf("v2", null)) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.seedLibrary()
            h.http.reply(body = mdbListLibraryListsBody(version))
            h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
            h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
            h.http.reply(body = "[]")
            h.libraryService(backgroundScope).refresh(TrackingRefreshIntent.USER_INITIATED)
            assertEquals(4, h.http.engine.requests.size)
            assertEquals(1, h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_TEST_LIST_KEY).size)
        }
    }

    @Test
    fun `removed or no longer static lists disappear with their membership`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val movie = decodeMdbListLibraryPage(MDBLIST_LIBRARY_MOVIE_PAGE).items
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsByList = mapOf(MDBLIST_TEST_LIST_KEY to movie)))
        h.http.reply(body = """[{"id":7,"type":"dynamic","name":"Changed"}]""")
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = "[]")
        val service = h.libraryService(backgroundScope)
        service.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY), service.tabs.first().map { it.key })
        assertTrue(service.items.first().isEmpty())
    }

    @Test
    fun `cursor and offset pagination preserve every page with bundled metadata`() = runTest {
        for (cursor in listOf(true, false)) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.http.reply(body = """{"movies":[{"id":1}],"pagination":${if (cursor) """{"next_cursor":"page-two","has_more":true}""" else """{"offset":0,"limit":1,"total":2}"""}}""")
            h.http.reply(body = """{"movies":[{"id":2}],"pagination":{"has_more":false}}""")
            val result = MdbListLibraryRemote(h.http.api, h.repository.currentScope()).items(MDBLIST_TEST_LIST_KEY)
            assertEquals(listOf(1L, 2L), result.map { it.media.ids.tmdb })
            val queries = h.http.engine.requests.map { it.query }
            assertEquals(if (cursor) "page-two" else "1", queries[1][if (cursor) "cursor" else "offset"])
            assertTrue(queries.all { it["limit"] == "1000" && it["append_to_response"] == "poster,description,genres" })
            // No sort, so MDBList applies the sort order saved for the list.
            assertTrue(queries.none { "sort" in it || "order" in it })
        }
    }

    @Test
    fun `repeated cursors and unpageable has-more headers never silently truncate`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        repeat(2) { h.http.reply(body = """{"movies":[{"id":1}],"pagination":{"next_cursor":"same"}}""") }
        expectMdbListFailure<MdbListDecodingException> {
            MdbListLibraryRemote(h.http.api, h.repository.currentScope()).items(MDBLIST_TEST_LIST_KEY)
        }
        assertEquals(2, h.http.engine.requests.size)
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE, headers = mapOf("X-Has-More" to "true"))
        expectMdbListFailure<MdbListDecodingException> {
            MdbListLibraryRemote(h.http.api, h.repository.currentScope()).items(MDBLIST_TEST_LIST_KEY)
        }
    }

    @Test
    fun `failed refresh retains the entire cached library and automatic attempts back off`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(body = mdbListLibraryListsBody("v2"))
        h.http.reply(body = MDBLIST_LIBRARY_MOVIE_PAGE)
        h.http.reply(body = "{}")
        val service = h.libraryService(backgroundScope)
        expectMdbListFailure<MdbListDecodingException> { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        assertEquals(mdbListLibrarySnapshot(h.http.now), h.repository.currentSnapshot()!!.library)
        repeat(3) { service.refresh(TrackingRefreshIntent.AUTOMATIC) }
        assertEquals(3, h.http.engine.requests.size)
        assertFalse(service.isRefreshing.first())
    }

    @Test
    fun `rate-limited refresh respects reset even when the user requests another refresh`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(429, "{}", mapOf("Retry-After" to "3600"))
        val service = h.libraryService(backgroundScope)
        repeat(3) { expectMdbListFailure<MdbListApiException> { service.refresh(TrackingRefreshIntent.USER_INITIATED) } }
        assertEquals(1, h.http.engine.requests.size)
        assertEquals(mdbListLibrarySnapshot(h.http.now), h.repository.currentSnapshot()!!.library)
    }

    @Test
    fun `overlapping refreshes coalesce to one request set`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.http.engine.intercept = { if (it.path == "/lists/user") { entered.complete(Unit); release.await() } }
        h.http.reply(body = "[]")
        h.http.reply(body = MDBLIST_EMPTY_LIBRARY_PAGE)
        h.http.reply(body = "[]")
        val service = h.libraryService(backgroundScope)
        val first = async { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        entered.await()
        val second = async { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(3, h.http.engine.requests.size)
    }

    @Test
    fun `profile switch during refresh cannot publish or persist the previous account library`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(body = mdbListLibraryListsBody("v2"))
        h.http.engine.intercept = { h.switch(2, 84) }
        val service = h.libraryService(backgroundScope)
        expectMdbListFailure<CancellationException> { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        h.http.engine.intercept = {}
        h.repository.ensureLoaded()
        assertEquals(84L, h.repository.currentSnapshot()?.accountId)
        assertEquals(null, h.repository.currentSnapshot()?.library)
        val old = Json.decodeFromString<MdbListSyncSnapshot>(h.storage.profiles.getValue(1))
        assertEquals("v1", old.library!!.lists.single().updatedAt)
    }

    @Test
    fun `disconnect hides cached items and membership before further account requests`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(itemsByList = mapOf(MDBLIST_WATCHLIST_KEY to decodeMdbListLibraryPage(MDBLIST_LIBRARY_MOVIE_PAGE).items)))
        val service = h.libraryService(backgroundScope)
        h.repository.ensureLoaded()
        assertEquals(1, service.items.first().size)
        h.http.store.clearAuth(h.http.store.scope())
        assertTrue(service.items.first().isEmpty())
        assertTrue(service.tabs.first().isEmpty())
        assertTrue(service.observeMembership("tt0111161", "movie").first().isEmpty())
        runCurrent()
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `cancelled refresh clears loading and keeps cached data`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.engine.intercept = { awaitCancellation() }
        val service = h.libraryService(backgroundScope)
        val job = launch { service.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        assertTrue(service.isRefreshing.first())
        job.cancel()
        job.join()
        assertFalse(service.isRefreshing.first())
        assertEquals(mdbListLibrarySnapshot(h.http.now), h.repository.currentSnapshot()?.library)
    }
}
