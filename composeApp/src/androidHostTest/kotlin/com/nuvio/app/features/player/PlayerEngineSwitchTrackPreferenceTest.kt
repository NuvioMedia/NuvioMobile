package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.ui.Modifier
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the audio/subtitle carry-over that happens when the playback engine switches, either
 * manually or through the automatic failover. Mirrors NuvioTV's engine-switch track preference
 * handling (`rememberCurrentTrackPreferenceForEngineSwitch` /
 * `clearPendingEngineSwitchTrackPreference`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerEngineSwitchTrackPreferenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_player_track_preferences", "nuvio_watch_progress").forEach {
            context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerTrackPreferenceStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        WatchProgressRepository.clearLocalState()
    }

    @Test
    fun switchingEngineRestoresTheAudioTrackOfTheOldEngine() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
                AudioTrack(index = 1, id = "2", label = "Japanese", language = "ja"),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        // libmpv reports numeric ids and a different ordering, so only the language can match.
        val newEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.Libmpv,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "Japanese", language = "ja"),
                AudioTrack(index = 1, id = "2", label = "English", language = "en"),
            ),
        )
        runtime.playerController = newEngine

        runtime.refreshTracks()

        // The English track now sits at index 1 in the new engine's list.
        assertEquals(listOf(1), newEngine.audioSelections)
        assertEquals(1, runtime.selectedAudioIndex)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun switchingEngineRestoresTheSubtitleTrackOfTheOldEngine() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedSubtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "1", label = "English", language = "en"),
                SubtitleTrack(index = 1, id = "2", label = "French", language = "fr", isSelected = true),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        val newEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.Libmpv,
            exposedSubtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "1", label = "French", language = "fr"),
                SubtitleTrack(index = 1, id = "2", label = "English", language = "en"),
            ),
        )
        runtime.playerController = newEngine

        runtime.refreshTracks()

        assertEquals(listOf(0), newEngine.subtitleSelections)
        assertEquals(0, runtime.selectedSubtitleIndex)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun switchingEngineKeepsSubtitlesDisabledWhenTheyWereTurnedOff() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedSubtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "1", label = "English", language = "en"),
            ),
        )
        runtime.isUserExplicitSubtitleSelection = true
        runtime.selectedSubtitleIndex = -1

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        val newEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.Libmpv,
            exposedSubtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "1", label = "English", language = "en"),
            ),
        )
        runtime.playerController = newEngine

        runtime.refreshTracks()

        assertEquals(listOf(-1), newEngine.subtitleSelections)
        assertEquals(-1, runtime.selectedSubtitleIndex)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun carryOverIsNotAppliedToTheEngineItWasCapturedOn() {
        val runtime = runtime()
        val oldEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )
        runtime.playerController = oldEngine

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        runtime.refreshTracks()

        // The old engine is still the one attached: nothing is selected explicitly.
        assertEquals(emptyList(), oldEngine.audioSelections)
        assertNotNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun carryOverIsDroppedWhenTheSourceChanges() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        runtime.activeSourceUrl = "https://example.com/other.mp4"
        val newEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.Libmpv,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en"),
            ),
        )
        runtime.playerController = newEngine

        runtime.refreshTracks()

        assertEquals(emptyList(), newEngine.audioSelections)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun carryOverKeepsWaitingUntilTheNewEngineExposesItsTracks() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        val newEngine = FakeController(playbackEngine = AndroidPlaybackEngine.Libmpv)
        runtime.playerController = newEngine

        runtime.refreshTracks()

        assertEquals(emptyList(), newEngine.audioSelections)
        assertNotNull(runtime.pendingEngineSwitchTrackPreference)

        newEngine.exposedAudioTracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English", language = "en"),
        )
        runtime.refreshTracks()

        assertEquals(listOf(0), newEngine.audioSelections)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun carryOverIsDroppedWhenAnotherEngineTakesOver() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)

        val otherEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en"),
            ),
        )
        runtime.playerController = otherEngine

        runtime.refreshTracks()

        assertEquals(emptyList(), otherEngine.audioSelections)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun explicitClearDropsThePendingCarryOver() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )

        runtime.rememberCurrentTrackPreferenceForEngineSwitch(AndroidPlaybackEngine.Libmpv)
        assertNotNull(runtime.pendingEngineSwitchTrackPreference)

        runtime.clearPendingEngineSwitchTrackPreference()

        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    @Test
    fun manualEngineSwitchCapturesTheCurrentSelection() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en"),
                AudioTrack(index = 1, id = "2", label = "Japanese", language = "ja", isSelected = true),
            ),
        )

        runtime.switchPlaybackEngine()

        assertEquals(AndroidPlaybackEngine.Libmpv, runtime.playbackEngineOverride)
        val pending = runtime.pendingEngineSwitchTrackPreference
        assertEquals("ja", pending?.preference?.audioLanguage)
        assertEquals(AndroidPlaybackEngine.Libmpv, pending?.targetEngine)
    }

    @Test
    fun autoFailoverCapturesTheCurrentSelection() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en"),
                AudioTrack(index = 1, id = "2", label = "Japanese", language = "ja", isSelected = true),
            ),
        )
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            autoSwitchPlaybackEngineOnError = true,
        )

        val failedOver = runtime.maybeFailoverPlaybackEngine(resolvedAutoEngine = AndroidPlaybackEngine.ExoPlayer)

        assertTrue(failedOver)
        assertEquals(AndroidPlaybackEngine.Libmpv, runtime.playbackEngineOverride)
        val pending = runtime.pendingEngineSwitchTrackPreference
        assertEquals("ja", pending?.preference?.audioLanguage)
        assertEquals(AndroidPlaybackEngine.Libmpv, pending?.targetEngine)
    }

    @Test
    fun manualEngineSwitchAppliesTheCarriedOverSelection() {
        val runtime = runtime()
        runtime.playerController = FakeController(
            playbackEngine = AndroidPlaybackEngine.ExoPlayer,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en", isSelected = true),
            ),
        )

        runtime.switchPlaybackEngine()

        val newEngine = FakeController(
            playbackEngine = AndroidPlaybackEngine.Libmpv,
            exposedAudioTracks = listOf(
                AudioTrack(index = 0, id = "1", label = "English", language = "en"),
            ),
        )
        runtime.playerController = newEngine
        runtime.refreshTracks()

        assertEquals(listOf(0), newEngine.audioSelections)
        assertEquals(0, runtime.selectedAudioIndex)
        assertNull(runtime.pendingEngineSwitchTrackPreference)
    }

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Title",
            sourceUrl = "https://example.com/video.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Provider",
            streamTitle = "Source",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt1234567",
            parentMetaId = "tt1234567",
            parentMetaType = "movie",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerEngineSwitchTrackPreferenceTest.scope
    }

    private class FakeController(
        override val playbackEngine: AndroidPlaybackEngine?,
        var exposedAudioTracks: List<AudioTrack> = emptyList(),
        var exposedSubtitleTracks: List<SubtitleTrack> = emptyList(),
    ) : PlayerEngineController {
        val audioSelections = mutableListOf<Int>()
        val subtitleSelections = mutableListOf<Int>()

        override fun getAudioTracks(): List<AudioTrack> = exposedAudioTracks
        override fun getSubtitleTracks(): List<SubtitleTrack> = exposedSubtitleTracks
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) {
            audioSelections += index
        }
        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
