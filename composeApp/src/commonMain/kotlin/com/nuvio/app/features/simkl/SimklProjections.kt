package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingCatalogReference
import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.extractTrackingYear
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.tracking.trackingMediaKind
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceSimklPlayback
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

internal data class SimklWatchedProjection(
    val items: List<WatchedItem>,
    val fullyWatchedSeriesKeys: Set<String>,
)

internal fun SimklSyncSnapshot.toSimklWatchedProjection(): SimklWatchedProjection {
    val watchedItems = mutableListOf<WatchedItem>()
    val fullyWatched = linkedSetOf<String>()

    entries.forEach { entry ->
        val media = entry.media ?: return@forEach
        val contentId = media.canonicalContentId() ?: return@forEach
        val contentType = if (entry.isMovieEntry()) "movie" else "series"
        val title = media.title?.takeIf(String::isNotBlank) ?: contentId
        val poster = entry.resolvedPosterUrl()
        val trackingProviderItemId = media.simklTrackingProviderItemId()
        val trackingSourceUrl = buildSimklSourceUrl(entry.mediaType, media)
        val lastWatchedAt = parseSimklUtcEpochMs(entry.lastWatchedAt)
            ?: parseSimklUtcEpochMs(entry.addedToWatchlistAt)
            ?: 0L

        if (entry.isMovieEntry()) {
            if (entry.lastWatchedAt != null || entry.status == SimklListStatus.COMPLETED) {
                watchedItems += WatchedItem(
                    id = contentId,
                    type = contentType,
                    name = title,
                    poster = poster,
                    releaseInfo = media.year?.toString(),
                    trackingProviderId = TrackingProviderId.SIMKL.storageId,
                    trackingProviderItemId = trackingProviderItemId,
                    trackingSourceUrl = trackingSourceUrl,
                    markedAtEpochMs = lastWatchedAt,
                )
            }
            return@forEach
        }

        var hasEpisodeHistory = false
        entry.seasons.forEach seasonLoop@{ season ->
            season.episodes.forEach episodeLoop@{ episode ->
                val seasonNumber = episode.tvdb?.season ?: season.number ?: return@episodeLoop
                val episodeNumber = episode.tvdb?.episode ?: episode.number ?: return@episodeLoop
                val watchedAt = parseSimklUtcEpochMs(episode.watchedAt) ?: return@episodeLoop
                hasEpisodeHistory = true
                watchedItems += WatchedItem(
                    id = contentId,
                    type = contentType,
                    name = title,
                    poster = poster,
                    releaseInfo = media.year?.toString(),
                    season = seasonNumber,
                    episode = episodeNumber,
                    trackingProviderId = TrackingProviderId.SIMKL.storageId,
                    trackingProviderItemId = trackingProviderItemId,
                    trackingSourceUrl = trackingSourceUrl,
                    markedAtEpochMs = watchedAt,
                )
            }
        }

        if (entry.status == SimklListStatus.COMPLETED) {
            fullyWatched += watchedItemKey(contentType, contentId)
            if (!hasEpisodeHistory) {
                watchedItems += WatchedItem(
                    id = contentId,
                    type = contentType,
                    name = title,
                    poster = poster,
                    releaseInfo = media.year?.toString(),
                    trackingProviderId = TrackingProviderId.SIMKL.storageId,
                    trackingProviderItemId = trackingProviderItemId,
                    trackingSourceUrl = trackingSourceUrl,
                    markedAtEpochMs = lastWatchedAt,
                )
            }
        }
    }

    val newestByKey = watchedItems
        .groupBy { item -> watchedItemKey(item.type, item.id, item.season, item.episode) }
        .mapNotNull { (_, candidates) -> candidates.maxByOrNull(WatchedItem::markedAtEpochMs) }
        .sortedByDescending(WatchedItem::markedAtEpochMs)
    return SimklWatchedProjection(
        items = newestByKey,
        fullyWatchedSeriesKeys = fullyWatched,
    )
}

/**
 * Builds a set of extra watched keys under all alternate content IDs for anime entries.
 * These are NOT added as items (to avoid CW duplicates), but are used to augment
 * the watchedKeys set so the UI can resolve watched status regardless of which
 * content ID the addon uses.
 */
