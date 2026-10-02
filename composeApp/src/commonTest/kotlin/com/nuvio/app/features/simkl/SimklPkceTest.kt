package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimklPkceTest {
    @Test
    fun `base64 url encoding is unpadded and url safe`() {
        assertEquals("", byteArrayOf().base64UrlWithoutPadding())
        assertEquals("Zg", "f".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm8", "fo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm9v", "foo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("-_8", byteArrayOf(0xfb.toByte(), 0xff.toByte()).base64UrlWithoutPadding())
    }

    @Test
    fun `pkce material uses compliant verifier and S256 challenge`() {
        val material = createSimklPkceMaterial(
            verifierEntropy = ByteArray(32) { it.toByte() },
            stateEntropy = ByteArray(32) { (it + 32).toByte() },
        )

        assertEquals(43, material.verifier.length)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.verifier)
        assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", material.challenge)
        assertTrue(material.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertFalse('=' in material.verifier)
        assertEquals(43, material.challenge.length)
        assertTrue(material.state.length >= 22)
    }

    @Test
    fun `oauth state comparison requires an exact value`() {
        assertTrue(constantTimeEquals("same-state", "same-state"))
        assertFalse(constantTimeEquals("same-state", "different-state"))
        assertFalse(constantTimeEquals("same-state", "same-state-extra"))
    }

    @Test
    fun `authorization URL uses browser host and exact S256 method`() {
        val url = buildSimklAuthorizationUrl(
            clientId = "client id",
            redirectUri = "lentra://auth/simkl",
            appName = "nuvio",
            appVersion = "1.2.3",
            material = SimklPkceMaterial("verifier", "challenge", "state"),
        )

        assertTrue(url.startsWith("https://simkl.com/oauth/authorize?"))
        assertTrue("client_id=client+id" in url || "client_id=client%20id" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("redirect_uri=nuvio%3A%2F%2Fauth%2Fsimkl" in url)
    }

    @Test
    fun `callback parser rejects other routes and missing state`() {
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("lentra://auth/trakt?code=a&state=b", "lentra://auth/simkl"),
        )
        assertIs<SimklAuthCallback.Invalid>(
            parseSimklAuthCallback("lentra://auth/simkl?code=a", "lentra://auth/simkl"),
        )
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback("lentra://auth/simkl?code=a&state=b", "lentra://auth/simkl"),
        )
    }

    @Test
    fun `pending authorization expires after five minutes`() {
        assertFalse(isSimklAuthorizationExpired(1_000L, 301_000L))
        assertTrue(isSimklAuthorizationExpired(1_000L, 301_001L))
        assertTrue(isSimklAuthorizationExpired(null, 1_000L))
        assertTrue(isSimklAuthorizationExpired(2_000L, 1_000L))
    }
}
