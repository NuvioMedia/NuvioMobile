package com.nuvio.app.features.membership

import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeAccessTest {
    @Test
    fun memberWithoutSavedThemeDefaultsToGold() {
        val entitlements = CosmeticEntitlements(setOf(CosmeticEntitlement.GOLD_THEME))

        assertEquals(AppTheme.GOLD, resolveAppTheme(null, entitlements))
    }

    @Test
    fun unavailableSupporterThemeFallsBackToWhite() {
        assertEquals(
            AppTheme.WHITE,
            resolveAppTheme(AppTheme.JADE, CosmeticEntitlements.None),
        )
    }

    @Test
    fun availableThemesKeepStandardThemesAndEntitledSupporterThemes() {
        val entitlements = CosmeticEntitlements(setOf(CosmeticEntitlement.ROSE_GOLD_THEME))
        val themes = availableAppThemes(entitlements)

        assertEquals(AppTheme.ROSE_GOLD, themes.first())
        assertTrue(AppTheme.WHITE in themes)
        assertTrue(AppTheme.CRIMSON in themes)
        assertTrue(AppTheme.GOLD !in themes)
    }

    @Test
    fun customThemesAreAvailableWithoutMembership() {
        val themes = availableAppThemes(CosmeticEntitlements.None)

        assertEquals(AppTheme.CUSTOM, themes.first())
        assertEquals(AppTheme.CUSTOM, resolveAppTheme(AppTheme.CUSTOM, CosmeticEntitlements.None))
        assertEquals(AppTheme.WHITE, resolveAppTheme(null, CosmeticEntitlements.None))
    }

    @Test
    fun savedGradientUsesItsMainColorAfterMembershipLossAndReturnsOnRenewal() {
        val saved = CustomThemeColors(0x112233, 0x445566, 0x778899)

        assertEquals(CustomThemeColors.solid(0x445566), resolveCustomThemeColors(saved, null))
        assertEquals(saved, resolveCustomThemeColors(saved, MemberTier.SUPPORTER))
        assertEquals("#112233,#445566,#778899", saved.encode())
    }

    @Test
    fun bothMemberTiersKeepAllGradientStops() {
        val colors = CustomThemeColors(0xFF0000, 0x00FF00, 0x0000FF)

        MemberTier.entries.forEach { tier ->
            assertEquals(colors, resolveCustomThemeColors(colors, tier))
        }
    }

    @Test
    fun solidColorsStayTheSameAcrossMembershipChanges() {
        val colors = CustomThemeColors.solid(0x123456)

        (listOf(null) + MemberTier.entries).forEach { tier ->
            assertEquals(colors, resolveCustomThemeColors(colors, tier))
        }
    }

    @Test
    fun customThemeFollowsEntitledPresetsAndPrecedesStandardThemes() {
        val themes = availableAppThemes(
            CosmeticEntitlements(setOf(CosmeticEntitlement.GOLD_THEME)),
        )

        assertEquals(listOf(AppTheme.GOLD, AppTheme.CUSTOM, AppTheme.WHITE), themes.take(3))
        assertEquals(themes.size, themes.distinct().size)
    }
}
