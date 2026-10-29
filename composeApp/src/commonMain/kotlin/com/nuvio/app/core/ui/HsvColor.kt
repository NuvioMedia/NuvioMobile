package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

internal data class HsvColor(val hue: Float, val saturation: Float, val brightness: Float) {
    fun toRgb(): Int = Color.hsv(hue, saturation, brightness).toArgb() and 0xFFFFFF

    companion object {
        fun fromRgb(color: Int): HsvColor {
            val red = ((color shr 16) and 0xFF) / 255f
            val green = ((color shr 8) and 0xFF) / 255f
            val blue = (color and 0xFF) / 255f
            val maximum = maxOf(red, green, blue)
            val delta = maximum - minOf(red, green, blue)
            val hue = when {
                delta == 0f -> 0f
                maximum == red -> ((green - blue) / delta) % 6f
                maximum == green -> (blue - red) / delta + 2f
                else -> (red - green) / delta + 4f
            } * 60f
            return HsvColor(
                hue = (hue + 360f) % 360f,
                saturation = if (maximum == 0f) 0f else delta / maximum,
                brightness = maximum,
            )
        }
    }
}
