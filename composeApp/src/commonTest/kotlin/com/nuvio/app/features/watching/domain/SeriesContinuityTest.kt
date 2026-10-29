package com.nuvio.app.features.watching.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeriesContinuityTest {
    private val show = WatchingContentRef(type = "series", id = "show")
    private val episodes = listOf(
        WatchingReleasedEpisode(videoId = "ep1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-03-01"),
        WatchingReleasedEpisode(videoId = "ep2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-03-08"),
        WatchingReleasedEpisode(videoId = "ep3", seasonNumber = 1, episodeNumber = 3, title = "Episode 3", releasedDate = "2026-03-15"),
    )

    @Test
    fun decideSeriesPrimaryAction_restarts_completed_series_from_first_episode() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:3",
                    seasonNumber = 1,
                    episodeNumber = 3,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = true,
                ),
            ),
            watchedRecords = emptyList(),
            todayIsoDate = "2026-03-30",
            defaultVideoId = "ep2",
            allowRewatch = true,
        )

        assertNotNull(action)
        assertEquals("show:1:1", action.videoId)
        assertEquals("Play S1 E1", action.label)
        assertNull(action.resumePositionMs)
    }

    @Test
    fun decideSeriesPrimaryAction_has_no_next_up_after_final_episode() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = emptyList(),
            watchedRecords = listOf(
                WatchingWatchedRecord(content = show, seasonNumber = 1, episodeNumber = 3, markedAtEpochMs = 100L),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNull(action)
    }

    @Test
    fun decideSeriesPrimaryAction_does_not_rewatch_unavailable_episodes() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes.map { it.copy(available = false, releasedDate = null) },
            progressRecords = emptyList(),
            watchedRecords = listOf(
                WatchingWatchedRecord(content = show, seasonNumber = 1, episodeNumber = 3, markedAtEpochMs = 100L),
            ),
            todayIsoDate = "2026-03-30",
            allowRewatch = true,
        )

        assertNull(action)
    }

    @Test
    fun continueWatchingProgressEntries_drops_older_resume_when_latest_series_progress_is_completed() {
        val result = continueWatchingProgressEntries(
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:4",
                    seasonNumber = 1,
                    episodeNumber = 4,
                    lastUpdatedEpochMs = 100L,
                    lastPositionMs = 10_000L,
                ),
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:5",
                    seasonNumber = 1,
                    episodeNumber = 5,
                    lastUpdatedEpochMs = 200L,
                    isCompleted = true,
                ),
            ),
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun continueWatchingProgressEntries_keeps_newer_resume_and_independent_movies() {
        val movieA = WatchingContentRef(type = "movie", id = "movie-a")
        val movieB = WatchingContentRef(type = "movie", id = "movie-b")
        val result = continueWatchingProgressEntries(
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:4",
                    seasonNumber = 1,
                    episodeNumber = 4,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = true,
                ),
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:5",
                    seasonNumber = 1,
                    episodeNumber = 5,
                    lastUpdatedEpochMs = 400L,
                    lastPositionMs = 10_000L,
                ),
                WatchingProgressRecord(
                    content = movieA,
                    videoId = "movie-a",
                    lastUpdatedEpochMs = 300L,
                    lastPositionMs = 10_000L,
                ),
                WatchingProgressRecord(
                    content = movieB,
                    videoId = "movie-b",
                    lastUpdatedEpochMs = 200L,
                    lastPositionMs = 10_000L,
                ),
            ),
        )

        assertEquals(listOf("show:1:5", "movie-a", "movie-b"), result.map { it.videoId })
    }

    @Test
    fun resumeProgressForSeries_coalesces_series_type_variants_independent_of_input_order() {
        val staleResume = WatchingProgressRecord(
            content = WatchingContentRef(type = "SeRiEs", id = "show"),
            videoId = "show:1:4",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 100L,
            lastPositionMs = 10_000L,
        )
        val completed = WatchingProgressRecord(
            content = WatchingContentRef(type = "tV", id = "show"),
            videoId = "show:1:5",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 200L,
            isCompleted = true,
        )
        val inputOrders = listOf(
            listOf(staleResume, completed),
            listOf(completed, staleResume),
        )

        listOf("series", "TV", "Show", "TvShow").forEach { requestedType ->
            inputOrders.forEach { progressRecords ->
                assertNull(
                    resumeProgressForSeries(
                        content = WatchingContentRef(type = requestedType, id = "show"),
                        progressRecords = progressRecords,
                    ),
                )
            }
        }
    }

    @Test
    fun resumeProgressForSeries_keeps_non_series_content_types_exact() {
        val movieResume = WatchingProgressRecord(
            content = WatchingContentRef(type = "movie", id = "shared"),
            videoId = "shared",
            lastUpdatedEpochMs = 100L,
            lastPositionMs = 10_000L,
        )
        val differentlyCasedMovie = movieResume.copy(
            content = WatchingContentRef(type = "Movie", id = "shared"),
            lastUpdatedEpochMs = 200L,
            isCompleted = true,
        )

        assertEquals(
            movieResume,
            resumeProgressForSeries(
                content = WatchingContentRef(type = "movie", id = "shared"),
                progressRecords = listOf(differentlyCasedMovie, movieResume),
            ),
        )
    }

    @Test
    fun decideSeriesPrimaryAction_prefers_up_next_when_completed_is_newer_than_resume() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastUpdatedEpochMs = 100L,
                    lastPositionMs = 1_000L,
                ),
            ),
            watchedRecords = listOf(
                WatchingWatchedRecord(
                    content = show,
                    seasonNumber = 1,
                    episodeNumber = 2,
                    markedAtEpochMs = 200L,
                ),
            ),
            todayIsoDate = "2026-03-30",
            allowRewatch = true,
        )

        assertNotNull(action)
        assertEquals("Next Up • S1 E3", action.label)
        assertEquals("show:1:3", action.videoId)
        assertEquals(3, action.episodeNumber)
    }

    @Test
    fun decideSeriesPrimaryAction_prefers_resume_when_resume_is_newer_than_completed() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastUpdatedEpochMs = 300L,
                    lastPositionMs = 1_500L,
                ),
            ),
            watchedRecords = listOf(
                WatchingWatchedRecord(
                    content = show,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    markedAtEpochMs = 200L,
                ),
            ),
            todayIsoDate = "2026-03-30",
            allowRewatch = true,
        )

        assertNotNull(action)
        assertEquals("Resume S1 E2", action.label)
        assertEquals("show:1:2", action.videoId)
        assertEquals(1_500L, action.resumePositionMs)
    }

    @Test
    fun decideSeriesPrimaryAction_skips_specials_for_initial_play() {
        val episodesWithSpecials = listOf(
            WatchingReleasedEpisode(videoId = "sp1", seasonNumber = 0, episodeNumber = 1, title = "Special 1", releasedDate = "2026-01-01"),
            WatchingReleasedEpisode(videoId = "ep1", seasonNumber = 1, episodeNumber = 1, title = "Pilot", releasedDate = "2026-01-15"),
            WatchingReleasedEpisode(videoId = "ep2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-01-22"),
            WatchingReleasedEpisode(videoId = "ep3", seasonNumber = 2, episodeNumber = 1, title = "S2 Premiere", releasedDate = "2026-03-01"),
        )
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodesWithSpecials,
            progressRecords = emptyList(),
            watchedRecords = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Play S1 E1", action.label)
        assertEquals("show:1:1", action.videoId)
    }

    @Test
    fun nextReleasedEpisodeAfter_global_index_fallback_ignores_specials() {
        val episodesWithSpecials = listOf(
            WatchingReleasedEpisode(videoId = "sp1", seasonNumber = 0, episodeNumber = 1, title = "Special 1", releasedDate = "2026-01-01"),
            WatchingReleasedEpisode(videoId = "s1e1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-01-08"),
            WatchingReleasedEpisode(videoId = "s1e2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-01-15"),
            WatchingReleasedEpisode(videoId = "s2e1", seasonNumber = 2, episodeNumber = 1, title = "Episode 3", releasedDate = "2026-01-22"),
            WatchingReleasedEpisode(videoId = "s2e2", seasonNumber = 2, episodeNumber = 2, title = "Episode 4", releasedDate = "2026-01-29"),
        )

        val nextEpisode = nextReleasedEpisodeAfter(
            content = show,
            episodes = episodesWithSpecials,
            seasonNumber = 1,
            episodeNumber = 3,
            todayIsoDate = "2026-02-01",
        )

        assertNotNull(nextEpisode)
        assertEquals(2, nextEpisode.seasonNumber)
        assertEquals(2, nextEpisode.episodeNumber)
        assertEquals("s2e2", nextEpisode.videoId)
    }

    @Test
    fun nextReleasedEpisodeAfter_skips_explicitly_unavailable_phantom_episode() {
        val episodesWithPhantom = listOf(
            WatchingReleasedEpisode(videoId = "s1e1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-01-01"),
            WatchingReleasedEpisode(videoId = "s3e1", seasonNumber = 3, episodeNumber = 1, title = "Episode 1", releasedDate = null, available = false),
        )

        val nextEpisode = nextReleasedEpisodeAfter(
            content = show,
            episodes = episodesWithPhantom,
            seasonNumber = 1,
            episodeNumber = 1,
            todayIsoDate = "2026-07-05",
            showUnairedNextUp = true,
        )

        assertNull(nextEpisode)
    }

    @Test
    fun nextReleasedEpisodeAfter_surfaces_dated_unavailable_upcoming_episode_when_enabled() {
        val episodes = listOf(
            WatchingReleasedEpisode(videoId = "s1e1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-07-01"),
            WatchingReleasedEpisode(videoId = "s1e2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-07-12", available = false),
        )

        val nextEpisode = nextReleasedEpisodeAfter(
            content = show,
            episodes = episodes,
            seasonNumber = 1,
            episodeNumber = 1,
            todayIsoDate = "2026-07-05",
            showUnairedNextUp = true,
        )

        assertNotNull(nextEpisode)
        assertEquals("s1e2", nextEpisode.videoId)
    }

    @Test
    fun nextReleasedEpisodeAfter_keeps_dated_unavailable_episode_on_release_day() {
        val episodes = listOf(
            WatchingReleasedEpisode(videoId = "s1e1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-07-01"),
            WatchingReleasedEpisode(videoId = "s1e2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-07-12T20:00:00", available = false),
        )

        val nextEpisode = nextReleasedEpisodeAfter(
            content = show,
            episodes = episodes,
            seasonNumber = 1,
            episodeNumber = 1,
            todayIsoDate = "2026-07-12",
            showUnairedNextUp = true,
        )

        assertNotNull(nextEpisode)
        assertEquals("s1e2", nextEpisode.videoId)
    }

    @Test
    fun decideSeriesPrimaryAction_falls_back_to_specials_when_no_main_season() {
        val specialsOnly = listOf(
            WatchingReleasedEpisode(videoId = "sp1", seasonNumber = 0, episodeNumber = 1, title = "Special 1", releasedDate = "2026-01-01"),
            WatchingReleasedEpisode(videoId = "sp2", seasonNumber = 0, episodeNumber = 2, title = "Special 2", releasedDate = "2026-01-15"),
        )
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = specialsOnly,
            progressRecords = emptyList(),
            watchedRecords = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Play S0 E1", action.label)
    }

    @Test
    fun decideSeriesPrimaryAction_skips_watched_episodes_after_rewatch() {
        val twoSeasons = listOf(
            WatchingReleasedEpisode(videoId = "ep1", seasonNumber = 1, episodeNumber = 1, title = "S1E1", releasedDate = "2026-01-01"),
            WatchingReleasedEpisode(videoId = "ep2", seasonNumber = 1, episodeNumber = 2, title = "S1E2", releasedDate = "2026-01-08"),
            WatchingReleasedEpisode(videoId = "ep3", seasonNumber = 1, episodeNumber = 3, title = "S1E3", releasedDate = "2026-01-15"),
            WatchingReleasedEpisode(videoId = "ep4", seasonNumber = 2, episodeNumber = 1, title = "S2E1", releasedDate = "2026-03-01"),
            WatchingReleasedEpisode(videoId = "ep5", seasonNumber = 2, episodeNumber = 2, title = "S2E2", releasedDate = "2026-03-08"),
        )
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = twoSeasons,
            progressRecords = listOf(
                // All of season 1 completed
                WatchingProgressRecord(content = show, videoId = "show:1:1", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 100L, isCompleted = true),
                WatchingProgressRecord(content = show, videoId = "show:1:2", seasonNumber = 1, episodeNumber = 2, lastUpdatedEpochMs = 200L, isCompleted = true),
                WatchingProgressRecord(content = show, videoId = "show:1:3", seasonNumber = 1, episodeNumber = 3, lastUpdatedEpochMs = 300L, isCompleted = true),
                // S2E1 completed
                WatchingProgressRecord(content = show, videoId = "show:2:1", seasonNumber = 2, episodeNumber = 1, lastUpdatedEpochMs = 400L, isCompleted = true),
                // Re-watched S1E1 recently — newer timestamp but earlier episode
                WatchingProgressRecord(content = show, videoId = "show:1:1", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 900L, isCompleted = true),
            ),
            watchedRecords = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Next Up • S2 E2", action.label)
        assertEquals("show:2:2", action.videoId)
    }
}
