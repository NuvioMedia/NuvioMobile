package com.nuvio.app.features.audiosync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure Kotlin inference for the 16 kHz branch of Silero VAD v5
 * (https://github.com/snakers4/silero-vad). The bundled weights (composeResources/files/silero_vad_v5_16k.bin, float16)
 * are converted from the official ONNX export: Copyright (c) 2020-present Silero Team, MIT License.
 *
 * The network is small: a Hann-windowed 256-point STFT, four 1D convolutions, one LSTM cell and a
 * 1x1 projection. Running it directly avoids shipping a native inference runtime. Each call to
 * [process] consumes 512 samples (32 ms) at 16 kHz and costs roughly 0.4M multiply-adds.
 */
internal class SileroVad(private val weights: SileroVadWeights) {
    private val context = FloatArray(CONTEXT_SAMPLES)
    private val padded = FloatArray(PADDED_SAMPLES)
    private val magnitude = FloatArray(STFT_BINS * STFT_FRAMES)
    private val enc0 = FloatArray(128 * 4)
    private val enc1 = FloatArray(64 * 2)
    private val enc2 = FloatArray(64 * 1)
    private val enc3 = FloatArray(128 * 1)
    private val lstmInput = FloatArray(LSTM_INPUT + LSTM_HIDDEN)
    private val gates = FloatArray(4 * LSTM_HIDDEN)
    private val cell = FloatArray(LSTM_HIDDEN)
    private val column = FloatArray(STFT_BINS * 3)
    private val fft = Fft(STFT_SIZE)
    private val fftRe = DoubleArray(STFT_SIZE)
    private val fftIm = DoubleArray(STFT_SIZE)

    fun reset() {
        context.fill(0f)
        lstmInput.fill(0f)
        cell.fill(0f)
    }

    /** Returns the speech probability of [chunk] (exactly [CHUNK_SAMPLES] mono samples at 16 kHz). */
    fun process(chunk: FloatArray, offset: Int = 0): Float {
        context.copyInto(padded, 0)
        chunk.copyInto(padded, CONTEXT_SAMPLES, offset, offset + CHUNK_SAMPLES)
        val inputLength = CONTEXT_SAMPLES + CHUNK_SAMPLES
        // Reflect-pad the right edge by 64 samples, as the exported model does.
        for (j in 0 until REFLECT_PAD) {
            padded[inputLength + j] = padded[inputLength - 2 - j]
        }
        chunk.copyInto(context, 0, offset + CHUNK_SAMPLES - CONTEXT_SAMPLES, offset + CHUNK_SAMPLES)

        computeStftMagnitude()
        conv1d(magnitude, STFT_BINS, STFT_FRAMES, weights.enc0Weight, weights.enc0Bias, 128, 1, enc0, column)
        conv1d(enc0, 128, 4, weights.enc1Weight, weights.enc1Bias, 64, 2, enc1, column)
        conv1d(enc1, 64, 2, weights.enc2Weight, weights.enc2Bias, 64, 2, enc2, column)
        conv1d(enc2, 64, 1, weights.enc3Weight, weights.enc3Bias, 128, 1, enc3, column)
        return lstmStep()
    }

    private fun computeStftMagnitude() {
        val window = HANN_WINDOW
        for (frame in 0 until STFT_FRAMES) {
            val start = frame * STFT_HOP
            for (i in 0 until STFT_SIZE) {
                fftRe[i] = (padded[start + i] * window[i]).toDouble()
                fftIm[i] = 0.0
            }
            fft.transform(fftRe, fftIm)
            for (bin in 0 until STFT_BINS) {
                val re = fftRe[bin]
                val im = fftIm[bin]
                magnitude[bin * STFT_FRAMES + frame] = sqrt(re * re + im * im).toFloat()
            }
        }
    }

    private fun lstmStep(): Float {
        val hidden = LSTM_HIDDEN
        // lstmInput = [encoder output (128), previous hidden state (128)]
        enc3.copyInto(lstmInput, 0, 0, LSTM_INPUT)
        val w = weights.lstmWeight
        val b = weights.lstmBias
        val width = LSTM_INPUT + hidden
        for (g in 0 until 4 * hidden) {
            gates[g] = b[g] + dot(w, g * width, lstmInput, width)
        }
        var logit = weights.decoderBias
        val decoder = weights.decoderWeight
        for (j in 0 until hidden) {
            val i = sigmoid(gates[j])
            val f = sigmoid(gates[hidden + j])
            val c = tanh(gates[2 * hidden + j])
            val o = sigmoid(gates[3 * hidden + j])
            val nextCell = f * cell[j] + i * c
            cell[j] = nextCell
            val h = o * tanh(nextCell)
            lstmInput[LSTM_INPUT + j] = h
            if (h > 0f) logit += decoder[j] * h
        }
        return sigmoid(logit)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 512
        const val CHUNK_DURATION_US = CHUNK_SAMPLES * 1_000_000L / SAMPLE_RATE
        private const val CONTEXT_SAMPLES = 64
        private const val REFLECT_PAD = 64
        private const val PADDED_SAMPLES = CONTEXT_SAMPLES + CHUNK_SAMPLES + REFLECT_PAD
        private const val STFT_SIZE = 256
        private const val STFT_HOP = 128
        private const val STFT_BINS = STFT_SIZE / 2 + 1
        private const val STFT_FRAMES = (PADDED_SAMPLES - STFT_SIZE) / STFT_HOP + 1
        private const val LSTM_INPUT = 128
        private const val LSTM_HIDDEN = 128

        private val HANN_WINDOW = FloatArray(STFT_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / STFT_SIZE)).toFloat()
        }

        /** a[aOffset until aOffset + n] . b[0 until n] with four independent accumulators for ILP. */
        private fun dot(a: FloatArray, aOffset: Int, b: FloatArray, n: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var i = 0
            val end = n - 3
            while (i < end) {
                s0 += a[aOffset + i] * b[i]
                s1 += a[aOffset + i + 1] * b[i + 1]
                s2 += a[aOffset + i + 2] * b[i + 2]
                s3 += a[aOffset + i + 3] * b[i + 3]
                i += 4
            }
            while (i < n) {
                s0 += a[aOffset + i] * b[i]
                i++
            }
            return (s0 + s1) + (s2 + s3)
        }

        private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

        private fun tanh(x: Float): Float = kotlin.math.tanh(x.toDouble()).toFloat()

        /**
         * Conv1d with kernel 3 and padding 1, followed by ReLU. Layouts are [channel * time + t];
         * weights are [out][in][3]. Each output column is gathered once (im2col) so the inner loop is a
         * branch-free contiguous dot product.
         */
        private fun conv1d(
            input: FloatArray,
            inChannels: Int,
            inLength: Int,
            weight: FloatArray,
            bias: FloatArray,
            outChannels: Int,
            stride: Int,
            output: FloatArray,
            column: FloatArray,
        ) {
            val outLength = (inLength - 1) / stride + 1
            val width = inChannels * 3
            for (t in 0 until outLength) {
                val center = t * stride
                var j = 0
                var row = 0
                for (c in 0 until inChannels) {
                    column[j] = if (center > 0) input[row + center - 1] else 0f
                    column[j + 1] = input[row + center]
                    column[j + 2] = if (center + 1 < inLength) input[row + center + 1] else 0f
                    j += 3
                    row += inLength
                }
                for (o in 0 until outChannels) {
                    val acc = bias[o] + dot(weight, o * width, column, width)
                    output[o * outLength + t] = if (acc > 0f) acc else 0f
                }
            }
        }
    }
}

