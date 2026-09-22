package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import kotlinx.serialization.json.buildJsonObject
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
@Config(sdk = [34], application = Application::class)
class PauseOverlaySettingsTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun pauseOverlayDefaultsToEnabledAndKeepsTheSavedChoiceAfterReload() {
        PlayerSettingsRepository.ensureLoaded()
        assertTrue(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)

        PlayerSettingsRepository.setPauseOverlayEnabled(false)
        PlayerSettingsRepository.onProfileChanged()

        assertFalse(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)
        assertEquals(false, PlayerSettingsStorage.loadPauseOverlayEnabled())
        assertTrue(PlayerSettingsRepository.uiState.value.showLoadingOverlay)
    }

    @Test
    fun loadingAndPauseOverlaysCanBeToggledIndependently() {
        PlayerSettingsRepository.setShowLoadingOverlay(false)
        PlayerSettingsRepository.setPauseOverlayEnabled(false)
        PlayerSettingsRepository.setShowLoadingOverlay(true)
        PlayerSettingsRepository.onProfileChanged()

        assertTrue(PlayerSettingsRepository.uiState.value.showLoadingOverlay)
        assertFalse(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)

        PlayerSettingsRepository.setShowLoadingOverlay(false)
        PlayerSettingsRepository.setPauseOverlayEnabled(true)
        PlayerSettingsRepository.onProfileChanged()

        assertFalse(PlayerSettingsRepository.uiState.value.showLoadingOverlay)
        assertTrue(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)
    }

    @Test
    fun pauseOverlayRoundTripsThroughSyncAndOlderPayloadsRestoreTheDefault() {
        PlayerSettingsRepository.setPauseOverlayEnabled(false)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("pause_overlay_enabled"))

        PlayerSettingsRepository.setPauseOverlayEnabled(true)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)

        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("pause_overlay_enabled", 99)
        preferences.edit().putBoolean(otherProfileKey, false).commit()

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        PlayerSettingsRepository.onProfileChanged()

        assertNull(PlayerSettingsStorage.loadPauseOverlayEnabled())
        assertTrue(PlayerSettingsRepository.uiState.value.pauseOverlayEnabled)
        assertFalse(preferences.getBoolean(otherProfileKey, true))
    }
}
