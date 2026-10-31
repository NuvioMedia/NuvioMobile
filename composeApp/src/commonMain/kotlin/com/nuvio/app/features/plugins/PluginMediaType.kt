package com.nuvio.app.features.plugins

/** Restore the declared episodic JS type after the TMDB bridge has mapped series to tv. */
internal fun jsPluginMediaType(
    mediaType: String,
    supportedTypes: List<String>,
    season: Int?,
    episode: Int?
): String = if (
    mediaType.equals("tv", ignoreCase = true) && season != null && episode != null &&
    supportedTypes.any { it.equals("series", ignoreCase = true) }
) "series" else mediaType
