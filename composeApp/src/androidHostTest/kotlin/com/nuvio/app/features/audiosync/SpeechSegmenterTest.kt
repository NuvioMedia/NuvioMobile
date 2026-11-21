package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.SpeechSegmenter
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechSegmenterTest {
    private val chunk = FloatArray(SileroVad.CHUNK_SAMPLES)

    private fun feed(segmenter: SpeechSegmenter, from: Int, count: Int, probability: Float) {
        for (frame in from until from + count) segmenter.onChunk(frame, chunk, probability)
    }

    @Test
    fun speechCutOffByTheEndOfASampledSpotIsKept() {
        val segments = ArrayList<Pair<Int, Int>>()
        val segmenter = SpeechSegmenter { start, samples -> segments += start to samples.size / SileroVad.CHUNK_SAMPLES }
        feed(segmenter, 0, 5, 0.1f)
        feed(segmenter, 5, 40, 0.9f) // Speech still running when the spot ends...
        feed(segmenter, 10_000, 5, 0.1f) // ...and the next spot starts elsewhere.
        assertEquals(listOf(2 to 43), segments)
    }

    @Test
    fun speechRunningWhenSamplingEndsIsKeptAndTooLittleIsNot() {
        val segments = ArrayList<Int>()
        val segmenter = SpeechSegmenter { start, _ -> segments += start }
        feed(segmenter, 0, 3, 0.1f)
        feed(segmenter, 3, 30, 0.9f)
        segmenter.flush()
        feed(segmenter, 500, 3, 0.1f)
        feed(segmenter, 503, 2, 0.9f) // A blip, shorter than any word.
        segmenter.onReset()
        segmenter.flush()
        assertEquals(listOf(0), segments)
    }
}
