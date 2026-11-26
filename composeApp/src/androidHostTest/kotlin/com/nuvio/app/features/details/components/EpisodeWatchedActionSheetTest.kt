package com.nuvio.app.features.details.components

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaVideo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h852dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeWatchedActionSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun longOverviewIsFullyLaidOutAndEveryActionStaysReachable() {
        show(overview = longOverview, showPlayManually = true)
        val layout = layout(compose.onNodeWithText(longOverview, useUnmergedTree = true))
        assertTrue(layout.lineCount >= 8, "Expected a long synopsis, got ${layout.lineCount} lines")
        assertFalse(layout.hasVisualOverflow)
        assertEquals(longOverview.length, layout.getLineEnd(layout.lineCount - 1))

        val lastAction = compose.onNodeWithText("Play manually")
        lastAction.assertIsNotDisplayed()
        lastAction.performScrollTo()
        lastAction.assertIsDisplayed()
    }

    @Test
    fun missingOverviewAddsNoText() {
        show(overview = null)
        assertEquals(headerAndActions, texts())
    }

    @Test
    fun blankOverviewAddsNoText() {
        show(overview = "  ")
        assertEquals(headerAndActions, texts())
    }

    private fun show(overview: String?, showPlayManually: Boolean = false) {
        compose.setContent {
            NuvioTheme {
                EpisodeWatchedActionSheet(
                    episode = MetaVideo("show:2:5", "The Keeper's Log", season = 2, episode = 5, overview = overview),
                    seasonLabel = "Season 2",
                    isEpisodeWatched = false,
                    canMarkPreviousEpisodes = false,
                    arePreviousEpisodesWatched = false,
                    isSeasonWatched = false,
                    onDismiss = {},
                    onToggleWatched = {},
                    onTogglePreviousWatched = {},
                    onToggleSeasonWatched = {},
                    showPlayManually = showPlayManually,
                    onPlayManually = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("The Keeper's Log")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun texts(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private companion object {
        val headerAndActions = listOf("The Keeper's Log", "S2 E5 • Season 2", "Mark as watched", "Mark Season 2 as watched")
        const val longOverview = "Six weeks after the flood, Mara returns to the lighthouse to find the logbook her " +
            "father kept for thirty years has been torn apart, page by page. While the harbour council argues over " +
            "who should pay for the new sea wall, Tomas tracks a stranger who has been buying up the fishing " +
            "licences one family at a time. A storm warning traps the whole crew in the keeper's cottage for a " +
            "night, and an old argument about the night the Aurora went down finally boils over. By morning, " +
            "Mara has a name, a key to a locker in the old customs house, and a reason to doubt everything her " +
            "father told her about why he never sailed again. Meanwhile, the harbour master's daughter finds the " +
            "missing pages in a place no one thought to look, and the last line is the one that matters."
    }
}
