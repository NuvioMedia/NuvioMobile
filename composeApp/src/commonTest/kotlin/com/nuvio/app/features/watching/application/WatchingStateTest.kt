package com.nuvio.app.features.watching.application

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watching.domain.WatchingCompletedEpisode
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchingStateTest {
    @Test
    fun `tv poster matches fully watched series key`() {
        val result = WatchingState.isPosterWatched(
            watchedKeys = emptySet(),
            item = MetaPreview(id = "tmdb:123", type = "tv", name = "Show"),
            fullyWatchedSeriesKeys = setOf(watchedItemKey("series", "tmdb:123")),
        )

        assertTrue(result)
    }

    @Test
    fun `film poster matches movie watched key`() {
        val result = WatchingState.isPosterWatched(
            watchedKeys = setOf(watchedItemKey("movie", "tt1234567")),
            item = MetaPreview(id = "tt1234567", type = "film", name = "Movie"),
        )

        assertTrue(result)
    }

    @Test
    fun `latest completed aggregates provider-filtered completed progress`() {
        val almostCompletePlayback = entry(
            videoId = "show:1:4",
            seasonNumber = 1,
            episodeNumber = 4,
            progressPercent = 94f,
            source = WatchProgressSourceTraktPlayback,
        )

        val result = WatchingState.latestCompletedBySeries(
            progressEntries = listOf(almostCompletePlayback),
            watchedItems = emptyList(),
        )

        assertEquals(4, result.values.single().episodeNumber)
    }

    @Test
    fun `visible continue watching drops stale resume when newer episode is completed`() {
        val resume = entry(
            videoId = "show:1:4",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 10L,
        )
        val completed = entry(
            videoId = "show:1:5",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 20L,
            isCompleted = true,
        )
        val latestCompleted = WatchingState.latestCompletedBySeries(
            progressEntries = listOf(resume, completed),
            watchedItems = emptyList(),
        )

        val result = WatchingState.visibleContinueWatchingEntries(
            progressEntries = listOf(resume, completed),
            latestCompletedBySeries = latestCompleted,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `latest completed normalizes compact watched timestamps before sorting`() {
        val expected = parseZonedIsoDateTimeToEpochMs("2026-04-25T10:02:00Z")

        val result = WatchingState.latestCompletedBySeries(
            progressEntries = emptyList(),
            watchedItems = listOf(
                WatchedItem(
                    id = "show",
                    type = "series",
                    name = "Show",
                    season = 3,
                    episode = 1,
                    markedAtEpochMs = 20260425100200L,
                ),
            ),
            preferFurthestEpisode = false,
        )

        assertEquals(expected, result.values.single().markedAtEpochMs)
    }

    @Test
    fun `latest completed keeps interleaved series separate for both episode policies`() {
        val progress = listOf(
            entry("alpha:1:10", 1, 10, lastUpdatedEpochMs = 30L, progressPercent = 94f)
                .copy(parentMetaId = "alpha"),
            entry("beta:1:8", 1, 8, lastUpdatedEpochMs = 50L, isCompleted = true)
                .copy(parentMetaId = "beta"),
            entry("gamma:1:4", 1, 4, lastUpdatedEpochMs = 90L)
                .copy(parentMetaId = "gamma"),
            entry("gamma:1:3", 1, 3, lastUpdatedEpochMs = 80L, isCompleted = true)
                .copy(parentMetaId = "gamma"),
            entry("gamma:5", null, 5, lastUpdatedEpochMs = 100L, isCompleted = true)
                .copy(parentMetaId = "gamma"),
        )
        val watched = listOf(
            watched("alpha", 9, 10L),
            watched("beta", 1, 20L).copy(season = 2),
            watched("alpha", 2, 40L),
            watched("empty", 1, 110L).copy(episode = null),
        )

        val furthest = WatchingState.latestCompletedBySeries(progress, watched, preferFurthestEpisode = true)
        val latest = WatchingState.latestCompletedBySeries(progress, watched, preferFurthestEpisode = false)

        assertEquals(
            mapOf(
                WatchingContentRef("series", "alpha") to WatchingCompletedEpisode(1, 10, 30L),
                WatchingContentRef("series", "beta") to WatchingCompletedEpisode(2, 1, 20L),
                WatchingContentRef("series", "gamma") to WatchingCompletedEpisode(1, 3, 80L),
            ),
            furthest,
        )
        assertEquals(
            mapOf(
                WatchingContentRef("series", "alpha") to WatchingCompletedEpisode(1, 2, 40L),
                WatchingContentRef("series", "beta") to WatchingCompletedEpisode(1, 8, 50L),
                WatchingContentRef("series", "gamma") to WatchingCompletedEpisode(1, 3, 80L),
            ),
            latest,
        )
    }

    @Test
    fun `latest completed preserves exact content type and id matching`() {
        val result = WatchingState.latestCompletedBySeries(
            progressEntries = listOf(
                entry("show:1:8", 1, 8, isCompleted = true),
            ),
            watchedItems = listOf(
                watched("show", 2, 20L).copy(type = "tv"),
                watched("SHOW", 3, 30L),
                watched("show", 4, 40L),
            ),
        )

        assertEquals(
            mapOf(
                WatchingContentRef("series", "show") to WatchingCompletedEpisode(1, 8, 1L),
                WatchingContentRef("tv", "show") to WatchingCompletedEpisode(1, 2, 20L),
                WatchingContentRef("series", "SHOW") to WatchingCompletedEpisode(1, 3, 30L),
            ),
            result,
        )
    }

    @Test
    fun `latest completed resolves large watched histories with recent rewatches`() {
        val watched = buildList {
            for (episode in 1..80) {
                for (series in 1..200) {
                    add(watched("show:$series", episode, episode * 1_000L + series))
                }
            }
            for (series in 1..200) {
                add(watched("show:$series", 1, 100_000L + series))
            }
        }

        val furthest = WatchingState.latestCompletedBySeries(emptyList(), watched, preferFurthestEpisode = true)
        val latest = WatchingState.latestCompletedBySeries(emptyList(), watched, preferFurthestEpisode = false)

        assertEquals(200, furthest.size)
        assertEquals(200, latest.size)
        for (series in 1..200) {
            val content = WatchingContentRef("series", "show:$series")
            assertEquals(WatchingCompletedEpisode(1, 80, 80_000L + series), furthest[content])
            assertEquals(WatchingCompletedEpisode(1, 1, 100_000L + series), latest[content])
        }
    }

    private fun watched(id: String, episode: Int, markedAtEpochMs: Long): WatchedItem =
        WatchedItem(
            id = id,
            type = "series",
            name = id,
            season = 1,
            episode = episode,
            markedAtEpochMs = markedAtEpochMs,
        )

    private fun entry(
        videoId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        lastUpdatedEpochMs: Long = 1L,
        isCompleted: Boolean = false,
        progressPercent: Float? = null,
        source: String = "local",
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = "series",
            parentMetaId = "show",
            parentMetaType = "series",
            videoId = videoId,
            title = "Show",
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            isCompleted = isCompleted,
            progressPercent = progressPercent,
            source = source,
        )
}
