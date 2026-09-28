package com.nuvio.app.features.details

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeriesPlaybackResolverTest {
    @Test
    fun seriesPrimaryAction_restarts_at_first_episode_when_series_is_marked_watched() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "show:0:1", title = "Special", season = 0, episode = 1, released = "2026-02-01"),
                MetaVideo(id = "show:1:1", title = "Pilot", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "show:1:2", title = "Finale", season = 1, episode = 2, released = "2026-03-08"),
            ),
        )
        val todayIsoDate = "2026-03-30"
        val watchedItems = listOf(meta.toSeriesWatchedItem(markedAtEpochMs = 200L)) +
            meta.releasedMainSeasonEpisodes(todayIsoDate).map { episode ->
                meta.toEpisodeWatchedItem(episode, markedAtEpochMs = 200L)
            }

        val action = meta.seriesPrimaryAction(
            entries = emptyList(),
            watchedItems = watchedItems,
            todayIsoDate = todayIsoDate,
            allowRewatch = true,
        )

        assertNotNull(action, "A watched series must still select an episode for the Play button")
        assertEquals("show:1:1", action.videoId)
        assertEquals(1, action.seasonNumber)
        assertEquals(1, action.episodeNumber)
        assertEquals("Pilot", action.episodeTitle)
        assertEquals("Play S1 E1", action.label)
        assertNull(action.resumePositionMs)
    }

    @Test
    fun seriesPrimaryAction_uses_latest_watched_episode_when_manual_mark_exists() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "ep3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = emptyList(),
            watchedItems = listOf(
                WatchedItem(
                    id = "show",
                    type = "series",
                    name = "Episode 2",
                    season = 1,
                    episode = 2,
                    markedAtEpochMs = 123L,
                ),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Next Up • S1 E3", action.label)
        assertEquals("show:1:3", action.videoId)
        assertEquals(1, action.seasonNumber)
        assertEquals(3, action.episodeNumber)
    }

    @Test
    fun seriesPrimaryAction_prefers_next_up_when_manual_watch_is_newer_than_resume() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "ep3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "show",
                    parentMetaType = "series",
                    videoId = "show:1:2",
                    title = "Show",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastPositionMs = 1_000L,
                    durationMs = 10_000L,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = false,
                ),
            ),
            watchedItems = listOf(
                WatchedItem(
                    id = "show",
                    type = "series",
                    name = "Episode 2",
                    season = 1,
                    episode = 2,
                    markedAtEpochMs = 200L,
                ),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Next Up • S1 E3", action.label)
        assertEquals("show:1:3", action.videoId)
    }

    @Test
    fun seriesPrimaryAction_uses_explicit_content_when_meta_id_is_alias() {
        val meta = MetaDetails(
            id = "tt1234567",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "s4e14", title = "Episode 14", season = 4, episode = 14, released = "2026-03-01"),
                MetaVideo(id = "s4e15", title = "Episode 15", season = 4, episode = 15, released = "2026-03-08"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            content = WatchingContentRef(type = "series", id = "tmdb:98765"),
            entries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "tmdb:98765",
                    parentMetaType = "series",
                    videoId = "tmdb:98765:4:14",
                    title = "Show",
                    seasonNumber = 4,
                    episodeNumber = 14,
                    lastPositionMs = 10_000L,
                    durationMs = 10_000L,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = true,
                ),
            ),
            watchedItems = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Next Up • S4 E15", action.label)
        assertEquals("tmdb:98765:4:15", action.videoId)
        assertEquals(4, action.seasonNumber)
        assertEquals(15, action.episodeNumber)
    }

    @Test
    fun seriesPrimaryAction_prefers_default_video_id_when_nothing_watched() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            defaultVideoId = "show:1:2",
            videos = listOf(
                MetaVideo(id = "show:1:1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "show:1:2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "show:1:3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = emptyList(),
            watchedItems = emptyList(),
            todayIsoDate = "2026-03-30",
            allowRewatch = true,
        )

        assertNotNull(action)
        assertEquals("Play S1 E2", action.label)
        assertEquals("show:1:2", action.videoId)
        assertEquals(1, action.seasonNumber)
        assertEquals(2, action.episodeNumber)
    }

    @Test
    fun seriesPrimaryAction_ignores_default_video_id_when_progress_exists() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            defaultVideoId = "show:1:2",
            videos = listOf(
                MetaVideo(id = "show:1:1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "show:1:2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "show:1:3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "show",
                    parentMetaType = "series",
                    videoId = "show:1:1",
                    title = "Show",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    lastPositionMs = 1_000L,
                    durationMs = 10_000L,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = false,
                ),
            ),
            watchedItems = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Resume S1 E1", action.label)
        assertEquals("show:1:1", action.videoId)
    }

    @Test
    fun nextReleasedEpisodeAfter_global_index_fallback_ignores_specials() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "sp1", title = "Special 1", season = 0, episode = 1, released = "2026-01-01"),
                MetaVideo(id = "s1e1", title = "Episode 1", season = 1, episode = 1, released = "2026-01-08"),
                MetaVideo(id = "s1e2", title = "Episode 2", season = 1, episode = 2, released = "2026-01-15"),
                MetaVideo(id = "s2e1", title = "Episode 3", season = 2, episode = 1, released = "2026-01-22"),
                MetaVideo(id = "s2e2", title = "Episode 4", season = 2, episode = 2, released = "2026-01-29"),
            ),
        )

        val nextEpisode = meta.nextReleasedEpisodeAfter(
            seasonNumber = 1,
            episodeNumber = 3,
            todayIsoDate = "2026-02-01",
        )

        assertNotNull(nextEpisode)
        assertEquals(2, nextEpisode.season)
        assertEquals(2, nextEpisode.episode)
        assertEquals("s2e2", nextEpisode.id)
    }

    @Test
    fun filterUnavailableFutureSeasons_removes_explicitly_unavailable_season_without_release_date() {
        val episodes = listOf(
            MetaVideo(id = "s1e1", title = "Episode 1", season = 1, episode = 1, released = "2026-01-01"),
            MetaVideo(id = "s3e1", title = "Episode 1", season = 3, episode = 1, released = null, available = false),
        )

        val filtered = episodes.filterUnavailableFutureSeasons(todayIsoDate = "2026-07-05")

        assertEquals(listOf("s1e1"), filtered.map(MetaVideo::id))
    }
}
