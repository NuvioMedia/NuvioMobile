package com.nuvio.app.features.audiosync.asr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

// Downloads are hashed before publication. Readiness only stats the files, avoiding a
// second 74 MB read on every cold start; partial files never have these final names.
fun AsrModelFiles.isComplete(directory: File, assets: List<AsrModelFiles.Asset>): Boolean =
    assets.all { asset -> File(directory, asset.name).let { it.isFile && it.length() == asset.size } }

fun AsrModelFiles.format(directory: File): AsrModelFiles.Format = when {
    isComplete(directory, optimized) -> AsrModelFiles.Format.ORT
    isComplete(directory, legacy) -> AsrModelFiles.Format.ONNX
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
fun AsrModelFiles.provider(directory: File, format: AsrModelFiles.Format, lowMemory: Boolean = false): String {
    val settings = providerSettings(format, lowMemory)
    if (settings.isEmpty()) return "cpu"
    val config = File(directory, "cpu.conf")
    val text = settings.joinToString(separator = "\n", postfix = "\n")
    if (!config.isFile || config.readText() != text) config.writeText(text)
    return "cpu:${config.absolutePath}"
}

/** A failed or truncated transfer cannot overwrite an existing final file. */
fun AsrModelFiles.saveVerified(input: InputStream, directory: File, asset: AsrModelFiles.Asset, onBytes: (Long) -> Unit = {}) {
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
