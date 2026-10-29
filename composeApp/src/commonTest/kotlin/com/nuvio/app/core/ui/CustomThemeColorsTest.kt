package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomThemeColorsTest {
    @Test
    fun savedColorsUseTheTvFormat() {
        assertEquals("#B75AFF,#EC70A9,#FFB37A", CustomThemeColors.Default.encode())
        val colors = CustomThemeColors(0x000001, 0x123ABC, 0xFFFFFF)
        assertEquals("#000001,#123ABC,#FFFFFF", colors.encode())
        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(colors, CustomThemeColors.decode("000001, #123abc ,ffffff"))
    }

    @Test
    fun invalidSavedColorsFallBackToTheDefaultGradient() {
        listOf(null, "", "#123456", "#123456,#654321", "#123456,invalid,#FFFFFF", "1,2,3", "#000000,#111111,#222222,#333333")
            .forEach { assertEquals(CustomThemeColors.Default, CustomThemeColors.decode(it)) }
    }

    @Test
    fun hexInputAcceptsOnlySixRgbDigits() {
        assertEquals(0xABCDEF, parseHexColor(" #abcdef "))
        assertEquals(0, parseHexColor("000000"))
        assertEquals(0xFFFFFF, parseHexColor("FFFFFF"))
        listOf("#FFF", "#FF000000", "GG0000", "12345", "1234567", "##123456", "-12345", "").forEach {
            assertNull(parseHexColor(it))
        }
        assertFailsWith<IllegalArgumentException> { CustomThemeColors(first = -1) }
        assertFailsWith<IllegalArgumentException> { CustomThemeColors(third = 0x1000000) }
    }

    @Test
    fun editingOneStopPreservesTheOtherStops() {
        val colors = CustomThemeColors(0x112233, 0x445566, 0x778899)
        assertEquals(CustomThemeColors(0x112233, 0xABCDEF, 0x778899), colors.withColor(1, 0xABCDEF))
        assertEquals(colors, colors.withColor(3, 0xABCDEF))
    }

    @Test
    fun customPaletteUsesSavedStopsAndReadableForegrounds() {
        val palette = ThemeColors.getColorPalette(AppTheme.CUSTOM, CustomThemeColors(0xFF0000, 0xFFFFFF, 0x000000))

        assertEquals(listOf(Color.Red, Color.White, Color.Black), palette.accentGradient)
        assertEquals(Color.White, palette.secondary)
        assertEquals(Color.Black, palette.secondaryVariant)
        assertEquals(Color.Black, palette.onSecondary)
        assertEquals(Color.White, palette.onSecondaryVariant)
        assertEquals(Color.White, palette.focusRing)
        assertEquals("#FFFFFF", palette.nativeAccentHex)
        assertTrue(palette.background.luminance() < 0.02f)
        assertEquals(ThemeColors.White, ThemeColors.getColorPalette(AppTheme.WHITE, CustomThemeColors(0, 0, 0)))
    }

    @Test
    fun solidColorsUseOneBrushColorAndKeepTheExistingStorageFormat() {
        val colors = CustomThemeColors.solid(0x123456)
        val color = Color(0xFF123456)
        val palette = colors.toColorPalette()

        assertTrue(colors.isSolid)
        assertEquals("#123456,#123456,#123456", colors.encode())
        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(listOf(color), palette.accentGradient)
        assertEquals(color, assertIs<SolidColor>(palette.accentBrush()).value)
        assertEquals(color, palette.secondary)
        assertEquals(color, palette.secondaryVariant)
        assertEquals("#123456", palette.nativeAccentHex)
    }

    @Test
    fun gradientsWithMatchingEndColorsKeepAllThreeStops() {
        val palette = CustomThemeColors(0xFF0000, 0x0000FF, 0xFF0000).toColorPalette()

        assertEquals(listOf(Color.Red, Color.Blue, Color.Red), palette.accentGradient)
    }

    @Test
    fun hsvRoundTripsGraysAndSaturatedColors() {
        listOf(0x000000, 0xFFFFFF, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0xB75AFF, 0xEC70A9)
            .forEach { assertEquals(it, HsvColor.fromRgb(it).toRgb()) }
        assertEquals(0f, HsvColor.fromRgb(0).saturation)
        assertEquals(0f, HsvColor.fromRgb(0x808080).saturation)
        assertEquals(120f, HsvColor.fromRgb(0x00FF00).hue)
        assertEquals(240f, HsvColor.fromRgb(0x0000FF).hue)
    }
}