internal class SileroVadWeights(
    val enc0Weight: FloatArray,
    val enc0Bias: FloatArray,
    val enc1Weight: FloatArray,
    val enc1Bias: FloatArray,
    val enc2Weight: FloatArray,
    val enc2Bias: FloatArray,
    val enc3Weight: FloatArray,
    val enc3Bias: FloatArray,
    /** Row-major [4 * 128, 128 + 128]: input weights followed by recurrent weights, gate order i, f, g, o. */
    val lstmWeight: FloatArray,
    /** Sum of the input and recurrent LSTM biases. */
    val lstmBias: FloatArray,
    val decoderWeight: FloatArray,
    val decoderBias: Float,
) {
    companion object {
        private val MAGIC = "NSVAD16\u0000".encodeToByteArray()

        /** Reads the float16 weight file produced from the official ONNX export. */
        fun read(bytes: ByteArray): SileroVadWeights {
            val data = LittleEndianReader(bytes)
            val magic = data.readBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Unexpected Silero VAD weight file" }
            val count = data.readLittleInt()
            require(count == 14) { "Unexpected Silero VAD tensor count $count" }
            val tensors = List(count) {
                val size = data.readLittleInt()
                FloatArray(size) { halfToFloat(data.readLittleShort()) }
            }
            val weightIh = tensors[8]
            val weightHh = tensors[9]
            require(weightIh.size == 512 * 128 && weightHh.size == 512 * 128) { "Unexpected LSTM shape" }
            val lstmWeight = FloatArray(512 * 256)
            for (g in 0 until 512) {
                weightIh.copyInto(lstmWeight, g * 256, g * 128, g * 128 + 128)
                weightHh.copyInto(lstmWeight, g * 256 + 128, g * 128, g * 128 + 128)
            }
            val lstmBias = FloatArray(512) { tensors[10][it] + tensors[11][it] }
            return SileroVadWeights(
                enc0Weight = tensors[0].requireSize(128 * 129 * 3),
                enc0Bias = tensors[1].requireSize(128),
                enc1Weight = tensors[2].requireSize(64 * 128 * 3),
                enc1Bias = tensors[3].requireSize(64),
                enc2Weight = tensors[4].requireSize(64 * 64 * 3),
                enc2Bias = tensors[5].requireSize(64),
                enc3Weight = tensors[6].requireSize(128 * 64 * 3),
                enc3Bias = tensors[7].requireSize(128),
                lstmWeight = lstmWeight,
                lstmBias = lstmBias,
                decoderWeight = tensors[12].requireSize(128),
                decoderBias = tensors[13].requireSize(1)[0],
            )
        }

        private fun FloatArray.requireSize(expected: Int): FloatArray {
            require(size == expected) { "Unexpected Silero VAD tensor size $size (expected $expected)" }
            return this
        }

        private class LittleEndianReader(private val bytes: ByteArray) {
            private var position = 0

            fun readBytes(count: Int): ByteArray {
                if (position + count > bytes.size) throw IllegalStateException("Silero VAD weight file is truncated")
                return bytes.copyOfRange(position, position + count).also { position += count }
            }

            fun readLittleInt(): Int {
                val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
                return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }

            fun readLittleShort(): Int {
                val b0 = read(); val b1 = read()
                return b0 or (b1 shl 8)
            }

            private fun read(): Int {
                if (position >= bytes.size) throw IllegalStateException("Silero VAD weight file is truncated")
                return bytes[position++].toInt() and 0xFF
            }
        }

        private fun halfToFloat(half: Int): Float {
            val sign = (half and 0x8000) shl 16
            val exponent = (half ushr 10) and 0x1F
            val mantissa = half and 0x3FF
            if (exponent == 0) {
                // Zero or subnormal: mantissa * 2^-24.
                val magnitude = mantissa * 5.9604645e-8f
                return if (sign != 0) -magnitude else magnitude
            }
            val bits = if (exponent == 0x1F) {
                sign or 0x7F800000 or (mantissa shl 13)
            } else {
                sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return Float.fromBits(bits)
        }
    }
}
