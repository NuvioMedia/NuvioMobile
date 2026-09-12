package com.nuvio.app.features.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklRewatchPolicyTest {

    @Test
    fun `episode already in history is a prior watch`() {
        val media = media(39687, imdb = "tt4574334")
        val snapshot = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = media,
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(
                            SimklEpisode(number = 5, watchedAt = "2024-04-30T22:14:00Z"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(snapshot.hasPriorWatch(scrobbleResult(media, episode = SimklPlaybackEpisode(season = 1, number = 5))))
    }

    @Test
    fun `episode without watched_at is not a prior watch`() {
        val media = media(39687, imdb = "tt4574334")
        val snapshot = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = media,
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(SimklEpisode(number = 5, watchedAt = null)),
                    ),
                ),
            ),
        )

        assertFalse(snapshot.hasPriorWatch(scrobbleResult(media, episode = SimklPlaybackEpisode(season = 1, number = 5))))
    }

    @Test
    fun `unknown show is not a prior watch`() {
        val snapshot = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = media(39687),
            ),
        )

        assertFalse(snapshot.hasPriorWatch(scrobbleResult(media(99999), episode = SimklPlaybackEpisode(season = 1, number = 1))))
    }

    @Test
    fun `completed movie is a prior watch but plan to watch is not`() {
        val movieMedia = media(53536, imdb = "tt0181852")
        val completed = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                status = SimklListStatus.COMPLETED,
                movie = movieMedia,
            ),
        )
        val planned = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                status = SimklListStatus.PLAN_TO_WATCH,
                movie = movieMedia,
            ),
        )
        val result = scrobbleResult(movieMedia, episode = null, mediaType = SimklMediaType.MOVIES)

        assertTrue(completed.hasPriorWatch(result))
        assertFalse(planned.hasPriorWatch(result))
    }

    @Test
    fun `anime episode matches through tvdb mapping`() {
        val animeMedia = media(439744, imdb = "tt2560140")
        val snapshot = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.ANIME,
                status = SimklListStatus.WATCHING,
                show = animeMedia,
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(
                            SimklEpisode(
                                number = 4,
                                watchedAt = "2024-04-30T22:14:00Z",
                                tvdb = SimklEpisodeMapping(season = 2, episode = 4),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = scrobbleResult(
            animeMedia,
            mediaType = SimklMediaType.ANIME,
            episode = SimklPlaybackEpisode(season = 1, number = 4, tvdbSeason = 2, tvdbNumber = 4),
        )

        assertTrue(snapshot.hasPriorWatch(result))
    }

    @Test
    fun `anime movie with canonical entry is a prior watch`() {
        val animeMovieMedia = media(11111, imdb = "tt1234567")
        val snapshot = snapshotOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.ANIME,
                animeType = "movie",
                status = SimklListStatus.COMPLETED,
                show = animeMovieMedia,
            ),
        )
        val result = scrobbleResult(
            animeMovieMedia,
            mediaType = SimklMediaType.ANIME,
            episode = null,
        )

        assertTrue(snapshot.hasPriorWatch(result))
    }

    private fun snapshotOf(vararg entries: SimklLibraryEntry): SimklSyncSnapshot =
        SimklSyncSnapshot(entries = entries.toList())

    private fun scrobbleResult(
        media: SimklMedia,
        episode: SimklPlaybackEpisode? = SimklPlaybackEpisode(season = 1, number = 1),
        mediaType: SimklMediaType = SimklMediaType.SHOWS,
    ): SimklScrobbleResult = SimklScrobbleResult(
        outcome = SimklScrobbleOutcome.SCROBBLE,
        playbackId = null,
        progress = 85.0,
        mediaType = mediaType,
        media = media,
        episode = episode,
        watchedAt = "2024-04-30T22:14:00Z",
    )

    private fun media(
        id: Long,
        imdb: String? = null,
        tvdb: String? = null,
    ): SimklMedia = SimklMedia(
        title = "Title $id",
        runtime = 50,
        ids = buildJsonObject {
            put("simkl", id)
            imdb?.let { put("imdb", it) }
            tvdb?.let { put("tvdb", it) }
        },
    )
}
