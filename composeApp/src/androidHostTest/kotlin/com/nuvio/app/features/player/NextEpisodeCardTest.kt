package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.skip.NextEpisodeCard
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class NextEpisodeCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val countdown = mutableStateOf<Int?>(null)
    private var dismissals = 0
    private var plays = 0
    private var seeks = 0

    @Test
    fun swipingRightDismissesWithoutPlayingOrSeeking() {
        showCard()
        card().performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY)) }
        assertDismissed()
    }

    @Test
    fun swipingLeftDoesNotMoveDismissPlayOrSeek() {
        showCard()
        val bounds = card().getUnclippedBoundsInRoot()
        card().performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.2f, centerY))
        }
        compose.waitForIdle()
        assertEquals(bounds, card().getUnclippedBoundsInRoot())
        card().performTouchInput { up() }
        card().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(0, plays)
            assertEquals(0, seeks)
        }
    }

    @Test
    fun unairedEpisodeCanBeDismissedWithoutSeeking() {
        showCard(hasAired = false)
        card().performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY)) }
        assertDismissed()
    }

    @Test
    fun shortSwipeReturnsToPlaceAndStillAllowsPlayback() {
        showCard()
        val bounds = card().getUnclippedBoundsInRoot()
        card().performTouchInput { swipe(center, Offset(centerX + width * 0.12f, centerY)) }
        compose.waitForIdle()
        assertEquals(bounds, card().getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(0, plays)
            assertEquals(0, seeks)
        }
        card().performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test
    fun cancelledSwipeReturnsToPlaceWithoutDismissing() {
        showCard()
        val bounds = card().getUnclippedBoundsInRoot()
        card().performTouchInput {
            down(center)
            moveTo(Offset(centerX + width * 0.4f, centerY))
            cancel()
        }
        compose.waitForIdle()
        assertEquals(bounds, card().getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertEquals(0, plays)
            assertEquals(0, seeks)
        }
    }

    @Test
    fun countdownUpdatesDoNotInterruptSwipeDismissal() {
        countdown.value = 5
        showCard()
        card().performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.65f, centerY))
        }
        compose.runOnIdle { countdown.value = 4 }
        card().performTouchInput { up() }
        assertDismissed()
    }

    @Test
    fun showingCardAgainResetsItsSwipePosition() {
        showCard()
        val bounds = card().getUnclippedBoundsInRoot()
        card().performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY)) }
        assertDismissed()
        compose.runOnIdle { visible.value = true }
        card().assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(bounds, card().getUnclippedBoundsInRoot())
    }

    @Test
    fun accessibilityDismissUsesTheSameAction() {
        showCard()
        card().performSemanticsAction(SemanticsActions.Dismiss) { it() }
        assertDismissed()
    }

    private fun card() = compose.onNodeWithText("Next Episode")

    private fun assertDismissed() {
        card().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, plays)
            assertEquals(0, seeks)
        }
    }

    private fun showCard(hasAired: Boolean = true) {
        compose.setContent {
            NuvioTheme {
                val unlocked = rememberUpdatedState(false)
                val noop = rememberUpdatedState<() -> Unit>({})
                Box(
                    modifier = Modifier.size(640.dp, 360.dp).playerSurfaceDragGestures(
                        gestureController = null,
                        layoutSize = IntSize(640, 360),
                        playbackGesturesEnabled = true,
                        sideGestureSystemEdgeExclusionPx = 0f,
                        playerControlsLockedState = unlocked,
                        touchGesturesEnabledState = rememberUpdatedState(true),
                        isHoldToSpeedGestureActiveState = unlocked,
                        currentPositionMsState = rememberUpdatedState(30_000L),
                        currentDurationMsState = rememberUpdatedState(120_000L),
                        deactivateHoldToSpeedState = noop,
                        showHorizontalSeekPreviewState = rememberUpdatedState { _: Long, _: Long -> seeks++ },
                        showBrightnessFeedbackState = rememberUpdatedState { _: Float -> },
                        showVolumeFeedbackState = rememberUpdatedState { _: PlayerAudioLevel -> },
                        clearLiveGestureFeedbackState = noop,
                        revealLockedOverlayState = noop,
                        commitHorizontalSeekState = rememberUpdatedState { _: Long -> seeks++ },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    NextEpisodeCard(
                        nextEpisode = NextEpisodeInfo(
                            videoId = "series:1:2",
                            season = 1,
                            episode = 2,
                            title = "Next episode",
                            thumbnail = null,
                            overview = null,
                            released = null,
                            hasAired = hasAired,
                            isWatched = false,
                            unairedMessage = null,
                        ),
                        visible = visible.value,
                        isAutoPlaySearching = false,
                        autoPlaySourceName = "Source",
                        autoPlayCountdownSec = countdown.value,
                        blurred = false,
                        onPlayNext = { plays++ },
                        onDismiss = { dismissals++; visible.value = false },
                    )
                }
            }
        }
    }
}
