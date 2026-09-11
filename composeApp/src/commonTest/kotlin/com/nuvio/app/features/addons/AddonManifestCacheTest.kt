package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestCacheTest {

    @Test
    fun `codec round-trips entries unchanged`() {
        val entries = mapOf(
            "https://addon-a.example/manifest.json" to CachedManifestEntry(
                payload = """{"id":"a","name":"A"}""",
                fetchedAtMillis = 1_700_000_000_000L,
            ),
            "https://addon-b.example/manifest.json?token=x" to CachedManifestEntry(
                payload = """{"id":"b","name":"B","resources":[]}""",
                fetchedAtMillis = 1_700_000_100_000L,
            ),
        )

        val decoded = AddonManifestCacheCodec.decode(AddonManifestCacheCodec.encode(entries))

        assertEquals(entries, decoded)
    }

    @Test
    fun `decode tolerates null, blank and malformed blobs`() {
        assertTrue(AddonManifestCacheCodec.decode(null).isEmpty())
        assertTrue(AddonManifestCacheCodec.decode("").isEmpty())
        assertTrue(AddonManifestCacheCodec.decode("   ").isEmpty())
        assertTrue(AddonManifestCacheCodec.decode("{not-json").isEmpty())
        assertTrue(AddonManifestCacheCodec.decode("""{"unexpected":true}""").isEmpty())
    }

    @Test
    fun `entry is fresh inside ttl and stale at the boundary`() {
        val now = 1_700_000_000_000L
        val entry = CachedManifestEntry(payload = "{}", fetchedAtMillis = now - MANIFEST_CACHE_TTL_MS + 1)

        assertFalse(entry.isStale(now))

        val atBoundary = CachedManifestEntry(payload = "{}", fetchedAtMillis = now - MANIFEST_CACHE_TTL_MS)
        assertTrue(atBoundary.isStale(now))
    }

    @Test
    fun `entry older than ttl is stale`() {
        val now = 1_700_000_000_000L
        val entry = CachedManifestEntry(payload = "{}", fetchedAtMillis = now - MANIFEST_CACHE_TTL_MS - 1)

        assertTrue(entry.isStale(now))
    }
}
