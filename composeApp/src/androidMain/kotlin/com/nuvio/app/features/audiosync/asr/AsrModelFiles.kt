package com.nuvio.app.features.audiosync.asr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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

    // Downloads are hashed before publication. Readiness only stats the files, avoiding a
    // second 74 MB read on every cold start; partial files never have these final names.
    fun isComplete(directory: File, assets: List<Asset>): Boolean =
        assets.all { asset -> File(directory, asset.name).let { it.isFile && it.length() == asset.size } }

    fun format(directory: File): Format = when {
        isComplete(directory, optimized) -> Format.ORT
        isComplete(directory, legacy) -> Format.ONNX
        else -> throw IOException("Speech model files are incomplete")
    }

    /**
     * Runtime settings for [format], in the config file sherpa-onnx reads with the provider. None
     * changes the arithmetic, so transcripts and word times stay exactly the same:
     * - The pre-optimized ORT graph needs no runtime graph optimization.
     * - The ORT model is memory-mapped and its weights used in place instead of copied into private
     *   memory: about 90 MB less while loading and 20 MB less while running, at no cost in speed.
     *   Safe because sherpa-onnx opens it by path and keeps the mapping as long as the session, and
     *   downloads replace files by renaming, never in place.
     * - With [lowMemory], the CPU memory pool is off. The pool grows to the longest segment
     *   recognised and never shrinks, keeping ~290 MB for the whole film; without it about half
     *   of that stays, for 5-10% slower recognition. Elsewhere it stays on for speed.
     * Weight prepacking stays on: off, it saves more memory but costs over 10% in speed.
     */
    fun provider(directory: File, format: Format, lowMemory: Boolean = false): String {
        val settings = buildList {
            if (format == Format.ORT) {
                add("GraphOptimizationLevel=0")
                add("SessionConfig.session.use_memory_mapped_ort_model=1")
                add("SessionConfig.session.use_ort_model_bytes_for_initializers=1")
            }
            if (lowMemory) add("EnableCpuMemArena=0")
        }
        if (settings.isEmpty()) return "cpu"
        val config = File(directory, "cpu.conf")
        val text = settings.joinToString(separator = "\n", postfix = "\n")
        if (!config.isFile || config.readText() != text) config.writeText(text)
        return "cpu:${config.absolutePath}"
    }

    /** A failed or truncated transfer cannot overwrite an existing final file. */
    fun saveVerified(input: InputStream, directory: File, asset: Asset, onBytes: (Long) -> Unit = {}) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create model directory" }
        val target = File(directory, asset.name)
        val partial = File(directory, asset.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastReport = 0L
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > asset.size) throw IOException("Model file exceeds expected size")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (written - lastReport >= 512 * 1024) {
                        lastReport = written
                        onBytes(written)
                    }
                }
            }
            if (written != asset.size) throw IOException("Model file size $written != ${asset.size}")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != asset.sha256) throw IOException("Model file checksum mismatch")
            if (!partial.renameTo(target)) throw IOException("Could not save model file")
            onBytes(written)
        } finally {
            partial.delete()
        }
    }
}
