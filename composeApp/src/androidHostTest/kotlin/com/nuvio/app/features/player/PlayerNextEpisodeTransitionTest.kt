package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class PlayerNextEpisodeTransitionTest {
    @get:Rule
    val compose = createComposeRule()

    private val searching = mutableStateOf(false)
    private val countdown = mutableStateOf<Int?>(null)
    private val position = mutableStateOf(30_000L)
    private var dismissals = 0

    @Test
    fun earlyNextEpisodeRequestShowsSearchAndCountdownUntilPlaybackStarts() {
        showOverlays()
        compose.onNodeWithText("Next Episode").assertDoesNotExist()

        compose.runOnIdle { searching.value = true }
        compose.onNodeWithText("Finding source…").assertIsDisplayed()
        compose.runOnIdle { position.value = 31_000L }
        compose.onNodeWithText("Finding source…").assertIsDisplayed()

        compose.runOnIdle {
            searching.value = false
            countdown.value = 3
        }
        compose.onNodeWithText("Playing via Source in 3…").assertIsDisplayed()
        compose.runOnIdle { countdown.value = 2 }
        compose.onNodeWithText("Playing via Source in 2…").assertIsDisplayed()
        compose.runOnIdle { countdown.value = 1 }
        compose.onNodeWithText("Playing via Source in 1…").assertIsDisplayed()

        compose.runOnIdle { countdown.value = null }
        compose.onNodeWithText("Next Episode").assertDoesNotExist()
    }

    @Test
    fun earlyNextEpisodeSearchCanBeDismissed() {
        searching.value = true
        showOverlays()

        compose.onNodeWithText("Next Episode").performSemanticsAction(SemanticsActions.Dismiss) { it() }

        compose.onNodeWithText("Next Episode").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun showOverlays() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    PlayerPlaybackOverlays(
                        playerControlsLocked = false,
                        useLegacyLayout = false,
                        lockedOverlayVisible = false,
                        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, positionMs = position.value, durationMs = 3_600_000L),
                        displayedPositionMs = position.value,
                        metrics = PlayerLayoutMetrics.fromWidth(640.dp),
                        horizontalSafePadding = 0.dp,
                        onUnlock = {},
                        showOpeningOverlay = false,
                        backdropArtwork = null,
                        logo = null,
                        title = "Series",
                        onBackWithProgress = {},
                        openingLoadingMessage = null,
                        p2pInitialLoadingProgress = null,
                        showP2pRebufferStats = false,
                        p2pRebufferMessage = null,
                        p2pRebufferProgress = null,
                        currentGestureFeedback = null,
                        renderedGestureFeedback = null,
                        initialLoadCompleted = true,
                        pausedOverlayVisible = false,
                        activeSkipInterval = null,
                        skipsToPostCredits = false,
                        skipIntervalDismissed = false,
                        controlsVisible = true,
                        onSkipInterval = {},
                        onDismissSkipInterval = {},
                        sliderEdgePadding = 20.dp,
                        overlayBottomPadding = 84.dp,
                        isSeries = true,
                        nextEpisodeInfo = NextEpisodeInfo(
                            videoId = "series:1:2", season = 1, episode = 2,
                            title = "Next episode", thumbnail = null, overview = null,
                            released = null, hasAired = true, isWatched = false, unairedMessage = null,
                        ),
                        showNextEpisodeCard = false,
                        nextEpisodeAutoPlaySearching = searching.value,
                        nextEpisodeAutoPlaySourceName = "Source",
                        nextEpisodeAutoPlayCountdown = countdown.value,
                        blurUnwatchedEpisodes = false,
                        onPlayNextEpisode = {},
                        onDismissNextEpisode = {
                            dismissals++
                            searching.value = false
                            countdown.value = null
                        },
                        errorMessage = null,
                        onDismissError = {},
                    )
                }
            }
        }
    }
}
