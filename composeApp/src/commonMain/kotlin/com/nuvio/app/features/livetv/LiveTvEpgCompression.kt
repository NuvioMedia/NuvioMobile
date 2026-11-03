package com.nuvio.app.features.livetv

private const val MAX_COMPRESSED_EPG_BYTES = 16 * 1024 * 1024
internal const val MAX_DECOMPRESSED_EPG_BYTES = 40 * 1024 * 1024

internal enum class LiveTvEpgCompression {
    Plain,
    Gzip,
    Xz,
    Zip,
}

internal data class LiveTvDecodedEpgPayload(
    val bytes: ByteArray,
    val size: Int,
)

internal fun detectLiveTvEpgCompression(url: String, payload: ByteArray): LiveTvEpgCompression {
    if (payload.startsWithBytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) {
        return LiveTvEpgCompression.Xz
    }
    if (payload.startsWithBytes(0x1F, 0x8B)) {
        return LiveTvEpgCompression.Gzip
    }
    if (
        payload.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
        payload.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
        payload.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    ) {
        return LiveTvEpgCompression.Zip
    }
    val normalizedUrl = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalizedUrl.endsWith(".xz") -> LiveTvEpgCompression.Xz
        normalizedUrl.endsWith(".gz") || normalizedUrl.endsWith(".gzip") -> LiveTvEpgCompression.Gzip
        normalizedUrl.endsWith(".zip") -> LiveTvEpgCompression.Zip
        else -> LiveTvEpgCompression.Plain
    }
}

internal fun maxLiveTvEpgDownloadBytes(url: String): Int {
    val normalizedUrl = url.substringBefore('?').substringBefore('#').lowercase()
    return if (
        normalizedUrl.endsWith(".xz") ||
        normalizedUrl.endsWith(".gz") ||
        normalizedUrl.endsWith(".gzip") ||
        normalizedUrl.endsWith(".zip")
    ) {
        MAX_COMPRESSED_EPG_BYTES
    } else {
        MAX_DECOMPRESSED_EPG_BYTES
    }
}

internal fun decodeLiveTvEpgPayload(
    url: String,
    payload: ByteArray,
    onProgress: () -> Unit = {},
): String {
    onProgress()
    val compression = detectLiveTvEpgCompression(url, payload)
    val inputLimit = if (compression == LiveTvEpgCompression.Plain) {
        MAX_DECOMPRESSED_EPG_BYTES
    } else {
        MAX_COMPRESSED_EPG_BYTES
    }
    require(payload.size <= inputLimit) { "XMLTV payload exceeds the safe size limit." }

    val decoded = decompressLiveTvEpgPayload(
        compression = compression,
        payload = payload,
        maxOutputBytes = MAX_DECOMPRESSED_EPG_BYTES,
        onProgress = onProgress,
    )
    require(decoded.size <= MAX_DECOMPRESSED_EPG_BYTES) {
        "Decompressed XMLTV payload exceeds the safe size limit."
    }
    return decoded.bytes.decodeToString(endIndex = decoded.size)
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index ->
        this[index].toInt() and 0xFF == expected[index]
    }

internal expect fun decompressLiveTvEpgPayload(
    compression: LiveTvEpgCompression,
    payload: ByteArray,
    maxOutputBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload
