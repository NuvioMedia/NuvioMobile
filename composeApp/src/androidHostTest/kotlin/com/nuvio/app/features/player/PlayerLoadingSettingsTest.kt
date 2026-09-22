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
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerLoadingSettingsTest {
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
    fun statusPreferenceSurvivesReloadAndSyncIndependentlyOfTheOverlay() {
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)
        PlayerSettingsRepository.setShowPlayerLoadingStatus(false)
        PlayerSettingsRepository.setShowLoadingOverlay(false)
        PlayerSettingsRepository.setShowLoadingOverlay(true)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(false, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("show_player_loading_status"))
        PlayerSettingsStorage.saveShowPlayerLoadingStatus(true)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(false, PlayerSettingsStorage.loadShowPlayerLoadingStatus())
    }

    @Test
    fun olderSyncedSettingsRestoreTheDefaultOnlyForTheActiveProfile() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        val otherKey = ProfileScopedKey.of("show_player_loading_status", 99)
        preferences.edit().putBoolean(otherKey, false).commit()
        PlayerSettingsStorage.saveShowPlayerLoadingStatus(false)
        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})

        assertNull(PlayerSettingsStorage.loadShowPlayerLoadingStatus())
        assertEquals(false, preferences.getBoolean(otherKey, true))
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(true, PlayerSettingsRepository.uiState.value.showPlayerLoadingStatus)
    }
}
