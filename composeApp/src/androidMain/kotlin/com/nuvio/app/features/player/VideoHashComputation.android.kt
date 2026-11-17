package com.nuvio.app.features.player

internal actual suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>? {
    val result = OpenSubtitlesHasher.compute(url, headers) ?: return null
    return result.hash to result.fileSize
}
