package com.nuvio.app.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp

internal object PosterOpenMotion {
    const val DurationMillis = 550
    const val ContentDelayMillis = 400
    private val expansion = CubicBezierEasing(0.345f, 0.602f, 0.186f, 1f)
    private val backgroundRecede = CubicBezierEasing(0.176f, 0.258f, 0.324f, 1f)
    private val dissolve = CubicBezierEasing(0.146f, 0f, 0.538f, 1f)
    private val backgroundDissolve = CubicBezierEasing(0.061f, 0f, 0.435f, 1f)
    private val blur = CubicBezierEasing(0.718f, 0.392f, 0.569f, 1f)

    fun bounds(start: Rect, end: Rect, elapsedMillis: Float): Rect =
        lerp(start, end, expansionProgress(elapsedMillis))

    fun backgroundScale(elapsedMillis: Float): Float =
        1f - 0.1f * backgroundRecede.transform((elapsedMillis / DurationMillis).coerceIn(0f, 1f))

    fun artworkAlpha(elapsedMillis: Float): Float =
        1f - dissolve.transform((elapsedMillis / 260f).coerceIn(0f, 1f))

    fun backgroundAlpha(elapsedMillis: Float): Float =
        1f - backgroundDissolve.transform((elapsedMillis / 300f).coerceIn(0f, 1f))

    fun artworkBlurFraction(elapsedMillis: Float): Float =
        0.14f * blur.transform((elapsedMillis / 200f).coerceIn(0f, 1f))

    fun artworkBlurRadius(artwork: Size, bounds: Size, elapsedMillis: Float): Size {
        val fraction = artworkBlurFraction(elapsedMillis)
        return Size(
            blurRadiusForSigma(artwork.width * fraction),
            blurRadiusForSigma(artwork.height * bounds.width / bounds.height * fraction),
        )
    }

    fun backgroundBlurSigmaDp(elapsedMillis: Float): Float {
        val progress = (elapsedMillis / 200f).coerceIn(0f, 1f)
        return 16f * progress * progress
    }

    fun blurRadiusForSigma(sigma: Float): Float =
        ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

    private fun expansionProgress(elapsedMillis: Float): Float =
        expansion.transform((elapsedMillis / DurationMillis).coerceIn(0f, 1f))
}
