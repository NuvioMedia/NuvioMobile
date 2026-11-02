package com.nuvio.app.features.simkl

import io.ktor.http.Url
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklAuthV2Test {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `authorize url targets oauth2 with S256 pkce write scope and state`() {
        val url = buildSimklAuthorizationUrl(
            clientId = "client-123",
            redirectUri = "nuvioiptv://auth/simkl",
            material = SimklPkceMaterial(verifier = "v", challenge = "chal_lenge-1", state = "st@te"),
        )

        assertTrue(url.startsWith("https://simkl.com/oauth2/authorize?"))
        val parameters = Url(url).parameters
        assertEquals("code", parameters["response_type"])
        assertEquals("client-123", parameters["client_id"])
        assertEquals("nuvioiptv://auth/simkl", parameters["redirect_uri"])
        assertEquals("media:read media:write", parameters["scope"])
        assertEquals("st@te", parameters["state"])
        assertEquals("chal_lenge-1", parameters["code_challenge"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertNull(parameters["app-name"])
        assertNull(parameters["app-version"])
        assertFalse("client_secret" in url)
        assertFalse("/oauth/authorize" in url)
    }

    @Test
    fun `authorization code grant form body has exactly the documented string fields`() {
        val body = encodeSimklForm(
            simklAuthorizationCodeForm(
                clientId = "client-123",
                code = "abc/def",
                codeVerifier = "verifier-._~",
                redirectUri = "nuvioiptv://auth/simkl",
            ),
        )

        assertEquals(
            "grant_type=authorization_code&client_id=client-123&code=abc%2Fdef" +
                "&code_verifier=verifier-._~&redirect_uri=nuvioiptv%3A%2F%2Fauth%2Fsimkl",
            body,
        )
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "client-123",
                "code" to "abc/def",
                "code_verifier" to "verifier-._~",
                "redirect_uri" to "nuvioiptv://auth/simkl",
            ),
            decodeForm(body),
        )
        assertFalse("client_secret" in body)
    }

    @Test
    fun `refresh grant form body sends client id and refresh token only`() {
        val body = encodeSimklForm(
            simklRefreshTokenForm(clientId = "client-123", refreshToken = "simkl_rt_abc"),
        )

        assertEquals("grant_type=refresh_token&client_id=client-123&refresh_token=simkl_rt_abc", body)
        assertFalse("client_secret" in body)
    }

    @Test
    fun `token requests post form encoded to the oauth2 endpoint with a user agent`() {
        assertEquals("https://api.simkl.com/oauth2/token", SIMKL_TOKEN_URL)
        val headers = simklTokenRequestHeaders()
        assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
        assertTrue(headers.getValue("User-Agent").contains('/'))
        assertFalse("Authorization" in headers)
    }

    @Test
    fun `refresh is needed only inside the expiry skew and only with a refresh token`() {
        val expiresAt = 1_000_000L
        val skew = 300_000L
        val tokens = SimklTokenSet("simkl_at_a", "simkl_rt_b", expiresAt)

        assertEquals(
            SimklTokenRefreshDecision.USE_CURRENT,
            simklTokenRefreshDecision(tokens, nowEpochMs = expiresAt - skew - 1, skewMs = skew),
        )
        assertEquals(
            SimklTokenRefreshDecision.REFRESH,
            simklTokenRefreshDecision(tokens, nowEpochMs = expiresAt - skew, skewMs = skew),
        )
        assertEquals(
            SimklTokenRefreshDecision.REFRESH,
            simklTokenRefreshDecision(tokens, nowEpochMs = expiresAt + 1, skewMs = skew),
        )
        assertEquals(
            SimklTokenRefreshDecision.USE_CURRENT,
            simklTokenRefreshDecision(tokens.copy(refreshToken = null), nowEpochMs = expiresAt + 1, skewMs = skew),
        )
        assertEquals(
            SimklTokenRefreshDecision.USE_CURRENT,
            simklTokenRefreshDecision(tokens.copy(expiresAtEpochMs = null), nowEpochMs = expiresAt + 1, skewMs = skew),
        )
        assertEquals(
            SimklTokenRefreshDecision.NO_TOKEN,
            simklTokenRefreshDecision(null, nowEpochMs = 0L, skewMs = skew),
        )
    }

    @Test
    fun `legacy stored v1 token without refresh token still loads`() {
        val legacyPayload = """
            {"username":"someone","accountId":42,"hasFetchedUserSettings":true,
             "tokenExpiresAtEpochMs":1900000000000}
        """.trimIndent()
        val state = json.decodeFromString<SimklStoredAuthState>(legacyPayload)

        val tokens = resolveSimklStoredTokens(
            accessToken = "legacy-v1-token",
            refreshToken = null,
            state = state,
        )

        assertNotNull(tokens)
        assertEquals("legacy-v1-token", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertFalse(tokens.canRefresh)
        assertEquals(1_900_000_000_000L, tokens.expiresAtEpochMs)
        assertNull(state.grantedScope)
        assertEquals("someone", state.username)
        assertEquals(
            SimklTokenRefreshDecision.USE_CURRENT,
            simklTokenRefreshDecision(tokens, nowEpochMs = 2_000_000_000_000L),
        )
        assertNull(resolveSimklStoredTokens(accessToken = " ", refreshToken = "simkl_rt_x", state = state))
    }

    @Test
    fun `token response keeps refresh token and computes expiry`() {
        val result = parseSimklTokenResult(
            status = 200,
            body = """{"access_token":"simkl_at_new","token_type":"Bearer","expires_in":604800,
                "refresh_token":"simkl_rt_same","scope":"media:read media:write"}""",
            json = json,
        )

        val success = assertIs<SimklTokenResult.Success>(result)
        val tokens = success.token.toTokenSet(nowEpochMs = 1_000L)
        assertEquals("simkl_at_new", tokens.accessToken)
        assertEquals("simkl_rt_same", tokens.refreshToken)
        assertEquals(1_000L + 604_800_000L, tokens.expiresAtEpochMs)
        assertEquals("media:read media:write", success.token.scope)
    }

    @Test
    fun `refresh response without a refresh token keeps the previous one`() {
        val response = SimklTokenResponse(accessToken = "simkl_at_new", expiresIn = 604800)
        assertEquals("simkl_rt_old", response.toTokenSet(0L, previousRefreshToken = "simkl_rt_old").refreshToken)
    }

    @Test
    fun `invalid grant is distinguished from transient token failures`() {
        assertIs<SimklTokenResult.InvalidGrant>(
            parseSimklTokenResult(400, """{"error":"invalid_grant","error_description":"x"}""", json),
        )
        assertIs<SimklTokenResult.Failed>(parseSimklTokenResult(503, "", json))
        assertIs<SimklTokenResult.Failed>(parseSimklTokenResult(401, """{"error":"invalid_client"}""", json))
        assertIs<SimklTokenResult.Failed>(parseSimklTokenResult(200, """{"access_token":""}""", json))
    }

    private fun decodeForm(body: String): Map<String, String> =
        Url("https://example.invalid/?$body").parameters.entries()
            .associate { (key, values) -> key to values.single() }
}