internal fun SimklSyncSnapshot.animeAlternateWatchedKeys(): Set<String> {
    val extraKeys = linkedSetOf<String>()
    entries.forEach { entry ->
        if (entry.mediaType != SimklMediaType.ANIME) return@forEach
        if (entry.isMovieEntry()) return@forEach
        val media = entry.media ?: return@forEach
        val contentId = media.canonicalContentId() ?: return@forEach
        val contentType = "series"
        val alternateIds = media.alternateContentIds() - contentId

        if (alternateIds.isEmpty()) return@forEach

        entry.seasons.forEach { season ->
            season.episodes.forEach episodeLoop@{ episode ->
                val seasonNumber = episode.tvdb?.season ?: season.number ?: return@episodeLoop
                val episodeNumber = episode.tvdb?.episode ?: episode.number ?: return@episodeLoop
                if (episode.watchedAt == null) return@episodeLoop
                alternateIds.forEach { altId ->
                    extraKeys += watchedItemKey(contentType, altId, seasonNumber, episodeNumber)
                }
            }
        }

        if (entry.status == SimklListStatus.COMPLETED) {
            alternateIds.forEach { altId ->
                extraKeys += watchedItemKey(contentType, altId)
            }
        }
    }
    return extraKeys
}

/**
 * Builds watched keys under alternate content IDs for completed/watched movies.
 * This allows entity browsers showing movies under tmdb: IDs to display watched badges
 * even though the primary watched key uses an IMDB ID.
 */
internal fun SimklSyncSnapshot.movieAlternateWatchedKeys(): Set<String> {
    val extraKeys = linkedSetOf<String>()
    entries.forEach { entry ->
        if (!entry.isMovieEntry()) return@forEach
        if (entry.lastWatchedAt == null && entry.status != SimklListStatus.COMPLETED) return@forEach
        val media = entry.media ?: return@forEach
        val contentId = media.canonicalContentId() ?: return@forEach
        val alternateIds = media.alternateContentIds() - contentId
        alternateIds.forEach { altId ->
            extraKeys += watchedItemKey("movie", altId)
        }
    }
    return extraKeys
}

internal fun SimklSyncSnapshot.toSimklProgressEntries(): List<WatchProgressEntry> =
    playback
        .mapNotNull { session ->
            session.toWatchProgressEntry(libraryEntries = entries)
        }
        .groupBy(WatchProgressEntry::progressKey)
        .mapNotNull { (_, candidates) -> candidates.maxByOrNull(WatchProgressEntry::lastUpdatedEpochMs) }
        .sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)

internal fun SimklSyncSnapshot.toSimklShowIdSiblings(): Map<String, Set<String>> {
    val siblingsMap = mutableMapOf<String, MutableSet<String>>()
    entries.forEach { entry ->
        val media = entry.media ?: return@forEach
        if (entry.mediaType == SimklMediaType.MOVIES) return@forEach
        val keys = media.alternateContentIds().toList()
        if (keys.size <= 1) return@forEach
        for (key in keys) {
            siblingsMap.getOrPut(key) { mutableSetOf() }.addAll(keys - key)
        }
    }
    return siblingsMap.mapValues { (_, siblings) -> siblings.toSet() }
}

internal fun SimklSyncSnapshot.mediaReference(
    contentId: String,
    contentType: String,
    title: String? = null,
    releaseInfo: String? = null,
    season: Int? = null,
    episode: Int? = null,
    episodeTitle: String? = null,
    videoId: String? = null,
    posterUrl: String? = null,
): TrackingMediaReference {
    val entry = entries.firstOrNull { candidate -> candidate.matchesContentId(contentId) }
    val media = entry?.media
    val parsedIds = parseTrackingExternalIds(contentId)
    val ids = media?.toTrackingExternalIds()?.mergeMissing(parsedIds) ?: parsedIds
    val kind = when (entry?.mediaType) {
        SimklMediaType.MOVIES -> TrackingMediaKind.MOVIE
        SimklMediaType.ANIME -> TrackingMediaKind.ANIME
        SimklMediaType.SHOWS -> TrackingMediaKind.SHOW
        null -> trackingMediaKind(contentType, ids)
    }
    return TrackingMediaReference(
        kind = kind,
        title = media?.title?.takeIf(String::isNotBlank) ?: title?.takeIf(String::isNotBlank),
        year = media?.year ?: extractTrackingYear(releaseInfo),
        ids = ids,
        episode = episode?.let { number ->
            TrackingEpisode(season = season, number = number, title = episodeTitle)
        },
        catalog = TrackingCatalogReference(
            contentId = contentId,
            contentType = contentType,
            videoId = videoId?.trim()?.takeIf(String::isNotBlank),
        ),
        posterUrl = posterUrl?.trim()?.takeIf(String::isNotBlank),
    )
}

