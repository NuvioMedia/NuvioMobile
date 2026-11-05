package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerCurrentStreamTest {
    private val hash = "a".repeat(40)
    private val otherHash = "b".repeat(40)

    private fun stream(
        name: String? = "1080P WEB-DL",
        description: String? = "Release A",
        url: String? = "https://addon.example/new-token",
        infoHash: String? = null,
        fileIdx: Int? = null,
        addonId: String = "aiostreams",
        filename: String? = null,
    ) = StreamItem(
        name = name,
        description = description,
        url = url,
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = addonId,
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    private fun current(
        url: String? = "https://addon.example/old-token",
        name: String? = "1080P WEB-DL",
        description: String? = "Release A",
        addonId: String? = "aiostreams",
        infoHash: String? = null,
        fileIdx: Int? = null,
        filename: String? = null,
    ) = PlayerCurrentStream(
        url = url,
        name = name,
        description = description,
        addonId = addonId,
        infoHash = infoHash,
        fileIdx = fileIdx,
        filename = filename,
    )

    @Test
    fun `nothing is marked without a current stream`() {
        assertEquals(-1, listOf(stream()).currentPlayerStreamIndex(null))
    }

    @Test
    fun `hash wins over url`() {
        val streams = listOf(
            stream(url = "https://addon.example/old-token", infoHash = otherHash),
            stream(infoHash = hash),
        )

        assertEquals(1, streams.currentPlayerStreamIndex(current(infoHash = hash)))
    }

    @Test
    fun `file index separates files of one torrent`() {
        val streams = listOf(
            stream(infoHash = hash, fileIdx = 1),
            stream(infoHash = hash, fileIdx = 2),
        )

        assertEquals(1, streams.currentPlayerStreamIndex(current(infoHash = hash, fileIdx = 2)))
    }

    @Test
    fun `p2p stream without a direct url is matched by hash`() {
        val streams = listOf(stream(name = "720P"), stream(url = null, infoHash = hash))

        assertEquals(1, streams.currentPlayerStreamIndex(current(url = "p2p://sentinel", infoHash = hash)))
    }

    @Test
    fun `url match wins over name match`() {
        val streams = listOf(
            stream(),
            stream(description = "Release B", url = "https://addon.example/old-token"),
        )

        assertEquals(1, streams.currentPlayerStreamIndex(current()))
    }

    @Test
    fun `filename separates streams with identical name and description`() {
        val streams = listOf(stream(filename = "a.mkv"), stream(filename = "b.mkv"))

        assertEquals(1, streams.currentPlayerStreamIndex(current(filename = "b.mkv")))
    }

    @Test
    fun `filename wins over a different template and description`() {
        val streams = listOf(
            stream(),
            stream(name = "Changed", description = "Changed", filename = "a.mkv"),
        )

        assertEquals(1, streams.currentPlayerStreamIndex(current(filename = "a.mkv")))
    }

    @Test
    fun `duplicate filename falls through to name and description`() {
        val streams = listOf(
            stream(description = "Release B", filename = "a.mkv"),
            stream(filename = "a.mkv"),
        )

        assertEquals(1, streams.currentPlayerStreamIndex(current(filename = "a.mkv")))
    }

    @Test
    fun `falls back to name when addon issued a new url`() {
        assertEquals(1, listOf(stream(name = "720P"), stream()).currentPlayerStreamIndex(current()))
    }

    @Test
    fun `description separates streams sharing a template name`() {
        val streams = listOf(stream(description = "Release B"), stream())

        assertEquals(1, streams.currentPlayerStreamIndex(current()))
    }

    @Test
    fun `ambiguous name without description marks nothing`() {
        val streams = listOf(stream(description = "Release B"), stream())

        assertEquals(-1, streams.currentPlayerStreamIndex(current(description = null)))
    }

    @Test
    fun `identical streams mark nothing`() {
        assertEquals(-1, listOf(stream(), stream()).currentPlayerStreamIndex(current()))
    }

    @Test
    fun `old cache entry without description matches a unique name`() {
        val streams = listOf(stream(name = "720P"), stream())

        assertEquals(1, streams.currentPlayerStreamIndex(current(description = null)))
    }

    @Test
    fun `same name from another addon does not match`() {
        assertEquals(-1, listOf(stream(addonId = "torrentio")).currentPlayerStreamIndex(current()))
    }

    @Test
    fun `filename from another addon does not match`() {
        val streams = listOf(stream(name = "720P", addonId = "torrentio", filename = "a.mkv"))

        assertEquals(-1, streams.currentPlayerStreamIndex(current(filename = "a.mkv")))
    }
}
