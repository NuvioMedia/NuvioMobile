package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonTransportUrlsTest {
    @Test
    fun `episode context converts TMDB tv to series and leaves live tv distinct`() {
        assertEquals("series", externalAddonType("tv", season = 1, episode = 1))
        assertEquals("tv", externalAddonType("tv", season = null, episode = null))
        assertEquals("channel", externalAddonType(" Channel ", season = null, episode = null))
        assertEquals("PPV", externalAddonType(" PPV ", season = null, episode = null))
    }

    @Test
    fun `resource URLs keep canonical external types distinct`() {
        val manifestUrl = "https://example.com/manifest.json"

        assertEquals(
            "https://example.com/stream/series/tmdb%3A123.json",
            buildAddonResourceUrl(manifestUrl, "stream", " SERIES ", "tmdb:123"),
        )
        assertEquals(
            "https://example.com/stream/tv/channel%3A1.json",
            buildAddonResourceUrl(manifestUrl, "stream", "tv", "channel:1"),
        )
        assertEquals(
            "https://example.com/stream/channel/channel%3A1.json",
            buildAddonResourceUrl(manifestUrl, "stream", "channel", "channel:1"),
        )
        assertEquals(
            "https://example.com/stream/Ppv/channel%3A1.json",
            buildAddonResourceUrl(manifestUrl, "stream", " Ppv ", "channel:1"),
        )
    }
}
