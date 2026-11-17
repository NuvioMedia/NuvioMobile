package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListSyncRemoteTest {
    @Test
    fun `watched cursor pages preserve requested fields and fetch every page`() = runTest {
        val harness = connectedHarness()
        harness.reply(body = moviePage(1, "opaque+/="))
        harness.reply(body = moviePage(2))

        val records = remote(harness).watched()

        assertEquals(listOf(1L, 2L), records.map { it.media.ids.tmdb })
        assertEquals(mapOf("append_to_response" to "poster", "limit" to "1000"), harness.engine.requests.first().query)
        assertEquals("opaque+/=", harness.engine.requests.last().query["cursor"])
        assertEquals("poster", harness.engine.requests.last().query["append_to_response"])
        assertEquals(null, harness.engine.requests.last().query["offset"])
    }

    @Test
    fun `journal continuations never combine since and cursor including filtered pages`() = runTest {
        val harness = connectedHarness()
        harness.reply(body = """{"journal":[{"category":"rated"}],"pagination":{"has_more":true,"next_cursor":"next"}}""")
        harness.reply(body = """{"journal":[],"pagination":{"has_more":false}}""")

        assertTrue(remote(harness).journal("2026-09-01T00:00:00Z").items.isEmpty())

        assertEquals("2026-09-01T00:00:00Z", harness.engine.requests.first().query["since"])
        assertEquals(mapOf("limit" to "1000", "cursor" to "next"), harness.engine.requests.last().query)
    }

    @Test
    fun `expired journal can be signalled by success or conflict and discards earlier pages`() = runTest {
        for (status in listOf(200, 409)) {
            val harness = connectedHarness()
            harness.reply(body = """{"journal":[{"category":"watched","item_type":"movie","ids":{"tmdb":1},"status":"removed","action_at":"2026-09-01T00:00:00Z"}],"pagination":{"next_cursor":"next"}}""")
            harness.reply(status, """{"requires_full_sync":true}""")

            val result = remote(harness).journal("2026-08-01T00:00:00Z")

            assertTrue(result.requiresFullSync)
            assertTrue(result.items.isEmpty())
        }
    }

    @Test
    fun `unrelated journal conflicts remain failures`() = runTest {
        val harness = connectedHarness()
        harness.reply(409, """{"journal":[]}""")
        expectMdbListFailure<MdbListApiException> { remote(harness).journal("2026-09-01T00:00:00Z") }
        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `repeated cursors stop without returning a partial list`() = runTest {
        val harness = connectedHarness()
        harness.reply(body = moviePage(1, "same"))
        harness.reply(body = moviePage(2, "same"))

        expectMdbListFailure<MdbListDecodingException> { remote(harness).watched() }
        assertEquals(2, harness.engine.requests.size)
    }

    @Test
    fun `page failure and profile change never produce a partial result`() = runTest {
        val harness = connectedHarness()
        harness.reply(body = moviePage(1, "next"))
        harness.reply(403)
        expectMdbListFailure<MdbListApiException> { remote(harness).watched() }

        val second = connectedHarness()
        val remote = remote(second)
        second.reply(body = moviePage(1, "next"))
        second.engine.intercept = { second.store.selectProfile(2) }
        expectMdbListFailure<CancellationException> { remote.watched() }
        assertEquals(1, second.engine.requests.size)
    }

    @Test
    fun `dropped reads fetch both scopes with independent cursor streams`() = runTest {
        val harness = connectedHarness()
        harness.reply(body = """{"shows":[],"pagination":{"next_cursor":"show-page"}}""")
        harness.reply(body = """{"shows":[{"show":{"ids":{"tmdb":1}}}]}""")
        harness.reply(body = """{"seasons":[{"season":{"number":2,"show":{"ids":{"tmdb":1}}}}]}""")

        val result = remote(harness).dropped()

        assertEquals(listOf(null, 2), result.map { it.season })
        assertEquals(listOf("/sync/dropped", "/sync/dropped", "/sync/seasons/dropped"), harness.engine.requests.map { it.path })
        assertEquals(null, harness.engine.requests.last().query["cursor"])
    }

    private fun remote(harness: MdbListTestHarness) = MdbListHttpSyncRemote(harness.api, harness.store.scope())
    private fun connectedHarness() = MdbListTestHarness().apply { connected() }
    private fun moviePage(id: Int, cursor: String? = null) = """{
      "movies":[{"watched_at":"2026-09-01T00:00:00Z","movie":{"ids":{"tmdb":$id}}}],
      "pagination":{"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"}}
    }"""
}