internal fun SimklSyncSnapshot.enrichMediaReference(reference: TrackingMediaReference): TrackingMediaReference {
    val entry = entries.firstOrNull { candidate ->
        candidate.media?.toTrackingExternalIds()?.sharesIdentityWith(reference.ids) == true
    } ?: return reference
    val media = entry.media ?: return reference
    val kind = when {
        entry.mediaType == SimklMediaType.MOVIES -> TrackingMediaKind.MOVIE
        entry.mediaType == SimklMediaType.ANIME && entry.animeType == "movie" -> TrackingMediaKind.MOVIE
        entry.mediaType == SimklMediaType.SHOWS -> TrackingMediaKind.SHOW
        entry.mediaType == SimklMediaType.ANIME -> TrackingMediaKind.ANIME
        else -> TrackingMediaKind.SHOW
    }
    return reference.copy(
        kind = kind,
        title = media.title?.takeIf(String::isNotBlank) ?: reference.title,
        year = media.year ?: reference.year,
        ids = media.toTrackingExternalIds().mergeMissing(reference.ids),
    )
}

internal fun SimklMedia.toTrackingExternalIds(): TrackingExternalIds = TrackingExternalIds(
    simkl = ids.simklIdValue()?.toLongOrNull(),
    imdb = ids.idValue("imdb"),
    tmdb = ids.idValue("tmdb")?.toLongOrNull(),
    tvdb = ids.idValue("tvdb"),
    mal = ids.idValue("mal")?.toLongOrNull(),
    anidb = ids.idValue("anidb")?.toLongOrNull(),
    anilist = ids.idValue("anilist")?.toLongOrNull(),
    kitsu = ids.idValue("kitsu")?.toLongOrNull(),
)

internal fun SimklMedia.canonicalContentId(): String? =
    canonicalContentId(TrackingSettingsRepository.uiState.value.simklAnimeIdPreference)

/**
 * Resolves the canonical content ID for this media entry.
 *
 * For non-anime content the priority is always IMDB → TMDB → TVDB → (fallback chain).
 * For anime, the [animeIdPreference] allows the user to choose which ID system takes priority:
 * - [SimklAnimeIdPreference.IMDB]: standard fallback (IMDB wins, seasons get grouped)
 * - [SimklAnimeIdPreference.MAL]: prefer MAL ID so each season is separate
 * - [SimklAnimeIdPreference.KITSU]: prefer Kitsu ID so each season is separate
 *
 * The function is also available without the preference parameter for call-sites that
 * don't have anime context (movies/shows always use the default chain).
 */
