package com.nuvio.app.features.streams

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.PlayerProviderFilterRow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h600dp-land")
class ProviderFilterRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun manualSelectionTracksProviderResults() {
        verifyProviderResults(inPlayer = false)
    }

    @Test
    fun playerSelectionTracksProviderResults() {
        verifyProviderResults(inPlayer = true)
    }

    private fun verifyProviderResults(inPlayer: Boolean) {
        val stream = StreamItem(url = "https://example.com/video.mp4", addonName = "Ready", addonId = "ready")
        val ready = AddonStreamGroup("Ready", "ready", listOf(stream))
        val pending = AddonStreamGroup("Pending", "pending", emptyList(), isLoading = true)
        val state = mutableStateOf(
            StreamsUiState(
                groups = listOf(
                    ready,
                    AddonStreamGroup("Empty", "empty", emptyList()),
                    AddonStreamGroup("Failed", "failed", emptyList(), error = "Unavailable"),
                    pending,
                ),
                isAnyLoading = true,
            ),
        )
        var refreshes = 0
        compose.setContent {
            NuvioTheme {
                val onFilterSelected: (String?) -> Unit = { state.value = state.value.copy(selectedFilter = it) }
                if (inPlayer) {
                    PlayerProviderFilterRow(state.value, onFilterSelected)
                } else {
                    ProviderFilterRow(
                        groups = state.value.groups,
                        selectedFilter = state.value.selectedFilter,
                        onFilterSelected = onFilterSelected,
                        onRefresh = { refreshes++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Empty").assertDoesNotExist()
        compose.onNodeWithText("Failed").assertDoesNotExist()
        compose.onNodeWithText("Pending").performClick()
        compose.runOnIdle {
            assertEquals("pending", state.value.selectedFilter)
            state.value = state.value.copy(
                groups = listOf(ready, pending.copy(streams = listOf(stream.copy(addonId = "pending")), isLoading = false)),
                isAnyLoading = false,
            )
        }
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("pending", state.value.selectedFilter)
            state.value = state.value.copy(groups = listOf(ready, pending), isAnyLoading = true)
        }
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("pending", state.value.selectedFilter)
            state.value = state.value.copy(groups = listOf(ready, pending.copy(isLoading = false)), isAnyLoading = false)
        }
        compose.onNodeWithText("Pending").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.value.selectedFilter) }
        compose.onNodeWithText("Ready").performClick()
        compose.runOnIdle {
            assertEquals("ready", state.value.selectedFilter)
            state.value = state.value.copy(groups = emptyList())
        }
        compose.onNodeWithText("Ready").assertDoesNotExist()
        compose.runOnIdle { assertNull(state.value.selectedFilter) }
        if (!inPlayer) {
            compose.onNodeWithContentDescription("Refresh streams").performClick()
            compose.runOnIdle { assertEquals(1, refreshes) }
        }
    }
}
