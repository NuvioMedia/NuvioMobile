package com.nuvio.app.features.servers.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinConnectionTest {
    @Test
    fun normalizesAddresses() {
        assertEquals("http://192.168.1.10:8096", normalizeServerAddress("192.168.1.10:8096"))
        assertEquals("https://media.example.com/jellyfin", normalizeServerAddress(" https://media.example.com/jellyfin/ "))
        assertEquals("https://media.example.com/jellyfin", normalizeServerAddress("https://media.example.com/jellyfin/web/index.html"))
        assertEquals("http://host:8096", normalizeServerAddress("http://host:8096/web/?x=1#/home"))
    }

    @Test
    fun rejectsInvalidAddresses() {
        assertNull(normalizeServerAddress(""))
        assertNull(normalizeServerAddress("ftp://host"))
        assertNull(normalizeServerAddress("https://user:secret@host"))
    }

    @Test
    fun preservesReverseProxyBasePathInRequests() {
        assertEquals(
            "https://media.example.com/jellyfin/Items/abc?userId=u1",
            buildUrl("https://media.example.com/jellyfin", "/Items/${pathSegment("abc")}", mapOf("userId" to "u1", "skip" to null)),
        )
    }

    @Test
    fun requiresSupportedServerVersion() {
        assertTrue(JellyfinProvider.isSupportedVersion("10.9.0"))
        assertTrue(JellyfinProvider.isSupportedVersion("10.10.7"))
        assertTrue(JellyfinProvider.isSupportedVersion("11.0.0"))
        assertFalse(JellyfinProvider.isSupportedVersion("10.8.13"))
        assertFalse(JellyfinProvider.isSupportedVersion(null))
    }
}
