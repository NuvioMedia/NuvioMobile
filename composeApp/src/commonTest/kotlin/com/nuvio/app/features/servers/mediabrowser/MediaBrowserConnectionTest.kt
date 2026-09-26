package com.nuvio.app.features.servers.mediabrowser

import com.nuvio.app.features.servers.jellyfin.JellyfinProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaBrowserConnectionTest {
    private val jellyfin = JellyfinProvider(TestHttp().client)

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
    fun requiresSupportedJellyfinVersion() {
        assertTrue(jellyfin.isSupportedVersion("10.9.0"))
        assertTrue(jellyfin.isSupportedVersion("10.10.7"))
        assertTrue(jellyfin.isSupportedVersion("10.11.0-rc1"))
        assertTrue(jellyfin.isSupportedVersion("11.0.0"))
        assertFalse(jellyfin.isSupportedVersion("10.8.13"))
        assertFalse(jellyfin.isSupportedVersion("4.8.10.0"))
        assertFalse(jellyfin.isSupportedVersion(null))
    }
}
