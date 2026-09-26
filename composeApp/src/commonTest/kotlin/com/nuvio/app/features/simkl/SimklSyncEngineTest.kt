package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklSyncEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `documented all items fixture decodes flexible ids and nullable fields`() {
        val response = json.decodeFromString<SimklAllItemsResponse>(ALL_ITEMS_FIXTURE)

        val show = response.entriesFor(SimklMediaType.SHOWS).single()
        assertEquals("2090", show.media?.ids?.idValue("simkl"))
        assertEquals("153021", show.media?.ids?.idValue("tvdb"))
        assertEquals(SimklListStatus.WATCHING, show.status)
        assertEquals("2026-05-15T00:32:20Z", show.seasons.single().episodes.single().watchedAt)

        val movie = response.entriesFor(SimklMediaType.MOVIES).single()
        assertEquals("238", movie.media?.ids?.idValue("tmdb"))
        assertNull(movie.userRating)
        assertEquals(SimklMediaType.MOVIES, movie.mediaType)
    }

    @Test
    fun `initial sync pulls each type sequentially before playback and activities`() = runBlocking {
        val remote = ScriptedRemote(
            Step.AllItems(SimklMediaType.SHOWS, responseOf(entry(SimklMediaType.SHOWS, "1"))),
            Step.AllItems(SimklMediaType.MOVIES, responseOf(entry(SimklMediaType.MOVIES, "2"))),
            Step.AllItems(SimklMediaType.ANIME, responseOf(entry(SimklMediaType.ANIME, "3"))),
            Step.Playback(listOf(playback("2"))),
            Step.Activities(activities(all = "v1")),
        )

        val result = SimklSyncEngine(remote) { 500L }.synchronize(SimklSyncSnapshot())

        assertTrue(result.isInitialized)
        assertEquals("v1", result.watermark)
        assertEquals(listOf("1", "2", "3"), result.entries.mapNotNull { it.media?.ids?.idValue("simkl") })
        assertEquals(1, result.playback.size)
        assertEquals(500L, result.lastSyncedAtEpochMs)
        assertTrue(remote.isExhausted)
        assertEquals(
            listOf<SimklAllItemsRequest>(
                SimklAllItemsRequest.Bootstrap(SimklMediaType.SHOWS),
                SimklAllItemsRequest.Bootstrap(SimklMediaType.MOVIES),
                SimklAllItemsRequest.Bootstrap(SimklMediaType.ANIME),
            ),
            remote.allItemsRequests,
        )
    }

    @Test
    fun `unchanged activities gate avoids library and playback calls`() = runBlocking {
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1"),
            entries = listOf(entry(SimklMediaType.SHOWS, "1")),
            lastCheckedAtEpochMs = 10L,
        )
        val remote = ScriptedRemote(Step.Activities(activities(all = "v1")))

        val result = SimklSyncEngine(remote) { 20L }.synchronize(current)

        assertEquals(current.entries, result.entries)
        assertEquals(20L, result.lastCheckedAtEpochMs)
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `playback only activity refreshes playback without all items`() = runBlocking {
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1", playback = "p1", library = "l1"),
            entries = listOf(entry(SimklMediaType.SHOWS, "1")),
            playback = listOf(playback("1")),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2", playback = "p2", library = "l1")),
            Step.Playback(listOf(playback("2"))),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertEquals(current.entries, result.entries)
        assertEquals("2", result.playback.single().media?.ids?.idValue("simkl"))
        assertEquals("v2", result.watermark)
        assertTrue(remote.allItemsRequests.isEmpty())
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `settings only activity updates watermark without projection calls`() = runBlocking {
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1", library = "l1", settings = "s1"),
            entries = listOf(entry(SimklMediaType.SHOWS, "1")),
            playback = listOf(playback("1")),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2", library = "l1", settings = "s2")),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertEquals(current.entries, result.entries)
        assertEquals(current.playback, result.playback)
        assertEquals("s2", result.activities?.settings?.all)
        assertEquals("v2", result.watermark)
        assertTrue(remote.allItemsRequests.isEmpty())
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `removal only activity reconciles ids without delta`() = runBlocking {
        val retained = entry(SimklMediaType.SHOWS, "1")
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1", removed = "r1", library = "l1"),
            entries = listOf(
                retained,
                entry(SimklMediaType.MOVIES, "2", SimklListStatus.PLAN_TO_WATCH),
            ),
        )
        val authoritative = SimklAllItemsResponse(
            shows = listOf(retained),
            movies = emptyList(),
            anime = emptyList(),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2", removed = "r2", library = "l1")),
            Step.AllItems(null, authoritative),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertEquals(listOf("1"), result.entries.mapNotNull { it.media?.ids?.idValue("simkl") })
        assertEquals(
            listOf<SimklAllItemsRequest>(SimklAllItemsRequest.CurrentIds),
            remote.allItemsRequests,
        )
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `each library activity timestamp requests an all items delta`() = runBlocking {
        val previousDomain = SimklActivityDomain(
            all = "d1",
            ratedAt = "rated1",
            playback = "playback",
            plantowatch = "plantowatch1",
            watching = "watching1",
            completed = "completed1",
            hold = "hold1",
            dropped = "dropped1",
            removedFromList = "removed",
        )
        val changes = listOf(
            "rated_at" to previousDomain.copy(ratedAt = "rated2"),
            "plantowatch" to previousDomain.copy(plantowatch = "plantowatch2"),
            "watching" to previousDomain.copy(watching = "watching2"),
            "completed" to previousDomain.copy(completed = "completed2"),
            "hold" to previousDomain.copy(hold = "hold2"),
            "dropped" to previousDomain.copy(dropped = "dropped2"),
        )

        changes.forEach { (name, changedDomain) ->
            val previousActivities = SimklActivities(
                all = "v1",
                tvShows = previousDomain,
                movies = previousDomain,
                anime = previousDomain,
            )
            val changedActivities = previousActivities.copy(
                all = "v2",
                tvShows = changedDomain.copy(all = "d2"),
            )
            val current = SimklSyncSnapshot(
                isInitialized = true,
                watermark = "v1",
                activities = previousActivities,
            )
            val remote = ScriptedRemote(
                Step.Activities(changedActivities),
                Step.AllItems(null, SimklAllItemsResponse()),
            )

            SimklSyncEngine(remote) { 900L }.synchronize(current)

            assertEquals(
                listOf<SimklAllItemsRequest>(SimklAllItemsRequest.Changes("v1")),
                remote.allItemsRequests,
                name,
            )
            assertTrue(remote.isExhausted, name)
        }
    }

    @Test
    fun `watched delta discards retained playback without fetching playback`() = runBlocking {
        val retainedPlayback = episodePlayback("1", "2024-04-30T22:13:00Z")
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1", playback = "p1"),
            entries = listOf(entry(SimklMediaType.SHOWS, "1")),
            playback = listOf(retainedPlayback),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2", playback = "p1")),
            Step.AllItems(
                null,
                responseOf(watchedShowEntry("1", "2024-04-30T22:14:00Z")),
            ),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertTrue(result.playback.isEmpty())
        assertTrue(result.toSimklProgressEntries().isEmpty())
        assertTrue(
            result.toSimklWatchedProjection().items.any { watched ->
                watched.season == 1 && watched.episode == 5
            },
        )
        assertEquals(
            listOf<SimklAllItemsRequest>(SimklAllItemsRequest.Changes("v1")),
            remote.allItemsRequests,
        )
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `initial sync discards playback superseded by watched history`() = runBlocking {
        val remote = ScriptedRemote(
            Step.AllItems(
                SimklMediaType.SHOWS,
                responseOf(watchedShowEntry("1", "2024-04-30T22:14:00Z")),
            ),
            Step.AllItems(SimklMediaType.MOVIES, SimklAllItemsResponse(movies = emptyList())),
            Step.AllItems(SimklMediaType.ANIME, SimklAllItemsResponse(anime = emptyList())),
            Step.Playback(listOf(episodePlayback("1", "2024-04-30T22:13:00Z"))),
            Step.Activities(activities(all = "v1", playback = "p1")),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(SimklSyncSnapshot())

        assertTrue(result.playback.isEmpty())
        assertEquals(1, result.toSimklWatchedProjection().items.size)
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `delta merge reconciles removals and replaces changed playback atomically`() = runBlocking {
        val previousActivities = activities(all = "v1", removed = "r1", playback = "p1")
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = previousActivities,
            entries = listOf(
                entry(SimklMediaType.SHOWS, "1"),
                entry(SimklMediaType.MOVIES, "2", SimklListStatus.PLAN_TO_WATCH),
            ),
            playback = listOf(playback("1")),
        )
        val changedActivities = activities(all = "v2", removed = "r2", playback = "p2")
        val delta = SimklAllItemsResponse(
            shows = listOf(entry(SimklMediaType.SHOWS, "3")),
            movies = listOf(entry(SimklMediaType.MOVIES, "2", SimklListStatus.DROPPED)),
        )
        val authoritative = SimklAllItemsResponse(
            shows = listOf(entry(SimklMediaType.SHOWS, "3")),
            movies = listOf(entry(SimklMediaType.MOVIES, "2")),
            anime = emptyList(),
        )
        val remote = ScriptedRemote(
            Step.Activities(changedActivities),
            Step.AllItems(null, delta),
            Step.AllItems(null, authoritative),
            Step.Playback(listOf(playback("3"))),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertEquals(setOf("2", "3"), result.entries.mapNotNull { it.media?.ids?.idValue("simkl") }.toSet())
        assertEquals(
            SimklListStatus.DROPPED,
            result.entries.single { it.media?.ids?.idValue("simkl") == "2" }.status,
        )
        assertEquals("3", result.playback.single().media?.ids?.idValue("simkl"))
        assertEquals("v2", result.watermark)
        assertEquals(
            listOf<SimklAllItemsRequest>(
                SimklAllItemsRequest.Changes("v1"),
                SimklAllItemsRequest.CurrentIds,
            ),
            remote.allItemsRequests,
        )
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `delta replaces local poster fallback with canonical Simkl artwork`() = runBlocking {
        val currentEntry = entry(SimklMediaType.SHOWS, "1").copy(
            localPosterUrl = "https://catalog.example/poster.webp",
        )
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1", library = "l1"),
            entries = listOf(currentEntry),
        )
        val canonical = entry(SimklMediaType.SHOWS, "1").copy(
            show = media("1").copy(poster = "12/canonical"),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2", library = "l2")),
            Step.AllItems(null, responseOf(canonical)),
        )

        val result = SimklSyncEngine(remote) { 900L }.synchronize(current)

        assertNull(result.entries.single().localPosterUrl)
        assertEquals(
            simklPosterUrl("12/canonical"),
            result.toSimklLibraryProjection().items.single().poster,
        )
        assertEquals(
            listOf<SimklAllItemsRequest>(SimklAllItemsRequest.Changes("v1")),
            remote.allItemsRequests,
        )
        assertTrue(remote.isExhausted)
    }

    @Test
    fun `failed delta leaves the caller snapshot unchanged`() = runBlocking {
        val current = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            activities = activities(all = "v1"),
            entries = listOf(entry(SimklMediaType.SHOWS, "1")),
        )
        val remote = ScriptedRemote(
            Step.Activities(activities(all = "v2")),
            Step.Failure(IllegalStateException("network")),
        )

        assertFailsWith<IllegalStateException> {
            SimklSyncEngine(remote) { 1_000L }.synchronize(current)
        }
        assertEquals("v1", current.watermark)
        assertEquals("1", current.entries.single().media?.ids?.idValue("simkl"))
    }

    @Test
    fun `remote applies documented bootstrap delta and reconciliation parameters`() = runBlocking {
        var now = 0L
        val urls = mutableListOf<String>()
        val engine = SimklHttpEngine { _, url, _, _ ->
            urls += url
            RawHttpResponse(200, "", url, "{}", emptyMap())
        }
        val client = SimklApiClient(
            engine = engine,
            accessToken = { "token" },
            onUnauthorized = {},
            nowEpochMs = { now },
            sleep = { duration -> now += duration },
            retryJitterMs = { 0L },
        )
        val remote = SimklApiSyncRemote(client)

        remote.fetchAllItems(SimklAllItemsRequest.Bootstrap(SimklMediaType.SHOWS))
        remote.fetchAllItems(SimklAllItemsRequest.Changes("2026-05-08T14:23:11Z"))
        remote.fetchAllItems(SimklAllItemsRequest.CurrentIds)

        assertFalse("date_from=" in urls[0])
        assertTrue("extended=full" in urls[0])
        assertTrue("episode_watched_at=yes" in urls[0])
        assertTrue("include_all_episodes=yes" in urls[0])
        assertTrue("episode_tvdb_id=yes" in urls[0])
        assertTrue("date_from=2026-05-08T14%3A23%3A11Z" in urls[1])
        assertTrue("extended=full_anime_seasons" in urls[1])
        assertTrue("episode_watched_at=yes" in urls[1])
        assertTrue("episode_tvdb_id=yes" in urls[1])
        assertTrue("include_all_episodes=yes" in urls[1])
        assertTrue("extended=simkl_ids_only" in urls[2])
    }

    @Test
    fun `remote treats top level empty all items variants as empty`() = runBlocking {
        listOf("null", "[]").forEach { body ->
            val engine = SimklHttpEngine { _, url, _, _ ->
                RawHttpResponse(200, "", url, body, emptyMap())
            }
            val client = SimklApiClient(
                engine = engine,
                accessToken = { "token" },
                onUnauthorized = {},
                nowEpochMs = { 0L },
                sleep = {},
                retryJitterMs = { 0L },
            )

            val response = SimklApiSyncRemote(client).fetchAllItems(
                SimklAllItemsRequest.Bootstrap(SimklMediaType.ANIME),
            )

            assertTrue(response.entriesFor(SimklMediaType.ANIME).isEmpty())
            assertTrue(response.presentTypes().isEmpty())
        }
    }

    private sealed interface Step {
        data class Activities(val value: SimklActivities) : Step
        data class AllItems(val type: SimklMediaType?, val value: SimklAllItemsResponse) : Step
        data class Playback(val value: List<SimklPlaybackSession>) : Step
        data class RewatchSessions(val value: List<SimklLibraryEntry>) : Step
        data class Failure(val error: Throwable) : Step
    }

    private class ScriptedRemote(vararg steps: Step) : SimklSyncRemote {
        private val remaining = steps.toMutableList()
        val allItemsRequests = mutableListOf<SimklAllItemsRequest>()
        val isExhausted: Boolean get() = remaining.isEmpty()

        override suspend fun fetchActivities(): SimklActivities = when (val step = next()) {
            is Step.Activities -> step.value
            is Step.Failure -> throw step.error
            else -> error("Expected activities, got $step")
        }

        override suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse {
            allItemsRequests += request
            return when (val step = next()) {
                is Step.AllItems -> {
                    assertEquals(step.type, request.type)
                    step.value
                }
                is Step.Failure -> throw step.error
                else -> error("Expected all-items, got $step")
            }
        }

        override suspend fun fetchPlayback(): List<SimklPlaybackSession> = when (val step = next()) {
            is Step.Playback -> step.value
            is Step.Failure -> throw step.error
            else -> error("Expected playback, got $step")
        }

        /** The rewatch read stays out of the scripted order: it only consumes a step when one fits. */
        override suspend fun fetchRewatchSessions(): List<SimklLibraryEntry> {
            val step = remaining.firstOrNull() ?: return emptyList()
            if (step !is Step.RewatchSessions) return emptyList()
            remaining.removeAt(0)
            return step.value
        }

        private fun next(): Step = remaining.removeAt(0)
    }

    private companion object {
        fun entry(
            type: SimklMediaType,
            id: String,
            status: SimklListStatus = SimklListStatus.WATCHING,
        ) = SimklLibraryEntry(
            mediaType = type,
            status = status,
            show = if (type == SimklMediaType.MOVIES) null else media(id),
            movie = if (type == SimklMediaType.MOVIES) media(id) else null,
        )

        fun media(id: String) = SimklMedia(
            title = "Title $id",
            ids = buildJsonObject { put("simkl", id.toLong()) },
        )

        fun responseOf(entry: SimklLibraryEntry): SimklAllItemsResponse = when (entry.mediaType) {
            SimklMediaType.SHOWS -> SimklAllItemsResponse(shows = listOf(entry))
            SimklMediaType.MOVIES -> SimklAllItemsResponse(movies = listOf(entry))
            SimklMediaType.ANIME -> SimklAllItemsResponse(anime = listOf(entry))
        }

        fun playback(id: String) = SimklPlaybackSession(
            id = id.toLong(),
            progress = 42.0,
            movie = media(id),
        )

        fun episodePlayback(id: String, pausedAt: String) = SimklPlaybackSession(
            id = id.toLong(),
            progress = 62.5,
            pausedAt = pausedAt,
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 5),
            show = media(id),
        )

        fun watchedShowEntry(id: String, watchedAt: String) =
            entry(SimklMediaType.SHOWS, id).copy(
                lastWatchedAt = watchedAt,
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(SimklEpisode(number = 5, watchedAt = watchedAt)),
                    ),
                ),
            )

        fun activities(
            all: String,
            removed: String = "removed",
            playback: String = "playback",
            library: String = all,
            settings: String = "settings",
        ): SimklActivities {
            val domain = SimklActivityDomain(
                all = all,
                ratedAt = library,
                playback = playback,
                plantowatch = library,
                watching = library,
                completed = library,
                hold = library,
                dropped = library,
                removedFromList = removed,
            )
            return SimklActivities(
                all = all,
                settings = SimklActivitySettings(all = settings),
                tvShows = domain,
                movies = domain,
                anime = domain,
            )
        }

        const val ALL_ITEMS_FIXTURE = """
            {
              "shows": [{
                "last_watched_at": "2026-05-15T00:35:15Z",
                "user_rating": null,
                "status": "watching",
                "last_watched": "S01E01",
                "watched_episodes_count": 1,
                "total_episodes_count": 177,
                "show": {
                  "title": "The Walking Dead",
                  "year": 2010,
                  "ids": {"simkl": 2090, "imdb": "tt1520211", "tvdb": "153021"}
                },
                "seasons": [{"number": 1, "episodes": [{"number": 1, "watched_at": "2026-05-15T00:32:20Z"}]}]
              }],
              "movies": [{
                "user_rating": null,
                "status": "completed",
                "movie": {
                  "title": "The Godfather",
                  "year": 1972,
                  "ids": {"simkl": 53434, "imdb": "tt0068646", "tmdb": "238"}
                }
              }]
            }
        """
    }
}
