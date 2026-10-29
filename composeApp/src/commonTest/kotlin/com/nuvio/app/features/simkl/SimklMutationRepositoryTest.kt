package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklMutationRepositoryTest {
    private val json = Json

    @Test
    fun `list mutation batches types and puts destination on every item`() {
        val body = buildSimklListMutationBody(
            items = listOf(
                movie().copy(posterUrl = "https://catalog.example/poster.webp"),
                anime(),
            ),
            destination = TrackingListStatus.PLAN_TO_WATCH,
        ).asObject()

        assertNull(body["to"])
        val movie = body.getValue("movies").jsonArray.single().jsonObject
        val anime = body.getValue("shows").jsonArray.single().jsonObject
        assertEquals("plantowatch", movie.getValue("to").jsonPrimitive.content)
        assertEquals("plantowatch", anime.getValue("to").jsonPrimitive.content)
        assertEquals(53536L, movie.getValue("ids").jsonObject.getValue("simkl").jsonPrimitive.content.toLong())
        assertEquals("tt0181852", movie.getValue("ids").jsonObject.getValue("imdb").jsonPrimitive.content)
        assertNull(movie.getValue("ids").jsonObject["trakt"])
        assertNull(movie["poster"])
        assertNull(movie["posterUrl"])
        assertEquals(16498L, anime.getValue("ids").jsonObject.getValue("mal").jsonPrimitive.content.toLong())
    }

    @Test
    fun `history mutation groups show episodes and keeps timestamps at event granularity`() {
        val show = show(episode = TrackingEpisode(season = 1, number = 1))
        val secondEpisode = show.copy(episode = TrackingEpisode(season = 1, number = 2))
        val body = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(show, watchedAtEpochMs = 1_700_000_000_000L),
                TrackingHistoryItem(secondEpisode, watchedAtEpochMs = 1_700_003_600_000L),
                TrackingHistoryItem(movie(), watchedAtEpochMs = 1_700_000_000_000L),
            ),
        ).asObject()

        val showItem = body.getValue("shows").jsonArray.single().jsonObject
        val episodes = showItem
            .getValue("seasons").jsonArray.single().jsonObject
            .getValue("episodes").jsonArray
        assertEquals(2, episodes.size)
        assertNull(episodes[0].jsonObject["season"])
        assertEquals("2023-11-14T22:13:20Z", episodes[0].jsonObject.getValue("watched_at").jsonPrimitive.content)
        assertEquals("2023-11-14T23:13:20Z", episodes[1].jsonObject.getValue("watched_at").jsonPrimitive.content)

        val movieItem = body.getValue("movies").jsonArray.single().jsonObject
        assertEquals("2023-11-14T22:13:20Z", movieItem.getValue("watched_at").jsonPrimitive.content)
    }

    @Test
    fun `anime history uses tvdb mapping flag only for seasonal coordinates`() {
        val seasonalBody = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(
                    anime(TrackingEpisode(season = 2, number = 4)),
                    watchedAtEpochMs = 1_700_000_000_000L,
                ),
            ),
        ).asObject()
        val seasonalAnime = seasonalBody.getValue("shows").jsonArray.single().jsonObject
        assertTrue(seasonalAnime.getValue("use_tvdb_anime_seasons").jsonPrimitive.content.toBoolean())

        val flatBody = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(
                    anime(TrackingEpisode(number = 4)),
                    watchedAtEpochMs = 1_700_000_000_000L,
                ),
            ),
        ).asObject()
        assertNull(flatBody.getValue("shows").jsonArray.single().jsonObject["use_tvdb_anime_seasons"])

        val showBody = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(
                    show(TrackingEpisode(season = 2, number = 4)),
                    watchedAtEpochMs = 1_700_000_000_000L,
                ),
            ),
        ).asObject()
        assertNull(showBody.getValue("shows").jsonArray.single().jsonObject["use_tvdb_anime_seasons"])
    }

    @Test
    fun `anime removal uses shows array and contains no response-only or watch fields`() {
        val body = buildSimklHistoryRemovalBody(
            listOf(anime(TrackingEpisode(number = 4))),
        ).asObject()

        assertNull(body["anime"])
        val item = body.getValue("shows").jsonArray.single().jsonObject
        assertNull(item["watched_at"])
        assertNull(item["status"])
        assertEquals(4, item.getValue("episodes").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.content.toInt())

        val seasonalBody = buildSimklHistoryRemovalBody(
            listOf(anime(TrackingEpisode(season = 2, number = 4))),
        ).asObject()
        val seasonalItem = seasonalBody.getValue("shows").jsonArray.single().jsonObject
        assertTrue(seasonalItem.getValue("use_tvdb_anime_seasons").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `scrobble payload clamps progress and uses type-specific wrappers`() {
        val movieBody = buildSimklScrobbleBody(
            TrackingScrobbleEvent(movie(), progressPercent = 105.129),
        ).asObject()
        assertEquals(100.0, movieBody.getValue("progress").jsonPrimitive.content.toDouble())
        assertTrue("movie" in movieBody)
        assertFalse("show" in movieBody)

        val tvStyleAnimeBody = buildSimklScrobbleBody(
            TrackingScrobbleEvent(
                anime(TrackingEpisode(season = 2, number = 4)),
                progressPercent = 42.236,
            ),
        ).asObject()
        assertEquals(42.24, tvStyleAnimeBody.getValue("progress").jsonPrimitive.content.toDouble())
        assertTrue("show" in tvStyleAnimeBody)
        assertFalse("anime" in tvStyleAnimeBody)
        assertEquals(2, tvStyleAnimeBody.getValue("episode").jsonObject.getValue("season").jsonPrimitive.content.toInt())
        assertEquals(4, tvStyleAnimeBody.getValue("episode").jsonObject.getValue("number").jsonPrimitive.content.toInt())

        val nativeAnimeBody = buildSimklScrobbleBody(
            TrackingScrobbleEvent(
                anime(TrackingEpisode(number = 4)),
                progressPercent = 42.236,
            ),
        ).asObject()
        assertTrue("anime" in nativeAnimeBody)
        assertFalse("show" in nativeAnimeBody)
        assertNull(nativeAnimeBody.getValue("episode").jsonObject["season"])
    }

    @Test
    fun `service reports partial not found and returns duplicate stop as scrobbled`() = runBlocking {
        val engine = RecordingEngine(
            response(
                status = 201,
                body = """{"added":{"movies":[{"to":"completed"}]},"not_found":{"movies":[{"title":"Missing"}],"shows":[]}}""",
            ),
            response(
                status = 409,
                body = """{"watched_at":"2026-05-14T23:46:29Z","expires_at":"2026-05-15T00:46:29Z"}""",
            ),
        )
        var now = 0L
        var committed = 0
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { now },
                sleep = { duration -> now += duration },
                retryJitterMs = { 0L },
            ),
            onMutationCommitted = { committed += 1 },
        )

        val result = service.moveToList(
            items = listOf(movie(), movie().copy(title = "Missing", ids = TrackingExternalIds())),
            destination = TrackingListStatus.PLAN_TO_WATCH,
        )
        val scrobbleResult = service.scrobble(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(movie(), progressPercent = 90.0),
        )

        assertEquals(2, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
        assertEquals(listOf(TrackingListStatus.COMPLETED), result.resolvedListStatuses)
        assertFalse(result.isComplete)
        assertEquals(listOf("/sync/add-to-list", "/scrobble/stop"), engine.paths)
        assertEquals(SimklScrobbleOutcome.SCROBBLE, scrobbleResult.outcome)
        assertEquals("2026-05-14T23:46:29Z", scrobbleResult.watchedAt)
        assertEquals(1, committed)
    }

    @Test
    fun `history response exposes resolved status catalog and anime subtype`() = runBlocking {
        val engine = RecordingEngine(
            response(
                status = 201,
                body = """
                    {
                      "added": {
                        "movies": 0,
                        "shows": 1,
                        "episodes": 1,
                        "statuses": [
                          {
                            "request": {"title":"Attack on Titan","type":"show"},
                            "response": {
                              "status":"watching",
                              "simkl_type":"anime",
                              "anime_type":"tv"
                            }
                          }
                        ]
                      },
                      "not_found": {"movies":[],"shows":[],"episodes":[]}
                    }
                """.trimIndent(),
            ),
        )
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
        )

        val result = service.addToHistory(
            listOf(
                TrackingHistoryItem(
                    media = anime(TrackingEpisode(number = 1)),
                    watchedAtEpochMs = 1_700_000_000_000L,
                ),
            ),
        )

        assertEquals(listOf(TrackingListStatus.WATCHING), result.resolvedListStatuses)
        assertEquals(TrackingMediaKind.ANIME, result.resolutions.single().mediaKind)
        assertEquals("tv", result.resolutions.single().providerSubtype)
    }

    @Test
    fun `service leaves failed scrobble retry to the next player event`() = runBlocking {
        val engine = RecordingEngine(response(status = 503), response(status = 200))
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
        )

        assertFailsWith<SimklApiException> {
            service.scrobble(
                action = TrackingScrobbleAction.PAUSE,
                event = TrackingScrobbleEvent(movie(), progressPercent = 45.0),
            )
        }

        assertEquals(listOf("/scrobble/pause"), engine.paths)
    }

    @Test
    fun `successful pause returns local reconciliation data without invalidating sync`() = runBlocking {
        val engine = RecordingEngine(
            response(
                status = 201,
                body = """{"id":42,"action":"pause","progress":45,"movie":{"title":"Terminator 3: Rise of the Machines","year":2003,"ids":{"simkl":53536}}}""",
            ),
        )
        var committed = 0
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
            onMutationCommitted = { committed += 1 },
        )

        val result = service.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(movie(), progressPercent = 45.0),
        )

        assertEquals(listOf("/scrobble/pause"), engine.paths)
        assertEquals(SimklScrobbleOutcome.PAUSE, result.outcome)
        assertEquals(42L, result.playbackId)
        assertEquals(45.0, result.progress)
        assertEquals(0, committed)
    }

    @Test
    fun `low progress stop returns a paused playback without invalidating sync`() = runBlocking {
        val engine = RecordingEngine(
            response(
                status = 201,
                body = """{"id":42,"action":"pause","progress":30,"movie":{"title":"Terminator 3: Rise of the Machines","year":2003,"ids":{"simkl":53536}}}""",
            ),
        )
        var committed = 0
        val service = SimklMutationService(
            client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            ),
            onMutationCommitted = { committed += 1 },
        )

        val result = service.scrobble(
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(movie(), progressPercent = 30.0),
        )

        assertEquals(listOf("/scrobble/stop"), engine.paths)
        assertEquals(SimklScrobbleOutcome.PAUSE, result.outcome)
        assertEquals(42L, result.playbackId)
        assertEquals(30.0, result.progress)
        assertEquals(0, committed)
    }

    private fun String.asObject() = json.parseToJsonElement(this).jsonObject

    @Test
    fun `a mark that is a repeat viewing carries the flag and the run it joins`() {
        val repeat = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(
                    show(TrackingEpisode(season = 1, number = 1)),
                    watchedAtEpochMs = 1_700_000_000_000L,
                    isRewatch = true,
                    rewatchId = 4321L,
                ),
            ),
        ).asObject().getValue("shows").jsonArray.single().jsonObject

        assertTrue(repeat.getValue("is_rewatch").jsonPrimitive.content.toBoolean())
        assertEquals("4321", repeat.getValue("rewatch_id").jsonPrimitive.content)

        // A mark of something the account has never watched stays a plain first watch.
        val plain = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(show(TrackingEpisode(season = 1, number = 1)))),
        ).asObject().getValue("shows").jsonArray.single().jsonObject

        assertNull(plain["is_rewatch"])
        assertNull(plain["rewatch_id"])
    }

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Terminator 3: Rise of the Machines",
        year = 2003,
        ids = TrackingExternalIds(
            simkl = 53536,
            imdb = "tt0181852",
            tmdb = 296,
            trakt = 123,
        ),
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "The Walking Dead",
        year = 2010,
        ids = TrackingExternalIds(simkl = 2090, imdb = "tt1520211", tvdb = "153021"),
        episode = episode,
    )

    private fun anime(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(simkl = 39687, mal = 16498, anidb = 9541),
        episode = episode,
    )

    private class RecordingEngine(vararg responses: RawHttpResponse) : SimklHttpEngine {
        private val queued = responses.toMutableList()
        val paths = mutableListOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            paths += url.substringAfter("api.simkl.com").substringBefore('?')
            return queued.removeAt(0)
        }
    }

    private companion object {
        fun response(status: Int, body: String = "{}") = RawHttpResponse(
            status = status,
            statusText = "",
            url = "https://api.simkl.com/test",
            body = body,
            headers = emptyMap(),
        )
    }
}
