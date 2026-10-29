package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.watched.watchedItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdbListProgressProjectionTest {
    @Test
    fun playbackUsesRuntimeAndStopsAtProviderCompletionThreshold() {
        val playback = mdbListTestPlayback(79.99f).copy(media = mdbListTestMovie().media.copy(runtimeMinutes = 100))
        val entry = MdbListProgressProjection(MdbListSyncSnapshot(42, playback = listOf(playback))).progress.single()
        assertEquals(6_000_000, entry.durationMs)
        assertEquals((6_000_000L * 79.99f.toDouble() / 100).toLong(), entry.lastPositionMs)
        assertFalse(entry.isEffectivelyCompleted)
        assertEquals("mdblist_playback", entry.source)
        assertTrue(MdbListProgressProjection(MdbListSyncSnapshot(42, playback = listOf(playback.copy(progress = 80f)))).progress.isEmpty())
    }

    @Test
    fun completedEpisodesProvideExactAliasesAndNextUpSeeds() {
        val record = mdbListTestEpisode(2, 4)
        val projection = MdbListProgressProjection(MdbListSyncSnapshot(42, watched = listOf(record)))
        assertTrue(watchedItemKey("series", "tmdb:1", 2, 4) in projection.watchedKeys)
        assertFalse(watchedItemKey("series", "tt1", 2, 5) in projection.watchedKeys)
        assertEquals("tt1:2:4", projection.watchedItems.single().videoId)
        assertTrue(projection.nextUpSeeds.single().isEffectivelyCompleted)
        assertEquals(2, projection.nextUpSeeds.single().seasonNumber)
    }

    @Test
    fun droppedShowsAndSeasonsHideOnlyMatchingProgress() {
        val playback = mdbListTestPlayback().copy(type = MdbListItemType.EPISODE, season = 2, episode = 4)
        val snapshot = MdbListSyncSnapshot(42, watched = listOf(mdbListTestEpisode()), playback = listOf(playback),
            dropped = listOf(MdbListDroppedRecord(MdbListIds(tmdb = 1), 2)))
        val projection = MdbListProgressProjection(snapshot)
        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.nextUpSeeds.isNotEmpty())
        assertTrue(projection.isHidden("tt1", 2))
        assertFalse(projection.isHidden("tt1", 1))
        val dropped = MdbListProgressProjection(snapshot.copy(dropped = listOf(MdbListDroppedRecord(MdbListIds(tmdb = 1)))))
        assertTrue(dropped.nextUpSeeds.isEmpty())
        assertTrue("tt1" in dropped.hiddenContentIds)
    }

    @Test
    fun newerRewatchRetainsResumeButStalePlaybackDoesNot() {
        val snapshot = MdbListSyncSnapshot(42, watched = listOf(mdbListTestMovie()), playback = listOf(mdbListTestPlayback()))
        assertTrue(MdbListProgressProjection(snapshot).progress.isEmpty())
        val newer = snapshot.copy(playback = listOf(mdbListTestPlayback(timestamp = "2026-09-06T01:00:00Z")))
        assertEquals(1, MdbListProgressProjection(newer).progress.size)
    }

    @Test
    fun percentTruncationPreservesEveryValidHundredthAndNeverCrossesCompletion() {
        val target = MdbListSyncSnapshot(42).mutationTarget(TrackingMediaReference(
            TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 1)))!!
        for (hundredths in 0..10_000) {
            val value = hundredths / 100.0
            assertEquals(value, target.scrobbleBody(value).text("progress")!!.toDouble())
        }
        assertEquals(79.99, target.scrobbleBody(79.999999).text("progress")!!.toDouble())
        assertEquals(0.0, target.scrobbleBody(0.00001).text("progress")!!.toDouble())
    }
}
