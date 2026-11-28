package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightModeAudioProcessorTest {

    private val levels = listOf(NightMode.LIGHT, NightMode.MEDIUM, NightMode.STRONG)

    @Test
    fun fullScaleIsNeverLifted() {
        levels.forEach { mode ->
            assertEquals(0f, NightModeAudioProcessor.liftDb(mode, 1f), 0.01f, "$mode")
        }
    }

    @Test
    fun quietPassagesGetTheFullLift() {
        val quiet = dbToLevel(-30f)
        assertEquals(6f, NightModeAudioProcessor.liftDb(NightMode.LIGHT, quiet), 0.01f)
        assertEquals(10f, NightModeAudioProcessor.liftDb(NightMode.MEDIUM, quiet), 0.01f)
        assertEquals(15f, NightModeAudioProcessor.liftDb(NightMode.STRONG, quiet), 0.01f)
    }

    @Test
    fun liftFadesOutNearSilence() {
        levels.forEach { mode ->
            assertEquals(0f, NightModeAudioProcessor.liftDb(mode, dbToLevel(-70f)), 0.01f, "$mode")
            val full = NightModeAudioProcessor.liftDb(mode, dbToLevel(-30f))
            val halfway = NightModeAudioProcessor.liftDb(mode, dbToLevel(-57.5f))
            assertEquals(full / 2f, halfway, 0.01f, "$mode")
        }
    }

    @Test
    fun louderInputNeverGetsMoreLiftThanQuieterInput() {
        levels.forEach { mode ->
            var previous = Float.MAX_VALUE
            var db = -50f
            while (db <= 0f) {
                val lift = NightModeAudioProcessor.liftDb(mode, dbToLevel(db))
                assertTrue(lift <= previous + 0.0001f, "$mode at $db dB")
                previous = lift
                db += 0.25f
            }
        }
    }

    @Test
    fun offCopiesPcmUnchanged() {
        val processor = activeProcessor { NightMode.OFF }
        assertTrue(processor.isActive)
        val input = sine(peakDb = -30f, seconds = 0.5f)
        assertContentEquals(input, process(processor, input))
    }

    @Test
    fun encodedAudioIsLeftToPassthrough() {
        val processor = NightModeAudioProcessor { NightMode.STRONG }
        processor.configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_AC3))
        processor.flush()
        assertFalse(processor.isActive)
    }

    @Test
    fun mediumLiftsASteadyQuietToneByItsMakeupGain() {
        val processor = activeProcessor { NightMode.MEDIUM }
        val input = sine(peakDb = -30f, seconds = 2f)
        val output = process(processor, input)
        // Skip the first second while the gain settles.
        val settledIn = input.copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        val settledOut = output.copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        assertEquals(10f, rmsDb(settledOut) - rmsDb(settledIn), 0.5f)
    }

    @Test
    fun aLevelSetDuringPlaybackAppliesWithoutReconfiguring() {
        var mode = NightMode.OFF
        val processor = activeProcessor { mode }
        val input = sine(peakDb = -30f, seconds = 2f)
        assertContentEquals(input, process(processor, input))

        mode = NightMode.LIGHT
        val output = process(processor, input)
        val settledIn = input.copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        val settledOut = output.copyOfRange(SAMPLE_RATE * CHANNELS, 2 * SAMPLE_RATE * CHANNELS)
        assertEquals(6f, rmsDb(settledOut) - rmsDb(settledIn), 0.5f)

        mode = NightMode.OFF
        assertContentEquals(input, process(processor, input))
    }

    @Test
    fun strongKeepsAFullScaleToneUnderTheLimiterCeiling() {
        val processor = activeProcessor { NightMode.STRONG }
        val output = process(processor, sine(peakDb = 0f, seconds = 1f))
        val peak = output.maxOf { abs(it) }
        assertTrue(peak <= 0.891f + 0.001f, "peak $peak")
    }

    private fun activeProcessor(modeProvider: () -> NightMode): NightModeAudioProcessor {
        return NightModeAudioProcessor(modeProvider).apply {
            configure(FLOAT_STEREO)
            flush()
        }
    }

    private fun process(processor: NightModeAudioProcessor, samples: FloatArray): FloatArray {
        val out = ArrayList<Float>(samples.size)
        val chunk = 1024 * CHANNELS
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunk, samples.size)
            val input = ByteBuffer.allocateDirect((end - offset) * 4).order(ByteOrder.nativeOrder())
            for (i in offset until end) input.putFloat(samples[i])
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            while (output.remaining() >= 4) out.add(output.float)
            offset = end
        }
        return out.toFloatArray()
    }

    private fun sine(peakDb: Float, seconds: Float): FloatArray {
        val amplitude = dbToLevel(peakDb)
        val frames = (SAMPLE_RATE * seconds).toInt()
        return FloatArray(frames * CHANNELS) { index ->
            val frame = index / CHANNELS
            amplitude * sin(2.0 * PI * 220.0 * frame / SAMPLE_RATE).toFloat()
        }
    }

    private fun rmsDb(samples: FloatArray): Float {
        val meanSquare = samples.fold(0.0) { sum, v -> sum + v * v } / samples.size
        return 20f * log10(max(sqrt(meanSquare).toFloat(), 1e-9f))
    }

    private fun dbToLevel(db: Float): Float = 10f.pow(db / 20f)

    private companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        val FLOAT_STEREO = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_FLOAT)
    }
}
