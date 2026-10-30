package com.nuvio.app.features.trailer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LetterboxDetectorTest {

    private val width = 64
    private val height = 120
    private val black = 0xFF101010.toInt()
    private val bright = 0xFF8090A0.toInt()

    @Test
    fun `full frame content has no bars`() {
        val bar = LetterboxDetector.detectBar(frame { _, _ -> bright }, width, height)

        assertEquals(0f, bar)
        assertEquals(1f, LetterboxDetector.zoomFor(bar!!))
    }

    @Test
    fun `scope trailer in 16x9 frame detects baked bars`() {
        val pixels = frame { _, y -> if (y < 15 || y >= height - 15) black else bright }

        val bar = LetterboxDetector.detectBar(pixels, width, height)!!

        assertEquals(15f / height + 0.01f, bar, 0.0001f)
        assertEquals(1f / (1f - 2f * bar), LetterboxDetector.zoomFor(bar), 0.0001f)
    }

    @Test
    fun `compression noise inside bars is ignored`() {
        val pixels = frame { x, y ->
            val inBar = y < 15 || y >= height - 15
            if (inBar && x != 7) black else bright
        }

        assertEquals(15f / height + 0.01f, LetterboxDetector.detectBar(pixels, width, height)!!, 0.0001f)
    }

    @Test
    fun `dark top of scene without matching bottom bar keeps full frame`() {
        val pixels = frame { _, y -> if (y < 30) black else bright }

        assertEquals(0f, LetterboxDetector.detectBar(pixels, width, height))
    }

    @Test
    fun `black frame is not a valid sample`() {
        assertNull(LetterboxDetector.detectBar(frame { _, _ -> black }, width, height))
    }

    @Test
    fun `logo on black is not a valid sample`() {
        val pixels = frame { _, y -> if (y in 50 until 70) bright else black }

        assertNull(LetterboxDetector.detectBar(pixels, width, height))
    }

    @Test
    fun `full frame intro followed by letterbox zooms in once bars are stable`() {
        val tracker = LetterboxTracker()
        val zooms = (List(6) { 0f } + List(4) { 0.135f }).map(tracker::onSample)

        assertEquals(List(9) { null }, zooms.dropLast(1))
        assertEquals(LetterboxDetector.zoomFor(0.135f), zooms.last()!!, 0.0001f)
    }

    @Test
    fun `stable full frame never zooms`() {
        val tracker = LetterboxTracker()

        val zooms = List(10) { tracker.onSample(0f) }

        assertEquals(List(10) { null }, zooms)
    }

    @Test
    fun `changing dark scene edges never zoom`() {
        val tracker = LetterboxTracker()

        val zooms = listOf(0.06f, 0.12f, 0.04f, 0.15f, 0.08f, 0.18f, 0.05f).map(tracker::onSample)

        assertEquals(List(7) { null }, zooms)
    }

    @Test
    fun `sampling stops after first fifth of trailer`() {
        assertEquals(false, LetterboxDetector.isSampleWindowOver(positionMs = 23_000, durationMs = 120_000))
        assertEquals(true, LetterboxDetector.isSampleWindowOver(positionMs = 24_000, durationMs = 120_000))
        assertEquals(false, LetterboxDetector.isSampleWindowOver(positionMs = 60_000, durationMs = -1))
    }

    private fun frame(color: (x: Int, y: Int) -> Int): IntArray =
        IntArray(width * height) { index -> color(index % width, index / width) }
}
