package com.nuvio.app.features.livetv

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

private const val MAX_XZ_DECODER_MEMORY_KIB = 72 * 1024

internal actual fun decompressLiveTvEpgPayload(
    compression: LiveTvEpgCompression,
    payload: ByteArray,
    maxOutputBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload {
    if (compression == LiveTvEpgCompression.Plain) {
        return LiveTvDecodedEpgPayload(payload, payload.size)
    }
    if (compression == LiveTvEpgCompression.Zip) {
        return readXmlTvZipEntry(payload, maxOutputBytes, onProgress)
    }

    val compressedInput = ByteArrayInputStream(payload)
    val decodedInput = when (compression) {
        LiveTvEpgCompression.Plain -> compressedInput
        LiveTvEpgCompression.Gzip -> GZIPInputStream(compressedInput)
        LiveTvEpgCompression.Xz -> XZInputStream(compressedInput, MAX_XZ_DECODER_MEMORY_KIB)
        LiveTvEpgCompression.Zip -> error("ZIP is handled before stream selection")
    }
    val estimatedSize = (payload.size.toLong() * ESTIMATED_COMPRESSION_RATIO)
        .coerceIn(DEFAULT_BUFFER_SIZE.toLong(), maxOutputBytes.toLong())
        .toInt()
    return decodedInput.use { input ->
        input.readBytesWithLimit(
            maxBytes = maxOutputBytes,
            initialCapacity = estimatedSize,
            onProgress = onProgress,
        )
    }
}

private fun readXmlTvZipEntry(
    payload: ByteArray,
    maxOutputBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload {
    val preferredEntry = findZipEntry(payload) { name ->
        val normalized = name.lowercase()
        normalized.endsWith(".xml") || normalized.endsWith(".xmltv")
    } ?: findZipEntry(payload) { true }
    requireNotNull(preferredEntry) { "ZIP archive does not contain an XMLTV file." }

    return ZipInputStream(ByteArrayInputStream(payload)).use { input ->
        while (true) {
            onProgress()
            val entry = input.nextEntry ?: error("ZIP XMLTV entry could not be read.")
            if (!entry.isDirectory && entry.name == preferredEntry) {
                return@use input.readBytesWithLimit(
                    maxBytes = maxOutputBytes,
                    initialCapacity = DEFAULT_BUFFER_SIZE,
                    onProgress = onProgress,
                )
            }
        }
        error("ZIP XMLTV entry could not be read.")
    }
}

private fun findZipEntry(payload: ByteArray, accepts: (String) -> Boolean): String? =
    ZipInputStream(ByteArrayInputStream(payload)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: return@use null
            if (!entry.isDirectory && accepts(entry.name)) return@use entry.name
        }
        null
    }

private fun InputStream.readBytesWithLimit(
    maxBytes: Int,
    initialCapacity: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload {
    var output = ByteArray(initialCapacity.coerceIn(1, maxBytes))
    var totalBytes = 0
    while (true) {
        onProgress()
        if (totalBytes == output.size) {
            if (output.size == maxBytes) {
                require(read() < 0) { "Decompressed XMLTV payload exceeds the safe size limit." }
                break
            }
            output = output.copyOf(minOf(maxBytes, output.size * 2))
        }
        val bytesRead = read(output, totalBytes, output.size - totalBytes)
        if (bytesRead < 0) break
        totalBytes += bytesRead
    }
    return LiveTvDecodedEpgPayload(output, totalBytes)
}

private const val ESTIMATED_COMPRESSION_RATIO = 24L
