package com.nuvio.app.features.servers

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerWatchedTest {
    @AfterTest
    fun tearDown() {
        removeFakeServer()
        ServerMatcher.clear()
    }

    private fun movie(id: String) = WatchedItem(id = id, type = "movie", name = "", markedAtEpochMs = 0L)

    private fun provider() = FakeServerProvider().apply {
        indexedIds["7"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
        indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947", tmdb = 1399)
        episodes[1 to 2] = ServerEpisode(itemId = "501", premiereDate = null)
    }

    @Test
    fun mirrorsCatalogMarksWhenAddonMetadataIsOn() = runTest {
        val provider = provider()
        val connection = installFakeServer(provider)
        ServerRepository.setCatalogMetadata(connection.id, true)
        val episode = WatchedItem(
            id = "tt0944947",
            type = "series",
            name = "",
            season = 1,
            episode = 2,
            videoId = "tt0944947:1:2",
            markedAtEpochMs = 0L,
        )

        val marked = assertNotNull(ServerWatched.mirror(listOf(movie("tt0111161"), episode, movie("tt9999999")), played = true))
        withContext(Dispatchers.Default) { withTimeout(15_000L) { marked.join() } }
        assertEquals(setOf("7" to true, "501" to true), provider.playedChanges.toSet())
        val cleared = assertNotNull(ServerWatched.mirror(listOf(movie("tt0111161")), played = false))
        withContext(Dispatchers.Default) { withTimeout(15_000L) { cleared.join() } }

        assertEquals("7" to false, provider.playedChanges.last())
        assertEquals(3, provider.playedChanges.size)
    }

    @Test
    fun leavesServerAloneWhenAddonMetadataIsOff() {
        installFakeServer(provider())

        assertNull(ServerWatched.mirror(listOf(movie("tt0111161")), played = true))
    }

    @Test
    fun serverItemsKeepTheirDirectPath() {
        val connection = installFakeServer(provider())
        ServerRepository.setCatalogMetadata(connection.id, true)

        assertNull(ServerWatched.mirror(listOf(movie(ServerItemRef(connection.id, "7").encode())), played = true))
    }

    private fun episode(connectionId: String, number: Int) = WatchedItem(
        id = ServerItemRef(connectionId, FakeServerProvider.SHOW_ID).encode(),
        type = "series",
        name = "",
        season = 1,
        episode = number,
        videoId = ServerItemRef(connectionId, "ep$number").encode(),
        markedAtEpochMs = 0L,
    )

    @Test
    fun writesMoviesAndEpisodesButNotWholeSeries() {
        val connectionId = "cfake"
        val series = WatchedItem(
            id = ServerItemRef(connectionId, FakeServerProvider.SHOW_ID).encode(),
            type = "series",
            name = "",
            markedAtEpochMs = 0L,
        )
        val targets = ServerWatched.targets(
            listOf(movie(ServerItemRef(connectionId, "7").encode()), series, episode(connectionId, 1), movie("tt0111161")),
        )
        assertEquals(listOf("7", "ep1"), targets.map { it.first.itemId })
    }

    @Test
    fun stopsWritingAfterAFailedBatch() = runTest {
        val provider = provider().apply { failingPlayed += "ep1" }
        val connection = installFakeServer(provider)
        val episodes = (1..8).map { episode(connection.id, it) }

        val failed = ServerWatched.write(ServerWatched.targets(episodes), played = true)

        assertEquals(listOf("ep1", "ep7", "ep8"), failed.map { ServerItemRef.parse(it.videoId)!!.itemId })
        assertEquals((1..6).map { "ep$it" }.toSet(), provider.playedChanges.map { it.first }.toSet())
    }
}
