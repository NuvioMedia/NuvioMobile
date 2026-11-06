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
    fun noCropMeansNoShift() {
        val profile = analyzeBackdropFocus(flatImageWithSubjectAt(subjectStartX = 70, subjectWidth = 20))
        assertEquals(0f, profile.horizontalBias(visibleFraction = 1f))
    }
}
