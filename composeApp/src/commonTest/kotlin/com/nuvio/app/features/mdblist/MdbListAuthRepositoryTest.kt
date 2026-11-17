package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListAuthRepositoryTest {
    @Test
    fun `device flow uses form encoded public client and stores both verification links`() = runTest {
        val harness = MdbListTestHarness()
        val session = harness.pending()
        val request = harness.engine.requests.single()

        assertEquals("/oauth/device-authorization/", request.path)
        assertEquals(mapOf("client_id" to "public-client", "scope" to "write"), request.form)
        assertNull(request.accessToken)
        assertFalse(request.retrySafe)
        assertEquals("ABCD-EFGH", session.userCode)
        assertEquals("https://mdblist.com/oauth/device/?user_code=ABCD-EFGH", session.verificationUriComplete)
        assertEquals(harness.now + 300_000L, session.expiresAtEpochMs)
        assertFalse(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun `missing client fails before network access`() = runTest {
        val harness = MdbListTestHarness()
        val auth = MdbListAuthRepository(harness.http, harness.configuration.copy(clientId = ""), harness.store)

        val error = expectMdbListFailure<MdbListAuthException> { auth.startDeviceAuthorization() }

        assertEquals(MdbListAuthError.MISSING_CLIENT_ID, error.error)
        assertTrue(harness.engine.requests.isEmpty())
    }

    @Test
    fun `untrusted verification destination and invalid response do not persist authorization`() = runTest {
        for (body in listOf(
            "{}", "not-json",
            MdbListTestHarness.DEVICE_RESPONSE.replace("https://mdblist.com", "https://example.com"),
            MdbListTestHarness.DEVICE_RESPONSE.replace("\"expires_in\":300", "\"expires_in\":-1"),
            MdbListTestHarness.DEVICE_RESPONSE.replace("\"interval\":5", "\"interval\":0")
        )) {
            val harness = MdbListTestHarness()
            harness.reply(body = body)
            expectMdbListFailure<MdbListAuthException> { harness.auth.startDeviceAuthorization() }
            assertNull(harness.store.state.value.session)
        }
    }

    @Test
    fun `polling before server interval makes no request`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()

        repeat(3) { assertEquals(MdbListDevicePollResult.Pending, harness.auth.pollDeviceAuthorization()) }

        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `pending response preserves device code and schedules next poll`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(400, """{"error":"authorization_pending"}""")

        assertEquals(MdbListDevicePollResult.Pending, harness.auth.pollDeviceAuthorization())

        val request = harness.engine.requests.last()
        assertEquals("/oauth/token/", request.path)
        assertEquals("device-secret", request.form?.get("device_code"))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", request.form?.get("grant_type"))
        assertEquals("write", request.form?.get("scope"))
        assertEquals(harness.now + 5_000L, harness.store.state.value.session?.nextPollAtEpochMs)
    }

    @Test
    fun `slow down permanently increases polling interval`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(400, """{"error":"slow_down"}""")
        harness.auth.pollDeviceAuthorization()

        assertEquals(10, harness.store.state.value.session?.intervalSeconds)
        harness.now += 5_000L
        harness.auth.pollDeviceAuthorization()
        assertEquals(2, harness.engine.requests.size)
        harness.now += 5_000L
        harness.reply(400, """{"error":"slow_down"}""")
        harness.auth.pollDeviceAuthorization()
        assertEquals(15, harness.store.state.value.session?.intervalSeconds)
    }

    @Test
    fun `expired local session ends without polling`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()
        harness.now += 300_000L

        assertEquals(MdbListDevicePollResult.Expired, harness.auth.pollDeviceAuthorization())
        assertNull(harness.store.state.value.session)
        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `denial and server expiry clear pending secret`() = runTest {
        for ((code, expected) in listOf(
            "access_denied" to MdbListDevicePollResult.Denied,
            "expired_token" to MdbListDevicePollResult.Expired
        )) {
            val harness = MdbListTestHarness()
            harness.pending()
            harness.now += 5_000L
            harness.reply(400, """{"error":"$code"}""")

            assertEquals(expected, harness.auth.pollDeviceAuthorization())
            assertNull(harness.store.deviceCode(harness.store.scope()))
            assertFalse(harness.store.state.value.isAuthenticated)
        }
    }

    @Test
    fun `approval stores renewable credentials without an extra identity or sync request`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)

        assertEquals(MdbListDevicePollResult.Authorized, harness.auth.pollDeviceAuthorization())

        assertEquals("access-two", harness.store.authorization()?.tokens?.accessToken)
        assertEquals("refresh-two", harness.store.authorization()?.tokens?.refreshToken)
        assertNull(harness.store.state.value.session)
        assertNull(harness.store.deviceCode(harness.store.scope()))
        assertEquals(2, harness.engine.requests.size)
    }

    @Test
    fun `read only token cannot silently enable watched writes`() = runTest {
        val harness = MdbListTestHarness()
        harness.pending()
        harness.now += 5_000L
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE.replace("\"scope\":\"write\"", "\"scope\":\"read\""))
        val error = expectMdbListFailure<MdbListAuthException> { harness.auth.pollDeviceAuthorization() }
        assertEquals(MdbListAuthError.INSUFFICIENT_SCOPE, error.error)
        assertFalse(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun `profile change and cancellation reject late device and token responses`() = runTest {
        for (approve in listOf(false, true)) {
            val harness = MdbListTestHarness()
            if (approve) {
                harness.pending()
                harness.now += 5_000L
                harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)
            } else {
                harness.reply(body = MdbListTestHarness.DEVICE_RESPONSE)
            }
            harness.engine.intercept = { harness.store.selectProfile(2) }
            expectMdbListFailure<CancellationException> {
                if (approve) harness.auth.pollDeviceAuthorization() else harness.auth.startDeviceAuthorization()
            }
            assertFalse(harness.store.state.value.isAuthenticated)
            assertNull(harness.store.state.value.session)
            harness.store.selectProfile(1)
            assertFalse(harness.store.state.value.isAuthenticated)
        }
        val harness = MdbListTestHarness()
        harness.reply(body = MdbListTestHarness.DEVICE_RESPONSE)
        harness.engine.intercept = { harness.auth.cancelDeviceAuthorization() }
        expectMdbListFailure<CancellationException> { harness.auth.startDeviceAuthorization() }
        assertNull(harness.store.state.value.session)
    }

    @Test
    fun `queued account actions cannot use another profile or a replaced session`() = runTest {
        for (switchProfile in listOf(false, true)) {
            val harness = MdbListTestHarness()
            val scope = harness.store.scope()
            if (switchProfile) harness.store.selectProfile(2) else harness.store.cancelSession()
            expectMdbListFailure<CancellationException> { harness.auth.startDeviceAuthorization(scope) }
            expectMdbListFailure<CancellationException> { harness.auth.pollDeviceAuthorization(scope) }
            harness.connected()
            expectMdbListFailure<CancellationException> { harness.auth.disconnect(scope) }
            assertTrue(harness.store.state.value.isAuthenticated)
            assertTrue(harness.engine.requests.isEmpty())
        }
    }

    @Test
    fun `simultaneous requests refresh expiring tokens only once`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected(expiresIn = 30_000L)
        val scope = harness.store.scope()
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)

        val results = List(8) { async { harness.auth.authorization(scope) } }.map { it.await() }

        assertTrue(results.all { it.tokens.accessToken == "access-two" })
        assertEquals(1, harness.engine.requests.size)
        assertEquals("refresh_token", harness.engine.requests.single().form?.get("grant_type"))
        assertEquals("refresh-two", harness.store.authorization()?.tokens?.refreshToken)
    }

    @Test
    fun `refresh response may retain previous refresh token`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected(expiresIn = 1)
        harness.reply(body = """{"access_token":"renewed","token_type":"bearer","expires_in":300}""")

        val current = harness.auth.authorization(harness.store.scope())

        assertEquals("refresh-one", current.tokens.refreshToken)
    }

    @Test
    fun `invalid grant disconnects while transient refresh errors keep credentials`() = runTest {
        for (status in listOf(400, 503)) {
            val harness = MdbListTestHarness()
            harness.connected(expiresIn = 1)
            harness.reply(status, if (status == 400) """{"error":"invalid_grant"}""" else "{}")

            expectMdbListFailure<Exception> { harness.auth.authorization(harness.store.scope()) }

            assertEquals(status != 400, harness.store.state.value.isAuthenticated)
            assertEquals(1, harness.engine.requests.size)
        }
    }

    @Test
    fun `disconnect removes local credentials even when revocation fails`() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(503)

        assertFalse(harness.auth.disconnect())

        assertFalse(harness.store.state.value.isAuthenticated)
        assertNull(harness.store.authorization())
        assertEquals("/oauth/revoke_token/", harness.engine.requests.single().path)
        assertEquals("refresh_token", harness.engine.requests.single().form?.get("token_type_hint"))
    }
}
