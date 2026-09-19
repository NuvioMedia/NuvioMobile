package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.cloud.TorboxCloudLibraryPosterUrl
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchProgressRulesTest {

    @Test
    fun `codec round trips entries in descending updated order`() {
        val older = entry(videoId = "movie-1", lastUpdatedEpochMs = 100L)
        val newer = entry(videoId = "movie-2", lastUpdatedEpochMs = 200L)

        val payload = WatchProgressCodec.encodeEntries(listOf(older, newer))
        val decoded = WatchProgressCodec.decodeEntries(payload)

        assertEquals(listOf("movie-2", "movie-1"), decoded.map { it.videoId })
    }

    @Test
    fun `codec persists dirty progress keys and drops keys without a stored row`() {
        val payload = WatchProgressCodec.encodePayload(
            entries = listOf(entry(videoId = "show:1:2", progressKey = "opaque-key")),
            lastSuccessfulPushEpochMs = 0L,
            deltaCursorEventId = 0L,
            deltaInitialized = false,
            dirtyProgressKeys = setOf("opaque-key", "missing-key"),
        )

        val decoded = WatchProgressCodec.decodePayload(payload)

        assertEquals(setOf("opaque-key"), decoded.dirtyProgressKeys)
    }

    @Test
    fun `codec ignores corrupt payload`() {
        assertTrue(WatchProgressCodec.decodeEntries("{not json").isEmpty())
    }

    @Test
    fun `save threshold starts after one second`() {
        assertFalse(shouldStoreWatchProgress(positionMs = 999L, durationMs = 600_000L))
        assertTrue(shouldStoreWatchProgress(positionMs = 1_000L, durationMs = 600_000L))
        assertTrue(shouldStoreWatchProgress(positionMs = 1_000L, durationMs = 0L))
    }

    @Test
    fun `completion detects watched threshold remaining time and ended state`() {
        assertTrue(isWatchProgressComplete(positionMs = 920_000L, durationMs = 1_000_000L, isEnded = false))
        assertTrue(isWatchProgressComplete(positionMs = 900_000L, durationMs = 1_000_000L, isEnded = false))
        assertFalse(isWatchProgressComplete(positionMs = 899_999L, durationMs = 1_000_000L, isEnded = false))
        assertTrue(isWatchProgressComplete(positionMs = 1L, durationMs = 0L, isEnded = true))
        assertFalse(isWatchProgressComplete(positionMs = 200_000L, durationMs = 1_000_000L, isEnded = false))
    }

    @Test
    fun `resume entry for series picks most recent episode`() {
        val older = entry(videoId = "show:1:1", parentMetaId = "show", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 10L)
        val newer = entry(videoId = "show:1:2", parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, lastUpdatedEpochMs = 20L)
        val other = entry(videoId = "movie", parentMetaId = "movie", lastUpdatedEpochMs = 30L)

        val result = listOf(older, newer, other).resumeEntryForSeries("show")

        assertEquals("show:1:2", result?.videoId)
    }

    @Test
    fun `resume entry returns null when no series entries exist`() {
        val result = listOf(entry(videoId = "movie", parentMetaId = "movie")).resumeEntryForSeries("show")

        assertNull(result)
    }

    @Test
    fun `continue watching entries are sorted and capped`() {
        val entries = (1..25).map { index ->
            entry(videoId = "video-$index", lastUpdatedEpochMs = index.toLong())
        }

        val result = entries.continueWatchingEntries()

        assertEquals(20, result.size)
        assertEquals("video-25", result.first().videoId)
        assertEquals("video-6", result.last().videoId)
    }

    @Test
    fun `continue watching deduplicates series keeping latest episode per show`() {
        val ep1 = entry(videoId = "show:1:1", parentMetaId = "show", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 10L)
        val ep2 = entry(videoId = "show:1:2", parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, lastUpdatedEpochMs = 20L)
        val movie = entry(videoId = "movie-1", parentMetaId = "movie-1", lastUpdatedEpochMs = 15L)

        val result = listOf(ep1, ep2, movie).continueWatchingEntries()

        assertEquals(2, result.size)
        assertEquals("show:1:2", result.first().videoId)
        assertEquals("movie-1", result.last().videoId)
    }

    @Test
    fun `continue watching keeps multiple movies without deduplication`() {
        val m1 = entry(videoId = "movie-a", parentMetaId = "movie-a", lastUpdatedEpochMs = 10L)
        val m2 = entry(videoId = "movie-b", parentMetaId = "movie-b", lastUpdatedEpochMs = 20L)

        val result = listOf(m1, m2).continueWatchingEntries()

        assertEquals(2, result.size)
    }

    @Test
    fun `cloud continue watching uses provider poster fallback`() {
        val item = WatchProgressEntry(
            contentType = "cloud",
            parentMetaId = "torbox:Torrent:29773238",
            parentMetaType = "cloud",
            videoId = "torbox:Torrent:29773238:8",
            title = "Cloud file",
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = 1L,
        ).toContinueWatchingItem()

        assertEquals(TorboxCloudLibraryPosterUrl, item.poster)
        assertEquals(TorboxCloudLibraryPosterUrl, item.imageUrl)
    }

    @Test
    fun `continue watching excludes explicit 100 percent entries even when completion flag is false`() {
        val completedByPercent = entry(
            videoId = "movie-complete",
            lastUpdatedEpochMs = 20L,
            lastPositionMs = 0L,
            durationMs = 0L,
            isCompleted = false,
            progressPercent = 100f,
        )
        val inProgress = entry(
            videoId = "movie-progress",
            lastUpdatedEpochMs = 10L,
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            progressPercent = 12f,
        )

        val result = listOf(completedByPercent, inProgress).continueWatchingEntries()

        assertEquals(listOf("movie-progress"), result.map { it.videoId })
    }

    @Test
    fun `continue watching drops stale resume when a newer series episode is completed`() {
        val inProgress = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 10L,
        )
        val completed = entry(
            videoId = "show:1:5",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 20L,
            isCompleted = true,
        )

        val result = listOf(inProgress, completed).continueWatchingEntries()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `continue watching keeps legitimate resume when it is newer than completed series progress`() {
        val completed = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 10L,
            isCompleted = true,
        )
        val inProgress = entry(
            videoId = "show:1:5",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 20L,
        )

        val result = listOf(completed, inProgress).continueWatchingEntries()

        assertEquals(listOf("show:1:5"), result.map { it.videoId })
    }

    @Test
    fun `resume entry for series returns null when newer overall progress is completed`() {
        val staleResume = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 10L,
        )
        val completed = entry(
            videoId = "show:1:5",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 20L,
            isCompleted = true,
        )

        assertNull(listOf(staleResume, completed).resumeEntryForSeries("show"))
    }

    @Test
    fun `resume entry coalesces series type variants independent of input order`() {
        val staleResume = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            parentMetaType = "SeRiEs",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 10L,
        )
        val completed = entry(
            videoId = "show:1:5",
            parentMetaId = "show",
            parentMetaType = "TV",
            seasonNumber = 1,
            episodeNumber = 5,
            lastUpdatedEpochMs = 20L,
            isCompleted = true,
        )

        assertNull(listOf(staleResume, completed).resumeEntryForSeries("show"))
        assertNull(listOf(completed, staleResume).resumeEntryForSeries("show"))
    }

    @Test
    fun `resume entry ignores a movie with the same content id`() {
        val movie = entry(
            videoId = "shared-movie",
            parentMetaId = "shared",
            parentMetaType = "movie",
            lastUpdatedEpochMs = 30L,
        )
        val seriesResume = entry(
            videoId = "shared:1:1",
            parentMetaId = "shared",
            parentMetaType = "Show",
            seasonNumber = 1,
            episodeNumber = 1,
            lastUpdatedEpochMs = 20L,
        )

        val forward = listOf(movie, seriesResume).resumeEntryForSeries("shared")
        val reversed = listOf(seriesResume, movie).resumeEntryForSeries("shared")

        assertEquals(seriesResume.resolvedProgressKey(), forward?.resolvedProgressKey())
        assertEquals(seriesResume.resolvedProgressKey(), reversed?.resolvedProgressKey())
    }

    @Test
    fun `continue watching does not cross map aliases that share a video id`() {
        val activeShow = entry(
            videoId = "shared-video",
            parentMetaId = "active-show",
            seasonNumber = 1,
            episodeNumber = 1,
            lastUpdatedEpochMs = 30L,
            progressKey = "active-show_s1e1",
        )
        val staleAlias = entry(
            videoId = "shared-video",
            parentMetaId = "completed-show",
            seasonNumber = 1,
            episodeNumber = 1,
            lastUpdatedEpochMs = 10L,
            progressKey = "completed-show_s1e1",
        )
        val completedAlias = entry(
            videoId = "completed-show:1:2",
            parentMetaId = "completed-show",
            seasonNumber = 1,
            episodeNumber = 2,
            lastUpdatedEpochMs = 20L,
            isCompleted = true,
            progressKey = "completed-show_s1e2",
        )

        val result = listOf(staleAlias, activeShow, completedAlias).continueWatchingEntries()

        assertEquals(listOf("active-show_s1e1"), result.map { it.resolvedProgressKey() })
    }

    @Test
    fun `Trakt history is not treated as active resume`() {
        val history = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastPositionMs = 1L,
            durationMs = 0L,
            progressPercent = 50f,
            source = WatchProgressSourceTraktHistory,
        )

        assertFalse(history.shouldTreatAsInProgressForContinueWatching())
    }

    @Test
    fun `Trakt playback does not replace watched history when watched timestamp is newer`() {
        val watched = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 3_000L,
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktHistory,
        )
        val playback = watched.copy(
            lastUpdatedEpochMs = 1_000L,
            isCompleted = false,
            progressPercent = 25f,
            source = WatchProgressSourceTraktPlayback,
        )

        assertFalse(shouldReplaceProgressSnapshotEntry(existing = watched, candidate = playback))
        assertTrue(shouldReplaceProgressSnapshotEntry(existing = playback, candidate = watched))
    }

    @Test
    fun `Trakt playback replaces watched history when playback timestamp is newer`() {
        val watched = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 1_000L,
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktHistory,
        )
        val playback = watched.copy(
            lastUpdatedEpochMs = 3_000L,
            isCompleted = false,
            progressPercent = 25f,
            source = WatchProgressSourceTraktPlayback,
        )

        assertTrue(shouldReplaceProgressSnapshotEntry(existing = watched, candidate = playback))
        assertFalse(shouldReplaceProgressSnapshotEntry(existing = playback, candidate = watched))
    }

    @Test
    fun `Trakt playback uses TV timestamp tolerance against watched history`() {
        val watched = entry(
            videoId = "show:1:4",
            parentMetaId = "show",
            seasonNumber = 1,
            episodeNumber = 4,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
            progressPercent = 100f,
            source = WatchProgressSourceTraktHistory,
        )
        val insideTolerance = watched.copy(
            lastUpdatedEpochMs = 1_001L,
            isCompleted = false,
            progressPercent = 25f,
            source = WatchProgressSourceTraktPlayback,
        )
        val outsideTolerance = insideTolerance.copy(lastUpdatedEpochMs = 999L)

        assertTrue(shouldReplaceProgressSnapshotEntry(existing = watched, candidate = insideTolerance))
        assertFalse(shouldReplaceProgressSnapshotEntry(existing = watched, candidate = outsideTolerance))
    }

    @Test
    fun `completed progress does not cascade when provider owns watched projection`() {
        val completed = entry(
            videoId = "movie-complete",
            isCompleted = true,
        )
        val inProgress = completed.copy(isCompleted = false)

        assertFalse(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = completed,
                providerOwnsCompletedHistory = true,
            ),
        )
        assertTrue(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = completed,
                providerOwnsCompletedHistory = false,
            ),
        )
        assertFalse(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = inProgress,
                providerOwnsCompletedHistory = false,
            ),
        )
    }

    @Test
    fun `codec normalizes completed entries inferred from percent`() {
        val payload = WatchProgressCodec.encodeEntries(
            listOf(
                entry(
                    videoId = "movie-complete",
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    isCompleted = false,
                    progressPercent = 100f,
                ),
            ),
        )

        val decoded = WatchProgressCodec.decodeEntries(payload)

        assertEquals(1, decoded.size)
        assertTrue(decoded.single().isCompleted)
    }

    @Test
    fun `build playback video id uses season and episode when present`() {
        assertEquals("show:1:2", buildPlaybackVideoId(parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, fallbackVideoId = "fallback"))
        assertEquals("fallback", buildPlaybackVideoId(parentMetaId = "movie", seasonNumber = null, episodeNumber = null, fallbackVideoId = "fallback"))
        assertEquals("movie", buildPlaybackVideoId(parentMetaId = "movie", seasonNumber = null, episodeNumber = null, fallbackVideoId = null))
    }

    @Test
    fun `up next continue watching uses actual episode id when available`() {
        val item = entry(
            videoId = "kitsu:244:1",
            parentMetaId = "kitsu:244",
            seasonNumber = 1,
            episodeNumber = 1,
        ).toUpNextContinueWatchingItem(
            MetaVideo(
                id = "kitsu:244:2",
                title = "Episode 2",
                season = 1,
                episode = 2,
            ),
        )

        assertEquals("kitsu:244:2", item.videoId)
    }

    @Test
    fun `parseReleaseDateToEpochMs handles ISO and date-only formats`() {
        val t1 = parseReleaseDateToEpochMs("2026-05-24T15:00:00Z")
        assertEquals(1779634800000L, t1)

        val t2 = parseReleaseDateToEpochMs("2026-05-24")
        assertEquals(parseTraktIsoDateTimeToEpochMs("2026-05-24T00:00:00Z"), t2)

        assertNull(parseReleaseDateToEpochMs(null))
        assertNull(parseReleaseDateToEpochMs("   "))
        assertNull(parseReleaseDateToEpochMs("invalid-date"))
    }

    @Test
    fun `a playback that stopped before the credits is not a finished watch`() {
        val beforeCredits = entry(
            videoId = "show:1:2",
            seasonNumber = 1,
            episodeNumber = 2,
            lastPositionMs = 940_000L,
            durationMs = 1_000_000L,
        ).copy(completionFraction = 0.95f)

        assertFalse(beforeCredits.isEffectivelyCompleted)

        val insideCredentials = beforeCredits.copy(lastPositionMs = 960_000L)

        assertTrue(insideCredentials.isEffectivelyCompleted)
    }

    @Test
    fun `a movie a provider paused inside the credits is still a position to resume`() {
        val row = entry(
            videoId = "tt0133093",
            parentMetaType = "movie",
            lastPositionMs = 100_000_000L,
            durationMs = 104_000_000L,
        ).copy(
            progressPercent = 96f,
            source = WatchProgressSourceSimklPlayback,
        )

        // The credits marker is what ends a playback, and a row a provider publishes does not carry it,
        // so the row stays a resume point instead of leaving Continue Watching as a finished watch.
        assertFalse(row.isEffectivelyCompleted)
        assertEquals(0.96f, row.progressFraction, 0.0005f)
    }

    private fun entry(
        videoId: String,
        parentMetaId: String = videoId.substringBefore(':'),
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        parentMetaType: String = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
        lastUpdatedEpochMs: Long = 1L,
        lastPositionMs: Long = 120_000L,
        durationMs: Long = 1_000_000L,
        isCompleted: Boolean = false,
        progressPercent: Float? = null,
        source: String = WatchProgressSourceLocal,
        progressKey: String? = null,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = parentMetaType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = "Title",
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            lastPositionMs = lastPositionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            isCompleted = isCompleted,
            progressPercent = progressPercent,
            source = source,
            progressKey = progressKey,
        )
}