internal fun SimklMedia.canonicalContentId(animeIdPreference: SimklAnimeIdPreference): String? {
    val hasAnimeIds = !ids.idValue("mal").isNullOrBlank() ||
        !ids.idValue("kitsu").isNullOrBlank() ||
        !ids.idValue("anidb").isNullOrBlank()

    if (hasAnimeIds) {
        when (animeIdPreference) {
            SimklAnimeIdPreference.MAL -> {
                ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { return "mal:$it" }
                ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { return "kitsu:$it" }
                ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { return "anidb:$it" }
            }
            SimklAnimeIdPreference.KITSU -> {
                ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { return "kitsu:$it" }
                ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { return "mal:$it" }
                ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { return "anidb:$it" }
            }
            SimklAnimeIdPreference.TVDB -> {
                ids.idValue("tvdb")?.takeIf(String::isNotBlank)?.let { return "tvdb:$it" }
            }
            SimklAnimeIdPreference.IMDB -> Unit
        }
    }
    // Standard fallback chain
    return when {
        !ids.idValue("imdb").isNullOrBlank() -> ids.idValue("imdb")
        !ids.idValue("tmdb").isNullOrBlank() -> "tmdb:${ids.idValue("tmdb")}"
        !ids.idValue("tvdb").isNullOrBlank() -> "tvdb:${ids.idValue("tvdb")}"
        !ids.idValue("kitsu").isNullOrBlank() -> "kitsu:${ids.idValue("kitsu")}"
        !ids.idValue("mal").isNullOrBlank() -> "mal:${ids.idValue("mal")}"
        !ids.idValue("anidb").isNullOrBlank() -> "anidb:${ids.idValue("anidb")}"
        !ids.idValue("anilist").isNullOrBlank() -> "anilist:${ids.idValue("anilist")}"
        !ids.idValue("kitsu").isNullOrBlank() -> "kitsu:${ids.idValue("kitsu")}"
        !ids.simklIdValue().isNullOrBlank() -> "simkl:${ids.simklIdValue()}"
        else -> null
    }
}

/**
 * Returns all content IDs (IMDB, MAL, AniDB, AniList, Kitsu, etc.) that this media entry
 * is known under. Used to emit watched items under all possible IDs for anime entries so
 * that the UI can find them regardless of which content ID the addon uses.
 *
 * When the user prefers MAL or Kitsu as the canonical anime ID, only the preferred ID type
 * is emitted to prevent duplicates in continue watching and watched badge resolution.
 */
private fun SimklMedia.alternateContentIds(): Set<String> {
    val preference = TrackingSettingsRepository.uiState.value.simklAnimeIdPreference
    return when (preference) {
        SimklAnimeIdPreference.IMDB -> buildSet {
            ids.idValue("imdb")?.takeIf(String::isNotBlank)?.let(::add)
            ids.idValue("tmdb")?.takeIf(String::isNotBlank)?.let { add("tmdb:$it") }
            ids.idValue("tvdb")?.takeIf(String::isNotBlank)?.let { add("tvdb:$it") }
            ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { add("mal:$it") }
            ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { add("anidb:$it") }
            ids.idValue("anilist")?.takeIf(String::isNotBlank)?.let { add("anilist:$it") }
            ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { add("kitsu:$it") }
            ids.simklIdValue()?.takeIf(String::isNotBlank)?.let { add("simkl:$it") }
        }
        SimklAnimeIdPreference.MAL -> buildSet {
            ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { add("mal:$it") }
            ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { add("kitsu:$it") }
            ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { add("anidb:$it") }
        }
        SimklAnimeIdPreference.KITSU -> buildSet {
            ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { add("kitsu:$it") }
            ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { add("mal:$it") }
            ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { add("anidb:$it") }
        }
        SimklAnimeIdPreference.TVDB -> buildSet {
            ids.idValue("tvdb")?.takeIf(String::isNotBlank)?.let { add("tvdb:$it") }
            ids.idValue("imdb")?.takeIf(String::isNotBlank)?.let(::add)
            ids.idValue("tmdb")?.takeIf(String::isNotBlank)?.let { add("tmdb:$it") }
            ids.idValue("mal")?.takeIf(String::isNotBlank)?.let { add("mal:$it") }
            ids.idValue("anidb")?.takeIf(String::isNotBlank)?.let { add("anidb:$it") }
            ids.idValue("anilist")?.takeIf(String::isNotBlank)?.let { add("anilist:$it") }
            ids.idValue("kitsu")?.takeIf(String::isNotBlank)?.let { add("kitsu:$it") }
            ids.simklIdValue()?.takeIf(String::isNotBlank)?.let { add("simkl:$it") }
        }
    }
}

internal fun simklPosterUrl(path: String?): String? =
    path
        ?.trim()
        ?.trim('/')
        ?.takeIf(String::isNotBlank)
        ?.let { normalized -> "https://wsrv.nl/?url=https://simkl.in/posters/${normalized}_m.webp&q=90" }

