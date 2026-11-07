package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropFocusTest {

    private fun flatImageWithSubjectAt(subjectStartX: Int, subjectWidth: Int): SampledPixels {
        val w = 96
        val h = 54
        val pixels = IntArray(w * h) { 0xFF202830.toInt() }
        for (y in 10 until 40) {
            for (x in subjectStartX until subjectStartX + subjectWidth) {
                // Checkered, saturated block = lots of edges and colour.
                val on = (x + y) % 2 == 0
                pixels[y * w + x] = if (on) 0xFFE0A080.toInt() else 0xFF802010.toInt()
            }
        }
        return SampledPixels(pixels, w, h)
    }

    @Test
    fun subjectOnRightPullsFramingRight() {
        val profile = analyzeBackdropFocus(flatImageWithSubjectAt(subjectStartX = 70, subjectWidth = 20))
        assertTrue(profile.horizontalBias(visibleFraction = 0.5f) > 0.5f)
    }

    @Test
    fun subjectOnLeftPullsFramingLeft() {
        val profile = analyzeBackdropFocus(flatImageWithSubjectAt(subjectStartX = 4, subjectWidth = 20))
        assertTrue(profile.horizontalBias(visibleFraction = 0.5f) < -0.5f)
    }

    @Test
    fun centeredSubjectKeepsCenter() {
        val profile = analyzeBackdropFocus(flatImageWithSubjectAt(subjectStartX = 38, subjectWidth = 20))
        assertEquals(0f, profile.horizontalBias(visibleFraction = 0.5f))
    }

    @Test
    fun denseTextureOnSidesDoesNotPullAwayFromCenteredSubject() {
        val w = 96
        val h = 54
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // Fine, low-saturation "city" texture everywhere.
            if ((x + y) % 2 == 0) 0xFFB0A898.toInt() else 0xFF605850.toInt()
        }
        // Smooth teal column with a dark figure in the middle.
        for (y in 0 until h) {
            for (x in 34 until 62) {
                pixels[y * w + x] = if (x in 44 until 52 && y in 14 until 40) {
                    0xFF181820.toInt()
                } else {
                    0xFF3A8A80.toInt()
                }
            }
        }
        val profile = analyzeBackdropFocus(SampledPixels(pixels, w, h))
        assertEquals(0f, profile.horizontalBias(visibleFraction = 0.42f))
    }

    @Test
    fun smallSkinAreaPullsTowardsFacesDespiteSaturatedBackground() {
        val w = 96
        val h = 54
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                // Saturated blue stands on the left.
                x < 40 && y in 10 until 34 -> 0xFF2838C0.toInt()
                else -> 0xFF505860.toInt()
            }
        }
        // Two faces on the right.
        for (y in 10 until 18) {
            for (x in 66 until 72) pixels[y * w + x] = 0xFFE0A88C.toInt()
            for (x in 74 until 80) pixels[y * w + x] = 0xFFD89C80.toInt()
        }
        val profile = analyzeBackdropFocus(SampledPixels(pixels, w, h))
        assertTrue(profile.horizontalBias(visibleFraction = 0.42f) > 0.5f)
    }

    @Test
    fun noCropMeansNoShift() {
        val profile = analyzeBackdropFocus(flatImageWithSubjectAt(subjectStartX = 70, subjectWidth = 20))
        assertEquals(0f, profile.horizontalBias(visibleFraction = 1f))
    }
}
