package com.nuvio.app.features.trailer

import kotlin.math.abs

object LetterboxDetector {

    const val SAMPLE_INTERVAL_MS = 400L
    private const val SAMPLE_WINDOW_FRACTION = 0.2f

    const val SAMPLE_WIDTH = 64
    const val SAMPLE_HEIGHT = 120

    private const val DARK_LUMA = 40
    private const val BRIGHT_PIXEL_TOLERANCE = 0.03f
    private const val MIN_BAR_FRACTION = 0.03f
    private const val MAX_BAR_FRACTION = 0.2f
    private const val EDGE_PADDING = 0.01f

    fun detectBar(pixels: IntArray, width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val top = (0 until height).firstOrNull { !isDarkRow(pixels, width, it) } ?: return null
        val bottom = (height - 1 downTo 0).first { !isDarkRow(pixels, width, it) }
        val bar = minOf(top, height - 1 - bottom).toFloat() / height
        return when {
            bar > MAX_BAR_FRACTION -> null
            bar < MIN_BAR_FRACTION -> 0f
            else -> bar + EDGE_PADDING
        }
    }

    fun isSampleWindowOver(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * SAMPLE_WINDOW_FRACTION

    fun zoomFor(bar: Float): Float = if (bar <= 0f) 1f else 1f / (1f - 2f * bar)

    private fun isDarkRow(pixels: IntArray, width: Int, row: Int): Boolean {
        val offset = row * width
        val allowedBright = (width * BRIGHT_PIXEL_TOLERANCE).toInt()
        var bright = 0
        for (x in 0 until width) {
            if (luma(pixels[offset + x]) > DARK_LUMA && ++bright > allowedBright) return false
        }
        return true
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

class LetterboxTracker {

    private var streakBar: Float? = null
    private var streak = 0

    fun onSample(bar: Float): Float? {
        val current = streakBar
        val stableBar = if (current != null && abs(bar - current) <= STABLE_TOLERANCE) {
            streak++
            minOf(current, bar)
        } else {
            streak = 1
            bar
        }
        streakBar = stableBar
        if (streak < STABLE_SAMPLES || stableBar <= 0f) return null
        return LetterboxDetector.zoomFor(stableBar)
    }

    private companion object {
        const val STABLE_SAMPLES = 4
        const val STABLE_TOLERANCE = 0.015f
    }
}
