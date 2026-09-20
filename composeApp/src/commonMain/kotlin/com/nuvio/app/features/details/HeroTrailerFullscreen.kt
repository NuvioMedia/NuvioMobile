package com.nuvio.app.features.details

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

internal const val HERO_TRAILER_FULLSCREEN_STRETCH_FRACTION = 0.55f
internal const val HERO_TRAILER_FULLSCREEN_ANIM_MS = 560
internal const val HERO_TRAILER_FULLSCREEN_ORIENTATION_SETTLE_MS = 140L
internal const val HERO_TRAILER_FULLSCREEN_CONTROLS_AT = 0.9f
internal val HeroTrailerFullscreenEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f)

internal fun shouldOpenHeroTrailerFullscreen(
    stretchPx: Float,
    maxStretchPx: Float,
    trailerReady: Boolean,
): Boolean =
    trailerReady &&
        maxStretchPx > 0f &&
        stretchPx >= maxStretchPx * HERO_TRAILER_FULLSCREEN_STRETCH_FRACTION

internal fun heroTrailerFullscreenTargetExtraPx(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    heroHeightPx: Int,
    lockToLandscape: Boolean,
): Float {
    val targetHeightPx = if (lockToLandscape) {
        minOf(viewportWidthPx, viewportHeightPx)
    } else {
        viewportHeightPx
    }
    return (targetHeightPx - heroHeightPx).toFloat()
}

internal fun heroTrailerFullscreenExtraPx(
    progress: Float,
    stretchPx: Float,
    startExtraPx: Float,
    fullExtraPx: Float,
): Float {
    if (progress <= 0f) return stretchPx
    val fraction = progress.coerceIn(0f, 1f)
    return startExtraPx + (fullExtraPx - startExtraPx) * fraction
}

internal fun heroTrailerFullscreenChromeAlpha(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return (1f - t * t * (3f - 2f * t)).coerceIn(0f, 1f)
}
