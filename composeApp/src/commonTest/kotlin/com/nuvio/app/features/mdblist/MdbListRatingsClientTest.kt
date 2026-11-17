package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdbListRatingsClientTest {
    private val harness = MdbListTestHarness()
    private val keyRequests = mutableListOf<Pair<String, String?>>()
    private val client = MdbListRatingsClient(
        harness.api,
        harness.store,
        getText = { url -> keyRequests += url to null; METADATA },
        postJson = { url, body -> keyRequests += url to body; RATINGS },
    )

    @Test
    fun connectedAccountFetchesRatingsAndMetadataWithoutAnApiKey() = runTest {
        harness.connected()
        val credential = requireNotNull(settings().credential)
        harness.reply(body = RATINGS)
        harness.reply(body = METADATA)

        assertEquals(RATINGS, client.getMediaBatch("movie", IDS, credential))
        val ratings = parseMdbListRatings(client.getMedia("movie", "tt1234567", credential))

        assertEquals(92.0, ratings.single().value)
        assertTrue(ratings.single().isCertified)
        assertEquals(listOf("/imdb/movie/", "/imdb/movie/tt1234567/"), harness.engine.requests.map { it.path })
        assertTrue(harness.engine.requests.all { it.accessToken == "access-one" && "apikey" !in it.query })
        assertEquals(mapOf("append_to_response" to "keyword"), harness.engine.requests.last().query)
        assertEquals(BODY, harness.engine.requests.first().body)
        assertTrue(keyRequests.isEmpty())
    }

    @Test
    fun explicitApiKeyOverridesTheConnectedAccountForAllRatingsRequests() = runTest {
        harness.connected()
        val credential = requireNotNull(settings(" separate+key ").credential)

        assertEquals(RATINGS, client.getMediaBatch("show", IDS, credential))
        assertEquals(METADATA, client.getMedia("show", "tt1234567", credential))

        assertEquals(listOf(
            "https://api.mdblist.com/imdb/show/?apikey=separate%2Bkey" to BODY,
            "https://api.mdblist.com/imdb/show/tt1234567/?apikey=separate%2Bkey&append_to_response=keyword" to null,
        ), keyRequests)
        assertTrue(harness.engine.requests.isEmpty())
        assertTrue(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun clearingTheKeyRestoresTheAccountAndKeepsRatingsEnabled() = runTest {
        harness.connected()
        val configured = settings("override")
        client.getMediaBatch("movie", IDS, requireNotNull(configured.credential))

        val cleared = configured.copy(apiKey = " ")
        harness.reply(body = RATINGS)
        client.getMediaBatch("movie", IDS, requireNotNull(cleared.credential))

        assertTrue(cleared.enabled)
        assertTrue(cleared.isActive)
        assertEquals(1, keyRequests.size)
        assertEquals("access-one", harness.engine.requests.single().accessToken)
    }

    @Test
    fun rejectedAccountTokenRefreshesAndReplaysTheRatingsRequest() = runTest {
        harness.connected()
        harness.reply(401)
        harness.reply(body = MdbListTestHarness.TOKEN_RESPONSE)
        harness.reply(body = RATINGS)

        assertEquals(RATINGS, client.getMediaBatch("movie", IDS, requireNotNull(settings().credential)))

        assertEquals(listOf("/imdb/movie/", "/oauth/token/", "/imdb/movie/"), harness.engine.requests.map { it.path })
        assertEquals("access-two", harness.engine.requests.last().accessToken)
        assertEquals(BODY, harness.engine.requests.last().body)
    }

    @Test
    fun failingOverrideDoesNotFallBackToOrDisconnectTheAccount() = runTest {
        harness.connected()
        val failingClient = MdbListRatingsClient(harness.api, harness.store,
            postJson = { _, _ -> throw IOException("Rejected key") })

        expectMdbListFailure<IOException> {
            failingClient.getMediaBatch("movie", IDS, requireNotNull(settings("override").credential))
        }

        assertTrue(harness.engine.requests.isEmpty())
        assertTrue(harness.store.state.value.isAuthenticated)
    }

    @Test
    fun accountChangesDiscardStaleRequestsAndUseSeparateCacheIdentities() = runTest {
        harness.connected()
        val original = requireNotNull(settings().credential)
        harness.reply(body = RATINGS)
        harness.engine.intercept = { harness.store.selectProfile(2) }

        expectMdbListFailure<CancellationException> { client.getMediaBatch("movie", IDS, original) }
        assertNull(settings().credential)
        harness.connected("profile-two")
        assertNotEquals(original, settings().credential)
        expectMdbListFailure<CancellationException> { client.checkCredential(original) }
    }

    @Test
    fun ratingsEligibilityIncludesOAuthAndRespectsDisabledProviders() {
        val meta = MetaDetails(id = "tt1234567", type = "movie", name = "Movie")
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, settings()))
        harness.connected()
        val connected = settings()
        assertTrue(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, connected))
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, connected.copy(enabled = false)))
        assertFalse(MdbListMetadataService.shouldFetchForMeta(meta, meta.id, connected.copy(
            useImdb = false, useTmdb = false, useTomatoes = false, useAudience = false,
            useMetacritic = false, useTrakt = false, useLetterboxd = false, useMal = false,
        )))
        assertFalse(connected.withAccount(harness.store.state.value, 2).hasCredentials)
        harness.store.clearAuth()
        assertFalse(settings().isActive)
        assertTrue(settings("override").isActive)
    }

    private fun settings(apiKey: String = "") = MdbListSettings(enabled = true, apiKey = apiKey)
        .withAccount(harness.store.state.value, harness.store.scope().profileId)

    private companion object {
        val IDS = listOf("tt1234567", "tt7654321")
        const val BODY = """{"ids":["tt1234567","tt7654321"],"append_to_response":["keyword"]}"""
        const val RATINGS = """[{"imdb_id":"tt1234567","ratings":[{"source":"imdb","value":8.1}]}]"""
        const val METADATA = """{"ratings":[{"source":"tomatoes","value":92}],"keywords":["certified-fresh"]}"""
    }
}
