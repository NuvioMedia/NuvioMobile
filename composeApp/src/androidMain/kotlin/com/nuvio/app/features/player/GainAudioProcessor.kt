@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Boosts 16-bit and float PCM by the amplification [gainDbProvider] returns, clipping at full
 * scale. It stays active for PCM even at 0 dB, where it only copies, so a new gain applies to the
 * next buffer without reconfiguring the audio sink. Encoded passthrough audio is left alone.
 */
internal class GainAudioProcessor(
    private val gainDbProvider: () -> Int,
) : BaseAudioProcessor() {

    private var appliedDb = AUDIO_AMPLIFICATION_MIN_DB
    private var scale = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val gainDb = gainDbProvider()
        if (gainDb != appliedDb) {
            appliedDb = gainDb
            scale = audioAmplificationGain(gainDb).toFloat()
        }

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        when {
            scale == 1f -> outputBuffer.put(inputBuffer)
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, scale)
            else -> processPcmFloat(inputBuffer, outputBuffer, scale)
        }
        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= 2) {
            val amplified = (inputBuffer.short * scale)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(amplified.toShort())
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= 4) {
            outputBuffer.putFloat((inputBuffer.float * scale).coerceIn(-1f, 1f))
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }
}
