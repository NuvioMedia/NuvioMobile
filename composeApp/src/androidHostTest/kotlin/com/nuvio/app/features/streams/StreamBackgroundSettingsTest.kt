package com.nuvio.app.features.streams

import android.app.Application
import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.SettingsSearchEntry
import com.nuvio.app.features.settings.settingsSearchEntries
import com.nuvio.app.features.settings.streamsSettingsContent
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
class StreamBackgroundSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_stream_badge_settings", Context.MODE_PRIVATE).edit().clear().commit()
        StreamBadgeSettingsStorage.initialize(context)
        StreamBadgeSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        StreamBadgeSettingsRepository.clearLocalState()
    }

    @Test
    fun existingProfilesUseTheAppBackgroundByDefault() {
        StreamBadgeSettingsRepository.ensureLoaded()
        assertEquals(StreamBackgroundMode.Normal, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
    }

    @Test
    fun cinematicBackgroundSurvivesReloadAndSettingsSync() {
        StreamBadgeSettingsRepository.setBackgroundMode(StreamBackgroundMode.Cinematic)
        StreamBadgeSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.ensureLoaded()
        assertEquals(StreamBackgroundMode.Cinematic, StreamBadgeSettingsRepository.uiState.value.backgroundMode)

        val payload = StreamBadgeSettingsStorage.exportToSyncPayload()
        assertEquals("cinematic", payload.decodeSyncString("stream_background_mode"))
        StreamBadgeSettingsRepository.setBackgroundMode(StreamBackgroundMode.Normal)
        StreamBadgeSettingsStorage.replaceFromSyncPayload(payload)
        StreamBadgeSettingsRepository.onProfileChanged()
        assertEquals(StreamBackgroundMode.Cinematic, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
    }

    @Test
    fun olderSettingsRestoreTheDefaultWithoutChangingOtherProfiles() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_stream_badge_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("stream_background_mode", 99)
        preferences.edit().putString(otherProfileKey, "normal").commit()
        StreamBadgeSettingsRepository.setBackgroundMode(StreamBackgroundMode.Cinematic)

        StreamBadgeSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        StreamBadgeSettingsRepository.onProfileChanged()

        assertEquals(StreamBackgroundMode.Normal, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
        assertNull(StreamBadgeSettingsStorage.loadStreamBackgroundMode())
        assertEquals("normal", preferences.getString(otherProfileKey, null))
    }

    @Test
    fun removedAndUnknownBackgroundModesFallBackToNormal() {
        listOf("dominantcolor", "unknown").forEach { mode ->
            StreamBadgeSettingsStorage.saveStreamBackgroundMode(mode)
            StreamBadgeSettingsRepository.onProfileChanged()
            assertEquals(StreamBackgroundMode.Normal, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
        }
    }

    @Test
    fun backgroundPickerChangesAndDisplaysTheSelectedMode() {
        compose.setContent {
            NuvioTheme {
                LazyColumn { streamsSettingsContent(isTablet = false) }
            }
        }
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithText("Dominant Color").assertDoesNotExist()
        compose.onNodeWithText("Cinematic").performClick()
        compose.runOnIdle {
            assertEquals(StreamBackgroundMode.Cinematic, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
        }
        compose.onNodeWithText("Cinematic").assertIsDisplayed()
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithText("Normal").performClick()
        compose.runOnIdle {
            assertEquals(StreamBackgroundMode.Normal, StreamBadgeSettingsRepository.uiState.value.backgroundMode)
        }
    }

    @Test
    fun tabletsHideTheBackgroundPickerEvenWhenItWasOpenOnMobile() {
        val isTablet = mutableStateOf(false)
        compose.setContent {
            NuvioTheme {
                LazyColumn { streamsSettingsContent(isTablet = isTablet.value) }
            }
        }
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithText("Cinematic").assertIsDisplayed()
        compose.runOnIdle { isTablet.value = true }
        compose.onNodeWithText("Background").assertDoesNotExist()
        compose.onNodeWithText("Cinematic").assertDoesNotExist()
    }

    @Test
    fun tabletsExcludeStreamBackgroundFromSettingsSearch() {
        val isTablet = mutableStateOf(false)
        var entries = emptyList<SettingsSearchEntry>()
        compose.setContent {
            NuvioTheme {
                entries = settingsSearchEntries(
                    isTablet = isTablet.value,
                    pluginsEnabled = false,
                    supportersContributorsPageEnabled = false,
                    accountDeletionEnabled = false,
                    personalMediaAddonCopyEnabled = false,
                    liquidGlassNativeTabBarSupported = false,
                    switchProfileAvailable = false,
                    checkForUpdatesAvailable = false,
                )
            }
        }
        compose.runOnIdle {
            assertTrue(entries.any { it.key == "stream-background" })
            isTablet.value = true
        }
        compose.runOnIdle {
            assertFalse(entries.any { it.key == "stream-background" })
            assertTrue(entries.any { it.key == "stream-addon-logo" })
        }
    }
}
