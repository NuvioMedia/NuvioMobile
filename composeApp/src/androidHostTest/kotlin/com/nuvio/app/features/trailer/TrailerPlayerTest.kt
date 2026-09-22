package com.nuvio.app.features.trailer

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.TrailerPlayerPopup
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.SubtitleTrack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class TrailerPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pauseAndScrubKeepPlaybackPaused() {
        val player = RecordingController()
        val state = playingState(player)
        showControls(state)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle {
            assertFalse(state.playWhenReady)
            assertEquals(1, player.pauses)
            state.snapshot = state.snapshot.copy(isPlaying = false)
        }
        compose.onNodeWithContentDescription("Playback position").performSemanticsAction(SemanticsActions.SetProgress) {
            it(75_000f)
        }
        compose.runOnIdle {
            assertEquals(75_000L, player.positionMs)
            assertEquals(75_000L, state.snapshot.positionMs)
            assertFalse(state.playWhenReady)
            assertNull(state.scrubPositionMs)
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun replaySeeksToStartBeforePlaying() {
        val player = RecordingController()
        val state = playingState(player).apply {
            snapshot = snapshot.copy(isPlaying = false, isEnded = true, positionMs = 120_000L)
        }
        showControls(state)
        compose.onNodeWithContentDescription("Play").performClick()
        compose.runOnIdle {
            assertEquals(0L, player.positionMs)
            assertEquals(1, player.plays)
            assertFalse(state.snapshot.isEnded)
            assertTrue(state.playWhenReady)
        }
    }

    @Test
    fun changingPresentationRetainsPositionAndPauseIntent() {
        val player = RecordingController()
        val state = playingState(player)
        state.togglePlayback()
        state.snapshot = state.snapshot.copy(isPlaying = false)
        state.changePresentation()
        assertEquals(35_000L, state.snapshot.positionMs)
        assertFalse(state.playWhenReady)
        assertNull(state.controller)
        assertEquals(1, state.presentationId)
    }

    @Test
    fun fullscreenCanRetryAndReturnToPopupOnFailure() {
        var retries = 0
        var dismissed = false
        compose.setContent {
            NuvioTheme {
                TrailerPlayerPopup(
                    visible = true,
                    trailerTitle = "Official trailer",
                    trailerType = "Trailer",
                    contentTitle = "Example movie",
                    playbackSource = null,
                    isLoading = false,
                    errorMessage = "Unavailable",
                    onDismiss = { dismissed = true },
                    onRetry = { retries += 1 },
                )
            }
        }
        compose.onNodeWithContentDescription("Enter fullscreen").performClick()
        compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithContentDescription("Exit fullscreen").performClick()
        compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test
    fun controlsHideDuringPlaybackAndReturnWhenPaused() {
        val state = playingState(RecordingController())
        compose.mainClock.autoAdvance = false
        showControls(state)
        compose.mainClock.advanceTimeBy(4_000L)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.runOnIdle { state.snapshot = state.snapshot.copy(isPlaying = false) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun preparingFullscreenHidesPlaybackButtonUntilReady() {
        val state = playingState(RecordingController())
        showControls(state)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { state.changePresentation() }
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
        compose.runOnIdle { state.snapshot = state.snapshot.copy(isLoading = false) }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    private fun showControls(state: TrailerPlaybackState) {
        compose.setContent {
            NuvioTheme {
                TrailerPlayerControls(state, canControlPlayback = true, title = "Trailer", onExitFullscreen = {})
            }
        }
    }

    private fun playingState(player: RecordingController) = TrailerPlaybackState().apply {
        controller = player
        snapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 120_000L,
            positionMs = 35_000L,
        )
    }

    private class RecordingController : PlayerEngineController {
        var plays = 0
        var pauses = 0
        var positionMs = -1L
        override fun play() { plays += 1 }
        override fun pause() { pauses += 1 }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
