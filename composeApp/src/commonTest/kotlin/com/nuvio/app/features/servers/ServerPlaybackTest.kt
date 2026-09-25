package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.isSelectableForPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerPlaybackTest {
    @AfterTest
    fun tearDown() = removeFakeServer()

    @Test
    fun nativeRequestsProduceOneAttributedGroup() = runTest {
        val connection = installFakeServer()
        val videoId = ServerItemRef(connection.id, "42").encode()

        val sources = ServerStreams.sources(type = "movie", videoId = videoId, season = null, episode = null)
        val group = sources.single().load()

        assertEquals("Fake · Box", group.addonName)
        assertTrue(ServerStreams.isServerSourceId(group.addonId))
        val stream = group.streams.single()
        assertEquals(ServerPlaybackTarget(ServerItemRef(connection.id, "42"), "src-42"), stream.serverTarget)
        assertNull(stream.playableDirectUrl)
        assertTrue(stream.hasPlayableSource)
        assertTrue(stream.needsServerPreparation)
        assertFalse(stream.isTorrentStream)
        assertTrue(stream.isSelectableForPlayback(debridEnabled = false))
    }

    @Test
    fun serverItemsAndMatchableCatalogTitlesArePlayable() {
        val connection = installFakeServer()
        assertTrue(ServerStreams.canServe("movie", ServerItemRef(connection.id, "42").encode()))
        assertTrue(ServerStreams.canServe("movie", "tt0111161"))
        assertTrue(ServerStreams.canServe("series", "tt0944947:1:1"))
        assertFalse(ServerStreams.canServe("movie", "kitsu:1"))
        assertFalse(ServerStreams.canServe("movie", ServerItemRef("cmissing", "42").encode()))

        ServerRepository.setEnabled(connection.id, false)
        assertFalse(ServerStreams.canServe("movie", ServerItemRef(connection.id, "42").encode()))
        assertFalse(ServerStreams.canServe("movie", "tt0111161"))
    }

    @Test
    fun autoplayCanChooseServerCandidates() = runTest {
        val connection = installFakeServer()
        val stream = ServerStreams.candidates(ServerItemRef(connection.id, "42")).single()

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("Some plugin"),
        )
        assertEquals(stream, selected)
    }

    @Test
    fun reportsLifecycleInOrderAndStopsOnce() = runTest {
        val provider = FakeServerProvider()
        val connection = installFakeServer(provider)
        val stream = ServerStreams.candidates(ServerItemRef(connection.id, "42")).single()

        val prepared = ServerPlayback.prepare(stream)
        val url = assertNotNull(prepared.playableDirectUrl)
        assertTrue(ServerPlayback.isServerSource(url))

        ServerPlayback.onPlaybackSnapshot(url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        ServerPlayback.onPlaybackSnapshot(url, 0L, isPlaying = true, isLoading = false, isEnded = false)
        ServerPlayback.onPlaybackSnapshot(url, 1_000L, isPlaying = false, isLoading = false, isEnded = false)
        ServerPlayback.onPlaybackSnapshot(url, 1_000L, isPlaying = true, isLoading = false, isEnded = false)
        ServerPlayback.onPlaybackSnapshot(url, 2_000L, isPlaying = false, isLoading = false, isEnded = true)
        ServerPlayback.stop(url)

        val expected = listOf(
            ServerPlaybackEventType.START,
            ServerPlaybackEventType.PAUSE,
            ServerPlaybackEventType.RESUME,
            ServerPlaybackEventType.STOP,
        )
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { while (provider.reported.size < expected.size) delay(10) }
        }
        assertEquals(expected, provider.reported.toList())
        assertFalse(ServerPlayback.isServerSource(url))
    }

    @Test
    fun providerWithoutTranscodingHasNoFallback() = runTest {
        val connection = installFakeServer()
        val prepared = ServerPlayback.prepare(ServerStreams.candidates(ServerItemRef(connection.id, "7")).single())
        val url = assertNotNull(prepared.playableDirectUrl)

        assertNull(ServerPlayback.fallback(url))
        ServerPlayback.stop(url)
    }
}
