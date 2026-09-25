package com.nuvio.app.features.servers

import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerMatcherTest {
    @AfterTest
    fun tearDown() {
        removeFakeServer()
        ServerMatcher.clear()
    }

    @Test
    fun buildsRequestsFromCatalogIds() {
        val movie = ServerMatcher.request("movie", "tt0111161", null, null)!!
        assertEquals(ServerMediaKind.MOVIE, movie.kind)
        assertEquals("tt0111161", movie.ids.imdb)

        val episode = ServerMatcher.request("series", "tmdb:1399:2:5", null, null)!!
        assertEquals(1399L, episode.ids.tmdb)
        assertEquals("tmdb:1399", episode.parentId)
        assertEquals(2, episode.season)
        assertEquals(5, episode.episode)
    }

    @Test
    fun skipsRequestsWithoutExactIds() {
        assertNull(ServerMatcher.request("movie", "kitsu:123", null, null))
        assertNull(ServerMatcher.request("movie", ServerItemRef("c1", "x").encode(), null, null))
        assertNull(ServerMatcher.request("channel", "tt0111161", null, null))
    }

    @Test
    fun rejectsConflictingIdentifiersButKeepsDuplicateVersions() {
        val index = LibraryIndex(
            listOf(
                ServerIndexEntry("a", TrackingExternalIds(imdb = "tt1", tmdb = 10)),
                ServerIndexEntry("b", TrackingExternalIds(imdb = "tt1", tmdb = 99)),
                ServerIndexEntry("c", TrackingExternalIds(tmdb = 10)),
                ServerIndexEntry("d", TrackingExternalIds(imdb = "tt2")),
            ),
        )
        assertEquals(listOf("a", "c"), index.lookup(TrackingExternalIds(imdb = "tt1", tmdb = 10)).sorted())
        assertTrue(index.lookup(TrackingExternalIds(imdb = "tt404")).isEmpty())
    }

    @Test
    fun rejectsEpisodesWithDifferentAirDates() {
        assertTrue(datesCompatible("2011-04-17", "2011-04-18T01:00:00.0000000Z"))
        assertTrue(datesCompatible(null, "2011-04-18"))
        assertFalse(datesCompatible("2011-04-17", "2011-05-01"))
    }

    @Test
    fun matchesMoviesByExactId() = runTest { withContext(Dispatchers.Default) {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161")
            indexedIds["43"] = TrackingExternalIds(imdb = "tt0068646")
        }
        val connection = installFakeServer(provider)
        val request = ServerMatcher.request("movie", "tt0111161", null, null)!!

        assertTrue(ServerMatcher.supports(connection, ServerMediaKind.MOVIE))
        assertEquals(listOf(ServerItemRef(connection.id, "42")), ServerMatcher.match(connection, request, forceRefresh = false))
    } }

    @Test
    fun matchesEpisodesThroughTheMatchedSeries() = runTest { withContext(Dispatchers.Default) {
        val provider = FakeServerProvider().apply {
            indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947")
            episodes[1 to 1] = ServerEpisode("901", "2011-04-17")
        }
        val connection = installFakeServer(provider)

        val hit = ServerMatcher.request("series", "tt0944947:1:1", 1, 1)!!
        assertEquals(listOf(ServerItemRef(connection.id, "901")), ServerMatcher.match(connection, hit, forceRefresh = false))

        val miss = ServerMatcher.request("series", "tt0944947:1:2", 1, 2)!!
        assertTrue(ServerMatcher.match(connection, miss, forceRefresh = false).isEmpty())
    } }

    @Test
    fun sharedLookupAddsServerGroupsForCatalogItems() = runTest { withContext(Dispatchers.Default) {
        val provider = FakeServerProvider().apply { indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161") }
        val connection = installFakeServer(provider)

        val group = ServerStreams.sources("movie", "tt0111161", null, null).single().load()
        assertEquals(ServerItemRef(connection.id, "42"), group.streams.single().serverTarget?.item)

        val empty = ServerStreams.sources("movie", "tt0068646", null, null).single().load()
        assertTrue(empty.streams.isEmpty())
        assertNull(empty.error)
    } }
}
