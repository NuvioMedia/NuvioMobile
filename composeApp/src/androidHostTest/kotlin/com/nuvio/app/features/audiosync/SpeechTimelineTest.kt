package com.nuvio.app.features.audiosync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTimelineTest {
    @Test
    fun firstStretchMatchesFirstSegment() {
        val random = Random(7)
        repeat(200) {
            val timeline = SpeechTimeline()
            var frame = random.nextInt(0, 2_000)
            repeat(random.nextInt(0, 12)) {
                val length = random.nextInt(1, 3_000)
                for (f in frame until frame + length) timeline.record(f, random.nextFloat())
                frame += length + random.nextInt(1, 8_000)
            }
            val joinGap = random.nextInt(1, 5_000)
            val from = random.nextInt(0, frame + 1)
            val expected = timeline.segments(fromFrame = from, joinGapFrames = joinGap).firstOrNull()
            val actual = timeline.firstStretch(from, joinGap)
            assertEquals(expected?.fromFrame, actual?.first)
            assertEquals(expected?.toFrame, actual?.let { it.last + 1 })
        }
    }
}
