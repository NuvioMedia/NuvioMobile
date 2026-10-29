package com.nuvio.app.features.simkl

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklScrobbleReconciliationTest {
    @Test
    fun `pause replaces local playback without advancing the sync watermark`() {
        val media = showMedia(simklId = 2090L, imdb = "tt1520211")
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.SHOWS,
                    status = SimklListStatus.WATCHING,
                    show = media,
                ),
            ),
            playback = listOf(
                SimklPlaybackSession(
                    id = 4L,
                    progress = 20.0,
                    pausedAt = "2023-11-14T21:00:00Z",
                    type = "episode",
                    episode = SimklPlaybackEpisode(season = 1, number = 2),
                    show = media,
                ),
            ),
        )

        val updated = snapshot.applyScrobbleResult(
            result = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.PAUSE,
                playbackId = 8L,
                progress = 45.0,
                mediaType = SimklMediaType.SHOWS,
                media = media,
                episode = SimklPlaybackEpisode(season = 1, number = 2),
            ),
            committedAtEpochMs = 1_700_000_000_000L,
        )

        assertEquals("v1", updated.watermark)
        assertEquals(1, updated.playback.size)
        assertEquals(8L, updated.playback.single().id)
        assertEquals(45.0, updated.playback.single().progress)
        assertEquals("2023-11-14T22:13:20Z", updated.playback.single().pausedAt)
        assertTrue(updated.toSimklWatchedProjection().items.isEmpty())
    }

    @Test
    fun `completed stop records the exact episode and removes its playback`() {
        val media = showMedia(simklId = 2090L, imdb = "tt1520211")
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "v1",
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.SHOWS,
                    status = SimklListStatus.WATCHING,
                    show = media,
                    seasons = listOf(
                        SimklSeason(
                            number = 1,
                            episodes = listOf(SimklEpisode(number = 2)),
                        ),
                    ),
                ),
            ),
            playback = listOf(
                SimklPlaybackSession(
                    id = 8L,
                    progress = 70.0,
                    pausedAt = "2023-11-14T21:00:00Z",
                    type = "episode",
                    episode = SimklPlaybackEpisode(season = 1, number = 2),
                    show = media,
                ),
            ),
        )

        val updated = snapshot.applyScrobbleResult(
            result = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                playbackId = 9L,
                progress = 90.0,
                mediaType = SimklMediaType.SHOWS,
                media = media,
                episode = SimklPlaybackEpisode(season = 1, number = 2),
            ),
            committedAtEpochMs = 1_700_000_000_000L,
        )

        assertEquals("v1", updated.watermark)
        assertTrue(updated.playback.isEmpty())
        assertEquals(1, updated.entries.single().watchedEpisodesCount)
        val watched = updated.toSimklWatchedProjection().items.single()
        assertEquals(1, watched.season)
        assertEquals(2, watched.episode)
        assertEquals(1_700_000_000_000L, watched.markedAtEpochMs)
    }

    @Test
    fun `anime stop updates only the matching Simkl title when siblings share IMDb`() {
        val firstMedia = animeMedia(100L, "tt2560140", 16498L)
        val secondMedia = animeMedia(101L, "tt2560140", 25777L)
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                animeEntry(firstMedia, season = 1, episode = 3),
                animeEntry(secondMedia, season = 1, episode = 3, tvdbSeason = 2),
            ),
        )

        val updated = snapshot.applyScrobbleResult(
            result = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                playbackId = 12L,
                progress = 95.0,
                mediaType = SimklMediaType.ANIME,
                media = secondMedia,
                episode = SimklPlaybackEpisode(
                    season = 1,
                    number = 3,
                    tvdbSeason = 2,
                    tvdbNumber = 3,
                ),
            ),
            committedAtEpochMs = 1_700_000_000_000L,
        )

        val first = updated.entries.first { it.media?.ids?.simklIdValue() == "100" }
        val second = updated.entries.first { it.media?.ids?.simklIdValue() == "101" }
        assertNull(first.seasons.single().episodes.single().watchedAt)
        assertEquals(
            "2023-11-14T22:13:20Z",
            second.seasons.single().episodes.single().watchedAt,
        )
        val watched = updated.toSimklWatchedProjection().items.single()
        assertEquals(2, watched.season)
        assertEquals(3, watched.episode)
    }

    @Test
    fun `completed movie stop creates watched state without an activities refresh`() {
        val media = SimklMedia(
            title = "Inception",
            year = 2010,
            ids = mapOf(
                "simkl" to JsonPrimitive(472214L),
                "imdb" to JsonPrimitive("tt1375666"),
            ),
        )

        val updated = SimklSyncSnapshot().applyScrobbleResult(
            result = SimklScrobbleResult(
                outcome = SimklScrobbleOutcome.SCROBBLE,
                playbackId = 15L,
                progress = 95.0,
                mediaType = SimklMediaType.MOVIES,
                media = media,
                episode = null,
                watchedAt = "2023-11-14T21:00:00Z",
            ),
            committedAtEpochMs = 1_700_000_000_000L,
        )

        assertEquals(SimklListStatus.COMPLETED, updated.entries.single().status)
        assertEquals("2023-11-14T21:00:00Z", updated.entries.single().lastWatchedAt)
        assertEquals("tt1375666", updated.toSimklWatchedProjection().items.single().id)
    }

    @Test
    fun `id disagreement resolves on the tv ids before the anime ids`() {
        val local = SimklMedia(
            title = "Show",
            ids = mapOf(
                "imdb" to JsonPrimitive("tt100"),
                "mal" to JsonPrimitive(111L),
            ),
        )
        val sharedAnimeId = SimklMedia(
            title = "Show",
            ids = mapOf(
                "imdb" to JsonPrimitive("tt200"),
                "mal" to JsonPrimitive(111L),
            ),
        )
        val sharedImdb = SimklMedia(
            title = "Show",
            ids = mapOf(
                "imdb" to JsonPrimitive("tt100"),
                "mal" to JsonPrimitive(222L),
            ),
        )

        assertFalse(local.matchesTarget(sharedAnimeId))
        assertTrue(local.matchesTarget(sharedImdb))
    }

    private fun showMedia(simklId: Long, imdb: String) = SimklMedia(
        title = "The Walking Dead",
        year = 2010,
        ids = mapOf(
            "simkl" to JsonPrimitive(simklId),
            "imdb" to JsonPrimitive(imdb),
        ),
    )

    private fun animeMedia(simklId: Long, imdb: String, mal: Long) = SimklMedia(
        title = "Attack on Titan",
        year = 2013,
        ids = mapOf(
            "simkl" to JsonPrimitive(simklId),
            "imdb" to JsonPrimitive(imdb),
            "mal" to JsonPrimitive(mal),
        ),
    )

    private fun animeEntry(
        media: SimklMedia,
        season: Int,
        episode: Int,
        tvdbSeason: Int? = null,
    ) = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        status = SimklListStatus.WATCHING,
        show = media,
        seasons = listOf(
            SimklSeason(
                number = season,
                episodes = listOf(
                    SimklEpisode(
                        number = episode,
                        tvdb = tvdbSeason?.let { value ->
                            SimklEpisodeMapping(value, episode)
                        },
                    ),
                ),
            ),
        ),
    )
}
