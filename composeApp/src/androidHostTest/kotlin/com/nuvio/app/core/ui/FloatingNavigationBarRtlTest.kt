package com.nuvio.app.core.ui

import com.nuvio.app.core.ui.jelly.JellyMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloatingNavigationBarRtlTest {

    private fun settle(motion: JellyMotion) {
        repeat(120) {
            motion.advance(1.0 / 60)
        }
    }

    @Test
    fun `visualNavIndex maps LTR destinations to identity positions`() {
        val count = 4
        // LTR first item
        assertEquals(0, visualNavIndex(logicalIndex = 0, count = count, isRtl = false))
        // LTR middle items
        assertEquals(1, visualNavIndex(logicalIndex = 1, count = count, isRtl = false))
        assertEquals(2, visualNavIndex(logicalIndex = 2, count = count, isRtl = false))
        // LTR last item
        assertEquals(3, visualNavIndex(logicalIndex = 3, count = count, isRtl = false))
    }

    @Test
    fun `visualNavIndex maps RTL destinations to mirrored positions`() {
        val count = 4
        // RTL first logical destination (Home) is visually on the far right (index 3)
        assertEquals(3, visualNavIndex(logicalIndex = 0, count = count, isRtl = true))
        // RTL middle destinations (Search and Library)
        assertEquals(2, visualNavIndex(logicalIndex = 1, count = count, isRtl = true))
        assertEquals(1, visualNavIndex(logicalIndex = 2, count = count, isRtl = true))
        // RTL last logical destination (Settings) is visually on the far left (index 0)
        assertEquals(0, visualNavIndex(logicalIndex = 3, count = count, isRtl = true))
    }

    @Test
    fun `logicalNavIndex correctly restores logical destination from visual touch position in RTL and LTR`() {
        val count = 4
        for (isRtl in listOf(false, true)) {
            for (logical in 0 until count) {
                val visual = visualNavIndex(logical, count, isRtl)
                val restored = logicalNavIndex(visual, count, isRtl)
                assertEquals(logical, restored, "Mismatch for logical=$logical, isRtl=$isRtl")
            }
        }
    }

    @Test
    fun `visual and logical mapping handles boundary conditions gracefully`() {
        val count = 4
        assertEquals(-1, visualNavIndex(-1, count, isRtl = true))
        assertEquals(-1, visualNavIndex(-1, count, isRtl = false))
        assertEquals(-1, logicalNavIndex(-1, count, isRtl = true))
        assertEquals(10, visualNavIndex(10, count, isRtl = true))
    }

    @Test
    fun `RTL JellyMotion indicator positions and animates in correct direction`() {
        val count = 4
        // Initial logical destination is Home (0) -> in RTL, visual position is 3 (rightmost)
        val initialVisual = visualNavIndex(0, count, isRtl = true)
        val motion = JellyMotion(initialVisual, count)
        motion.resize(320f, 64f, count)
        assertEquals(3f, motion.frame.position)

        // Navigate to Search (logical 1) -> in RTL, visual target is 2 (moving right to left)
        val searchVisual = visualNavIndex(1, count, isRtl = true)
        motion.select(searchVisual)
        // Advance slightly to check direction of animation
        motion.advance(1.0 / 60)
        assertTrue(motion.frame.position < 3f, "Indicator should move to the left (decreasing position)")

        settle(motion)
        assertEquals(2f, motion.frame.position, 0.001f, "Active indicator must match Search visual destination")

        // Navigate to Settings (logical 3) -> in RTL, visual target is 0 (far left)
        val settingsVisual = visualNavIndex(3, count, isRtl = true)
        motion.select(settingsVisual)
        settle(motion)
        assertEquals(0f, motion.frame.position, 0.001f, "Active indicator must match Settings visual destination")

        // Navigate back to Home (logical 0) -> in RTL, visual target is 3 (moving left to right)
        motion.select(initialVisual)
        motion.advance(1.0 / 60)
        assertTrue(motion.frame.position > 0f, "Indicator should move to the right (increasing position)")
        settle(motion)
        assertEquals(3f, motion.frame.position, 0.001f, "Active indicator must return to Home visual destination")
    }

    @Test
    fun `RTL touch and drag maps to correct logical destinations`() {
        val count = 4
        val initialVisual = visualNavIndex(0, count, isRtl = true) // 3
        val motion = JellyMotion(initialVisual, count)
        motion.resize(320f, 64f, count) // tabWidth = (320 - 8) / 4 = 78

        // User taps on the right side (where Home is located in RTL)
        motion.begin(280f, 32f)
        val tappedVisual = motion.finish()
        assertEquals(3, tappedVisual)
        assertEquals(0, logicalNavIndex(tappedVisual, count, isRtl = true), "Right tap must select Home")

        // User taps on the left side (where Settings is located in RTL)
        motion.begin(40f, 32f)
        val leftVisual = motion.finish()
        assertEquals(0, leftVisual)
        assertEquals(3, logicalNavIndex(leftVisual, count, isRtl = true), "Left tap must select Settings")

        // User drags from Home (right side) leftward towards Search
        motion.begin(280f, 32f)
        motion.drag(-80f, 0f)
        val draggedVisual = motion.finish()
        assertEquals(2, draggedVisual)
        assertEquals(1, logicalNavIndex(draggedVisual, count, isRtl = true), "Drag left from Home must select Search")
    }

    @Test
    fun `JellyTabTargets coverage calculation correctly tracks visual index in RTL`() {
        val count = 4
        val isRtl = true

        // When Home (logical 0) is selected, pill settles at visual 3 (rightmost)
        val homeMotion = JellyMotion(visualNavIndex(0, count, isRtl), count)
        homeMotion.resize(320f, 64f, count)

        // For logical tab 0 (Home), visual index is 3
        val homeVisualIndex = visualNavIndex(0, count, isRtl)
        val homeCoverage = (1f - abs(homeMotion.frame.position - homeVisualIndex)).coerceIn(0f, 1f)
        assertEquals(1f, homeCoverage, "Home tab must receive full coverage when selected in RTL")

        // Other tabs must receive 0 coverage
        val searchVisualIndex = visualNavIndex(1, count, isRtl) // 2
        val searchCoverage = (1f - abs(homeMotion.frame.position - searchVisualIndex)).coerceIn(0f, 1f)
        assertEquals(0f, searchCoverage, "Search tab must receive 0 coverage when Home is selected in RTL")

        // When Settings (logical 3) is selected, pill settles at visual 0 (leftmost)
        val settingsMotion = JellyMotion(visualNavIndex(3, count, isRtl), count)
        settingsMotion.resize(320f, 64f, count)

        val settingsVisualIndex = visualNavIndex(3, count, isRtl) // 0
        val settingsCoverage = (1f - abs(settingsMotion.frame.position - settingsVisualIndex)).coerceIn(0f, 1f)
        assertEquals(1f, settingsCoverage, "Settings tab must receive full coverage when selected in RTL")
    }

    @Test
    fun `LTR active-state and indicator behavior remains completely unaffected`() {
        val count = 4
        val isRtl = false

        // LTR first item (Home = 0)
        val homeMotion = JellyMotion(visualNavIndex(0, count, isRtl), count)
        homeMotion.resize(320f, 64f, count)
        assertEquals(0f, homeMotion.frame.position)

        // LTR middle item (Search = 1)
        homeMotion.select(visualNavIndex(1, count, isRtl))
        settle(homeMotion)
        assertEquals(1f, homeMotion.frame.position)

        // LTR middle item (Library = 2)
        homeMotion.select(visualNavIndex(2, count, isRtl))
        settle(homeMotion)
        assertEquals(2f, homeMotion.frame.position)

        // LTR last item (Settings = 3)
        homeMotion.select(visualNavIndex(3, count, isRtl))
        settle(homeMotion)
        assertEquals(3f, homeMotion.frame.position)

        // Touch left tap in LTR selects Home (0)
        homeMotion.begin(40f, 32f)
        val tappedVisual = homeMotion.finish()
        assertEquals(0, tappedVisual)
        assertEquals(0, logicalNavIndex(tappedVisual, count, isRtl))

        // Touch right tap in LTR selects Settings (3)
        homeMotion.begin(280f, 32f)
        val rightVisual = homeMotion.finish()
        assertEquals(3, rightVisual)
        assertEquals(3, logicalNavIndex(rightVisual, count, isRtl))
    }
}
