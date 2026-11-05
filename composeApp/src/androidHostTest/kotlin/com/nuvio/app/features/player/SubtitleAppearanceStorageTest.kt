package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncString
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
class SubtitleAppearanceStorageTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clear() { PlayerSettingsRepository.clearLocalState() }

    @Test
    fun appearanceSurvivesRestartAndProfileSync() {
        val style = SubtitleStyleState(
            backgroundColor = Color.Black.copy(alpha = 0.6f),
            appearance = SubtitleAppearance("serif", 12, true, 65, 4),
        )
        PlayerSettingsRepository.setSubtitleStyle(style)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(style, PlayerSettingsRepository.uiState.value.subtitleStyle)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(style.appearance, decodeSubtitleAppearance(payload.decodeSyncString("subtitle_appearance")))
        PlayerSettingsStorage.saveSubtitleAppearance(SubtitleAppearance().encode())
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(style.appearance, decodeSubtitleAppearance(PlayerSettingsStorage.loadSubtitleAppearance()))
    }

    @Test
    fun olderSyncClearsAppearanceOnlyForActiveProfile() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        val otherKey = ProfileScopedKey.of("subtitle_appearance", 99)
        val saved = SubtitleAppearance(fontId = "mono").encode()
        preferences.edit().putString(otherKey, saved).commit()
        PlayerSettingsStorage.saveSubtitleAppearance(saved)
        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertNull(PlayerSettingsStorage.loadSubtitleAppearance())
        assertEquals(saved, preferences.getString(otherKey, null))
    }
}
