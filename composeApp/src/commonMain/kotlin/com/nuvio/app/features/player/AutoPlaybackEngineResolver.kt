package com.nuvio.app.features.player

/**
 * Mirrors NuvioTV's `resolveAutoInternalPlayerEngine()`: anime goes to libmpv, everything else to
 * ExoPlayer, HDR/Dolby Vision takes precedence over anime.
 */
internal fun resolveAutoPlaybackEngine(
    playbackEngineSetting: AndroidPlaybackEngine,
    videoId: String?,
    streamFilename: String?,
    streamName: String?,
    streamDescription: String?,
    contentTitle: String?,
    genres: List<String>,
    country: String?,
): AndroidPlaybackEngine? {
    if (playbackEngineSetting != AndroidPlaybackEngine.Auto) return null

    val streamMetadataText = buildString {
        streamFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        streamDescription?.let { appendLine(it) }
        append(contentTitle.orEmpty())
    }

    // HDR / Dolby Vision takes precedence: libmpv would improperly tonemap it to SDR.
    if (HDR_OR_DOLBY_VISION_REGEX.containsMatchIn(streamMetadataText)) {
        return AndroidPlaybackEngine.ExoPlayer
    }

    val hasAnimeId = videoId?.startsWith("kitsu:") == true ||
        videoId?.startsWith("mal:") == true ||
        videoId?.startsWith("anilist:") == true
    if (hasAnimeId) return AndroidPlaybackEngine.Libmpv

    if (genres.any { it.equals("anime", ignoreCase = true) }) {
        return AndroidPlaybackEngine.Libmpv
    }

    val isAnimationFromJapan = genres.any { it.equals("animation", ignoreCase = true) } &&
        isJapan(country)
    if (isAnimationFromJapan) return AndroidPlaybackEngine.Libmpv

    return AndroidPlaybackEngine.ExoPlayer
}

private val HDR_OR_DOLBY_VISION_REGEX = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""")

// TMDB enrichment rewrites `country` as comma-joined ISO codes ("US, GB"), addon metadata as
// full names ("Japan") — match whole comma-separated tokens.
private fun isJapan(country: String?): Boolean =
    country?.split(',')?.any { token ->
        val value = token.trim()
        value.equals("japan", ignoreCase = true) || value.equals("jp", ignoreCase = true)
    } == true
