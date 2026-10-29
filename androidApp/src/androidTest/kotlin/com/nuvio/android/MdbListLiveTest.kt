package com.nuvio.android

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.mdblist.MdbListTracker
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun verifyRegisteredDeviceAccountAndLiveContracts() = runBlocking {
        assumeTrue(arguments.getString("mdblistLive") == "true")
        ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val tracker = MdbListTracker
            ensureTrackingProvidersRegistered()
            tracker.ensureLoaded()
            when (arguments.getString("phase")) {
                "connect" -> {
                    if (!tracker.auth.state.value.isAuthenticated) {
                        val uri = tracker.account.connect()
                        assertNotNull(uri)
                        Log.i(TAG, "Approve MDBList: $uri")
                        withTimeout(300_000) {
                            while (!tracker.auth.state.value.isAuthenticated) {
                                check(tracker.auth.state.value.error == null) { "Device authorization failed" }
                                delay(500)
                            }
                        }
                    }
                    tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                    assertTrue(tracker.sync.state.value.hasLoaded)
                    assertEquals(null, tracker.sync.state.value.error)
                    tracker.library.refresh(TrackingRefreshIntent.USER_INITIATED)
                    assertTrue(tracker.library.snapshot().hasLoaded)
                    record("device_authorization_and_reads_passed")
                }
                "exercise" -> {
                    assertTrue(tracker.auth.state.value.isAuthenticated)
                    tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
                    tracker.library.refresh(TrackingRefreshIntent.USER_INITIATED)
                    exerciseLibrary()
                    exercisePlayback()
                    record("library_and_playback_contracts_passed")
                }
                "reload" -> {
                    assertTrue(tracker.auth.state.value.isAuthenticated)
                    tracker.sync.ensureLoaded()
                    assertTrue(tracker.sync.state.value.hasLoaded)
                    assertTrue(tracker.library.snapshot().hasLoaded)
                    record("encrypted_account_and_cache_survive_process_restart")
                }
                "disconnect" -> {
                    tracker.account.disconnect()
                    assertFalse(tracker.auth.state.value.isAuthenticated)
                    assertFalse(tracker.account.status.value.revokeFailed)
                    assertTrue(tracker.progressProvider.snapshot().entries.isEmpty())
                    assertTrue(tracker.library.snapshot().items.isEmpty())
                    record("local_disconnect_and_remote_revocation_passed")
                }
                "disconnected" -> {
                    assertFalse(tracker.auth.state.value.isAuthenticated)
                    assertTrue(tracker.library.snapshot().items.isEmpty())
                    record("disconnect_survives_process_restart")
                }
                else -> error("Choose a live test phase")
            }
        }
    }

    private suspend fun exerciseLibrary() {
        val library = MdbListTracker.library
        val name = "Mobile QA ${System.currentTimeMillis()}"
        val movie = LibraryItem("tt0000417", "movie", "A Trip to the Moon", savedAtEpochMs = 0, imdbId = "tt0000417", tmdbId = 775)
        val manager = library.listManager
        var key: String? = null
        val hadWatchlist = library.getMembershipSnapshot(movie)["mdblist:watchlist"] == true
        try {
            manager.createList(name, null, LibraryListPrivacy.PRIVATE)
            key = library.snapshot().tabs.single { it.title == name }.key
            library.applyMembershipChanges(movie, mapOf(key to true))
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(library.getMembershipSnapshot(movie)[key] == true)
            manager.updateList(key, "$name renamed", null, LibraryListPrivacy.PUBLIC)
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            val updated = library.snapshot().tabs.single { it.key == key }
            assertEquals("$name renamed", updated.title)
            assertEquals(LibraryListPrivacy.PUBLIC, updated.privacy)
            library.applyMembershipChanges(movie, mapOf(key to false))
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertFalse(library.getMembershipSnapshot(movie)[key] == true)
            if (!hadWatchlist) {
                library.applyMembershipChanges(movie, mapOf("mdblist:watchlist" to true))
                library.refresh(TrackingRefreshIntent.USER_INITIATED)
                assertTrue(library.getMembershipSnapshot(movie)["mdblist:watchlist"] == true)
            }
        } finally {
            if (!hadWatchlist) library.applyMembershipChanges(movie, mapOf("mdblist:watchlist" to false))
            key?.let { manager.deleteList(it) }
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(library.snapshot().tabs.none { it.key == key })
            assertEquals(hadWatchlist, library.getMembershipSnapshot(movie)["mdblist:watchlist"] == true)
        }
        record("static_create_membership_rename_visibility_delete_and_watchlist_restored")
    }

    private suspend fun exercisePlayback() {
        val tracker = MdbListTracker
        val initial = requireNotNull(tracker.sync.state.value.snapshot)
        val id = "tt0000417"
        if (initial.watched.any { it.media.ids.imdb == id } || initial.playback.any { it.media.ids.imdb == id }) {
            record("playback_fixture_already_present_left_unchanged")
            return
        }
        assertEquals(listOf(TrackingProviderId.MDBLIST), TrackingProviderRegistry.connectedScrobblers().map { it.providerId })
        val profile = tracker.auth.state.value.scope.profileId
        val media = TrackingMediaReference(TrackingMediaKind.MOVIE, "A Trip to the Moon", 1902,
            TrackingExternalIds(imdb = id, tmdb = 775))
        suspend fun send(action: TrackingScrobbleAction, progress: Double, seek: Boolean = false) {
            val event = TrackingScrobbleEvent(media, progress)
            val failures = if (seek) TrackingScrobbleCoordinator.scrobbleSeek(profile, action, event)
                else TrackingScrobbleCoordinator.scrobble(profile, action, event)
            assertTrue("Scrobble must succeed", failures.isEmpty())
        }
        try {
            send(TrackingScrobbleAction.START, 0.0)
            send(TrackingScrobbleAction.STOP, 12.05, true)
            send(TrackingScrobbleAction.START, 25.0, true)
            send(TrackingScrobbleAction.PAUSE, 50.45989227294922)
            assertTrue(requireNotNull(tracker.sync.state.value.snapshot).playback.any { it.media.ids.imdb == id })
            tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            val paused = requireNotNull(tracker.sync.state.value.snapshot).playback.single { it.media.ids.imdb == id }
            assertEquals(50.45f, paused.progress, 0.001f)
            val entries = tracker.progressProvider.snapshot().entries.filter { it.parentMetaId == id }
            assertTrue(entries.isNotEmpty())
            tracker.progressProvider.removeProgress(entries)
            tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(requireNotNull(tracker.sync.state.value.snapshot).playback.none { it.media.ids.imdb == id })
            send(TrackingScrobbleAction.START, 60.0)
            send(TrackingScrobbleAction.STOP, 80.0)
            tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(requireNotNull(tracker.sync.state.value.snapshot).watched.any { it.media.ids.imdb == id })
            assertTrue(tracker.writes.removeFromHistory(profile, listOf(media)).isComplete)
            assertTrue(tracker.writes.addToHistory(profile, listOf(TrackingHistoryItem(media, System.currentTimeMillis()))).isComplete)
        } finally {
            tracker.progressProvider.removeProgress(tracker.progressProvider.snapshot().entries.filter { it.parentMetaId == id })
            tracker.writes.removeFromHistory(profile, listOf(media))
            tracker.sync.refresh(TrackingRefreshIntent.USER_INITIATED)
            val restored = requireNotNull(tracker.sync.state.value.snapshot)
            assertTrue(restored.watched.none { it.media.ids.imdb == id })
            assertTrue(restored.playback.none { it.media.ids.imdb == id })
        }
        record("start_seek_pause_precision_resume_clear_completion_history_and_cleanup")
    }

    private fun record(message: String) {
        Log.i(TAG, message)
        File(instrumentation.targetContext.cacheDir, "mdblist-live-results.txt").appendText("$message\n")
    }

    companion object {
        private const val TAG = "MDBListQa"
    }
}
