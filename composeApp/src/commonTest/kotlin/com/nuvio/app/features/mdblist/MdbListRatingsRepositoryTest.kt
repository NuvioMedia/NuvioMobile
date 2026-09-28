package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MdbListRatingsRepositoryTest {
    private val harness = MdbListTestHarness()
    private val requests = mutableListOf<Pair<String, String?>>()
    private val credential = MdbListRatingsCredential.ApiKey("test-key")
    private val providers = MdbListMetadataService.PROVIDER_PRIORITY_ORDER
    private var respond: suspend (String?) -> String = { body ->
        if (body == null) MEDIA else requestIds(body).reversed().joinToString(prefix = "[", postfix = "]") { id ->
            MEDIA.replace("tt1234567", id).replace("\"value\":4.2", "\"value\":8.4")
        }
    }
    private val client = MdbListRatingsClient(
        harness.api,
        harness.store,
        getText = { url -> requests += url to null; respond(null) },
        postJson = { url, body -> requests += url to body; respond(body) },
    )

    @Test
    fun allEnabledSourcesAndCertificationsUseOneRequest() = runTest {
        val repository = repository()

        val ratings = repository.getRatings("movie", "tt1234567", credential, providers)

        assertEquals(providers, ratings.map { it.source })
        assertEquals(listOf(8.1, 78.0, 92.0, 75.0, 81.0, 4.2, 89.0, 8.5), ratings.map { it.value })
        assertEquals(listOf("tomatoes", "audience"), ratings.filter { it.isCertified }.map { it.source })
        assertEquals(1, requests.size)
        assertEquals(null, requests.single().second)
    }

    @Test
    fun changingSourcesReadsTheCachedResponseInTheRequestedOrder() = runTest {
        val repository = repository()

        assertEquals(listOf(MetaExternalRating("imdb", 8.1)),
            repository.getRatings("movie", "tt1234567", credential, listOf("imdb")))
        val ratings = repository.getRatings("movie", "tt1234567", credential, listOf("mal", "audience", "tmdb"))

        assertEquals(listOf("mal", "audience", "tmdb"), ratings.map { it.source })
        assertEquals(listOf(8.5, 89.0, 78.0), ratings.map { it.value })
        assertEquals(1, requests.size)
    }

    @Test
    fun concurrentRequestsForTheSameItemShareOneLookup() = runTest {
        val repository = repository()
        val imdb = async { repository.getRatings("movie", "tt1234567", credential, listOf("imdb")) }
        val tmdb = async { repository.getRatings("movie", "tt1234567", credential, listOf("tmdb")) }

        assertEquals("imdb", imdb.await().single().source)
        assertEquals("tmdb", tmdb.await().single().source)
        assertEquals(1, requests.size)
    }

    @Test
    fun nearbyItemsAreBatchedAndMatchedByIdInsteadOfResponseOrder() = runTest {
        respond = {
            """[
                {"ids":{"imdb":"tt7654321"},"ratings":[{"source":"imdb","value":6.5}]},
                {"imdb_id":"tt1234567","ratings":[{"source":"imdb","value":8.1}]}
            ]"""
        }
        val repository = repository()
        val first = async { repository.getRatings("movie", "tt1234567", credential, listOf("imdb")) }
        runCurrent()
        advanceTimeBy(25)
        val second = async { repository.getRatings("movie", "tt7654321", credential, listOf("imdb")) }

        assertEquals(8.1, first.await().single().value)
        assertEquals(6.5, second.await().single().value)
        assertEquals(listOf("tt1234567", "tt7654321"), requestIds(requireNotNull(requests.single().second)))
        assertEquals(6.5, repository.getRatings("movie", "tt7654321", credential, listOf("imdb")).single().value)
        assertEquals(1, requests.size)
    }

    @Test
    fun batchesRespectTheTwoHundredItemMediaLimit() = runTest {
        val repository = repository()
        val ratings = (1..405).map { index ->
            async { repository.getRatings("movie", "tt$index", credential, providers) }
        }.awaitAll()

        assertTrue(ratings.all { it.size == 8 })
        val batches = requests.map { requestIds(requireNotNull(it.second)) }
        assertEquals(listOf(200, 200, 5), batches.map { it.size })
        assertEquals((1..405).map { "tt$it" }, batches.flatten())
    }

    @Test
    fun differentMediaTypesAndCredentialsUseSeparateBatchesAndCaches() = runTest {
        val repository = repository()
        val otherKey = MdbListRatingsCredential.ApiKey("other-key")
        val groups = listOf("movie" to credential, "show" to credential, "movie" to otherKey)
        groups.flatMap { (type, key) ->
            listOf("tt1234567", "tt7654321").map { id ->
                async { repository.getRatings(type, id, key, providers) }
            }
        }.awaitAll()

        assertEquals(listOf(
            "https://api.mdblist.com/imdb/movie/?apikey=test-key",
            "https://api.mdblist.com/imdb/show/?apikey=test-key",
            "https://api.mdblist.com/imdb/movie/?apikey=other-key",
        ), requests.map { it.first })
        assertTrue(requests.all { requestIds(requireNotNull(it.second)).size == 2 })
    }

    @Test
    fun omittedItemsAndSourcesStayEmptyWithoutIndividualFallbackRequests() = runTest {
        respond = { """[{"imdb_id":"tt1234567","ratings":[{"source":"imdb","value":8.1}]}]""" }
        val repository = repository()
        val found = async { repository.getRatings("movie", "tt1234567", credential, providers) }
        val missing = async { repository.getRatings("movie", "tt7654321", credential, providers) }

        assertEquals(listOf("imdb"), found.await().map { it.source })
        assertTrue(missing.await().isEmpty())
        assertTrue(repository.getRatings("movie", "tt7654321", credential, providers).isEmpty())
        assertTrue(repository.getRatings("movie", "tt1234567", credential, listOf("tmdb")).isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun failedResponsesCanBeRetriedWithoutCachingAnEmptyResult() = runTest {
        val repository = repository()
        respond = { throw IOException("Unavailable") }
        assertTrue(repository.getRatings("movie", "tt1234567", credential, providers).isEmpty())
        respond = { "invalid json" }
        assertTrue(repository.getRatings("movie", "tt1234567", credential, providers).isEmpty())
        respond = { MEDIA }

        assertEquals(8, repository.getRatings("movie", "tt1234567", credential, providers).size)
        assertEquals(3, requests.size)
    }

    @Test
    fun cancellingOneCallerKeepsTheSharedRequestAvailable() = runTest {
        val repository = repository()
        val cancelled = async { repository.getRatings("movie", "tt1234567", credential, providers) }
        runCurrent()
        val waiting = async { repository.getRatings("movie", "tt1234567", credential, providers) }
        runCurrent()
        cancelled.cancel()

        assertEquals(8, waiting.await().size)
        assertTrue(cancelled.isCancelled)
        assertEquals(1, requests.size)
    }

    @Test
    fun clearingTheCacheDiscardsOldRequestsWithoutOverwritingNewResults() = runTest {
        val oldResponse = CompletableDeferred<String>()
        respond = { if (requests.size == 1) oldResponse.await() else MEDIA }
        val repository = repository()
        val old = async { repository.getRatings("movie", "tt1234567", credential, listOf("imdb")) }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(1, requests.size)

        repository.clearCache()
        val current = async { repository.getRatings("movie", "tt1234567", credential, listOf("imdb")) }
        assertEquals(8.1, current.await().single().value)
        oldResponse.complete(MEDIA.replace("8.1", "1.0"))
        runCurrent()

        assertTrue(old.isCancelled)
        assertEquals(8.1, repository.getRatings("movie", "tt1234567", credential, listOf("imdb")).single().value)
        assertEquals(2, requests.size)
    }

    @Test
    fun accountChangesRejectCachedRatingsFromThePreviousScope() = runTest {
        harness.connected()
        val repository = MdbListRatingsRepository(MdbListRatingsClient(harness.api, harness.store), backgroundScope)
        val original = MdbListRatingsCredential.Account(harness.store.scope())
        harness.reply(body = MEDIA)
        assertEquals(8, repository.getRatings("movie", "tt1234567", original, providers).size)

        harness.store.selectProfile(2)
        expectMdbListFailure<CancellationException> {
            repository.getRatings("movie", "tt1234567", original, providers)
        }
        harness.connected("profile-two")
        val current = MdbListRatingsCredential.Account(harness.store.scope())
        harness.reply(body = MEDIA.replace("8.1", "7.0"))

        assertEquals(7.0, repository.getRatings("movie", "tt1234567", current, listOf("imdb")).single().value)
        assertEquals(2, harness.engine.requests.size)
    }

    @Test
    fun cancellationFromTheClientPropagatesAndAllowsRetry() = runTest {
        val repository = repository()
        respond = { throw CancellationException("Account changed") }
        expectMdbListFailure<CancellationException> {
            repository.getRatings("movie", "tt1234567", credential, providers)
        }
        respond = { MEDIA }

        assertEquals(8, repository.getRatings("movie", "tt1234567", credential, providers).size)
        assertEquals(2, requests.size)
    }

    @Test
    fun noEnabledSourcesDoesNotMakeARequest() = runTest {
        assertTrue(repository().getRatings("movie", "tt1234567", credential, emptyList()).isEmpty())
        assertTrue(requests.isEmpty())
    }

    private fun TestScope.repository() = MdbListRatingsRepository(client, backgroundScope)

    private fun requestIds(body: String): List<String> = Json.parseToJsonElement(body)
        .jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        val MEDIA = """{
            "ids":{"imdb":"tt1234567","tmdb":123,"mal":null},
            "ratings":[
                {"source":"popcorn","value":89},
                {"source":"imdb","value":8.1,"score":81},
                {"source":"tmdb","value":78},
                {"source":"trakt","value":81},
                {"source":"tomatoes","value":92},
                {"source":"metacritic","value":75},
                {"source":"letterboxd","value":4.2,"score":84},
                {"source":"myanimelist","value":8.5}
            ],
            "keywords":["certified-fresh",{"name":"mdblist.certified-hot"}]
        }"""
    }
}
