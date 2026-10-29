package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAvailabilityTest {
    @Test
    fun `empty setup and catalog only addons do not enable playback`() {
        assertFalse(available())
        assertFalse(available(addons = listOf(addon(resource = "catalog"))))
        assertFalse(available(addons = listOf(addon(resource = "meta"))))
    }

    @Test
    fun `a compatible stream addon enables playback without plugins`() {
        assertTrue(available(addons = listOf(addon())))
        assertFalse(available(addons = listOf(addon().copy(enabled = false))))
        assertFalse(available(addons = listOf(addon().copy(manifest = null))))
    }

    @Test
    fun `stream addons must support the requested type and id`() {
        val addons = listOf(addon())
        assertFalse(available(addons = addons, type = "series"))
        assertFalse(available(addons = addons, videoId = "kitsu:123"))
        assertTrue(available(addons = listOf(addon(prefixes = emptyList())), videoId = "kitsu:123"))
    }

    @Test
    fun `an enabled compatible plugin enables playback without addons`() {
        val plugins = PluginsUiState(scrapers = listOf(scraper()))
        assertTrue(available(plugins = plugins))
        assertTrue(available(plugins = plugins, type = "series"))
        assertFalse(available(plugins = plugins, type = "channel"))
        assertFalse(available(plugins = plugins.copy(pluginsEnabled = false)))
        assertFalse(available(plugins = plugins.copy(scrapers = listOf(scraper().copy(enabled = false)))))
    }

    @Test
    fun `either source can enable playback independently`() {
        assertTrue(available(
            addons = listOf(addon(resource = "catalog")),
            plugins = PluginsUiState(scrapers = listOf(scraper())),
        ))
        assertTrue(available(
            addons = listOf(addon()),
            plugins = PluginsUiState(pluginsEnabled = false, scrapers = listOf(scraper())),
        ))
    }

    private fun available(
        addons: List<ManagedAddon> = emptyList(),
        plugins: PluginsUiState = PluginsUiState(),
        type: String = "movie",
        videoId: String = "tt123",
    ): Boolean = hasCompatiblePlaybackSource(addons, plugins, type, videoId)

    private fun addon(
        resource: String = "stream",
        prefixes: List<String> = listOf("tt"),
    ): ManagedAddon = ManagedAddon(
        manifestUrl = "https://example.com/manifest.json",
        manifest = AddonManifest(
            id = "test",
            name = "Test",
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource(resource, listOf("movie"), prefixes)),
            types = listOf("movie"),
            transportUrl = "https://example.com",
        ),
    )

    private fun scraper(): PluginScraper = PluginScraper(
        id = "test",
        repositoryUrl = "https://example.com/plugins.json",
        name = "Test",
        description = "",
        version = "1.0.0",
        filename = "test.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )
}
