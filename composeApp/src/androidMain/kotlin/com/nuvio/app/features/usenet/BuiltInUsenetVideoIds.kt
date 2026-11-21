package com.nuvio.app.features.usenet

/** The content types the built-in Usenet search covers. */
internal val BUILT_IN_USENET_TYPES = setOf("movie", "series", "tv", "show")

/** Season and episode from an episode video id (tt123:1:2, tmdb:10:1:2); nulls when it has none. */
internal fun episodeNumbers(videoId: String): Pair<Int?, Int?> {
    val parts = videoId.split(':')
    if (parts.size < if (videoId.startsWith("tmdb:")) 4 else 3) return null to null
    return parts[parts.lastIndex - 1].toIntOrNull() to parts.last().toIntOrNull()
}

/**
 * Whether the built-in search can look up [videoId]: a movie, or an episode that names its season
 * and episode. A series id alone is not searched, so it must not count as playable.
 */
internal fun builtInUsenetCanSearch(type: String, videoId: String): Boolean {
    val kind = type.lowercase()
    if (kind !in BUILT_IN_USENET_TYPES) return false
    if (kind == "movie") return true
    val (season, episode) = episodeNumbers(videoId)
    return season != null && season >= 0 && episode != null && episode >= 1
}
