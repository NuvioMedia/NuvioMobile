package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktAuthRepositoryTest {

    @Test
    fun `invalid grant permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400, "invalid_grant"),
        )
    }

    @Test
    fun `other HTTP 400 responses preserve credentials`() {
        assertEquals(
            TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
            traktTokenRefreshResponseAction(400, "invalid_request"),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
            traktTokenRefreshResponseAction(400, null),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(TraktTokenRefreshResponseAction.ACCEPT, traktTokenRefreshResponseAction(200))
        assertEquals(TraktTokenRefreshResponseAction.ACCEPT, traktTokenRefreshResponseAction(201))
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(TraktTokenRefreshResponseAction.TRANSIENT_FAILURE, traktTokenRefreshResponseAction(status))
        }
    }

    @Test
    fun `PKCE S256 challenge matches the RFC example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", traktPkceChallenge(verifier))
    }

    @Test
    fun `PKCE verifier and challenge are URL safe and unpadded`() {
        val pkce = createTraktPkceParameters()
        assertEquals(43, pkce.verifier.length)
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(43, pkce.challenge.length)
        assertTrue(pkce.challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `authorization URL includes PKCE and OAuth state`() {
        val url = buildTraktAuthorizationUrl(
            clientId = "client-id",
            redirectUri = "nuvio://auth/trakt",
            state = "state-value",
            challenge = "challenge-value",
        )
        assertTrue(url.startsWith("https://auth.trakt.tv/oauth/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-id"))
        assertTrue(url.contains("redirect_uri=nuvio%3A%2F%2Fauth%2Ftrakt"))
        assertTrue(url.contains("state=state-value"))
        assertTrue(url.contains("code_challenge=challenge-value"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalse(url.contains("client_secret"))
    }
}
