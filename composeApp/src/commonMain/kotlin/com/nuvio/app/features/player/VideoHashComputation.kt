package com.nuvio.app.features.player

/**
 * Computes the OpenSubtitles-style file hash for a remote video stream.
 *
 * Returns a pair of `(hash, fileSize)` on success, or `null` when the hash
 * cannot be computed (e.g. server does not support range requests, file is
 * too small, or the platform has no implementation).
 */
internal expect suspend fun computeVideoHash(
    url: String,
    headers: Map<String, String>,
): Pair<String, Long>?
