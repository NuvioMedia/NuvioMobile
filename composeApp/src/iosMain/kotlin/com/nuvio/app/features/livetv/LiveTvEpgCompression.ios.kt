package com.nuvio.app.features.livetv

internal actual fun decompressLiveTvEpgPayload(
    compression: LiveTvEpgCompression,
    payload: ByteArray,
    maxOutputBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload = when (compression) {
    LiveTvEpgCompression.Plain -> LiveTvDecodedEpgPayload(payload, payload.size)
    LiveTvEpgCompression.Gzip,
    LiveTvEpgCompression.Xz,
    LiveTvEpgCompression.Zip,
    -> error("Compressed XMLTV is not supported on iOS yet.")
}
