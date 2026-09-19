package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class MdbListSyncEngineTest {
    @Test
    fun `bootstrap fetches required buckets once and saves server time rather than device clock`() = runTest {
        val remote = Remote()
        remote.watched = listOf(movie())

        val result = MdbListSyncEngine(remote) { 999L }.synchronize(MdbListSyncSnapshot(42))

        assertEquals(listOf("activities", "watched", "playback", "dropped"), remote.calls)
        assertEquals(TIME, result.watermark)
        assertEquals(999L, result.checkedAtEpochMs)
        assertTrue(result.isInitialized)
        assertEquals(listOf(movie()), result.watched)
    }

    @Test
    fun `unchanged account only requests activities and reuses data projections`() = runTest {
        val previous = snapshot()
        val remote = Remote()

        val result = MdbListSyncEngine(remote).synchronize(previous)

        assertEquals(listOf("activities"), remote.calls)
        assertSame(previous.watched, result.watched)
        assertSame(previous.playback, result.playback)
        assertSame(previous.dropped, result.dropped)
    }

    @Test
    fun `unrelated library activity never triggers a history or playback request`() = runTest {
        val remote = Remote().apply { activities = activities.copy(values = activities.values + ("watchlisted_at" to TIME)) }
        MdbListSyncEngine(remote).synchronize(snapshot())
        assertEquals(listOf("activities"), remote.calls)
    }

    @Test
    fun `watched changes fetch journal alone with previous server watermark`() = runTest {
        val remote = Remote().apply {
            activities = activities.copy(values = activities.values + ("journal_at" to TIME))
            journal = MdbListPage(listOf(change(1, removed = true), change(2)))
        }

        val result = MdbListSyncEngine(remote).synchronize(snapshot())

        assertEquals(listOf("activities", "journal:$TIME"), remote.calls)
        assertEquals(listOf(2L), result.watched.map { it.media.ids.tmdb })
        assertEquals("2020-01-01T00:00:00Z", result.watched.single().watchedAt)
        assertEquals(TIME, result.watermark)
    }

    @Test
    fun `playback and dropped invalidations refresh only their own buckets`() = runTest {
        for ((key, expected) in listOf("paused_at" to "playback", "episode_paused_at" to "playback", "dropped_at" to "dropped")) {
            val remote = Remote().apply { activities = activities.copy(values = activities.values + (key to TIME)) }
            val result = MdbListSyncEngine(remote).synchronize(snapshot())
            assertEquals(listOf("activities", expected), remote.calls)
            assertEquals(1, result.watched.size)
        }
    }

    @Test
    fun `expired or future watermarks use complete snapshots without journal replay`() = runTest {
        for (watermark in listOf(null, "2026-08-01T00:00:00Z", "2026-09-07T00:00:00Z")) {
            val remote = Remote()
            MdbListSyncEngine(remote).synchronize(snapshot().copy(watermark = watermark))
            assertEquals(listOf("activities", "watched", "playback", "dropped"), remote.calls)
        }
    }

    @Test
    fun `server journal expiry falls back to authoritative watched replacement`() = runTest {
        val remote = Remote().apply {
            activities = activities.copy(values = activities.values + ("journal_at" to TIME))
            journal = MdbListPage(emptyList(), requiresFullSync = true)
            watched = listOf(movie(2))
        }
        val result = MdbListSyncEngine(remote).synchronize(snapshot())
        assertEquals(listOf("activities", "journal:$TIME", "watched"), remote.calls)
        assertEquals(listOf(2L), result.watched.map { it.media.ids.tmdb })
    }

    @Test
    fun `failure after successful watched read leaves original cache and watermark untouched`() = runTest {
        val original = snapshot().copy(isInitialized = false)
        val remote = Remote().apply { watched = listOf(movie(2)); failPlayback = true }

        expectMdbListFailure<IOException> { MdbListSyncEngine(remote).synchronize(original) }

        assertEquals(listOf(movie()), original.watched)
        assertEquals(TIME, original.watermark)
        assertFalse(original.isInitialized)
    }

    @Test
    fun `journal aliases retain titles and episode metadata across identifier changes`() {
        val known = movie().copy(type = MdbListItemType.EPISODE, season = 1, episode = 2, episodeTitle = "Second", episodeTmdbId = 200)
        val snapshot = snapshot().copy(watched = listOf(known))
        val update = change(1).copy(type = MdbListItemType.EPISODE, ids = MdbListIds(tmdb = 1, mdblist = "public"), season = 1, episode = 2)

        val result = applyMdbListJournal(snapshot, listOf(update)).single()

        assertEquals("Movie 1", result.media.title)
        assertEquals("tt1", result.media.ids.imdb)
        assertEquals("public", result.media.ids.mdblist)
        assertEquals("Second", result.episodeTitle)
        assertEquals(200L, result.episodeTmdbId)
    }

    @Test
    fun `show and season tombstones do not fabricate removals for unmentioned episodes`() {
        val episode = movie().copy(type = MdbListItemType.EPISODE, season = 1, episode = 2)
        val show = episode.copy(type = MdbListItemType.SHOW, season = null, episode = null)
        val season = episode.copy(type = MdbListItemType.SEASON, episode = null)
        val original = snapshot().copy(watched = listOf(show, season, episode))
        val tombstones = listOf(
            change(1, true).copy(type = MdbListItemType.SHOW),
            change(1, true).copy(type = MdbListItemType.SEASON, season = 1)
        )
        assertEquals(listOf(episode), applyMdbListJournal(original, tombstones))
    }

    @Test
    fun `movie and show sharing a TMDB number remain separate`() {
        val movie = movie()
        val episode = movie.copy(type = MdbListItemType.EPISODE, media = MdbListMedia(MdbListIds(tmdb = 1, imdb = "tt99"), "Series"), season = 1, episode = 1)
        val normalized = snapshot().copy(watched = listOf(movie, episode)).normalizeMedia()
        assertEquals(listOf("tt1", "tt99"), normalized.watched.map { it.media.ids.imdb })
    }

    private class Remote : MdbListSyncRemote {
        val calls = mutableListOf<String>()
        var activities = MdbListActivities(mapOf("watched_at" to null, "journal_at" to null), TIME)
        var watched = emptyList<MdbListWatchedRecord>()
        var journal = MdbListPage<MdbListJournalRecord>(emptyList())
        var failPlayback = false

        override suspend fun activities() = activities.also { calls += "activities" }
        override suspend fun watched() = watched.also { calls += "watched" }
        override suspend fun journal(since: String) = journal.also { calls += "journal:$since" }
        override suspend fun playback(): List<MdbListPlayback> {
            calls += "playback"
            if (failPlayback) throw IOException("Offline")
            return emptyList()
        }
        override suspend fun dropped() = emptyList<MdbListDroppedRecord>().also { calls += "dropped" }
    }

    private fun movie(id: Long = 1) = MdbListWatchedRecord(
        MdbListItemType.MOVIE, MdbListMedia(MdbListIds(imdb = "tt$id", tmdb = id), "Movie $id"), TIME
    )
    private fun snapshot() = MdbListSyncSnapshot(42, watched = listOf(movie()), activities = Remote().activities, watermark = TIME, isInitialized = true)
    private fun change(id: Long, removed: Boolean = false) = MdbListJournalRecord(
        MdbListItemType.MOVIE, MdbListIds(tmdb = id), removed, TIME, if (removed) null else "2020-01-01T00:00:00Z"
    )

    private companion object {
        const val TIME = "2026-09-06T00:00:00Z"
    }
}
