package com.nuvio.app.features.audiosync.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * English speech recognition with sherpa-onnx (Apache-2.0) and an int8 Zipformer transducer
 * trained on GigaSpeech (podcasts and YouTube, close to film dialogue). Runs fully on-device.
 */
class SherpaSpeechToText(
    modelDir: File,
    threads: Int,
    /** Trades some speed for a smaller footprint (see [AsrModelFiles.provider]). */
    lowMemory: Boolean = false,
) : SpeechToText, AutoCloseable {
    val modelFormat = AsrModelFiles.format(modelDir)
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.${modelFormat.extension}").absolutePath,
                    decoder = File(modelDir, "decoder.int8.${modelFormat.extension}").absolutePath,
                    joiner = File(modelDir, "joiner.int8.${modelFormat.extension}").absolutePath,
                ),
                tokens = File(modelDir, AsrModel.TOKENS).absolutePath,
                numThreads = threads,
                modelType = "transducer",
                provider = AsrModelFiles.provider(modelDir, modelFormat, lowMemory),
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(samples: FloatArray): List<Pair<Double, String>> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return mergeTokens(result.tokens, result.timestamps)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** BPE pieces starting with "▁" (or a space) begin a new word. */
        fun mergeTokens(tokens: Array<String>, timestamps: FloatArray): List<Pair<Double, String>> =
            AsrTokens.merge(tokens.asList(), timestamps.map { it.toDouble() })
    }
}
