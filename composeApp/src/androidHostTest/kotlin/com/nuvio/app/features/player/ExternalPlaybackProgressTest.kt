package com.nuvio.app.features.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressCodec
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExternalPlaybackProgressTest {
    private val source = "https://example.com/movie.mkv"
    private fun session(profileId: Int = ProfileRepository.activeProfileId) = WatchProgressPlaybackSession(
        profileId = profileId, contentType = "movie", parentMetaId = "tt123", parentMetaType = "movie",
        videoId = "tt123", title = "Movie", lastSourceUrl = source,
    )

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_watch_progress", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        WatchProgressStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        ExternalPlayerPlatform.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun infuseReturnWithoutDurationPersistsResumeWithoutMarkingWatched() = runBlocking {
        val result = infuseResult(positionSeconds = 1800, durationMs = null)
        recordExternalPlaybackProgress(result, fallbackSession = null)
        WatchProgressRepository.clearLocalState()
        val entry = assertNotNull(WatchProgressRepository.progressForVideo("tt123"))
        assertEquals(1_800_000L, entry.lastPositionMs)
        assertEquals(0L, entry.durationMs)
        assertFalse(entry.isCompleted)
        assertTrue(entry.isResumable)
    }

    @Test
    fun infuseUsesKnownDurationToRecognizeCompletion() = runBlocking {
        recordExternalPlaybackProgress(infuseResult(3300, 3_600_000L), fallbackSession = null)
        val entry = assertNotNull(WatchProgressRepository.progressForVideo("tt123"))
        assertTrue(entry.isCompleted)
        assertEquals(3_600_000L, entry.durationMs)
    }

    @Test
    fun infuseReturnIsSavedForItsOriginalProfile() = runBlocking {
        val original = session(profileId = 99)
        recordExternalPlaybackProgress(infuseResult(1800, null, original), fallbackSession = session())
        val entries = WatchProgressCodec.decodeEntries(assertNotNull(WatchProgressStorage.loadPayload(99)))
        assertEquals(1_800_000L, entries.single().lastPositionMs)
        assertTrue(WatchProgressRepository.uiState.value.entries.isEmpty())
    }

    @Test
    fun androidStillSendsResumePositionAndRecordsCompatiblePlayerResults() = runBlocking {
        val request = ExternalPlayerPlaybackRequest(sourceUrl = source, title = "Movie", resumePositionMs = 125_000L)
        val built = assertIs<ExternalPlayerIntentResult.Success>(ExternalPlayerPlatform.buildIntent(request, "android_system"))
        val intent = assertIs<Intent>(built.intent)
        assertEquals(125_000, intent.getIntExtra("position", 0))
        assertEquals(125_000, intent.getIntExtra("startfrom", 0))
        assertTrue(intent.getBooleanExtra("return_result", false))
        val returned = Intent().apply {
            putExtra("position", 1_800_000)
            putExtra("duration", 3_600_000)
            putExtra("end_by", "user")
        }
        val result = assertNotNull(ExternalPlayerActivityContract().parseResult(Activity.RESULT_OK, returned))
        recordExternalPlaybackProgress(
            ExternalPlaybackResult(result.positionMs, result.durationMs, result.endedByUser),
            fallbackSession = session(),
        )
        val entry = assertNotNull(WatchProgressRepository.progressForVideo("tt123"))
        assertEquals(1_800_000L, entry.lastPositionMs)
        assertEquals(3_600_000L, entry.durationMs)
        assertFalse(entry.isCompleted)
    }

    @Test
    fun mxPlayerCompletionWithoutPositionIsRecordedAsWatched() = runBlocking {
        // MX Player omits "position" and "duration" when playback runs to the end.
        val returned = Intent().apply { putExtra("end_by", "playback_completion") }
        val result = assertNotNull(ExternalPlayerActivityContract().parseResult(Activity.RESULT_OK, returned))
        assertFalse(result.endedByUser)
        recordExternalPlaybackProgress(
            ExternalPlaybackResult(result.positionMs, result.durationMs, result.endedByUser),
            fallbackSession = session(),
        )
        val entry = assertNotNull(WatchProgressRepository.progressForVideo("tt123"))
        assertTrue(entry.isCompleted)
    }

    @Test
    fun mxPlayerCompletionWithoutPositionIsDroppedOnErrorResult() {
        val returned = Intent().apply { putExtra("end_by", "playback_completion") }
        assertNull(ExternalPlayerActivityContract().parseResult(Activity.RESULT_FIRST_USER, returned))
    }

    private fun infuseResult(
        positionSeconds: Int,
        durationMs: Long?,
        playbackSession: WatchProgressPlaybackSession = session(),
    ): ExternalPlaybackResult {
        val callbacks = InfusePlaybackCallbacks(
            load = PlayerSettingsStorage::loadPendingExternalPlayback,
            save = PlayerSettingsStorage::savePendingExternalPlayback,
        )
        val launch = Url(callbacks.prepare(ExternalPlayerPlaybackRequest(
            sourceUrl = source, title = "Movie", playbackSession = playbackSession, durationMs = durationMs,
        )))
        callbacks.handleUrl("${launch.parameters["x-success"]}?lastPlayedUrl=${source.encodeURLParameter()}&position=$positionSeconds")
        return assertNotNull(callbacks.results.value?.playbackResult())
    }
}
