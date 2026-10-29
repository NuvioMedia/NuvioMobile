package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

fun CustomThemeColors.toColorPalette(): ThemeColorPalette {
    val gradient = colors.map { Color(it or 0xFF000000.toInt()) }
    val accent = gradient[1]
    val focusRing = gradient.maxBy { it.luminance() }
    fun surface(base: Long, tint: Float) = lerp(Color(base), accent, tint)
    fun foreground(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White

    return ThemeColorPalette(
        secondary = accent,
        secondaryVariant = gradient[2],
        onSecondary = foreground(accent),
        onSecondaryVariant = foreground(gradient[2]),
        accentGradient = if (isSolid) listOf(accent) else gradient,
        nativeAccentHex = formatHexColor(focusRing.toArgb() and 0xFFFFFF),
        focusRing = focusRing,
        focusBackground = surface(0xFF242424, 0.18f),
        background = surface(0xFF0C0D0F, 0.025f),
        backgroundElevated = surface(0xFF17191D, 0.045f),
        backgroundCard = surface(0xFF20242A, 0.06f),
    )
}
