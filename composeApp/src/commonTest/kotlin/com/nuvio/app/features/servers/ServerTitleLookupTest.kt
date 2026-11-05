package com.nuvio.app.features.servers

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerTitleLookupTest {
    private val ids = TrackingExternalIds(imdb = "tt0078788", tmdb = 28)
    private val movie = MatchRequest(ServerMediaKind.MOVIE, "tt0078788", ids)
    private val show = MetaDetails("tt0078788", "series", "Below", releaseInfo = "2025")

    @AfterTest
    fun tearDown() {
        removeFakeServer()
        removeFakeServer("cother")
        ServerMatcher.shared.clear()
    }

    private fun TestScope.matcher(
        cached: () -> MetaDetails? = { null },
        fetch: suspend (String, String) -> MetaDetails? = { _, _ -> null },
    ) = ServerMatcher(
        cachedMeta = { _, _ -> cached() },
        fetchMeta = fetch,
        originalTitle = { _, _ -> null },
        scope = backgroundScope,
        nowMs = { currentTime },
    )

    private fun nameProvider() = FakeServerProvider().apply {
        indexedIds[FakeServerProvider.SHOW_ID] = ids
        episodes[1 to 3] = ServerEpisode("ep3", null)
        episodes[1 to 4] = ServerEpisode("ep4", null)
        titleLookup = { _, query ->
            query.name?.let { listOf(ServerIndexEntry(FakeServerProvider.SHOW_ID, ids, 2025)) }
        }
    }

    @Test
    fun findsTitlesWithoutIndexingAndCachesHitsAndMisses() = runTest {
        val provider = FakeServerProvider().apply {
            titleLookup = { _, query -> if (query.ids == ids) listOf(ServerIndexEntry("42", ids)) else emptyList() }
        }
        val connection = installFakeServer(provider)
        val matcher = matcher(fetch = { _, _ -> error("ID lookup must not fetch metadata") })
        val missing = movie.copy(ids = TrackingExternalIds(imdb = "tt404", tmdb = 404))
        repeat(2) {
            assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, movie, false))
            assertTrue(matcher.match(connection, missing, false).isEmpty())
        }
        assertEquals(2, provider.titleQueries.size)
        assertEquals(0, provider.indexRequests)
        matcher.match(connection, movie, true)
        assertEquals(3, provider.titleQueries.size)
        delay(600_001)
        matcher.match(connection, movie, false)
        assertEquals(4, provider.titleQueries.size)
    }

    @Test
    fun remembersTheShowAfterCachedDetailsDisappear() = runTest {
        var cached: MetaDetails? = show
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val matcher = matcher(cached = { cached }, fetch = { _, _ -> error("Name should be remembered") })
        val third = matcher.request("series", "tt0078788:1:3", null, null)!!.copy(ids = ids)
        assertEquals(listOf(ServerItemRef(connection.id, "ep3")), matcher.match(connection, third, false))
        cached = null
        val fourth = matcher.request("series", "tt0078788:1:4", null, null)!!.copy(ids = ids)
        assertEquals("Below", fourth.name)
        assertEquals(2025, fourth.year)
        assertEquals(listOf(ServerItemRef(connection.id, "ep4")), matcher.match(connection, fourth, false))
        assertEquals(1, provider.titleQueries.size)
        delay(600_001)
        matcher.match(connection, fourth, false)
        assertEquals(2, provider.titleQueries.size)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun fetchesMissingDetailsAndReusesTheNameForLaterEpisodes() = runTest {
        var fetches = 0
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val matcher = matcher(fetch = { type, id ->
            assertEquals("series", type)
            assertEquals(show.id, id)
            fetches++
            show
        })
        val third = matcher.request("series", "tt0078788:1:3", null, null)!!.copy(ids = ids)
        assertNull(third.name)
        assertEquals(listOf(ServerItemRef(connection.id, "ep3")), matcher.match(connection, third, false))
        val fourth = matcher.request("series", "tt0078788:1:4", null, null)!!.copy(ids = ids)
        assertEquals("Below", fourth.name)
        assertEquals(listOf(ServerItemRef(connection.id, "ep4")), matcher.match(connection, fourth, false))
        assertEquals(1, fetches)
        assertEquals(2, provider.titleQueries.size)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun slowMetadataFallsBackAfterThreeSeconds() = runTest {
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val matcher = matcher(fetch = { _, _ -> delay(20_000); show })
        val request = MatchRequest(ServerMediaKind.SERIES, show.id, ids, 1, 4)
        assertEquals(listOf(ServerItemRef(connection.id, "ep4")), matcher.match(connection, request, false))
        assertEquals(3_000, currentTime)
        assertEquals(1, provider.indexRequests)
    }

    @Test
    fun missingOrFailedMetadataUsesTheIndex() = runTest {
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val request = MatchRequest(ServerMediaKind.SERIES, show.id, ids, 1, 4)
        for (fetch in listOf<suspend (String, String) -> MetaDetails?>(
            { _, _ -> null },
            { _, _ -> throw IllegalStateException("unavailable") },
            { _, _ -> show.copy(name = " ") },
        )) {
            assertEquals(listOf(ServerItemRef(connection.id, "ep4")), matcher(fetch = fetch).match(connection, request, false))
        }
        assertEquals(3, provider.indexRequests)
    }

    @Test
    fun cancellationDoesNotStartAnIndex() = runTest {
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val matcher = matcher(fetch = { _, _ -> throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { matcher.match(connection, movie, false) }
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun slowLookupTimesOutWithoutBlockingAnotherConnection() = runTest {
        val provider = FakeServerProvider().apply {
            titleLookup = { library, _ ->
                if (library.id == "slow") delay(20_000)
                listOf(ServerIndexEntry("42", ids))
            }
        }
        val slow = installFakeServer(provider, libraries = listOf(FakeServerProvider.MOVIE_LIBRARY.copy(id = "slow")))
        val quick = installFakeServer(provider, connectionId = "cother")
        val matcher = matcher()
        val waiting = async { runCatching { matcher.match(slow, movie, false) } }
        assertEquals(listOf(ServerItemRef(quick.id, "42")), matcher.match(quick, movie, false))
        assertEquals(0, currentTime)
        assertEquals(ServerFailure.INCOMPLETE, (waiting.await().exceptionOrNull() as ServerException).failure)
        assertEquals(6_000, currentTime)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun concurrentRequestsShareOneLookup() = runTest {
        val provider = FakeServerProvider().apply {
            titleLookup = { _, _ -> delay(100); listOf(ServerIndexEntry("42", ids)) }
        }
        val connection = installFakeServer(provider)
        val matcher = matcher()
        val first = async { matcher.match(connection, movie, false) }
        val second = async { matcher.match(connection, movie, false) }
        assertEquals(first.await(), second.await())
        assertEquals(1, provider.titleQueries.size)
    }

    @Test
    fun rejectsWrongIdsAndUsesTheYearOnlyWithoutAnImdbMatch() {
        val entries = listOf(
            ServerIndexEntry("wrong", TrackingExternalIds(imdb = "tt999", tmdb = 28), 1979),
            ServerIndexEntry("empty", TrackingExternalIds(), 1979),
            ServerIndexEntry("right", TrackingExternalIds(tmdb = 28), 1979),
            ServerIndexEntry("remake", TrackingExternalIds(tmdb = 28), 1985),
            ServerIndexEntry("imdb", ids, 2001),
            ServerIndexEntry("unknown", TrackingExternalIds(tmdb = 28)),
            ServerIndexEntry("near", TrackingExternalIds(tmdb = 28), 1980),
        )
        val query = ServerTitleQuery(ids, "Apocalypse Now", 1979)
        assertEquals(listOf("imdb", "near", "right", "unknown"), verifiedTitleMatches(entries, query).sorted())
    }

    @Test
    fun verifiesSearchResultsBeforeMatching() = runTest {
        val provider = FakeServerProvider().apply {
            titleLookup = { _, _ -> listOf(ServerIndexEntry("wrong", TrackingExternalIds(imdb = "tt999"))) }
        }
        val connection = installFakeServer(provider)
        assertTrue(matcher().match(connection, movie.copy(name = "Apocalypse Now"), false).isEmpty())
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun passesTheCachedOriginalTitleToTheProvider() = runTest {
        val provider = FakeServerProvider().apply { titleLookup = { _, _ -> emptyList() } }
        val connection = installFakeServer(provider)
        val matcher = ServerMatcher(
            originalTitle = { id, kind ->
                assertEquals(28, id)
                assertEquals(ServerMediaKind.MOVIE, kind)
                "Original"
            },
            scope = backgroundScope,
        )
        matcher.match(connection, movie.copy(name = "Localized"), false)
        assertEquals("Original", provider.titleQueries.single().originalName)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun clearDiscardsRememberedNamesAndLookups() = runTest {
        var cached: MetaDetails? = show
        val provider = nameProvider()
        val connection = installFakeServer(provider)
        val matcher = matcher(cached = { cached })
        val request = matcher.request("series", "tt0078788:1:4", null, null)!!.copy(ids = ids)
        matcher.match(connection, request, false)
        cached = null
        matcher.clear()
        assertNull(matcher.request("series", "tt0078788:1:4", null, null)!!.name)
        matcher.match(connection, request, false)
        assertEquals(2, provider.titleQueries.size)
    }

    @Test
    fun libraryChangesInvalidateLookupsWithoutBuildingAnIndex() = runTest {
        val provider = FakeServerProvider().apply { titleLookup = { _, _ -> emptyList() } }
        val connection = installFakeServer(provider)
        val matcher = matcher()
        assertTrue(matcher.supports(connection, ServerMediaKind.MOVIE))
        assertEquals(0, provider.titleQueries.size)
        matcher.match(connection, movie, false)
        ServerRepository.setLibrarySelected(connection.id, provider.movies.id, false)
        ServerRepository.setLibrarySelected(connection.id, provider.movies.id, true)
        matcher.match(ServerRepository.connection(connection.id)!!, movie, false)
        assertEquals(2, provider.titleQueries.size)
        assertEquals(0, provider.indexRequests)
    }
}
