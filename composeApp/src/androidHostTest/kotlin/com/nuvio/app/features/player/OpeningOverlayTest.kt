package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class OpeningOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val message = mutableStateOf<String?>(null)
    private val progress = mutableStateOf<Float?>(null)

    @Test
    fun statusToggleKeepsTheTitleAndCloseActionVisible() {
        val statusVisible = mutableStateOf(true)
        var closed = false
        compose.setContent {
            NuvioTheme {
                OpeningOverlay(
                    artwork = null,
                    logo = null,
                    title = "Example movie",
                    onBack = { closed = true },
                    horizontalSafePadding = 0.dp,
                    message = if (statusVisible.value) "Finding stream source" else null,
                )
            }
        }
        compose.onNodeWithText("Example movie").assertIsDisplayed()
        compose.onNodeWithText("Finding stream source").assertIsDisplayed()
        compose.runOnIdle { statusVisible.value = false }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Finding stream source").assertDoesNotExist()
        compose.onNodeWithText("Example movie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun subtitleProgressUsesTheTvCopy() {
        compose.setContent {
            NuvioTheme {
                OpeningOverlay(
                    artwork = null,
                    logo = null,
                    title = "Example movie",
                    onBack = {},
                    horizontalSafePadding = 0.dp,
                    message = subtitleLoadingStatusMessage(
                        SubtitleLoadingProgress(total = 3, completed = 1, addonName = "Example addon"),
                    ),
                )
            }
        }
        compose.onNodeWithText("Subtitles: Example addon (1 / 3)").assertIsDisplayed()
    }

    @Test
    fun loadingContentIsCenteredWhenStatusIsHidden() {
        showOverlay()
        assertContentCentered()
    }

    @Test
    fun loadingContentStaysCenteredWithStatusAndProgressBelowIt() {
        message.value = "Preparing playback"
        progress.value = 0.5f
        showOverlay()
        assertContentCentered()
        val contentBounds = compose.onNodeWithText("Example title").getUnclippedBoundsInRoot()
        val statusBounds = compose.onNodeWithText("Preparing playback").getUnclippedBoundsInRoot()
        assertTrue(statusBounds.top > contentBounds.bottom)
    }

    @Test
    fun statusChangesDoNotMoveTheLoadingContent() {
        showOverlay()
        assertContentCentered()
        compose.runOnIdle {
            message.value = "Finding a source"
            progress.value = 0.2f
        }
        compose.onNodeWithText("Finding a source").assertIsDisplayed()
        assertContentCentered()
        compose.runOnIdle {
            message.value = "Preparing playback"
            progress.value = 0.8f
        }
        compose.onNodeWithText("Preparing playback").assertIsDisplayed()
        assertContentCentered()
        compose.runOnIdle {
            message.value = null
            progress.value = null
        }
        assertContentCentered()
    }

    private fun assertContentCentered() {
        compose.waitForIdle()
        val overlay = compose.onNodeWithTag("opening-overlay").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithText("Example title").getUnclippedBoundsInRoot()
        assertEquals((overlay.left.value + overlay.right.value) / 2, (content.left.value + content.right.value) / 2, absoluteTolerance = 1f)
        assertEquals((overlay.top.value + overlay.bottom.value) / 2, (content.top.value + content.bottom.value) / 2, absoluteTolerance = 1f)
    }

    private fun showOverlay() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    OpeningOverlay(
                        artwork = null,
                        logo = null,
                        title = "Example title",
                        onBack = {},
                        horizontalSafePadding = 0.dp,
                        message = message.value,
                        progress = progress.value,
                        modifier = Modifier.testTag("opening-overlay"),
                    )
                }
            }
        }
    }
}
