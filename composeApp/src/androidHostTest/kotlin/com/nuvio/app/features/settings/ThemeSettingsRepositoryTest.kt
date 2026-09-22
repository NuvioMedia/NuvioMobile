package com.nuvio.app.features.settings

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.MemberAssetStorage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeSettingsRepositoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_theme_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ThemeSettingsStorage.initialize(context)
        MemberAssetStorage.initialize(context)
        MemberAccessRepository.clearLocalState()
        ThemeSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        ThemeSettingsRepository.clearLocalState()
    }

    @Test
    fun glowDefaultsOnAndRetainsItsPreferenceAcrossStyleChangesAndReloads() {
        ThemeSettingsRepository.ensureLoaded()
        assertEquals(true, ThemeSettingsRepository.navBarGlowEnabled.value)

        ThemeSettingsRepository.setNavBarGlowEnabled(false)
        ThemeSettingsRepository.setNavBarStyle(NavBarStyle.CLASSIC)
        ThemeSettingsRepository.setNavBarStyle(NavBarStyle.COMPACT)
        ThemeSettingsRepository.clearLocalState()
        ThemeSettingsRepository.ensureLoaded()

        assertEquals(false, ThemeSettingsRepository.navBarGlowEnabled.value)
        assertEquals(NavBarStyle.COMPACT, ThemeSettingsRepository.navBarStyle.value)

        ThemeSettingsStorage.replaceFromSyncPayload(kotlinx.serialization.json.buildJsonObject {})
        ThemeSettingsRepository.onProfileChanged()
        assertEquals(true, ThemeSettingsRepository.navBarGlowEnabled.value)
    }

    @Test
    fun nonMembersCanSaveAndReloadASolidCustomTheme() {
        val colors = CustomThemeColors.solid(0x2255AA)

        ThemeSettingsRepository.setCustomTheme(colors)
        ThemeSettingsRepository.onProfileChanged()

        assertEquals(AppTheme.CUSTOM, ThemeSettingsRepository.selectedTheme.value)
        assertEquals(colors, ThemeSettingsRepository.customThemeColors.value)
        assertEquals(colors, ThemeSettingsRepository.customThemePreference.value)
        assertEquals(colors.encode(), ThemeSettingsStorage.loadCustomThemeColors())
    }

    @Test
    fun savingAGradientWithoutMembershipStoresOnlyItsMainColor() {
        ThemeSettingsRepository.setCustomTheme(CustomThemeColors(0x112233, 0x445566, 0x778899))
        val expected = CustomThemeColors.solid(0x445566)

        assertEquals(AppTheme.CUSTOM, ThemeSettingsRepository.selectedTheme.value)
        assertEquals(expected, ThemeSettingsRepository.customThemeColors.value)
        assertEquals(expected, ThemeSettingsRepository.customThemePreference.value)
        assertEquals(expected.encode(), ThemeSettingsStorage.loadCustomThemeColors())
    }

    @Test
    fun loadingASyncedGradientWithoutMembershipPreservesTheSavedStops() {
        val saved = CustomThemeColors(0x112233, 0x445566, 0x778899)
        ThemeSettingsStorage.saveCustomThemeColors(saved.encode())
        ThemeSettingsStorage.saveSelectedTheme(AppTheme.CUSTOM.name)

        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.setTheme(AppTheme.WHITE)
        ThemeSettingsRepository.setTheme(AppTheme.CUSTOM)

        assertEquals(AppTheme.CUSTOM, ThemeSettingsRepository.selectedTheme.value)
        assertEquals(CustomThemeColors.solid(0x445566), ThemeSettingsRepository.customThemeColors.value)
        assertEquals(saved, ThemeSettingsRepository.customThemePreference.value)
        assertEquals(saved.encode(), ThemeSettingsStorage.exportToSyncPayload().decodeSyncString("custom_theme_colors"))
    }
}
