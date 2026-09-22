package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.ui.CustomThemeColors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeSettingsStorageTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ThemeSettingsStorage.initialize(context)
    }

    @Test
    fun glowPreferenceSurvivesReloadAndSync() {
        ThemeSettingsStorage.saveNavBarGlowEnabled(false)
        ThemeSettingsStorage.initialize(RuntimeEnvironment.getApplication())
        assertEquals(false, ThemeSettingsStorage.loadNavBarGlowEnabled())
        val payload = ThemeSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("nav_bar_glow_enabled"))

        ThemeSettingsStorage.saveNavBarGlowEnabled(true)
        ThemeSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals(false, ThemeSettingsStorage.loadNavBarGlowEnabled())
    }

    @Test
    fun olderPayloadClearsOnlyTheCurrentProfilesGlowPreference() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("nav_bar_glow_enabled", 99)
        preferences.edit().putBoolean(otherProfileKey, false).commit()
        ThemeSettingsStorage.saveNavBarGlowEnabled(false)

        ThemeSettingsStorage.replaceFromSyncPayload(buildJsonObject {})

        assertNull(ThemeSettingsStorage.loadNavBarGlowEnabled())
        assertEquals(false, preferences.getBoolean(otherProfileKey, true))
    }

    @Test
    fun savedGradientRoundTripsThroughProfileSync() {
        val colors = CustomThemeColors(0x123456, 0xABCDEF, 0x654321).encode()
        ThemeSettingsStorage.saveCustomThemeColors(colors)
        ThemeSettingsStorage.saveSelectedTheme("CUSTOM")
        val payload = ThemeSettingsStorage.exportToSyncPayload()

        assertEquals(colors, payload.decodeSyncString("custom_theme_colors"))
        assertEquals("CUSTOM", payload.decodeSyncString("selected_theme"))

        ThemeSettingsStorage.saveCustomThemeColors(CustomThemeColors.Default.encode())
        ThemeSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(colors, ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals("CUSTOM", ThemeSettingsStorage.loadSelectedTheme())
    }

    @Test
    fun choosingAnotherThemeKeepsTheSavedGradient() {
        val colors = CustomThemeColors(0x112233, 0x445566, 0x778899).encode()
        ThemeSettingsStorage.saveCustomThemeColors(colors)
        ThemeSettingsStorage.saveSelectedTheme("CUSTOM")
        ThemeSettingsStorage.saveSelectedTheme("WHITE")

        assertEquals(colors, ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals(colors, ThemeSettingsStorage.exportToSyncPayload().decodeSyncString("custom_theme_colors"))
    }

    @Test
    fun replacingAnOlderPayloadClearsOnlyTheCurrentProfilesGradient() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("custom_theme_colors", 99)
        preferences.edit().putString(otherProfileKey, "#111111,#222222,#333333").commit()
        ThemeSettingsStorage.saveCustomThemeColors(CustomThemeColors.Default.encode())

        ThemeSettingsStorage.replaceFromSyncPayload(buildJsonObject {
            put("selected_theme", encodeSyncString("WHITE"))
        })

        assertNull(ThemeSettingsStorage.loadCustomThemeColors())
        assertEquals("WHITE", ThemeSettingsStorage.loadSelectedTheme())
        assertEquals("#111111,#222222,#333333", preferences.getString(otherProfileKey, null))
    }
}
