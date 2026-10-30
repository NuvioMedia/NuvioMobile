package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.SubtitleAppearance
import com.nuvio.app.features.player.SubtitleStyleState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class SubtitleAppearanceUiTest {
    @get:Rule val compose = createComposeRule()

    @BeforeTest fun initialize() { PlayerSettingsStorage.initialize(RuntimeEnvironment.getApplication()) }

    @Test
    fun fontPickerShadowAndResetUpdateTheSharedStyle() {
        val state = mutableStateOf(SubtitleStyleState(stripSdh = true, useForcedSubtitles = true))
        compose.setContent {
            NuvioTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SubtitleAppearanceSettings(state.value, onStyleChanged = { state.value = it })
                }
            }
        }
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Font", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Serif").performClick()
        compose.runOnIdle { assertEquals("serif", state.value.appearance.fontId) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Drop shadow").performScrollTo().performClick()
        compose.onNodeWithText("Shadow opacity").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.value.appearance.shadowEnabled) }
        compose.onNodeWithText("Reset appearance").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(SubtitleAppearance(), state.value.appearance)
            assertTrue(state.value.stripSdh && state.value.useForcedSubtitles)
        }
    }

    @Test
    fun externalPlayersDisableAppearanceActions() {
        compose.setContent {
            NuvioTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SubtitleAppearanceSettings(
                        SubtitleStyleState(backgroundColor = Color.Black),
                        enabled = false, onStyleChanged = { error("Disabled controls changed the style") },
                    )
                }
            }
        }
        compose.onNodeWithText("Font").assertIsNotEnabled()
        compose.onNodeWithText("Reset appearance").performScrollTo().assertIsNotEnabled()
    }
}
