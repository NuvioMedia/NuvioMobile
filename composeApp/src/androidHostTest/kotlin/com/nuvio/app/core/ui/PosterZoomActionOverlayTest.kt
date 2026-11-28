package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlaylistAddCheckCircle
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import dev.chrisbanes.haze.rememberHazeState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h440dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterZoomActionOverlayTest {
    @get:Rule
    val compose = createComposeRule()
    private var dismissed = 0

    @Test
    fun longDescriptionIsFullyLaidOutAndScrolls() {
        show(description)
        val text = compose.onNodeWithText(description, useUnmergedTree = true)
        val layout = layout(text)
        assertTrue(layout.lineCount >= 20)
        assertFalse(layout.hasVisualOverflow)
        assertEquals(description.length, layout.getLineEnd(layout.lineCount - 1))

        val container = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(description)))
        assertFalse(isLastLineVisible(text, container))
        // Each swipe drags the synopsis by its own height; three reach the end of 20 lines.
        container.performTouchInput { repeat(3) { swipeUp() } }
        compose.waitForIdle()
        assertTrue(isLastLineVisible(text, container))
        assertEquals(0, dismissed)
    }

    @Test
    fun everyActionStaysOnScreenInLandscapeWithDescription() {
        show(description)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in actionLabels) {
            val action = compose.onNodeWithText(label)
            action.assertIsDisplayed()
            // boundsInRoot is already clipped, so build the row's full rectangle from its own size.
            val node = action.fetchSemanticsNode()
            val full = Rect(node.positionInRoot, node.size.toSize())
            assertTrue(full.top >= root.top && full.bottom <= root.bottom, "$label at $full outside $root")
            assertEquals(full.height, node.boundsInRoot.height, 0.5f, "$label is partly clipped")
        }
    }

    @Test
    fun missingDescriptionAddsNoNode() {
        show(null)
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
        // The poster placeholder repeats the title when there is no image, so compare the set of texts.
        assertEquals(setOf("The Keeper's Log", "S2 E5") + actionLabels, texts.toSet())
    }

    private fun isLastLineVisible(text: SemanticsNodeInteraction, container: SemanticsNodeInteraction): Boolean {
        val layout = layout(text)
        val lastLineBottom = text.fetchSemanticsNode().positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
        val viewport = container.fetchSemanticsNode().boundsInRoot
        return lastLineBottom > viewport.top && lastLineBottom <= viewport.bottom + 1f
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun show(description: String?) {
        compose.setContent {
            NuvioTheme {
                NuvioPosterZoomActionOverlay(
                    imageUrl = null,
                    title = "The Keeper's Log",
                    subtitle = "S2 E5",
                    description = description,
                    depthSurface = NuvioCardDepthSurface.EpisodeCards,
                    anchor = null,
                    actions = listOf(
                        PosterZoomOverlayAction(Icons.Default.CheckCircle, actionLabels[0]) {},
                        PosterZoomOverlayAction(Icons.Default.DoneAll, actionLabels[1]) {},
                        PosterZoomOverlayAction(Icons.Default.PlaylistAddCheckCircle, actionLabels[2]) {},
                    ),
                    hazeState = rememberHazeState(),
                    onDismissed = { dismissed++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        val actionLabels = listOf("Mark as watched", "Mark previous as watched", "Mark Season 2 as watched")
        val description = (1..20).joinToString("\n") { "Line $it of a long episode synopsis." }
    }
}
