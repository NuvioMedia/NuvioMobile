package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListApiClientTest {
    @Test
    fun `list update and delete preserve scope and never retry ambiguous failures`() = runTest {
        for (method in listOf(MdbListHttpMethod.PUT, MdbListHttpMethod.DELETE)) {
            for (status in listOf(204, 500)) {
                val harness = MdbListTestHarness()
                harness.connected()
                harness.reply(status, "")
                val request: suspend () -> MdbListHttpResponse = {
                    if (method == MdbListHttpMethod.PUT) harness.api.put("/lists/42", "list-body")
                    else harness.api.delete("/lists/42")
                }
                if (status == 204) assertEquals(204, request().status)
                else assertEquals(status, expectMdbListFailure<MdbListApiException> { request() }.status)
                assertEquals(1, harness.engine.requests.size)
                assertEquals(method, harness.engine.requests.single().method)
                assertFalse(harness.engine.requests.single().retrySafe)
            }
        }
    }

    @Test
    fun `initial user response quota follows the newly identified account`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"user_id":42,"username":"viewer"}""", headers = mapOf(
            "X-RateLimit-Remaining" to "0",
            "X-RateLimit-Reset" to ((harness.now + 60_000L) / 1_000L).toString()
        ))

        harness.api.refreshUser()
        expectMdbListFailure<MdbListApiException> { harness.api.get("/sync/playback") }

        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `valid token reads user once and stores identity`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"user_id":42,"username":"viewer","is_supporter":true,"unknown":"field"}""")

        val user = harness.api.refreshUser()

        assertEquals(42L, user.id)
        assertEquals("viewer", harness.store.state.value.user?.username)
        assertTrue(user.isSupporter)
        assertEquals(1, harness.engine.requests.size)
        assertEquals("access-one", harness.engine.requests.single().accessToken)
        assertTrue(harness.engine.requests.single().query.isEmpty())
    }

    @Test
    fun `rejected access token refreshes then replays original request exactly once`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(401)
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)
        harness.reply(body = """{"added":{"movies":1}}""")

        harness.api.post("/sync/watched", "movie-body")

        assertEquals(listOf("/sync/watched", "/oauth/token/", "/sync/watched"), harness.engine.requests.map { it.path })
        assertEquals("access-two", harness.engine.requests.last().accessToken)
        assertEquals("movie-body", harness.engine.requests.last().body)
    }

    @Test
    fun `second unauthorized response ends connection instead of refreshing forever`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(401)
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)
        harness.reply(401)

        expectMdbListFailure<MdbListAuthException> { harness.api.get("/sync/playback") }

        assertEquals(3, harness.engine.requests.size)
        assertFalse(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun `unrelated HTTP failures do not clear connection`() = runTest {
        for (status in listOf(400, 403, 404, 409)) {
            val harness = MdbListTestHarness()
            harness.connected()
            harness.reply(status)

            assertEquals(status, expectMdbListFailure<MdbListApiException> { harness.api.get("/sync/playback") }.status)
            assertTrue(harness.store.state.value.isAuthenticated)
            assertEquals(1, harness.engine.requests.size)
        }
    }

    @Test
    fun `stale profile response cannot update or clear another account`() = runTest {
        for (status in listOf(200, 401)) {
            val harness = MdbListTestHarness()
            harness.connected()
            harness.reply(status, """{"user_id":42,"username":"old-user"}""")
            harness.engine.intercept = {
                harness.store.selectProfile(2)
                harness.connected("profile-two-access", "profile-two-refresh")
            }

            expectMdbListFailure<CancellationException> { harness.api.refreshUser() }

            assertEquals("profile-two-access", harness.store.authorization()?.tokens?.accessToken)
            assertEquals(null, harness.store.state.value.user)
            assertEquals(1, harness.engine.requests.size)
        }
    }

    @Test
    fun `malformed user response never overwrites cached identity`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.store.saveUser(MdbListUser(42, "existing"), harness.store.scope())
        harness.reply(body = "{}")

        expectMdbListFailure<MdbListAuthException> { harness.api.refreshUser() }

        assertEquals("existing", harness.store.state.value.user?.username)
    }
}
