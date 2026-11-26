package com.nuvio.app.features.audiosync.asr

import com.nuvio.app.features.audiosync.SileroVad
import com.nuvio.app.features.audiosync.SpeechAnalyzer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Cuts the analysed 16 kHz audio into speech segments using the Silero probabilities that are
 * already being computed, so the recogniser only ever sees dialogue: no silence, music-only
 * stretches or effects. Segments are at most [MAX_SEGMENT_FRAMES] long so words come back quickly.
 * Speech still running when the audio breaks off (a sampled spot ends, a seek) is handed on as it
 * is rather than dropped: its words are as good as any other.
 */
internal class SpeechSegmenter(
    private val onSegment: (startFrame: Int, samples: FloatArray) -> Unit,
) : SpeechAnalyzer.ChunkListener {
    private val chunk = SileroVad.CHUNK_SAMPLES
    private val preroll = ArrayDeque<FloatArray>()
    private var buffer = FloatArray(0)
    private var bufferFrames = 0
    private var inSpeech = false
    private var startFrame = 0
    private var lastSpeechFrame = 0
    private var expectedFrame = Int.MIN_VALUE
    private val lock = SynchronizedObject()

    override fun onChunk(frame: Int, chunk: FloatArray, probability: Float) {
        synchronized(lock) { onChunkLocked(frame, chunk, probability) }
    }

    private fun onChunkLocked(frame: Int, chunk: FloatArray, probability: Float) {
        if (frame != expectedFrame) flushPending()
        expectedFrame = frame + 1
        val speech = probability >= SPEECH_THRESHOLD
        if (!inSpeech) {
            if (speech) {
                inSpeech = true
                startFrame = frame - preroll.size
                lastSpeechFrame = frame
                bufferFrames = 0
                preroll.forEach(::append)
                preroll.clear()
                append(chunk)
            } else {
                preroll.addLast(chunk.copyOf())
                if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
            }
            return
        }
        append(chunk)
        if (speech) lastSpeechFrame = frame
        if (frame - lastSpeechFrame > HANGOVER_FRAMES || bufferFrames >= MAX_SEGMENT_FRAMES) finish()
    }

    override fun onReset() = synchronized(lock) { flushPending() }

    /** The audio ends here (e.g. sampling is done): hands on the speech in progress. */
    fun flush() = synchronized(lock) { flushPending() }

    private fun flushPending() {
        if (inSpeech) finish()
        discard()
    }

    private fun finish() {
        val keepFrames = (lastSpeechFrame - startFrame + 1 + TAIL_FRAMES).coerceAtMost(bufferFrames)
        val speechFrames = lastSpeechFrame - startFrame + 1 - PREROLL_FRAMES
        if (speechFrames >= MIN_SPEECH_FRAMES) {
            onSegment(startFrame, buffer.copyOf(keepFrames * chunk))
        }
        inSpeech = false
        bufferFrames = 0
        preroll.clear()
    }

    private fun discard() {
        inSpeech = false
        bufferFrames = 0
        preroll.clear()
        expectedFrame = Int.MIN_VALUE
    }

    private fun append(samples: FloatArray) {
        val required = (bufferFrames + 1) * chunk
        if (buffer.size < required) buffer = buffer.copyOf(maxOf(required, buffer.size * 2))
        samples.copyInto(buffer, bufferFrames * chunk, 0, chunk)
        bufferFrames++
    }

    companion object {
        private const val SPEECH_THRESHOLD = 0.5f
        private const val PREROLL_FRAMES = 3
        private const val TAIL_FRAMES = 4
        private const val HANGOVER_FRAMES = 8
        private const val MIN_SPEECH_FRAMES = 6
        /** 12 s. */
        private const val MAX_SEGMENT_FRAMES = 375
    }
}
