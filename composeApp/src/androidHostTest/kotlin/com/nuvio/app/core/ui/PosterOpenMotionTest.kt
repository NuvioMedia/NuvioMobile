package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosterOpenMotionTest {
    @Test
    fun `expansion follows the reference frames at their recorded times`() {
        val start = Rect(738f, 923f, 1068f, 1382f)
        val end = Rect(0f, 177f, 1180f, 2556f)
        val samples = listOf(
            16.3f to 704f, 33f to 642f, 51.3f to 602f, 66.3f to 567f,
            83f to 507f, 99.7f to 441f, 116.3f to 381f, 133f to 326f,
            149.7f to 275f, 166.3f to 232f, 183f to 195f, 199.7f to 162f,
            216.3f to 134f, 233f to 111f, 249.7f to 91f, 266.3f to 73f,
            283f to 60f, 316.3f to 40f, 349.7f to 26f, 383f to 17f,
            416.3f to 11f, 449.7f to 7f, 483f to 4f,
        )
        val errors = samples.map { (millis, left) ->
            abs(PosterOpenMotion.bounds(start, end, millis).left - left)
        }
        assertTrue(errors.max() < 16f)
        assertTrue(sqrt(errors.sumOf { (it * it).toDouble() } / errors.size) < 5.5)
    }

    @Test
    fun `portrait landscape and edge posters start and finish at exact bounds`() {
        val end = Rect(0f, 24f, 1080f, 2340f)
        for (start in listOf(Rect(30f, 650f, 340f, 1100f), Rect(-80f, 850f, 620f, 1240f), Rect(800f, 1850f, 1110f, 2300f))) {
            assertEquals(start, PosterOpenMotion.bounds(start, end, 0f))
            assertEquals(end, PosterOpenMotion.bounds(start, end, PosterOpenMotion.DurationMillis.toFloat()))
            var previousWidth = start.width
            for (millis in 1..PosterOpenMotion.DurationMillis) {
                val bounds = PosterOpenMotion.bounds(start, end, millis.toFloat())
                assertTrue(bounds.width >= previousWidth)
                assertTrue(bounds.width <= end.width)
                previousWidth = bounds.width
            }
        }
    }

    @Test
    fun `poster blur stays circular in screen space throughout expansion`() {
        val viewport = Rect(0f, 177f, 1180f, 2556f)
        for (artwork in listOf(Size(330f, 459f), Size(720f, 405f), Size(300f, 300f))) {
            val start = Rect(400f, 900f, 400f + artwork.width, 900f + artwork.height)
            for (millis in 16..260) {
                val elapsed = millis.toFloat()
                val bounds = PosterOpenMotion.bounds(start, viewport, elapsed)
                val radius = PosterOpenMotion.artworkBlurRadius(artwork, bounds.size, elapsed)
                val screenRadius = bounds.width * PosterOpenMotion.artworkBlurFraction(elapsed)
                assertEquals(screenRadius, renderedSigma(radius.width) * bounds.width / artwork.width, 0.0001f)
                assertEquals(screenRadius, renderedSigma(radius.height) * bounds.height / artwork.height, 0.0001f)
            }
        }
    }

    @Test
    fun `poster blur and fade follow the first moving reference frames`() {
        val start = Rect(738f, 923f, 1069f, 1385f)
        val end = Rect(0f, 177f, 1180f, 2556f)
        val samples = listOf(
            Triple(16.666f, 1.56f, 0.969f),
            Triple(33.333f, 7.42f, 0.847f),
            Triple(51.666f, 14.62f, 0.739f),
            Triple(66.666f, 16.10f, 0.739f),
            Triple(83.333f, 28.06f, 0.626f),
            Triple(100f, 43.76f, 0.509f),
            Triple(116.666f, 61.85f, 0.397f),
            Triple(133.333f, 85.74f, 0.285f),
        )
        val fadeErrors = samples.map { (millis, sigma, alpha) ->
            val bounds = PosterOpenMotion.bounds(start, end, millis)
            val radius = PosterOpenMotion.artworkBlurRadius(start.size, bounds.size, millis)
            val screenSigma = renderedSigma(radius.width) * bounds.width / start.width
            assertEquals(sigma, screenSigma, 3f)
            PosterOpenMotion.artworkAlpha(millis) - alpha
        }
        assertTrue(sqrt(fadeErrors.sumOf { (it * it).toDouble() } / fadeErrors.size) < 0.035)
        assertEquals(Size.Zero, PosterOpenMotion.artworkBlurRadius(start.size, start.size, 0f))
    }

    @Test
    fun `background recedes and softens with the reference frames`() {
        val samples = listOf(
            Triple(33f, 0.988f, 0.788f),
            Triple(66f, 0.978f, 0.645f),
            Triple(100f, 0.963f, 0.467f),
            Triple(133f, 0.953f, 0.342f),
        )
        for ((millis, scale, alpha) in samples) {
            assertEquals(scale, PosterOpenMotion.backgroundScale(millis), 0.002f)
            assertEquals(alpha, PosterOpenMotion.backgroundAlpha(millis), 0.045f)
        }
        assertEquals(1f, PosterOpenMotion.backgroundScale(0f))
        assertEquals(0.9f, PosterOpenMotion.backgroundScale(550f))
        val radius = PosterOpenMotion.blurRadiusForSigma(PosterOpenMotion.backgroundBlurSigmaDp(100f) * 3f)
        assertEquals(12.54f, renderedSigma(radius) * PosterOpenMotion.backgroundScale(100f), 1.1f)
    }

    @Test
    fun `artwork and background dissolve before expansion settles`() {
        assertEquals(1f, PosterOpenMotion.artworkAlpha(0f))
        assertTrue(PosterOpenMotion.artworkAlpha(150f) in 0.1f..0.4f)
        assertEquals(0f, PosterOpenMotion.artworkAlpha(260f))
        assertEquals(0f, PosterOpenMotion.backgroundAlpha(300f))
    }

    private fun renderedSigma(radius: Float): Float =
        if (radius > 0f) 0.57735f * radius + 0.5f else 0f
}
