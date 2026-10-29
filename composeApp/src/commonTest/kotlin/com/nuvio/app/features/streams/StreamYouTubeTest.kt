package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamYouTubeTest {

    private val videoId = "dQw4w9WgXcQ"
    private val hexHash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `ytId-only stream needs a YouTube resolve`() {
        assertEquals(videoId, stream(ytId = videoId).youTubeIdToResolve)
    }

    @Test
    fun `ytId is trimmed`() {
        assertEquals(videoId, stream(ytId = "  $videoId\n").youTubeIdToResolve)
    }

    @Test
    fun `blank or missing ytId needs no resolve`() {
        assertNull(stream(ytId = null).youTubeIdToResolve)
        assertNull(stream(ytId = "").youTubeIdToResolve)
        assertNull(stream(ytId = "   ").youTubeIdToResolve)
    }

    @Test
    fun `url takes precedence over ytId`() {
        assertNull(stream(ytId = videoId, url = "https://example.com/video.mp4").youTubeIdToResolve)
    }

    @Test
    fun `externalUrl takes precedence over ytId`() {
        assertNull(stream(ytId = videoId, externalUrl = "https://example.com/watch").youTubeIdToResolve)
    }

    @Test
    fun `torrent takes precedence over ytId`() {
        assertNull(stream(ytId = videoId, infoHash = hexHash).youTubeIdToResolve)
    }

    @Test
    fun `debrid resolve takes precedence over ytId`() {
        val stream = stream(
            ytId = videoId,
            clientResolve = StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
        )
        assertNull(stream.youTubeIdToResolve)
    }

    @Test
    fun `blank url or externalUrl does not hide ytId`() {
        assertEquals(videoId, stream(ytId = videoId, url = " ", externalUrl = "").youTubeIdToResolve)
    }

    @Test
    fun `ytId-only stream is selectable for playback`() {
        val stream = stream(ytId = videoId)
        assertTrue(stream.isSelectableForPlayback(debridEnabled = false))
        assertTrue(stream.isSelectableForPlayback(debridEnabled = true))
    }

    @Test
    fun `ytId-only stream has a playable source`() {
        assertTrue(stream(ytId = videoId).hasPlayableSource)
    }

    @Test
    fun `ytId-only stream has no direct url and does not open externally before resolving`() {
        val stream = stream(ytId = videoId)
        assertNull(stream.playableDirectUrl)
        assertFalse(stream.shouldOpenExternally)
        assertFalse(stream.isTorrentStream)
    }

    @Test
    fun `stream without any source is not selectable`() {
        val stream = stream(ytId = null)
        assertFalse(stream.isSelectableForPlayback(debridEnabled = false))
        assertFalse(stream.hasPlayableSource)
    }

    private fun stream(
        ytId: String?,
        url: String? = null,
        infoHash: String? = null,
        externalUrl: String? = null,
        clientResolve: StreamClientResolve? = null,
    ): StreamItem = StreamItem(
        name = "YouTube",
        url = url,
        infoHash = infoHash,
        externalUrl = externalUrl,
        ytId = ytId,
        clientResolve = clientResolve,
        addonName = "TestAddon",
        addonId = "test.addon",
    )
}
