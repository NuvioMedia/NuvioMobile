package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class PlayerGestureOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply {
        // Never advanced: keeps feedback timeouts and post-seek progress syncs from running.
        scope = TestScope()
        playerController = RecordingController(seeks)
        playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            positionMs = 60_000L,
            durationMs = 596_000L,
        )
    }
    private val useLegacyLayout = mutableStateOf(false)

    private fun setPlayerContent() {
        compose.setContent {
            NuvioTheme {
                val callbacks = runtime.rememberSurfaceGestureCallbacks()
                val currentFeedback = runtime.liveGestureFeedback ?: runtime.gestureFeedback
                LaunchedEffect(currentFeedback) {
                    if (currentFeedback != null) runtime.renderedGestureFeedback = currentFeedback
                }
                Box(
                    Modifier.size(480.dp, 270.dp)
                        .testTag("surface")
                        .playerSurfaceDragGestures(
                            gestureController = null,
                            layoutSize = IntSize(480, 270),
                            playbackGesturesEnabled = true,
                            sideGestureSystemEdgeExclusionPx = 0f,
                            playerControlsLockedState = callbacks.playerControlsLocked,
                            touchGesturesEnabledState = callbacks.touchGesturesEnabled,
                            isHoldToSpeedGestureActiveState = callbacks.isHoldToSpeedGestureActive,
                            currentPositionMsState = callbacks.currentPositionMs,
                            currentDurationMsState = callbacks.currentDurationMs,
                            deactivateHoldToSpeedState = callbacks.deactivateHoldToSpeed,
                            showHorizontalSeekPreviewState = callbacks.showHorizontalSeekPreview,
                            showBrightnessFeedbackState = callbacks.showBrightnessFeedback,
                            showVolumeFeedbackState = callbacks.showVolumeFeedback,
                            clearLiveGestureFeedbackState = callbacks.clearLiveGestureFeedback,
                            revealLockedOverlayState = callbacks.revealLockedOverlay,
                            commitHorizontalSeekState = callbacks.commitHorizontalSeek,
                        ),
                ) {
                    PlayerGestureOverlay(
                        currentFeedback = currentFeedback,
                        renderedFeedback = runtime.renderedGestureFeedback,
                        useLegacyLayout = useLegacyLayout.value,
                        horizontalSafePadding = 0.dp,
                        horizontalPadding = 0.dp,
                    )
                }
            }
        }
    }

    @Test
    fun swipingRightShowsTheSeekTargetWhileTheFingerIsDown() {
        setPlayerContent()
        // A twelfth of the width is 5 s: swipes on titles under 30 minutes span 60 s.
        compose.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveBy(Offset(width / 12f, 0f))
        }

        val target = compose.onNodeWithText("01:05").assertIsDisplayed().getUnclippedBoundsInRoot()
        val delta = compose.onNodeWithText("+5s").assertIsDisplayed().getUnclippedBoundsInRoot()
        val surface = compose.onNodeWithTag("surface").getUnclippedBoundsInRoot()
        assertTrue(target.right <= delta.left, "the target time comes before the offset")
        assertTrue(delta.bottom < surface.top + (surface.bottom - surface.top) / 3, "the preview sits at the top")
        val center = (target.left + delta.right) / 2
        assertTrue(abs((center - (surface.left + surface.right) / 2).value) < 2f, "the preview is centered")
        compose.runOnIdle { assertEquals(emptyList(), seeks) }
    }

    @Test
    fun swipingBackPastTheStartStopsAtZero() {
        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 10_000L)
        setPlayerContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveBy(Offset(-width / 4f, 0f))
        }

        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("-10s").assertIsDisplayed()
    }

    @Test
    fun longTitlesShowHoursAndSeekFurtherPerSwipe() {
        // Titles of an hour or more span 120 s across the width.
        runtime.playbackSnapshot = runtime.playbackSnapshot.copy(positionMs = 3_600_000L, durationMs = 7_200_000L)
        setPlayerContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveBy(Offset(width / 12f, 0f))
        }

        compose.onNodeWithText("1:00:10").assertIsDisplayed()
        compose.onNodeWithText("+10s").assertIsDisplayed()
    }

    @Test
    fun plainSecondaryMessageIsShownNextToTheMessage() {
        setPlayerContent()
        compose.runOnIdle {
            runtime.liveGestureFeedback = GestureFeedbackState(
                message = "12:00",
                icon = GestureFeedbackIcon.SeekBackward,
                secondaryMessage = "-3s",
            )
        }

        compose.onNodeWithText("12:00").assertIsDisplayed()
        compose.onNodeWithText("-3s").assertIsDisplayed()
    }

    @Test
    fun swipingLeftShowsABackwardTarget() {
        setPlayerContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveBy(Offset(-width / 6f, 0f))
        }

        compose.onNodeWithText("00:50").assertIsDisplayed()
        compose.onNodeWithText("-10s").assertIsDisplayed()
    }

    @Test
    fun thePreviewFollowsTheFingerAcrossTheStartingPoint() {
        setPlayerContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput {
            down(center)
            moveBy(Offset(width / 4f, 0f))
        }
        compose.onNodeWithText("01:15").assertIsDisplayed()
        compose.onNodeWithText("+15s").assertIsDisplayed()

        surface.performTouchInput { moveBy(Offset(-width / 3f, 0f)) }
        compose.onNodeWithText("00:55").assertIsDisplayed()
        compose.onNodeWithText("-5s").assertIsDisplayed()
        compose.onNodeWithText("+15s").assertDoesNotExist()
    }

    @Test
    fun releasingSeeksToThePreviewedTargetAndHidesThePreview() {
        setPlayerContent()
        val surface = compose.onNodeWithTag("surface")
        surface.performTouchInput {
            down(center)
            moveBy(Offset(width / 12f, 0f))
        }
        compose.onNodeWithText("+5s").assertIsDisplayed()

        surface.performTouchInput { up() }

        compose.runOnIdle {
            assertEquals(listOf(65_000L), seeks)
            assertNull(runtime.liveGestureFeedback)
        }
        compose.onNodeWithText("+5s").assertDoesNotExist()
        compose.onNodeWithText("01:05").assertDoesNotExist()
    }

    @Test
    fun legacyLayoutStillShowsTheSeekTarget() {
        useLegacyLayout.value = true
        setPlayerContent()
        compose.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveBy(Offset(width / 12f, 0f))
        }

        compose.onNodeWithText("01:05").assertIsDisplayed()
        compose.onNodeWithText("+5s").assertIsDisplayed()
    }

    @Test
    fun doubleTapSeekAmountIsShown() {
        setPlayerContent()
        compose.runOnIdle { runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward) }
        compose.onNodeWithText("+10s").assertIsDisplayed()

        compose.runOnIdle { runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward) }
        compose.onNodeWithText("+20s").assertIsDisplayed()

        compose.runOnIdle { runtime.handleDoubleTapSeek(PlayerSeekDirection.Backward) }
        compose.onNodeWithText("-10s").assertIsDisplayed()
    }

    @Test
    fun speedAndBrightnessFeedbackAreUnchanged() {
        setPlayerContent()
        compose.runOnIdle {
            runtime.liveGestureFeedback = GestureFeedbackState(message = "2x", icon = GestureFeedbackIcon.Speed)
        }
        compose.onNodeWithText("2x").assertIsDisplayed()

        compose.runOnIdle {
            runtime.liveGestureFeedback = null
            runtime.showBrightnessFeedback(0.4f)
        }
        compose.onNodeWithText("40").assertIsDisplayed()
        compose.onNodeWithText("2x").assertDoesNotExist()
    }

    private class RecordingController(private val seeks: MutableList<Long>) : PlayerEngineController {
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
        }
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
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
    )
}
