package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktAuthFlowTest {

    @Test
    fun `successful device token responses are approved`() {
        assertEquals(TraktDeviceTokenPollOutcome.Approved, traktDeviceTokenPollOutcome(200, 5))
        assertEquals(TraktDeviceTokenPollOutcome.Approved, traktDeviceTokenPollOutcome(201, 5))
    }

    @Test
    fun `HTTP 400 means the user has not approved yet`() {
        assertEquals(TraktDeviceTokenPollOutcome.Pending, traktDeviceTokenPollOutcome(400, 5))
    }

    @Test
    fun `HTTP 429 slows polling down by five seconds up to a minute`() {
        assertEquals(TraktDeviceTokenPollOutcome.SlowDown(10), traktDeviceTokenPollOutcome(429, 5))
        assertEquals(TraktDeviceTokenPollOutcome.SlowDown(60), traktDeviceTokenPollOutcome(429, 58))
        assertEquals(TraktDeviceTokenPollOutcome.SlowDown(6), traktDeviceTokenPollOutcome(429, 0))
    }

    @Test
    fun `terminal device token responses end the flow`() {
        assertEquals(TraktDeviceTokenPollOutcome.InvalidCode, traktDeviceTokenPollOutcome(404, 5))
        assertEquals(TraktDeviceTokenPollOutcome.AlreadyUsed, traktDeviceTokenPollOutcome(409, 5))
        assertEquals(TraktDeviceTokenPollOutcome.Expired, traktDeviceTokenPollOutcome(410, 5))
        assertEquals(TraktDeviceTokenPollOutcome.Denied, traktDeviceTokenPollOutcome(418, 5))
    }

    @Test
    fun `unexpected statuses are reported as failures`() {
        listOf(401, 403, 500, 503).forEach { status ->
            assertEquals(TraktDeviceTokenPollOutcome.Failed(status), traktDeviceTokenPollOutcome(status, 5))
        }
    }

    @Test
    fun `rate limit wait rounds up to whole minutes and defaults to five`() {
        assertEquals(5L, traktRateLimitedMinutes(null))
        assertEquals(1L, traktRateLimitedMinutes(1L))
        assertEquals(1L, traktRateLimitedMinutes(60L))
        assertEquals(2L, traktRateLimitedMinutes(61L))
    }

    @Test
    fun `loopback redirect URIs are recognised`() {
        assertEquals(TraktLoopbackTarget(8765, "/trakt"), parseTraktLoopbackRedirect("http://localhost:8765/trakt"))
        assertEquals(TraktLoopbackTarget(8765, "/trakt"), parseTraktLoopbackRedirect("http://127.0.0.1:8765/trakt"))
        assertNull(parseTraktLoopbackRedirect("nuvio://auth/trakt"))
        assertNull(parseTraktLoopbackRedirect("urn:ietf:wg:oauth:2.0:oob"))
        assertNull(parseTraktLoopbackRedirect("https://localhost:8765/trakt"))
        assertNull(parseTraktLoopbackRedirect("http://localhost.example.com:8765/trakt"))
    }

    @Test
    fun `flow follows the redirect URI and platform support`() {
        assertEquals(TraktAuthFlow.LOOPBACK_PKCE, selectTraktAuthFlow("http://localhost:8765/trakt", true))
        assertEquals(TraktAuthFlow.DEVICE_CODE, selectTraktAuthFlow("http://localhost:8765/trakt", false))
        assertEquals(TraktAuthFlow.DEVICE_CODE, selectTraktAuthFlow("urn:ietf:wg:oauth:2.0:oob", true))
    }

    @Test
    fun `authorize URL carries the PKCE challenge`() {
        val url = buildTraktAuthorizeUrl("id", "http://localhost:8765/trakt", "st", "ch")
        assertTrue(url.startsWith("https://trakt.tv/oauth/authorize?response_type=code&client_id=id"))
        assertTrue("redirect_uri=http%3A%2F%2Flocalhost%3A8765%2Ftrakt" in url)
        assertTrue("&state=st" in url)
        assertTrue("&code_challenge=ch&code_challenge_method=S256" in url)
    }

    @Test
    fun `loopback callbacks are parsed`() {
        assertEquals(
            TraktLoopbackCallback.AuthorizationCode(code = "abc", state = "xyz"),
            parseTraktLoopbackCallback("/trakt?code=abc&state=xyz"),
        )
        val error = parseTraktLoopbackCallback("/trakt?error=access_denied&error_description=No%20thanks")
        assertIs<TraktLoopbackCallback.Error>(error)
        assertEquals("access_denied", error.error)
        assertEquals("No thanks", error.description)
        assertEquals(TraktLoopbackCallback.Invalid, parseTraktLoopbackCallback("/trakt?code=abc"))
    }
}
