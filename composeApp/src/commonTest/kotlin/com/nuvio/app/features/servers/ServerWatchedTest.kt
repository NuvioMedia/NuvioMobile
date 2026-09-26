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
        val cleared = assertNotNull(ServerWatched.mirror(listOf(movie("tt0111161")), played = false))
        withContext(Dispatchers.Default) { withTimeout(15_000L) { cleared.join() } }

        assertEquals(listOf("7" to true, "501" to true, "7" to false), provider.playedChanges)
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
}
