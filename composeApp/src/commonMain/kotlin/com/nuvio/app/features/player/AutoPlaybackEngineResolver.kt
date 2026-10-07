package com.nuvio.app.features.player

/**
 * Resolves which concrete internal playback engine [AndroidPlaybackEngine.Auto] should use for
 * the current playback.
 *
 * Ported from NuvioTV's `resolveAutoInternalPlayerEngine()`, which is the source of truth for this
 * policy: anime goes to libmpv (native libass), everything else goes to ExoPlayer, and HDR/Dolby
 * Vision detection takes precedence over anime detection so that HDR anime is not tonemapped to
 * SDR by libmpv.
 *
 * @return the concrete engine to use, or `null` when [playbackEngineSetting] is not
 *   [AndroidPlaybackEngine.Auto] — in that case the caller should use the configured engine as-is.
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

/**
 * Matches both the full country name and the ISO code, because this app's TMDB enrichment rewrites
 * `country` as comma-joined ISO codes (e.g. "US, GB", "JP") while addon metadata supplies full
 * country names (e.g. "Japan"). Matching is done per comma-separated token so that unrelated
 * countries cannot accidentally satisfy the check.
 */
private fun isJapan(country: String?): Boolean =
    country?.split(',')?.any { token ->
        val value = token.trim()
        value.equals("japan", ignoreCase = true) || value.equals("jp", ignoreCase = true)
    } == true
