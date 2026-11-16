package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonEventsTest {

    @Test
    fun `library event url encodes ids and extras and keeps the manifest query`() {
        val url = buildAddonEventUrl(
            manifestUrl = "https://addon.example/token/manifest.json?lang=pl",
            resource = ADDON_LIBRARY_RESOURCE,
            type = "series",
            id = "tt0898266",
            extra = listOf("action" to "watched", "videoId" to "tt0898266:1:1,tt0898266:1:2"),
        )

        assertEquals(
            "https://addon.example/token/library/series/tt0898266/" +
                "action=watched&videoId=tt0898266%3A1%3A1%2Ctt0898266%3A1%3A2.json?lang=pl",
            url,
        )
    }

    @Test
    fun `player event url encodes the video id`() {
        val url = buildAddonEventUrl(
            manifestUrl = "http://192.168.1.2:7000/manifest.json",
            resource = ADDON_PLAYER_RESOURCE,
            type = "series",
            id = "kitsu:48239:1",
            extra = listOf("action" to "start", "currentTime" to "0", "duration" to "1465319"),
        )

        assertEquals(
            "http://192.168.1.2:7000/player/series/kitsu%3A48239%3A1/action=start&currentTime=0&duration=1465319.json",
            url,
        )
    }

    @Test
    fun `video ids are deduplicated and chunked by 100`() {
        val ids = (1..250).map { "tt1:1:$it" } + listOf("tt1:1:1", "")

        val chunks = libraryVideoIdChunks(ids)

        assertEquals(listOf(100, 100, 50), chunks.map { it.size })
        assertEquals("tt1:1:1", chunks.first().first())
        assertEquals("tt1:1:250", chunks.last().last())
    }

    @Test
    fun `tracking addon is detected from player or library resources`() {
        assertTrue(manifest(listOf("player")).isTrackingAddon)
        assertTrue(manifest(listOf("library")).isTrackingAddon)
        assertFalse(manifest(listOf("stream", "meta")).isTrackingAddon)
    }

    private fun manifest(resources: List<String>) = AddonManifest(
        id = "org.test",
        name = "Test",
        description = "",
        version = "1.0.0",
        resources = resources.map { AddonResource(name = it, types = listOf("movie", "series")) },
        types = listOf("movie", "series"),
        transportUrl = "https://addon.example/manifest.json",
    )
}
