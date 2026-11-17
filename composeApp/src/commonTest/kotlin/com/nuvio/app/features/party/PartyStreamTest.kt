package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyStreamTest {
    private val stream = PartyStream(
        mediaKey = "movie|tt1||",
        url = "https://cdn.example.com/movie.mkv",
        headers = mapOf("Referer" to "https://example.com"),
        responseHeaders = mapOf("Content-Type" to "video/x-matroska"),
        name = "TB HD",
        description = "Movie 2017\n1.5 GB",
        addonName = "Torrentio",
        addonId = "addon:torrentio",
        addonLogo = "https://example.com/logo.png",
        filename = "movie.mkv",
        videoSize = 1_500_000_000L,
        bingeGroup = "torrentio|720p",
        badges = listOf(StreamBadge(name = "HDR", imageURL = "https://example.com/hdr.png")),
    )

    @Test
    fun remoteLinksCanBeShared() {
        assertTrue(isShareableStreamUrl("https://cdn.example.com/movie.mkv"))
        assertTrue(isShareableStreamUrl("http://192.168.1.20:8096/video"))
    }

    @Test
    fun localAndNonWebLinksCannotBeShared() {
        assertFalse(isShareableStreamUrl("http://127.0.0.1:11470/stream"))
        assertFalse(isShareableStreamUrl("http://localhost:8080/video"))
        assertFalse(isShareableStreamUrl("http://[::1]:8080/video"))
        assertFalse(isShareableStreamUrl("http://0.0.0.0/video"))
        assertFalse(isShareableStreamUrl("file:///data/movie.mkv"))
        assertFalse(isShareableStreamUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun sharedStreamKeepsCardDetailsAndHeaders() {
        val item = stream.toStreamItem()
        assertEquals("https://cdn.example.com/movie.mkv", item.playableDirectUrl)
        assertEquals("TB HD", item.streamLabel)
        assertEquals("Movie 2017\n1.5 GB", item.streamSubtitle)
        assertEquals("Torrentio", item.addonName)
        assertEquals("https://example.com/logo.png", item.addonLogo)
        assertEquals(1_500_000_000L, item.behaviorHints.videoSize)
        assertEquals("movie.mkv", item.behaviorHints.filename)
        assertEquals(mapOf("Referer" to "https://example.com"), item.behaviorHints.proxyHeaders?.request)
        assertEquals("HDR", item.badges.single().name)
        assertFalse(item.isTorrentStream)
    }

    @Test
    fun sharedStreamSurvivesTheWire() {
        val encoded = PartyApi.json.encodeToString(PartyStream.serializer(), stream)
        assertEquals(stream, PartyApi.json.decodeFromString(PartyStream.serializer(), encoded))
    }
}
