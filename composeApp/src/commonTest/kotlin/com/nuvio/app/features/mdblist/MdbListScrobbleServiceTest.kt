package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListScrobbleServiceTest {
    @Test
    fun `fractional player progress survives start pause resume and exit for movies and episodes`() = runTest {
        val movie = event(0.0).media
        val episode = movie.copy(kind = TrackingMediaKind.SHOW, episode = TrackingEpisode(1, 1))
        for (media in listOf(movie, episode)) {
            val harness = MdbListSyncTestHarness(backgroundScope)
            val actions = listOf(
                Triple(TrackingScrobbleAction.START, 0.20450681447982788, 0.20),
                Triple(TrackingScrobbleAction.PAUSE, 64.321533203125, 64.32),
                Triple(TrackingScrobbleAction.START, 64.32167053222656, 64.32),
                Triple(TrackingScrobbleAction.STOP, 64.38255310058594, 64.38)
            )
            for ((action, progress, expected) in actions) {
                val start = action == TrackingScrobbleAction.START
                val responseAction = if (start) "start" else "pause"
                val timestamp = if (start) "started_at" else "paused_at"
                harness.http.reply(body = """{"action":"$responseAction","progress":$expected,"$timestamp":"$MDBLIST_TEST_TIME"}""")

                service(harness).scrobble(harness.repository.currentScope(), action, TrackingScrobbleEvent(media, progress))

                val request = harness.http.engine.requests.last()
                assertEquals("/scrobble/${action.wireValue}", request.path)
                assertEquals(expected, mdbListResponseElement(request.body).objectValue().text("progress")!!.toDouble(), 0.0)
                assertTrue(harness.repository.currentSnapshot()!!.watched.isEmpty())
                if (start) assertTrue(harness.repository.currentSnapshot()!!.playback.isEmpty())
                else assertEquals(expected.toFloat(), harness.repository.currentSnapshot()!!.playback.single().progress, 0f)
            }
            assertEquals(4, harness.http.engine.requests.size)
            assertTrue(harness.remote.calls.isEmpty())
        }
    }

    @Test
    fun `start replaces paused progress without marking history even above eighty percent`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(harness.snapshot().copy(watched = emptyList(), playback = listOf(mdbListTestPlayback())))
        harness.http.reply(201, """{"id":8,"action":"start","progress":90,"started_at":"2026-09-06T01:00:00Z"}""")

        service(harness).scrobble(harness.repository.currentScope(), TrackingScrobbleAction.START, event(90.0))

        assertTrue(harness.repository.currentSnapshot()?.watched?.isEmpty() == true)
        assertTrue(harness.repository.currentSnapshot()?.playback?.isEmpty() == true)
        assertEquals(listOf("/scrobble/start"), harness.http.engine.requests.map { it.path })
    }

    @Test
    fun `both pause and stop save below eighty and mark watched at eighty`() = runTest {
        for (action in listOf(TrackingScrobbleAction.PAUSE, TrackingScrobbleAction.STOP)) {
            for (progress in listOf(79.99, 80.0, 100.0)) {
                val harness = MdbListSyncTestHarness(backgroundScope)
                harness.http.reply(body = if (progress < 80.0) {
                    """{"id":7,"action":"pause","progress":$progress,"paused_at":"2026-09-06T01:00:00Z"}"""
                } else """{"action":"scrobble","progress":$progress,"watched_at":"2026-09-06T01:00:00Z"}""")

                service(harness).scrobble(harness.repository.currentScope(), action, event(progress))

                assertEquals(progress >= 80.0, harness.repository.currentSnapshot()!!.watched.isNotEmpty())
                assertEquals(progress < 80.0, harness.repository.currentSnapshot()!!.playback.isNotEmpty())
                assertEquals(1, harness.http.engine.requests.size)
                assertTrue(harness.remote.calls.isEmpty())
            }
        }
    }

    @Test
    fun `minimal stop response still saves resumable progress without inventing a playback ID`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.http.reply(body = """{"action":"pause","progress":35}""", headers = mapOf("Date" to "Sun, 06 Sep 2026 01:00:00 GMT"))

        service(harness).scrobble(harness.repository.currentScope(), TrackingScrobbleAction.STOP, event(35.0))

        val playback = harness.repository.currentSnapshot()!!.playback.single()
        assertEquals(null, playback.id)
        assertEquals("2026-09-06T01:00:00Z", playback.updatedAt)
        assertEquals(35f, playback.progress, 0f)
    }

    @Test
    fun `paused timestamp never falls back to an old session start time`() {
        val target = MdbListMutationTarget(MdbListItemType.MOVIE, mdbListTestMovie().media)
        val response = MdbListHttpResponse(200, """{"action":"pause","progress":30,"started_at":"2026-09-05T00:00:00Z"}""", mapOf("Date" to "Sun, 06 Sep 2026 01:00:00 GMT"))
        val receipt = decodeMdbListScrobbleReceipt(response, target, TrackingScrobbleAction.PAUSE, 0)
        assertEquals("2026-09-06T01:00:00Z", receipt.timestamp)
    }

    @Test
    fun `server resolved episode coordinates and IDs are applied to local history`() {
        val target = MdbListMutationTarget(MdbListItemType.EPISODE, mdbListTestMovie().media, 1, 12)
        val response = MdbListHttpResponse(200, """{
          "action":"scrobble","progress":95,"watched_at":"2026-09-06T01:00:00Z",
          "show":{"ids":{"tmdb":1,"imdb":"tt1"}},"episode":{"season":2,"number":3,"ids":{"tmdb":900},"title":"Resolved"}
        }""")
        val receipt = decodeMdbListScrobbleReceipt(response, target, TrackingScrobbleAction.STOP, 0)
        val snapshot = MdbListSyncSnapshot(42).applyScrobble(receipt)
        val watched = snapshot.watched.single()
        assertEquals(2, watched.season)
        assertEquals(3, watched.episode)
        assertEquals(900L, watched.episodeTmdbId)
        assertEquals("Resolved", watched.episodeTitle)
    }

    @Test
    fun `malformed or unrelated media response never fabricates progress`() = runTest {
        for (body in listOf("{}", """{"action":"pause","progress":101}""", """{"action":"pause","progress":40,"movie":{"ids":{"imdb":"tt999"}}}""")) {
            val harness = MdbListSyncTestHarness(backgroundScope)
            harness.http.reply(body = body)
            expectMdbListFailure<MdbListDecodingException> {
                service(harness).scrobble(harness.repository.currentScope(), TrackingScrobbleAction.PAUSE, event(40.0))
            }
            assertTrue(harness.repository.currentSnapshot()?.playback?.isEmpty() == true)
            assertTrue(harness.repository.currentSnapshot()?.watched?.isEmpty() == true)
            assertEquals(setOf(MdbListSyncBucket.WATCHED, MdbListSyncBucket.PLAYBACK), harness.repository.currentSnapshot()?.invalidatedBuckets)
            assertEquals(1, harness.http.engine.requests.size)
        }
    }

    @Test
    fun `clear removes paused progress and retains watched history for true false and absent sessions`() = runTest {
        for ((status, body) in listOf(200 to """{"action":"clear","deleted":true}""", 200 to """{"action":"clear","deleted":false}""", 404 to "{}")) {
            val harness = MdbListSyncTestHarness(backgroundScope)
            harness.seed(harness.snapshot().copy(playback = listOf(mdbListTestPlayback())))
            harness.http.reply(status, body)

            service(harness).clear(harness.repository.currentScope(), "tmdb:1", null, null)

            assertEquals(listOf(mdbListTestMovie()), harness.repository.currentSnapshot()?.watched)
            assertTrue(harness.repository.currentSnapshot()?.playback?.isEmpty() == true)
            assertEquals(listOf("/scrobble/clear"), harness.http.engine.requests.map { it.path })
        }
    }

    @Test
    fun `clearing one episode leaves other episodes untouched`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val first = mdbListTestPlayback().copy(type = MdbListItemType.EPISODE, season = 1, episode = 1)
        val second = first.copy(id = 8, episode = 2)
        harness.seed(harness.snapshot().copy(playback = listOf(first, second)))
        harness.http.reply(body = """{"action":"clear","deleted":true}""")
        service(harness).clear(harness.repository.currentScope(), "tt1", 1, 1)
        assertEquals(listOf(second), harness.repository.currentSnapshot()?.playback)
        assertEquals(1, harness.http.engine.requests.size)
    }

    @Test
    fun `cancellation after dispatch records pending reconciliation without retrying the write`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val dispatched = CompletableDeferred<Unit>()
        harness.http.engine.intercept = { dispatched.complete(Unit); awaitCancellation() }
        val job = launch { service(harness).scrobble(harness.repository.currentScope(), TrackingScrobbleAction.STOP, event(95.0)) }
        runCurrent()
        assertTrue(dispatched.isCompleted)
        job.cancel()
        job.join()
        assertEquals(1, harness.http.engine.requests.size)
        assertTrue(harness.repository.currentSnapshot()?.watched?.isEmpty() == true)
        assertEquals(setOf(MdbListSyncBucket.WATCHED, MdbListSyncBucket.PLAYBACK), harness.repository.currentSnapshot()?.invalidatedBuckets)
    }

    @Test
    fun `unsupported identifiers and nonfinite progress never dispatch requests`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val service = service(harness)
        service.scrobble(harness.repository.currentScope(), TrackingScrobbleAction.PAUSE, event(Double.NaN))
        service.scrobble(harness.repository.currentScope(), TrackingScrobbleAction.PAUSE, event(Double.POSITIVE_INFINITY))
        service.scrobble(harness.repository.currentScope(), TrackingScrobbleAction.PAUSE, event(40.0).copy(media = event(40.0).media.copy(ids = TrackingExternalIds(mal = 100))))
        assertTrue(harness.http.engine.requests.isEmpty())
        assertFalse(harness.repository.state.value.hasLoaded)
    }

    private fun service(harness: MdbListSyncTestHarness) = MdbListScrobbleService(harness.http.api, harness.repository)
    private fun event(progress: Double) = TrackingScrobbleEvent(
        TrackingMediaReference(TrackingMediaKind.MOVIE, "Movie", ids = TrackingExternalIds(imdb = "tt1")), progress
    )
}
