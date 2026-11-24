package com.nuvio.app.features.details

internal fun selectHeroTrailer(trailers: List<MetaTrailer>): MetaTrailer? =
    trailers
        .asSequence()
        .filter { it.url != null || it.isPlayableYouTubeTrailerCandidate() }
        .distinctBy { it.key }
        .maxWithOrNull(
            compareBy<MetaTrailer>(
                { it.fromTmdb },
                { it.url != null },
                { it.heroTrailerPriority() },
                { it.publishedAt.orEmpty() },
                { it.size ?: 0 },
                { it.name },
            ),
        )

internal fun MetaTrailer.youtubePlaybackUrl(): String =
    key.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: "https://www.youtube.com/watch?v=$key"

private fun MetaTrailer.isPlayableYouTubeTrailerCandidate(): Boolean =
    key.isNotBlank() && site.equals("YouTube", ignoreCase = true)

private fun MetaTrailer.heroTrailerPriority(): Int {
    val isSeriesTrailer = seasonNumber != null
    val isTrailerType = type.equals("Trailer", ignoreCase = true)
    return when {
        !isSeriesTrailer && isTrailerType && official -> 70
        !isSeriesTrailer && isTrailerType -> 60
        !isSeriesTrailer && official -> 50
        !isSeriesTrailer -> 40
        isTrailerType && official -> 30
        isTrailerType -> 20
        official -> 10
        else -> 0
    }
}
