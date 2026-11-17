package com.nuvio.app.features.details

import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.core.time.parseIsoCalendarDate
import com.nuvio.app.features.library.LibraryClock

internal data class AddonEpisodeAirDate(
    val season: Int?,
    val episode: Int?,
    val released: String?,
)

internal data class ExternalEpisodeRating(
    val season: Int,
    val episode: Int,
    val airDate: String?,
    val rating: Double?,
)

private data class DatedEpisode(
    val season: Int,
    val episode: Int,
    val day: Long?,
    val rating: Double? = null,
)

private const val MILLIS_PER_DAY = 86_400_000L

internal fun alignEpisodeRatings(
    addonEpisodes: List<AddonEpisodeAirDate>,
    externalEpisodes: List<ExternalEpisodeRating>,
    today: Long = LibraryClock.nowEpochMs() / MILLIS_PER_DAY,
): Map<Pair<Int, Int>, Double> {
    val byNumber = externalEpisodes
        .mapNotNull { episode -> episode.rating?.let { (episode.season to episode.episode) to it } }
        .toMap()

    val addonRegular = addonEpisodes
        .mapNotNull { addon ->
            val season = addon.season?.takeIf { it > 0 } ?: return@mapNotNull null
            val episode = addon.episode ?: return@mapNotNull null
            DatedEpisode(season, episode, parseAirDay(addon.released))
        }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy(DatedEpisode::season, DatedEpisode::episode))
    val externalRegular = externalEpisodes
        .filter { it.season > 0 }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy(ExternalEpisodeRating::season, ExternalEpisodeRating::episode))
        .map { DatedEpisode(it.season, it.episode, parseAirDay(it.airDate), it.rating) }

    if (addonRegular.isEmpty() || externalRegular.isEmpty()) return byNumber
    if (addonRegular.map { it.season }.toSet() == externalRegular.map { it.season }.toSet()) return byNumber

    val addonAired = addonRegular.filter { it.day == null || it.day <= today }
    val externalAired = externalRegular.filter { it.day == null || it.day <= today }
    if (addonAired.size == externalAired.size) {
        return buildAlignedRatings(byNumber, addonAired.zip(externalAired))
    }

    val addonByDay = addonRegular.filter { it.day != null }.groupBy { it.day!! }
    val externalByDay = externalRegular.filter { it.day != null }.groupBy { it.day!! }
    if (addonByDay.isEmpty() || externalByDay.isEmpty()) return byNumber

    val matches = addonByDay.flatMap { (day, sameDayAddon) ->
        val sameDayExternal = (externalByDay[day] ?: externalByDay[day - 1] ?: externalByDay[day + 1])
            ?.takeIf { it.size == sameDayAddon.size }
            ?: return@flatMap emptyList()
        sameDayAddon.zip(sameDayExternal)
    }
    return buildAlignedRatings(byNumber, matches)
}

private fun buildAlignedRatings(
    byNumber: Map<Pair<Int, Int>, Double>,
    matches: List<Pair<DatedEpisode, DatedEpisode>>,
): Map<Pair<Int, Int>, Double> = buildMap {
    byNumber.forEach { (key, rating) -> if (key.first == 0) put(key, rating) }
    matches.forEach { (addon, external) ->
        external.rating?.let { put(addon.season to addon.episode, it) }
    }
}

private fun parseAirDay(value: String?): Long? =
    parseIsoCalendarDate(value?.trim()?.take(10))?.let(::isoEpochDay)
