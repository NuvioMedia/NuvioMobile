package com.nuvio.app.features.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YouTubeStreamResolverTest {

    private val videoId = "dQw4w9WgXcQ"
    private val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val manifestUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/1/file/index.m3u8"

    @Test
    fun `watch url is built from the video id`() {
        assertEquals(watchUrl, YouTubeStreamResolver.watchUrl(videoId))
    }

    @Test
    fun `in-app playback resolves the watch url and sets only the stream url`() = runTest {
        val requested = mutableListOf<String>()
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { url ->
            requested += url
            manifestUrl
        }
        val stream = ytIdStream()

        val resolved = resolver.resolve(stream)

        assertEquals(listOf(watchUrl), requested)
        assertEquals(stream.copy(url = manifestUrl), resolved)
        assertEquals(manifestUrl, resolved?.playableDirectUrl)
        assertNull(resolved?.youTubeIdToResolve)
    }

    @Test
    fun `resolved url is trimmed`() = runTest {
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { " $manifestUrl\n" }
        assertEquals(manifestUrl, resolver.resolve(ytIdStream())?.url)
    }

    @Test
    fun `resolves the trimmed id`() = runTest {
        val requested = mutableListOf<String>()
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { url ->
            requested += url
            manifestUrl
        }
        resolver.resolve(ytIdStream(ytId = "  $videoId "))
        assertEquals(listOf(watchUrl), requested)
    }

    @Test
    fun `failed extraction returns null`() = runTest {
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { null }
        assertNull(resolver.resolve(ytIdStream()))
    }

    @Test
    fun `blank extraction returns null`() = runTest {
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) { "   " }
        assertNull(resolver.resolve(ytIdStream()))
    }

    @Test
    fun `without in-app playback the watch page opens externally and nothing is extracted`() = runTest {
        var calls = 0
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = false) {
            calls++
            manifestUrl
        }
        val stream = ytIdStream()

        val resolved = resolver.resolve(stream)

        assertEquals(0, calls)
        assertEquals(stream.copy(externalUrl = watchUrl), resolved)
        assertTrue(resolved?.shouldOpenExternally == true)
        assertEquals(watchUrl, resolved?.externalOpenUrl)
        assertNull(resolved?.youTubeIdToResolve)
    }

    @Test
    fun `streams that do not need resolving are returned untouched`() = runTest {
        var calls = 0
        val resolver = YouTubeStreamResolver(inAppPlaybackEnabled = true) {
            calls++
            manifestUrl
        }
        val direct = ytIdStream().copy(url = "https://example.com/video.mp4")
        val external = ytIdStream().copy(externalUrl = "https://example.com/watch")
        val torrent = ytIdStream().copy(infoHash = "0123456789abcdef0123456789abcdef01234567")
        val plain = ytIdStream(ytId = null).copy(url = "https://example.com/video.mp4")

        assertSame(direct, resolver.resolve(direct))
        assertSame(external, resolver.resolve(external))
        assertSame(torrent, resolver.resolve(torrent))
        assertSame(plain, resolver.resolve(plain))
        assertEquals(0, calls)
    }

    private fun ytIdStream(ytId: String? = videoId): StreamItem = StreamItem(
        name = "YouTube",
        description = "Watch on YouTube",
        ytId = ytId,
        addonName = "TestAddon",
        addonId = "test.addon",
        behaviorHints = StreamBehaviorHints(bingeGroup = "yt"),
    )
}
