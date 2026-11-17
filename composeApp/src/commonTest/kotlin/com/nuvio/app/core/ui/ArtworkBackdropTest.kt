package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkBackdropTest {
    @Test
    fun uniformArtworkKeepsItsColorAndOpacity() {
        val color = 0xFFDB8234.toInt()
        val pixels = IntArray(128 * 72) { color }

        blurBackdropPixels(pixels, 128, 72)

        assertTrue(pixels.all { it == color })
    }

    @Test
    fun colorsBlendWithoutWrappingAtTheArtworkEdges() {
        val width = 128
        val height = 72
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val pixels = IntArray(width * height) { if (it % width < width / 2) red else blue }

        blurBackdropPixels(pixels, width, height)

        repeat(height) { row ->
            assertEquals(red, pixels[row * width])
            assertEquals(blue, pixels[row * width + width - 1])
            val center = pixels[row * width + width / 2]
            assertTrue((center ushr 16) and 255 in 1..254)
            assertTrue(center and 255 in 1..254)
            assertEquals(0, (center ushr 8) and 255)
        }
        assertTrue(pixels.all { it ushr 24 == 255 })
    }

    @Test
    fun portraitAndLandscapeArtworkBlurTheSameWay() {
        val width = 24
        val height = 48
        val portrait = IntArray(width * height) { index ->
            if (index / width < height / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val landscape = IntArray(width * height) { index ->
            portrait[(index % height) * width + index / height]
        }

        blurBackdropPixels(portrait, width, height)
        blurBackdropPixels(landscape, height, width)

        portrait.forEachIndexed { index, pixel ->
            assertEquals(pixel, landscape[(index % width) * height + index / width])
        }
    }

    @Test
    fun singlePixelArtworkRemainsValid() {
        val color = 0xFF253C62.toInt()
        val pixels = intArrayOf(color)

        blurBackdropPixels(pixels, 1, 1)

        assertEquals(color, pixels.single())
    }
}
