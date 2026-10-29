package com.nuvio.app.core.ui.jelly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyMotionTest {
    @Test
    fun `matches upstream pointer and release frames at 60 and 120 Hz`() {
        val references = mapOf(
            60 to listOf(
                Sample(1, 0.2955424845220378f, 1.0382096611265257f, 0.999841607817523f, 1.0197028323014692f, 0f),
                Sample(6, 2.471442104369919f, 1.4774106201118176f, 0.9741601825733887f, 1.1647628069579945f, 0f),
                Sample(12, 1.4777631393843675f, 1.3343273408066567f, 1.290506337314779f, 1.1973752268004212f, -2.467214402081245f),
                Sample(18, 1.0564740641943913f, 1.1079109279946617f, 1.3841417663989846f, 1.1801380951889426f, -1.3058457822898932f),
                Sample(30, 1.0002310935268677f, 1.0052277801528704f, 0.9853341108498888f, 1.001660565467633f, -0.1012128035274391f),
                Sample(60, 1f, 0.9999933119726964f, 0.9999994431264191f, 1f, -1.1583472653453918e-05f)
            ),
            120 to listOf(
                Sample(1, 0.08755448652301467f, 1.0072650194290778f, 1.0008565122201156f, 1.005836965768201f, 0f),
                Sample(12, 2.4714421043699186f, 1.4774106201118171f, 0.9754013177495393f, 1.1647628069579947f, 0f),
                Sample(24, 1.4777631393843675f, 1.3648276739133671f, 1.2809745893177693f, 1.1973752268004212f, -2.467214402081245f),
                Sample(36, 1.0564740641943913f, 1.105702782638676f, 1.3851699868993097f, 1.1801380951889426f, -1.3058457822898926f),
                Sample(60, 1.0002310935268677f, 1.0054124976950722f, 0.9852750639118489f, 1.001660565467633f, -0.10121280352743904f),
                Sample(120, 1f, 1.0000153469991357f, 0.9999920994206077f, 1f, -1.1583472653453918e-05f)
            )
        )
        references.forEach { (hz, samples) ->
            val motion = JellyMotion(0, 4)
            motion.resize(320f, 64f, 4)
            motion.begin(277f, 32f)
            for (frame in 1..hz) {
                if (frame == hz / 10 + 1) motion.drag(-140.4f, 0f)
                if (frame == hz / 5 + 1) assertEquals(1, motion.finish())
                motion.advance(1.0 / hz)
                samples.firstOrNull { it.frame == frame }?.let { expected ->
                    val actual = motion.frame
                    assertEquals(expected.position, actual.position, 0.00001f, "$hz Hz frame $frame position")
                    assertEquals(expected.scaleX, actual.pillScaleX, 0.00001f, "$hz Hz frame $frame scale X")
                    assertEquals(expected.scaleY, actual.pillScaleY, 0.00001f, "$hz Hz frame $frame scale Y")
                    assertEquals(expected.contentScale, actual.contentScale, 0.00001f, "$hz Hz frame $frame content")
                    assertEquals(expected.panelOffset, actual.panelOffset, 0.00001f, "$hz Hz frame $frame panel")
                }
            }
        }
    }

    @Test
    fun `compact resize preserves selection and drag snapping`() {
        val motion = JellyMotion(2, 4)
        motion.resize(320f, 64f, 4)
        motion.resize(260f, 48f, 4)
        assertEquals(2f, motion.frame.position)
        motion.begin(161.5f, 24f)
        motion.drag(63f, 0f)
        assertEquals(3, motion.finish())
        settle(motion)
        assertEquals(3f, motion.frame.position)
        motion.resize(320f, 64f, 4)
        assertEquals(3f, motion.frame.position)
    }

    @Test
    fun `cancel restores controlled tab and settles every distortion`() {
        val motion = JellyMotion(1, 4)
        motion.resize(320f, 64f, 4)
        motion.begin(277f, 32f)
        motion.drag(200f, -500f)
        repeat(10) { motion.advance(1.0 / 60) }
        assertTrue(motion.frame.trackOffsetY < 0f)
        assertTrue(motion.frame.trackScaleX < 1f)
        motion.cancel(1)
        settle(motion)
        assertEquals(1f, motion.frame.position)
        assertEquals(1f, motion.frame.pillScaleX, 0.0001f)
        assertEquals(1f, motion.frame.pillScaleY, 0.0001f)
        assertEquals(1f, motion.frame.trackScaleX, 0.0001f)
        assertEquals(0f, motion.frame.trackOffsetY, 0.0001f)
        assertEquals(0f, motion.frame.glowOpacity, 0.0001f)
        assertFalse(motion.dragging)
    }

    @Test
    fun `edge drags stay bounded and programmatic navigation interrupts touch`() {
        val motion = JellyMotion(0, 4)
        motion.resize(260f, 48f, 4)
        motion.begin(10f, 24f)
        motion.drag(-1000f, 0f)
        assertEquals(0, motion.finish())
        motion.begin(10f, 24f)
        motion.drag(1000f, 0f)
        assertEquals(3, motion.finish())
        motion.begin(10f, 24f)
        motion.select(2)
        assertFalse(motion.dragging)
        settle(motion)
        assertEquals(2f, motion.frame.position)
    }

    @Test
    fun `single tab and stalled frames stay finite`() {
        val motion = JellyMotion(0, 1)
        motion.resize(200f, 48f, 1)
        motion.begin(100f, 24f)
        motion.drag(1000f, 1000f)
        motion.advance(10.0)
        assertTrue(motion.frame.pillScaleX.isFinite())
        assertTrue(motion.frame.pillScaleY.isFinite())
        assertEquals(0, motion.finish())
        settle(motion)
    }

    private fun settle(motion: JellyMotion) {
        repeat(240) { motion.advance(1.0 / 60) }
        assertFalse(motion.running)
    }

    private data class Sample(
        val frame: Int,
        val position: Float,
        val scaleX: Float,
        val scaleY: Float,
        val contentScale: Float,
        val panelOffset: Float,
    )
}
