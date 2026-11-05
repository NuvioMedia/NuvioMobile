package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsPagesTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        ThemeSettingsStorage.initialize(context)
        ThemeSettingsRepository.clearLocalState()
        ThemeSettingsRepository.ensureLoaded()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
        ThemeSettingsRepository.clearLocalState()
    }

    @Test
    fun phoneRootRoutesLayoutAndPlayback() {
        var opened: SettingsPage? = null
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    settingsRootContent(isTablet = false, onPageClick = { opened = it })
                }
            }
        }
        compose.onNodeWithText("Layout").performScrollTo().performClick()
        assertEquals(SettingsPage.Layout, opened)
        compose.onNodeWithText("Playback").performScrollTo().performClick()
        assertEquals(SettingsPage.Playback, opened)
        compose.onNodeWithText("Tracking").performScrollTo().performClick()
        assertEquals(SettingsPage.TraktAuthentication, opened)
    }

    @Test
    fun tabletServicesGroupContainsIntegrationsAndTracking() {
        var opened: SettingsPage? = null
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    settingsRootContent(
                        isTablet = true,
                        category = SettingsCategory.Services,
                        onPageClick = { opened = it },
                    )
                }
            }
        }
        compose.onNodeWithText("Integrations").assertIsDisplayed()
        compose.onNodeWithText("Tracking").performClick()
        assertEquals(SettingsPage.TraktAuthentication, opened)
        compose.onNodeWithText("Appearance").assertDoesNotExist()
        compose.onNodeWithText("Playback").assertDoesNotExist()
    }

    @Test
    fun layoutKeepsItsChildPagesAndMobileNavigationControls() {
        var opened = ""
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    layoutSettingsContent(
                        isTablet = false,
                        liquidGlassNativeTabBarSupported = false,
                        onHomescreenClick = { opened = "home" },
                        onMetaScreenClick = { opened = "details" },
                        onStreamsClick = { opened = "streams" },
                        onCollectionsClick = { opened = "collections" },
                        onContinueWatchingClick = { opened = "continue" },
                        onPosterCustomizationClick = { opened = "posters" },
                    )
                }
            }
        }
        mapOf(
            "Home Layout" to "home",
            "Collections" to "collections",
            "Continue Watching" to "continue",
            "Poster Card Style" to "posters",
            "Streams" to "streams",
            "Detail Page" to "details",
        ).forEach { (label, destination) ->
            compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
            assertEquals(destination, opened)
        }
        compose.onNode(hasText("Navigation Bar") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Classic").performScrollTo().performClick()
        assertEquals(NavBarStyle.CLASSIC, ThemeSettingsRepository.navBarStyle.value)
        compose.runOnIdle { ThemeSettingsRepository.onProfileChanged() }
        assertEquals(NavBarStyle.CLASSIC, ThemeSettingsRepository.navBarStyle.value)
    }

    @Test
    fun playbackNavigationKeepsMobileControlsAndSavesTheirChanges() {
        val page = mutableStateOf(SettingsPage.Playback)
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (page.value != SettingsPage.Playback) {
                        item {
                            Button(onClick = { page.value = page.value.previousPage()!! }) { Text("Back") }
                        }
                    }
                    playbackSettingsContent(false, page.value) { page.value = it }
                }
            }
        }
        compose.onNodeWithText("Player interface").performScrollTo().performClick()
        compose.onNodeWithText("Touch Gestures").performScrollTo().performClick()
        assertFalse(PlayerSettingsRepository.uiState.value.touchGesturesEnabled)
        assertEquals(false, PlayerSettingsStorage.loadTouchGesturesEnabled())
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Audio").performScrollTo().performClick()
        compose.onNodeWithText("Preferred Audio Language").performClick()
        compose.onNodeWithText("Original language").performClick()
        assertEquals("original", PlayerSettingsRepository.uiState.value.preferredAudioLanguage)
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        assertFalse(PlayerSettingsRepository.uiState.value.touchGesturesEnabled)
        assertEquals("original", PlayerSettingsRepository.uiState.value.preferredAudioLanguage)
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Player").performScrollTo().performClick()
        compose.onNodeWithText("Auto-switch engine on playback error").performScrollTo().performClick()
        assertFalse(PlayerSettingsRepository.uiState.value.autoSwitchPlaybackEngineOnError)
        assertEquals(false, PlayerSettingsStorage.loadAutoSwitchPlaybackEngineOnError())
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        assertFalse(PlayerSettingsRepository.uiState.value.autoSwitchPlaybackEngineOnError)
        compose.onNodeWithText("Playback Engine").performClick()
        compose.onNodeWithText("libmpv").performClick()
        assertEquals(AndroidPlaybackEngine.Libmpv, PlayerSettingsRepository.uiState.value.androidPlaybackEngine)
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Buffer and cache").assertDoesNotExist()
    }

    @Test
    fun subtitleRenderModePersistsAndRespectsTunneledPlayback() {
        PlayerSettingsRepository.setUseLibass(true)
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    playbackSettingsContent(false, SettingsPage.PlaybackSubtitles) {}
                }
            }
        }
        compose.onNodeWithText("ASS Render Mode").performScrollTo().performClick()
        compose.onNodeWithText("Video Effect").performClick()
        assertEquals("VIDEO_EFFECT", PlayerSettingsRepository.uiState.value.libassRenderMode)
        assertEquals("VIDEO_EFFECT", PlayerSettingsStorage.loadLibassRenderMode())
        compose.runOnIdle { PlayerSettingsRepository.onProfileChanged() }
        compose.onNodeWithText("Video Effect").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { PlayerSettingsRepository.setTunnelingEnabled(true) }
        compose.onNodeWithText("ASS Render Mode").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun externalPlayersStillDisableMobileGestures() {
        compose.setContent {
            NuvioTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { PlaybackInterfaceSettings(false, PlayerSettingsUiState(externalPlayerEnabled = true)) }
                }
            }
        }
        compose.onNodeWithText("Touch Gestures").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hold To Speed").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun searchOpensTheMovedControlsAndFiltersUnsupportedBufferOptions() {
        var entries = emptyList<SettingsSearchEntry>()
        compose.setContent {
            NuvioTheme {
                entries = settingsSearchEntries(
                    isTablet = false,
                    pluginsEnabled = true,
                    supportersContributorsPageEnabled = true,
                    accountDeletionEnabled = true,
                    personalMediaAddonCopyEnabled = true,
                    liquidGlassNativeTabBarSupported = true,
                    switchProfileAvailable = true,
                    checkForUpdatesAvailable = true,
                )
            }
        }
        compose.runOnIdle {
            assertEquals(entries.size, entries.map { it.key }.distinct().size)
            mapOf(
                "playback-touch-gestures" to SettingsPage.PlaybackInterface,
                "playback-preferred-audio" to SettingsPage.PlaybackAudio,
                "playback-preferred-subtitles" to SettingsPage.PlaybackSubtitles,
                "playback-auto-switch-engine" to SettingsPage.PlaybackPlayer,
                "playback-libass-render-mode" to SettingsPage.PlaybackSubtitles,
                "playback-custom-buffers" to SettingsPage.PlaybackBuffer,
                "liquid-glass" to SettingsPage.Layout,
                "navigation-bar" to SettingsPage.Layout,
                "app-icon" to SettingsPage.Appearance,
            ).forEach { (key, page) ->
                assertEquals(SettingsSearchTarget.Page(page), entries.single { it.key == key }.target)
            }
            assertEquals("Services", entries.single { it.key == "tracking" }.category)
            PlayerSettingsRepository.setAndroidPlaybackEngine(AndroidPlaybackEngine.Libmpv)
        }
        compose.runOnIdle {
            assertTrue(entries.none { it.target == SettingsSearchTarget.Page(SettingsPage.PlaybackBuffer) })
            assertTrue(entries.any { it.target == SettingsSearchTarget.Page(SettingsPage.PlaybackVideo) })
        }
    }
}
