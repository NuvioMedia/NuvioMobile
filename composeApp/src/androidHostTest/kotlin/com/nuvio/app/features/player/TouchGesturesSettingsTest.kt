package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
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
@Config(sdk = [34], application = Application::class)
class TouchGesturesSettingsTest {
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
    fun bothGestureSettingsDefaultToEnabled() {
        PlayerSettingsRepository.ensureLoaded()
        assertTrue(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertTrue(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
    }

    @Test
    fun legacyDisabledPreferenceMigratesToBothSettings() {
        PlayerSettingsStorage.saveTouchGesturesEnabled(false)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertFalse(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
    }

    @Test
    fun legacyEnabledPreferenceMigratesToBothSettings() {
        PlayerSettingsStorage.saveTouchGesturesEnabled(true)
        PlayerSettingsRepository.onProfileChanged()
        assertTrue(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertTrue(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
    }

    @Test
    fun changingOneSettingAfterMigrationLeavesTheOtherAtTheLegacyValue() {
        PlayerSettingsStorage.saveTouchGesturesEnabled(false)
        PlayerSettingsRepository.onProfileChanged()
        PlayerSettingsRepository.setVerticalTouchGesturesEnabled(true)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertTrue(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
    }

    @Test
    fun settingsAreIndependentAndPersistAfterReload() {
        PlayerSettingsRepository.setHorizontalTouchGesturesEnabled(false)
        PlayerSettingsRepository.onProfileChanged()
        assertFalse(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertTrue(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
        assertEquals(false, PlayerSettingsStorage.loadHorizontalTouchGesturesEnabled())

        PlayerSettingsRepository.setHorizontalTouchGesturesEnabled(true)
        PlayerSettingsRepository.setVerticalTouchGesturesEnabled(false)
        PlayerSettingsRepository.onProfileChanged()
        assertTrue(PlayerSettingsRepository.uiState.value.horizontalTouchGesturesEnabled)
        assertFalse(PlayerSettingsRepository.uiState.value.verticalTouchGesturesEnabled)
        assertEquals(false, PlayerSettingsStorage.loadVerticalTouchGesturesEnabled())
    }
}
