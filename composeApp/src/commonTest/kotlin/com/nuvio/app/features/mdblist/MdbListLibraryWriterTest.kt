package com.nuvio.app.features.mdblist

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.LibraryListPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListLibraryWriterTest {
    private val movie = LibraryItem("tt1", "movie", "Movie", tmdbId = 1, savedAtEpochMs = 0)

    @Test
    fun `unchecked MDBList destinations do not fail edits for titles known only to another provider`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val unknown = movie.copy(id = "simkl:123", type = "anime", tmdbId = null)
        writer(h).applyMembershipChanges(h.repository.currentScope(), unknown, changes(false, false))
        assertTrue(h.http.engine.requests.isEmpty())
        expectMdbListFailure<IllegalArgumentException> {
            writer(h).applyMembershipChanges(h.repository.currentScope(), unknown, changes(true, false))
        }
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `watchlist and static lists use their different ID payloads and retain other memberships`() = runTest {
        for (type in listOf("movie", "series", "anime")) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.seedLibrary()
            val input = movie.copy(type = type)
            val bucket = if (type == "movie") "movies" else "shows"
            h.http.reply(body = """{"added":1,"existing":0,"not_found":0}""")
            h.http.reply(body = """{"added":{"$bucket":1},"existing":{"$bucket":0},"not_found":{"$bucket":0}}""")
            val writer = writer(h)
            writer.applyMembershipChanges(h.repository.currentScope(), input, changes(true, true))
            assertEquals(listOf("/watchlist/items/add", "/lists/7/items/add"), h.http.engine.requests.map { it.path })
            val bodies = h.http.engine.requests.map { mdbListResponseElement(it.body).objectValue().arrayValue(bucket).single().objectValue() }
            assertEquals(1L, bodies[0].objectValue("ids")!!.number("tmdb"))
            assertEquals(1L, bodies[1].number("tmdb"))
            assertFalse(bodies[1].containsKey("ids"))
            h.http.reply(body = """{"removed":1,"not_found":0}""")
            writer.applyMembershipChanges(h.repository.currentScope(), input, (mapOf(MDBLIST_WATCHLIST_KEY to false)))
            val library = h.repository.currentSnapshot()!!.library!!
            assertTrue(library.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).isEmpty())
            assertEquals(1, library.itemsByList.getValue(MDBLIST_TEST_LIST_KEY).size)
            assertEquals(3, h.http.engine.requests.size)
        }
    }

    @Test
    fun `already correct membership makes no network writes`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        writer(h).applyMembershipChanges(h.repository.currentScope(), movie, changes(false, false))
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `server already-added receipt reconciles stale local membership`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(body = """{"added":0,"existing":1,"not_found":0}""")
        writer(h).applyMembershipChanges(h.repository.currentScope(), movie, (mapOf(MDBLIST_WATCHLIST_KEY to true)))
        assertEquals(1, h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).size)
    }

    @Test
    fun `partial success is persisted when another list write fails and is not replayed`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.reply(body = """{"added":1,"existing":0,"not_found":0}""")
        h.http.reply(500)
        expectMdbListFailure<MdbListApiException> {
            writer(h).applyMembershipChanges(h.repository.currentScope(), movie, changes(true, true))
        }
        val library = h.repository.currentSnapshot()!!.library!!
        assertEquals(1, library.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).size)
        assertTrue(library.itemsByList.getValue(MDBLIST_TEST_LIST_KEY).isEmpty())
        assertTrue(library.invalidated)
        assertEquals(2, h.http.engine.requests.size)
        assertEquals(library, Json.decodeFromString<MdbListSyncSnapshot>(h.storage.profiles.getValue(1)).library)
    }

    @Test
    fun `unresolved or malformed mutation receipts do not fabricate membership`() = runTest {
        for (body in listOf("{}", """{"added":0,"existing":0,"not_found":1}""", """{"added":2}""", """{"added":-1,"existing":2}""")) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.seedLibrary()
            h.http.reply(body = body)
            expectMdbListFailure<MdbListDecodingException> {
                writer(h).applyMembershipChanges(h.repository.currentScope(), movie, (mapOf(MDBLIST_WATCHLIST_KEY to true)))
            }
            assertTrue(h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).isEmpty())
            assertTrue(h.repository.currentSnapshot()!!.library!!.invalidated)
            assertEquals(1, h.http.engine.requests.size)
        }
    }

    @Test
    fun `foreign keys nonexistent lists and incompatible item types are rejected before writes`() = runTest {
        for (key in listOf("watchlist", "personal:7", "mdblist:list:../8", "mdblist:list:8")) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.seedLibrary()
            expectMdbListFailure<IllegalArgumentException> {
                writer(h).applyMembershipChanges(h.repository.currentScope(), movie, (mapOf(key to true)))
            }
            assertTrue(h.http.engine.requests.isEmpty())
        }
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary(mdbListLibrarySnapshot(h.http.now).copy(lists = listOf(MdbListLibraryList(7, "Shows", true, mediaType = MdbListItemType.SHOW))))
        expectMdbListFailure<IllegalArgumentException> {
            writer(h).applyMembershipChanges(h.repository.currentScope(), movie, (mapOf(MDBLIST_TEST_LIST_KEY to true)))
        }
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `create rename privacy and delete update cached tabs after confirmed responses`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val writer = writer(h)
        h.http.reply(201, """{"id":8,"slug":"new-list"}""")
        writer.createList(" New list ", null, LibraryListPrivacy.PRIVATE)
        assertEquals("New list", h.repository.currentSnapshot()!!.library!!.lists.last().name)
        h.http.reply(body = """{"success":true,"id":8,"name":"Renamed","private":false}""")
        writer.updateList("mdblist:list:8", "Renamed", null, LibraryListPrivacy.PUBLIC)
        assertFalse(h.repository.currentSnapshot()!!.library!!.lists.last().private)
        h.http.reply(body = """{"success":true,"id":8,"name":"Renamed"}""")
        writer.deleteList("mdblist:list:8")
        assertEquals(listOf(7L), h.repository.currentSnapshot()!!.library!!.lists.map { it.id })
        assertFalse(h.repository.currentSnapshot()!!.library!!.itemsByList.containsKey("mdblist:list:8"))
        assertEquals(listOf(MdbListHttpMethod.POST, MdbListHttpMethod.PUT, MdbListHttpMethod.DELETE), h.http.engine.requests.map { it.method })
        assertEquals("""{"name":"New list","private":true}""", h.http.engine.requests.first().body)
        assertEquals(3, h.http.engine.requests.size)
    }

    @Test
    fun `unsupported description privacy and ordering cannot silently discard user choices`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val writer = writer(h)
        assertFalse(writer.capabilities.supportsDescription)
        assertFalse(writer.capabilities.supportsReordering)
        for (privacy in listOf(LibraryListPrivacy.LINK, LibraryListPrivacy.FRIENDS)) {
            expectMdbListFailure<IllegalArgumentException> { writer.createList("List", null, privacy) }
        }
        expectMdbListFailure<IllegalArgumentException> { writer.createList("List", "Description", LibraryListPrivacy.PRIVATE) }
        expectMdbListFailure<IllegalArgumentException> { writer.createList(" ", null, LibraryListPrivacy.PRIVATE) }
        expectMdbListFailure<UnsupportedOperationException> { writer.reorderLists(listOf(MDBLIST_TEST_LIST_KEY)) }
        assertTrue(h.http.engine.requests.isEmpty())
    }

    @Test
    fun `account list limit and forbidden changes retain lists without retrying the write`() = runTest {
        for (status in listOf(400, 403, 404, 429, 500)) {
            val h = MdbListSyncTestHarness(backgroundScope)
            h.seedLibrary()
            h.http.reply(status, "{}", if (status == 429) mapOf("Retry-After" to "600") else emptyMap())
            expectMdbListFailure<MdbListApiException> { writer(h).createList("List", null, LibraryListPrivacy.PRIVATE) }
            assertEquals(listOf(7L), h.repository.currentSnapshot()!!.library!!.lists.map { it.id })
            assertTrue(h.repository.currentSnapshot()!!.library!!.invalidated)
            assertEquals(1, h.http.engine.requests.size)
        }
    }

    @Test
    fun `false or mismatched metadata confirmations cannot rename or delete cached lists`() = runTest {
        for (body in listOf("{}", """{"success":false,"id":7}""", """{"success":true,"id":8}""")) {
            for (delete in listOf(true, false)) {
                val h = MdbListSyncTestHarness(backgroundScope)
                h.seedLibrary()
                h.http.reply(body = body)
                expectMdbListFailure<MdbListDecodingException> {
                    if (delete) writer(h).deleteList(MDBLIST_TEST_LIST_KEY)
                    else writer(h).updateList(MDBLIST_TEST_LIST_KEY, "Changed", null, LibraryListPrivacy.PUBLIC)
                }
                assertEquals("Favourites", h.repository.currentSnapshot()!!.library!!.lists.single().name)
            }
        }
    }

    @Test
    fun `concurrent duplicate additions send one write and keep one cached item`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.http.engine.intercept = { entered.complete(Unit); release.await() }
        h.http.reply(body = """{"added":1,"existing":0,"not_found":0}""")
        val writer = writer(h)
        val scope = h.repository.currentScope()
        val changes = (mapOf(MDBLIST_WATCHLIST_KEY to true))
        val first = async { writer.applyMembershipChanges(scope, movie, changes) }
        entered.await()
        val second = async { writer.applyMembershipChanges(scope, movie, changes) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, h.http.engine.requests.size)
        assertEquals(1, h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).size)
    }

    @Test
    fun `switching profiles after server acceptance cannot write the new profile cache`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.engine.intercept = { h.switch(2, 84) }
        h.http.reply(201, """{"id":8}""")
        expectMdbListFailure<CancellationException> { writer(h).createList("List", null, LibraryListPrivacy.PRIVATE) }
        h.repository.ensureLoaded()
        assertEquals(84L, h.repository.currentSnapshot()?.accountId)
        assertEquals(null, h.repository.currentSnapshot()?.library)
        assertFalse(h.storage.profiles.containsKey(2))
        assertEquals(1, h.http.engine.requests.size)
    }

    @Test
    fun `cancellation invalidates uncertain membership for later reconciliation`() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.http.engine.intercept = { awaitCancellation() }
        val job = launch { writer(h).applyMembershipChanges(h.repository.currentScope(), movie, changes(true, false)) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(h.repository.currentSnapshot()!!.library!!.invalidated)
        assertTrue(h.repository.currentSnapshot()!!.library!!.itemsByList.getValue(MDBLIST_WATCHLIST_KEY).isEmpty())
    }

    private fun writer(h: MdbListSyncTestHarness) = MdbListLibraryWriter(h.http.api, h.repository) { h.http.now }

    private fun changes(watchlist: Boolean, personal: Boolean) = (linkedMapOf(
        MDBLIST_WATCHLIST_KEY to watchlist, MDBLIST_TEST_LIST_KEY to personal
    ))
}
