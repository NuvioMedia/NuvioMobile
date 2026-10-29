package com.nuvio.app.features.details

internal const val SPECIALS_SEASON_NUMBER = 0

internal val metaVideoSeasonEpisodeComparator: Comparator<MetaVideo> =
    compareBy<MetaVideo>(
        { seasonSortKey(it.season) },
        { it.episode ?: Int.MAX_VALUE },
        { it.released ?: "" },
        { it.title },
    )

internal fun normalizeSeasonNumber(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        SPECIALS_SEASON_NUMBER
    } else {
        seasonNumber
    }

internal fun seasonSortKey(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        Int.MAX_VALUE
    } else {
        seasonNumber
    }

internal fun preferredEpisodeNumberForSeason(
    displayedSeasonNumber: Int,
    preferredSeasonNumber: Int?,
    preferredEpisodeNumber: Int?,
): Int? = preferredEpisodeNumber.takeIf { displayedSeasonNumber == preferredSeasonNumber }

internal fun MetaDetails.groupedEpisodesForDisplay(): Map<Int, List<MetaVideo>> {
    val numberedEpisodes = videos.filter { it.season != null || it.episode != null }
    return when {
        numberedEpisodes.isNotEmpty() -> numberedEpisodes
            .sortedWith(metaVideoSeasonEpisodeComparator)
            .groupBy { normalizeSeasonNumber(it.season) }
        type != "series" && videos.isNotEmpty() -> mapOf(normalizeSeasonNumber(null) to videos)
        else -> emptyMap()
    }
}
