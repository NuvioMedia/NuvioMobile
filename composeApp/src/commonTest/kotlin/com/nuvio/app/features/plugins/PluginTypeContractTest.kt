package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTypeContractTest {
    @Test
    fun `manifest defaults to movie and series`() {
        val manifest = PluginManifestScraper(
            id = "test",
            name = "Test",
            version = "1.0.0",
            filename = "test.js",
        )

        assertEquals(listOf("movie", "series"), manifest.supportedTypes)
    }

    @Test
    fun `plugin matching keeps series tv channel and custom types distinct`() {
        assertTrue(scraper(listOf(" Series ")).supportsType("series"))
        assertFalse(scraper(listOf("tv")).supportsType("series"))
        assertTrue(scraper(listOf(" TV ")).supportsType("tv"))
        assertFalse(scraper(listOf("series")).supportsType("tv"))
        assertTrue(scraper(listOf("channel")).supportsType("channel"))
        assertFalse(scraper(listOf("tv")).supportsType("channel"))
        assertTrue(scraper(listOf("ppv")).supportsType(" ppv "))
        assertEquals("series", normalizePluginType(" Series "))
        assertEquals("tv", normalizePluginType("tv"))
        assertEquals("ppv", normalizePluginType("ppv"))
    }

    private fun scraper(types: List<String>) = PluginScraper(
        id = "test",
        repositoryUrl = "https://example.com/manifest.json",
        name = "Test",
        description = "",
        version = "1.0.0",
        filename = "test.js",
        supportedTypes = types,
        enabled = true,
        manifestEnabled = true,
        code = "",
    )
}
