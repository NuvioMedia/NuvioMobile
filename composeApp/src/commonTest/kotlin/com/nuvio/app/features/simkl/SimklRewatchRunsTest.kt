package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.RewatchRunPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklRewatchRunsTest {
    @Test
    fun `always offers the episode after a single rewatch`() {
        val runs = runsFor(
            mode = SimklRewatchNextUpMode.ALWAYS,
            rewatched = listOf(Marked(season = 2, episode = 6)),
        )

        assertEquals(1, runs.size)
        assertEquals(2, runs.single().seasonNumber)
        assertEquals(6, runs.single().episodeNumber)
    }

    @Test
    fun `after two waits for a second consecutive episode`() {
        val single = runsFor(
            mode = SimklRewatchNextUpMode.AFTER_TWO,
            rewatched = listOf(Marked(season = 2, episode = 6)),
        )
        assertTrue(single.isEmpty(), "one episode on its own is not a run")

        val pair = runsFor(
            mode = SimklRewatchNextUpMode.AFTER_TWO,
            rewatched = listOf(
                Marked(season = 2, episode = 6),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )
        assertEquals(1, pair.size)
        assertEquals(7, pair.single().episodeNumber, "the run stands at its last episode")
    }

    @Test
    fun `never keeps rewatches out of the app entirely`() {
        val runs = runsFor(
            mode = SimklRewatchNextUpMode.NEVER,
            rewatched = listOf(
                Marked(season = 2, episode = 5),
                Marked(season = 2, episode = 6),
                Marked(season = 2, episode = 7, watchedAt = NEWER),
            ),
        )

        assertTrue(runs.isEmpty())
    }

    @Test
    fun `a gap breaks the run`() {
        val runs = runsFor(
            mode = SimklRewatchNextUpMode.AFTER_TWO,
            rewatched = listOf(
                Marked(season = 2, episode = 6),
                Marked(season = 2, episode = 9, watchedAt = NEWER),
            ),
        )

        assertTrue(runs.isEmpty(), "episodes that are not consecutive are not a run")
    }

    @Test
    fun `the newest rewatch decides which chain is the run`() {
        // An old pair from season 1 and a single, recent episode from season 3: the run follows the
        // recent episode, so with Always the season 3 episode is the run.
        val runs = runsFor(
            mode = SimklRewatchNextUpMode.ALWAYS,
            rewatched = listOf(
                Marked(season = 1, episode = 1, watchedAt = OLD),
                Marked(season = 1, episode = 2, watchedAt = OLD),
                Marked(season = 3, episode = 4, watchedAt = NEWER),
            ),
        )

        assertEquals(1, runs.size)
        assertEquals(3, runs.single().seasonNumber)
        assertEquals(4, runs.single().episodeNumber)
    }

    @Test
    fun `a canonical row is not a rewatch`() {
        val rows = listOf(
            rewatchRow(
                rewatched = listOf(Marked(season = 2, episode = 6)),
                isRewatch = false,
            ),
        )

        assertTrue(
            deriveSimklRewatchRuns(
                entries = rows,
                animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
                minimumRunEpisodes = SimklRewatchNextUpMode.ALWAYS.minimumRunEpisodes,
            ).isEmpty(),
        )
    }

    @Test
    fun `the default offers every rewatch`() {
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.Default)
        assertEquals(1, SimklRewatchNextUpMode.ALWAYS.minimumRunEpisodes)
        assertEquals(2, SimklRewatchNextUpMode.AFTER_TWO.minimumRunEpisodes)
        assertNull(SimklRewatchNextUpMode.NEVER.minimumRunEpisodes)
    }

    @Test
    fun `a stored mode falls back to always`() {
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage(null))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage(""))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage("something-else"))
        assertEquals(SimklRewatchNextUpMode.AFTER_TWO, SimklRewatchNextUpMode.fromStorage("after_two"))
        assertEquals(SimklRewatchNextUpMode.NEVER, SimklRewatchNextUpMode.fromStorage("NEVER"))
    }

    private fun runsFor(
        mode: SimklRewatchNextUpMode,
        rewatched: List<Marked>,
    ): List<RewatchRunPosition> = deriveSimklRewatchRuns(
        entries = listOf(rewatchRow(rewatched = rewatched, isRewatch = true)),
        animeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
        minimumRunEpisodes = mode.minimumRunEpisodes,
    )

    private fun rewatchRow(
        rewatched: List<Marked>,
        isRewatch: Boolean,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.COMPLETED,
        lastWatchedAt = rewatched.maxByOrNull(Marked::watchedAt)?.watchedAt,
        show = SimklMedia(
            title = "Dark",
            year = 2017,
            ids = mapOf("simkl" to jsonValue("39687"), "imdb" to jsonValue("tt5753856")),
        ),
        seasons = rewatched
            .groupBy(Marked::season)
            .map { (season, episodes) ->
                SimklSeason(
                    number = season,
                    episodes = episodes.map { marked ->
                        SimklEpisode(number = marked.episode, watchedAt = marked.watchedAt)
                    },
                )
            },
        isRewatch = isRewatch,
        rewatchId = if (isRewatch) 1L else null,
        rewatchStatus = if (isRewatch) "active" else null,
    )

    private fun jsonValue(value: String): JsonElement = Json.parseToJsonElement("\"$value\"")

    private data class Marked(
        val season: Int,
        val episode: Int,
        val watchedAt: String = OLD,
    )

    private companion object {
        const val OLD = "2026-03-01T20:00:00Z"
        const val NEWER = "2026-08-01T20:00:00Z"
    }
}
