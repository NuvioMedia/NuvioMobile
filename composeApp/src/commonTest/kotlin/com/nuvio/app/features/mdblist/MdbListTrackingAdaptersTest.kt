package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import com.nuvio.app.features.watched.watchedItemKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdbListTrackingAdaptersTest {
    @Test
    fun progressAndWatchedPortsExposeCachedEpisodesAndClearOnProfileChange() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seed(h.snapshot().copy(watched = listOf(mdbListTestEpisode())))
        h.repository.ensureLoaded()
        val scrobble = MdbListScrobbleService(h.http.api, h.repository)
        val progress = MdbListTrackingProgressProvider(h.repository, scrobble, h.http.store, h.activeProfile, {})
        val watched = MdbListWatchedSyncAdapter(h.repository, MdbListHistoryService(h.http.api, h.repository), h.http.store, h.activeProfile)
        assertTrue(progress.snapshot().entries.single().isCompleted)
        assertTrue(progress.ownsCompletedHistoryProjection)
        assertEquals(setOf("tt1", "imdb:tt1", "tmdb:1"), progress.showIdSiblings()["tt1"])
        assertEquals("tt1:1:1", watched.pull(1, 100).single().videoId)
        assertTrue(watchedItemKey("series", "tmdb:1", 1, 1) in watched.pullExtraWatchedKeys(1))
        h.switch(2, 99)
        assertTrue(progress.snapshot().entries.isEmpty())
        expectMdbListFailure<CancellationException> { watched.pull(1, 100) }
    }

    @Test
    fun sharedScrobblerUsesSeekPolicyAndRejectsWrongProfileBeforeWriting() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        val writer = MdbListTrackingWrites(h.repository, MdbListHistoryService(h.http.api, h.repository),
            MdbListScrobbleService(h.http.api, h.repository))
        assertEquals(TrackingSeekScrobblePolicy.STOP_AND_RESTART, writer.seekScrobblePolicy)
        val event = TrackingScrobbleEvent(TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 1)), 50.45989227294922)
        expectMdbListFailure<CancellationException> { writer.scrobble(2, TrackingScrobbleAction.PAUSE, event) }
        assertTrue(h.http.engine.requests.isEmpty())
        h.http.reply(body = """{"action":"pause","progress":50.45,"paused_at":"$MDBLIST_TEST_TIME"}""")
        writer.scrobble(1, TrackingScrobbleAction.PAUSE, event)
        assertEquals("50.45", mdbListResponseElement(h.http.engine.requests.single().body).objectValue().text("progress"))
        assertEquals(50.45f, h.repository.currentProjection().progress.single().progressPercent)
    }

    @Test
    fun libraryPortRetainsDistinctTabsAndHonorsMembershipProfile() = runTest {
        val h = MdbListSyncTestHarness(backgroundScope)
        h.seedLibrary()
        h.repository.ensureLoaded()
        val provider = MdbListTrackingLibraryProvider(h.libraryService(backgroundScope), h.repository, {})
        assertEquals(listOf(MDBLIST_WATCHLIST_KEY, MDBLIST_TEST_LIST_KEY), provider.snapshot().tabs.map { it.key })
        assertTrue(provider.toggledDefaultMembership(emptyMap())[MDBLIST_WATCHLIST_KEY] == true)
        expectMdbListFailure<CancellationException> {
            provider.applyMembership(2, com.nuvio.app.features.library.LibraryItem("tt1", "movie", "Movie", savedAtEpochMs = 0),
                mapOf(MDBLIST_WATCHLIST_KEY to true), false)
        }
        assertTrue(h.http.engine.requests.isEmpty())
    }
}
