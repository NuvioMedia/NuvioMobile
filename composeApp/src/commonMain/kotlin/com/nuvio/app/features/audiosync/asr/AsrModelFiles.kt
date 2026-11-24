package com.nuvio.app.features.audiosync.asr

/** Pinned models: the portable CPU ORT conversion, and the upstream ONNX files it was made from as a fallback. */
object AsrModelFiles {
    data class Asset(val name: String, val size: Long, val sha256: String)
    enum class Format(val extension: String) { ORT("ort"), ONNX("onnx") }

    val optimized = listOf(
        Asset("encoder.int8.ort", 72_881_256L, "c35d7f458e9c0dd87e3d0b4399cc19c5345549d0b8bc9947aec902b82afb5bb2"),
        Asset("decoder.int8.ort", 547_512L, "ec8ef35b880d1dfaf665bd790ce80b416e5334a78d45527f2049ee30a65cd60b"),
        Asset("joiner.int8.ort", 262_152L, "e02f89cd9a95afc7d02bcadf466cc9003ca5b2b1fd88ee7e6ffd14c35e713639"),
        Asset("tokens.txt", 5_020L, "0ef7d736bf4de3ef947292e4b119ef13f6808cd5f3aec225a843a7135ac1c2ce"),
    )
    val legacy = listOf(
        Asset("encoder.int8.onnx", 72_850_738L, "d3eff4b1bd747bd781a47966795988539227d05638524b0313f26d3a166962d7"),
        Asset("decoder.int8.onnx", 540_688L, "2b0580feb757696fa929922670b7126aaee609630349a1809fd5a2aee926d56e"),
        Asset("joiner.int8.onnx", 259_417L, "80160e45cca71dd52f6b0a6d3d12be18126f5308b2d4ba03f001300fea377c64"),
        optimized.last(),
    )

    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74
    const val ONNX_VERSION = "gigaspeech-2023-12-12"
    const val ORT_VERSION = "$ONNX_VERSION-ort-v1"

    /**
     * Where the ORT conversion of the upstream files below is hosted, or null to use the upstream
     * ONNX files only. Should only ever point at a Nuvio-controlled release; the files are pinned
     * in [optimized] either way.
     */
    val ORT_DOWNLOAD: String? =
        "https://github.com/NuvioMedia/speech-models/releases/download/$ORT_VERSION/"

    /** The upstream model repository, pinned to a commit so its files cannot change under us. */
    const val ONNX_DOWNLOAD =
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/f7515a672ffb69232794aaaa44a0a360f7f25cd1/"

    /** Upstream names of [legacy], by local name. */
    val ONNX_UPSTREAM_NAMES = mapOf(
        "encoder.int8.onnx" to "encoder-epoch-30-avg-1.int8.onnx",
        "decoder.int8.onnx" to "decoder-epoch-30-avg-1.int8.onnx",
        "joiner.int8.onnx" to "joiner-epoch-30-avg-1.int8.onnx",
        TOKENS to TOKENS,
    )

    fun providerSettings(format: Format, lowMemory: Boolean): List<String> = buildList {
        if (format == Format.ORT) {
            add("GraphOptimizationLevel=0")
            add("SessionConfig.session.use_memory_mapped_ort_model=1")
            add("SessionConfig.session.use_ort_model_bytes_for_initializers=1")
        }
        if (lowMemory) add("EnableCpuMemArena=0")
    }
}
