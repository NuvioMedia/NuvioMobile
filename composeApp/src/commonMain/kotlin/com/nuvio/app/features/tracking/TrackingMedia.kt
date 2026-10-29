package com.nuvio.app.features.tracking

enum class TrackingMediaKind {
    MOVIE,
    SHOW,
    ANIME,
}

data class TrackingExternalIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: String? = null,
    val trakt: Long? = null,
    val simkl: Long? = null,
    val mal: Long? = null,
    val anidb: Long? = null,
    val anilist: Long? = null,
    val kitsu: Long? = null,
    val mdblist: String? = null,
) {
    val hasAny: Boolean
        get() = !imdb.isNullOrBlank() ||
            tmdb != null ||
            !tvdb.isNullOrBlank() ||
            trakt != null ||
            simkl != null ||
            mal != null ||
            anidb != null ||
            anilist != null ||
            kitsu != null || !mdblist.isNullOrBlank()

    fun mergeMissing(other: TrackingExternalIds): TrackingExternalIds =
        TrackingExternalIds(
            imdb = imdb?.takeIf(String::isNotBlank) ?: other.imdb,
            tmdb = tmdb ?: other.tmdb,
            tvdb = tvdb?.takeIf(String::isNotBlank) ?: other.tvdb,
            trakt = trakt ?: other.trakt,
            simkl = simkl ?: other.simkl,
            mal = mal ?: other.mal,
            anidb = anidb ?: other.anidb,
            anilist = anilist ?: other.anilist,
            kitsu = kitsu ?: other.kitsu,
            mdblist = mdblist ?: other.mdblist,
        )
}

data class TrackingEpisode(
    val season: Int? = null,
    val number: Int,
    val title: String? = null,
    val tmdbId: Long? = null,
    val tvdbId: Long? = null,
    val usesTvdbSeasonMapping: Boolean = false,
)

data class TrackingCatalogReference(
    val contentId: String,
    val contentType: String,
    val videoId: String? = null,
)

data class TrackingMediaReference(
    val kind: TrackingMediaKind,
    val title: String? = null,
    val year: Int? = null,
    val ids: TrackingExternalIds = TrackingExternalIds(),
    val episode: TrackingEpisode? = null,
    val catalog: TrackingCatalogReference? = null,
    val posterUrl: String? = null,
) {
    val hasResolvableIdentity: Boolean
        get() = ids.hasAny || !title.isNullOrBlank()

    val stableKey: String
        get() = buildString {
            append(kind.name.lowercase())
            append(':')
            append(
                ids.simkl?.let { "simkl:$it" }
                    ?: ids.imdb?.let { "imdb:$it" }
                    ?: ids.tmdb?.let { "tmdb:$it" }
                    ?: ids.tvdb?.let { "tvdb:$it" }
                    ?: ids.trakt?.let { "trakt:$it" }
                    ?: ids.mal?.let { "mal:$it" }
                    ?: ids.anidb?.let { "anidb:$it" }
                    ?: ids.anilist?.let { "anilist:$it" }
                    ?: ids.kitsu?.let { "kitsu:$it" }
                    ?: ids.mdblist?.let { "mdblist:$it" }
                    ?: "title:${title.orEmpty().trim().lowercase()}:${year ?: 0}",
            )
        }
}

/**
 * Parses the canonical IDs Nuvio receives from addons and tracker-backed lists.
 * An unprefixed number retains its legacy Trakt meaning; it is never guessed to
 * be a Simkl ID.
 */
fun parseTrackingExternalIds(rawValue: String?): TrackingExternalIds {
    if (rawValue.isNullOrBlank()) return TrackingExternalIds()
    val full = rawValue.trim()

    if (full.startsWith("tt", ignoreCase = true)) {
        return TrackingExternalIds(imdb = full.substringBefore(':'))
    }

    val prefix = full.substringBefore(':').lowercase()
    val value = full.substringAfter(':', missingDelimiterValue = "").substringBefore(':').trim()
    return when (prefix) {
        "imdb" -> TrackingExternalIds(imdb = value.takeIf(String::isNotBlank))
        "tmdb" -> TrackingExternalIds(tmdb = value.toLongOrNull())
        "tvdb" -> TrackingExternalIds(tvdb = value.takeIf(String::isNotBlank))
        "trakt" -> TrackingExternalIds(trakt = value.toLongOrNull())
        "simkl" -> TrackingExternalIds(simkl = value.toLongOrNull())
        "mal" -> TrackingExternalIds(mal = value.toLongOrNull())
        "anidb" -> TrackingExternalIds(anidb = value.toLongOrNull())
        "anilist" -> TrackingExternalIds(anilist = value.toLongOrNull())
        "kitsu" -> TrackingExternalIds(kitsu = value.toLongOrNull())
        "mdblist" -> TrackingExternalIds(mdblist = value.takeIf(String::isNotBlank))
        else -> TrackingExternalIds(trakt = full.toLongOrNull())
    }
}

fun trackingMediaKind(contentType: String, ids: TrackingExternalIds = TrackingExternalIds()): TrackingMediaKind {
    val normalized = contentType.trim().lowercase()
    val hasAnimeIds = ids.mal != null || ids.anidb != null || ids.anilist != null || ids.kitsu != null
    return when {
        hasAnimeIds || normalized == "anime" -> TrackingMediaKind.ANIME
        normalized in setOf("movie", "film") -> TrackingMediaKind.MOVIE
        else -> TrackingMediaKind.SHOW
    }
}

fun extractTrackingYear(value: String?): Int? =
    value
        ?.let { YEAR_PATTERN.find(it)?.value }
        ?.toIntOrNull()

fun buildTrackingMediaReference(
    contentType: String,
    parentMetaId: String,
    videoId: String? = null,
    title: String? = null,
    releaseInfo: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
): TrackingMediaReference {
    val ids = parseTrackingExternalIds(parentMetaId)
        .mergeMissing(parseTrackingExternalIds(videoId))
    return TrackingMediaReference(
        kind = trackingMediaKind(contentType, ids),
        title = title?.trim()?.takeIf(String::isNotBlank),
        year = extractTrackingYear(releaseInfo),
        ids = ids,
        episode = episodeNumber?.let { number ->
            TrackingEpisode(
                season = seasonNumber,
                number = number,
                title = episodeTitle,
            )
        },
        catalog = TrackingCatalogReference(
            contentId = parentMetaId.trim(),
            contentType = contentType.trim(),
            videoId = videoId?.trim()?.takeIf(String::isNotBlank),
        ),
    )
}

private val YEAR_PATTERN = Regex("(19|20)\\d{2}")