internal fun SimklLibraryEntry.resolvedPosterUrl(): String? =
    simklPosterUrl(media?.poster) ?: localPosterUrl?.trim()?.takeIf(String::isNotBlank)

internal fun buildSimklSourceUrl(mediaType: SimklMediaType, media: SimklMedia): String? {
    val id = media.ids.simklIdValue()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val category = when (mediaType) {
        SimklMediaType.MOVIES -> "movies"
        SimklMediaType.SHOWS -> "tv"
        SimklMediaType.ANIME -> "anime"
    }
    val slug = media.ids.idValue("slug")
        ?.trim()
        ?.trim('/')
        ?.takeIf { value -> value.isNotBlank() && value.all { it.isLetterOrDigit() || it in "-_." } }
    return if (slug == null) {
        "https://simkl.com/$category/$id"
    } else {
        "https://simkl.com/$category/$id/$slug"
    }
}

internal fun parseSimklUtcEpochMs(value: String?): Long? {
    val match = value?.trim()?.let(SIMKL_UTC_PATTERN::matchEntire) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    val millis = match.groupValues[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
    if (year < 1970 || month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val monthDays = daysInMonths(year)
    if (day !in 1..monthDays[month - 1]) return null

    var days = 0L
    for (currentYear in 1970 until year) days += if (currentYear.isLeapYear()) 366L else 365L
    for (monthIndex in 0 until month - 1) days += monthDays[monthIndex]
    days += day - 1L
    return (((days * 24L + hour) * 60L + minute) * 60L + second) * 1_000L + millis
}

internal fun SimklPlaybackSession.toWatchProgressEntry(
    libraryEntries: List<SimklLibraryEntry> = emptyList(),
): WatchProgressEntry? {
    val media = media ?: return null
    val parentId = media.canonicalContentId() ?: return null
    val isAnimeMovie = mediaType == SimklMediaType.ANIME && (
        type == "movie" ||
        libraryEntries.any { entry ->
            entry.mediaType == SimklMediaType.ANIME &&
                entry.animeType == "movie" &&
                entry.media?.toTrackingExternalIds()?.sharesIdentityWith(media.toTrackingExternalIds()) == true
        }
    )
    val isMovie = mediaType == SimklMediaType.MOVIES ||
        (mediaType == SimklMediaType.ANIME && episode == null) ||
        isAnimeMovie
    val season = episode?.tvdbSeason ?: episode?.season
    val episodeNumber = episode?.tvdbNumber ?: episode?.number
    if (!isMovie && episodeNumber == null) return null
    val videoId = if (isMovie) {
        parentId
    } else {
        buildPlaybackVideoId(
            parentMetaId = parentId,
            seasonNumber = season,
            episodeNumber = episodeNumber,
        )
    }
    val normalizedProgress = progress.coerceIn(0.0, 100.0)
    // A series reports the runtime of the show, not of the episode, so scaling the percentage by it
    // invents a timecode: an episode stopped at 83 % of 47 minutes resumed at 42 minutes, because
    // 83 % of the show's 52 minutes is 43. Leaving the duration out, with the percentage in place,
    // makes the player scale it by the duration it really has, which is what a Trakt row already does.
    val durationMs = if (isMovie) {
        media.runtime?.takeIf { it > 0 }?.toLong()?.times(60_000L) ?: 0L
    } else {
        0L
    }
    val positionMs = if (durationMs > 0L) (durationMs * normalizedProgress / 100.0).toLong() else 0L
    val updatedAt = parseSimklUtcEpochMs(pausedAt)
        ?: parseSimklUtcEpochMs(watchedAt)
        ?: 0L
    return WatchProgressEntry(
        contentType = if (isMovie) "movie" else "series",
        parentMetaId = parentId,
        parentMetaType = if (isMovie) "movie" else "series",
        videoId = videoId,
        title = media.title?.takeIf(String::isNotBlank) ?: parentId,
        poster = simklPosterUrl(media.poster),
        seasonNumber = season,
        episodeNumber = episodeNumber,
        episodeTitle = episode?.title,
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = updatedAt,
        // A playback session is a position Simkl can resume, not a finished watch. Where a playback
        // ends is the credits marker when there is one, and the app reports a playback that has not
        // reached it as a pause, which Simkl answers by keeping this row with the percentage the user
        // stopped at. Reading that percentage as a finished watch would drop the position out of
        // Continue Watching and offer the next episode instead. A watch the account really recorded
        // arrives through the watched history, and that supersedes this row.
        isCompleted = false,
        progressPercent = normalizedProgress.toFloat(),
        source = WatchProgressSourceSimklPlayback,
        trackingProviderId = TrackingProviderId.SIMKL.storageId,
        trackingProviderItemId = media.simklTrackingProviderItemId(),
        trackingSourceUrl = buildSimklSourceUrl(mediaType, media),
        progressKey = id?.let { "simkl-playback:$it" }
            ?: "simkl-playback:$parentId:${season ?: -1}:${episodeNumber ?: -1}",
    )
}

internal fun SimklMedia.simklTrackingProviderItemId(): String? =
    ids.simklIdValue()?.toLongOrNull()?.takeIf { it > 0L }?.let { id -> "simkl:$id" }

internal fun SimklLibraryEntry.matchesContentId(contentId: String): Boolean {
    val media = media ?: return false
    if (media.canonicalContentId().equals(contentId, ignoreCase = true)) return true
    val parsed = parseTrackingExternalIds(contentId)
    val candidateIds = media.toTrackingExternalIds()
    return (parsed.simkl != null && parsed.simkl == candidateIds.simkl) ||
        (!parsed.imdb.isNullOrBlank() && parsed.imdb.equals(candidateIds.imdb, ignoreCase = true)) ||
        (parsed.tmdb != null && parsed.tmdb == candidateIds.tmdb) ||
        (!parsed.tvdb.isNullOrBlank() && parsed.tvdb.equals(candidateIds.tvdb, ignoreCase = true)) ||
        (parsed.mal != null && parsed.mal == candidateIds.mal) ||
        (parsed.anidb != null && parsed.anidb == candidateIds.anidb) ||
        (parsed.anilist != null && parsed.anilist == candidateIds.anilist) ||
        (parsed.kitsu != null && parsed.kitsu == candidateIds.kitsu)
}

private fun TrackingExternalIds.sharesIdentityWith(other: TrackingExternalIds): Boolean =
    (simkl != null && simkl == other.simkl) ||
        (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (!tvdb.isNullOrBlank() && tvdb.equals(other.tvdb, ignoreCase = true)) ||
        (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu)

private fun Int.isLeapYear(): Boolean = (this % 4 == 0 && this % 100 != 0) || this % 400 == 0

private fun daysInMonths(year: Int): IntArray = if (year.isLeapYear()) {
    intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
} else {
    intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
}

/**
 * For anime with per-season MAL/Kitsu/AniDB/AniList video IDs, resolves both the
 * anime-specific IDs and the episode number to match Simkl's anime-native format
 * (Path B: flat episode numbering per MAL/Kitsu entry, no season).
 *
 * Example: video ID "mal:42203:7" means episode 7 of mal:42203. Even if the UI shows
 * this as "Season 2 Episode 20" of the franchise, Simkl needs:
 * - ids with mal=42203 (not the franchise parent mal)
 * - episode number=7 (not 20)
 * - no season (flat/absolute numbering)
 *
 * Returns the reference unchanged if:
 * - Not anime
 * - No catalog videoId
 * - VideoId doesn't have an anime-tracker prefix with episode number
 */
internal fun TrackingMediaReference.resolveAnimeEpisodeForSimkl(): TrackingMediaReference {
    if (kind != TrackingMediaKind.ANIME) return this
    if (episode == null) {
        val videoId = catalog?.videoId
        val parsed = videoId?.let { parseSimklAnimeVideoId(it) }
        if (parsed?.episodeNumber == null) {
            return copy(episode = TrackingEpisode(season = null, number = 1))
        }
    }
    val videoId = catalog?.videoId ?: return stripAnimeIdsIfSeasoned()
    val parsed = parseSimklAnimeVideoId(videoId) ?: return stripAnimeIdsIfSeasoned()
    val videoEpisodeNumber = parsed.episodeNumber ?: return stripAnimeIdsIfSeasoned()

    // Override IDs: use ONLY the anime-specific ID from videoId.
    // Clear all other IDs to prevent Simkl from matching a wrong entry
    // (e.g. shared anidb/anilist IDs from the enriched parent entry).
    val videoIds = parseTrackingExternalIds("${parsed.prefix}:${parsed.id}")
    val overriddenIds = TrackingExternalIds(
        imdb = null,
        tmdb = null,
        tvdb = null,
        trakt = null,
        simkl = null,
        mal = videoIds.mal,
        anidb = videoIds.anidb,
        anilist = videoIds.anilist,
        kitsu = videoIds.kitsu,
    )

    // Override episode: use absolute number from videoId, drop season
    return copy(
        ids = overriddenIds,
        episode = episode?.copy(season = null, number = videoEpisodeNumber)
            ?: TrackingEpisode(season = null, number = videoEpisodeNumber),
    )
}

/**
 * If this anime reference uses TVDB-style season coordinates, strip anime-specific IDs
 * (MAL, Kitsu, AniDB, AniList) to prevent Simkl from matching a wrong per-season entry.
 * When a season is present the parent IMDB/TMDB is the correct identifier.
 */
private fun TrackingMediaReference.stripAnimeIdsIfSeasoned(): TrackingMediaReference {
    val season = episode?.season ?: return this
    if (season <= 0) return this
    if (ids.mal == null && ids.kitsu == null && ids.anidb == null && ids.anilist == null && ids.simkl == null) return this
    return copy(
        ids = ids.copy(mal = null, kitsu = null, anidb = null, anilist = null, simkl = null),
    )
}

/**
 * Resolve a contentId to its canonical form by finding a matching Simkl entry.
 * e.g. "mal:123" → finds entry with mal=123 → returns "tt2560140" (its IMDB/canonical ID)
 */
internal fun SimklSyncSnapshot.resolveCanonicalContentId(contentId: String): String? {
    val entry = entries.firstOrNull { it.matchesContentId(contentId) }
    return entry?.media?.canonicalContentId()
}

/**
 * Check if an episode is watched by resolving through the video ID.
 * Parses the anime-specific ID from videoId (e.g. "mal:123:5" → mal=123, ep=5),
 * finds the Simkl entry, and checks if that episode number has watched_at.
 */
internal fun isWatchedByAnimeVideoId(
    snapshot: SimklSyncSnapshot,
    videoId: String,
    episode: Int,
): Boolean {
    val parsed = parseSimklAnimeVideoId(videoId) ?: return false
    val parsedIds = parseTrackingExternalIds("${parsed.prefix}:${parsed.id}")
    val entry = snapshot.entries.firstOrNull { entry ->
        entry.mediaType == SimklMediaType.ANIME &&
            entry.media?.toTrackingExternalIds()?.sharesAnimeIdentityWith(parsedIds) == true
    } ?: return false

    val targetEpisodeNumber = parsed.episodeNumber ?: episode
    return entry.seasons.any { season ->
        season.episodes.any { ep ->
            ep.number == targetEpisodeNumber && ep.watchedAt != null
        }
    }
}

private fun TrackingExternalIds.sharesAnimeIdentityWith(other: TrackingExternalIds): Boolean =
    (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu)

private val SIMKL_ANIME_VIDEO_ID_PREFIXES = setOf("mal", "anidb", "anilist", "kitsu")

private data class SimklAnimeVideoIdParts(
    val prefix: String,
    val id: Long,
    val episodeNumber: Int?,
)

private fun parseSimklAnimeVideoId(videoId: String): SimklAnimeVideoIdParts? {
    val trimmed = videoId.trim()
    if (trimmed.isBlank()) return null
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in SIMKL_ANIME_VIDEO_ID_PREFIXES) return null
    val afterPrefix = trimmed.substringAfter(':', "")
    val idValue = afterPrefix.substringBefore(':').toLongOrNull() ?: return null
    val episodePart = afterPrefix.substringAfter(':', "").substringBefore(':')
    return SimklAnimeVideoIdParts(prefix, idValue, episodePart.toIntOrNull())
}

private val SIMKL_UTC_PATTERN = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$",
    RegexOption.IGNORE_CASE,
)
