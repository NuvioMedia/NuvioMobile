package com.nuvio.app.features.mdblist

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressEntry

internal class MdbListProgressProjection(snapshot: MdbListSyncSnapshot) {
    private val mediaIndex = MdbListMediaIndex(snapshot)
    private val droppedById = snapshot.dropped.flatMap { record ->
        mediaIndex.resolve(MdbListItemType.SHOW, record.ids).ids.aliases().map { it to record.season }
    }.groupBy({ it.first }, { it.second })
    private val historyByKey = snapshot.watched.associateBy(MdbListWatchedRecord::key)
    private val watchedRecords = snapshot.watched.filter { it.type in setOf(MdbListItemType.MOVIE, MdbListItemType.EPISODE) }
    val watchedItems: List<WatchedItem> = watchedItems { null }
    val watchedKeys: Set<String> = watchedKeys { null }
    val hiddenContentIds = droppedById.filterValues { null in it }.keys
    val showIdSiblings = (snapshot.watched.filter { it.type != MdbListItemType.MOVIE }.map { it.media.ids.aliases() } +
        snapshot.playback.filter { it.type != MdbListItemType.MOVIE }.map { it.media.ids.aliases() })
        .flatMap { aliases -> aliases.map { it to aliases } }.toMap()
    val progress = snapshot.playback.filter { session ->
        val watched = historyByKey["${session.type}:${session.media.ids.key}:${session.season ?: -1}:${session.episode ?: -1}"]
        session.progress < COMPLETION_PERCENT && !isHidden(session.media.ids.contentId, session.season) &&
            (watched == null || mdbListTimestamp(session.updatedAt) > mdbListTimestamp(watched.watchedAt))
    }.map { session ->
        entry(session.type, session.media, session.season, session.episode, session.episodeTitle,
            session.progress, session.updatedAt, session.runtimeMinutes, "mdblist_playback")
    }.groupBy { Triple(it.parentMetaId, it.seasonNumber, it.episodeNumber) }
        .map { (_, entries) -> entries.maxBy(WatchProgressEntry::lastUpdatedEpochMs) }
        .sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)
    val nextUpSeeds = snapshot.watched.filter { record ->
        record.type == MdbListItemType.EPISODE && record.season != null && record.season > 0 &&
            !isHidden(record.media.ids.contentId)
    }.map { record ->
        entry(record.type, record.media, record.season, record.episode, record.episodeTitle,
            100f, record.watchedAt, null, "mdblist_history")
    }

    fun isHidden(contentId: String, season: Int? = null): Boolean =
        droppedById[contentId]?.any { it == null || (season != null && it == season) } == true

    fun canonicalContentId(contentId: String): String? =
        runCatching {
            val ids = com.nuvio.app.features.tracking.parseTrackingExternalIds(contentId).toMdbListIds() ?: return null
            mediaIndex.resolve(MdbListItemType.SHOW, ids).ids.contentId
        }.getOrNull()

    fun tmdbShowId(contentId: String): Long? =
        com.nuvio.app.features.tracking.parseTrackingExternalIds(contentId).toMdbListIds()
            ?.let { mediaIndex.resolve(MdbListItemType.SHOW, it).ids.tmdb }

    /**
     * Watched movies and episodes, with the episodes of shows [mappingFor] remaps in the addon's
     * numbering. Episodes the addon does not list are left out.
     */
    fun watchedItems(mappingFor: (String) -> MdbListEpisodeMapping?): List<WatchedItem> =
        watchedRecords.mapNotNull { record ->
            val episode = record.addonEpisode(mappingFor) ?: return@mapNotNull null
            WatchedItem(
                id = record.media.ids.contentId,
                type = record.type.contentType,
                name = record.media.title ?: record.media.ids.contentId,
                season = episode.season,
                episode = episode.episode,
                videoId = episode.videoId ?: videoId(record.media, episode.season, episode.episode),
                markedAtEpochMs = mdbListTimestamp(record.watchedAt),
                poster = record.media.poster,
                releaseInfo = record.media.year?.toString(),
                trackingProviderId = "mdblist",
                trackingProviderItemId = record.media.ids.mdblist?.let { "mdblist:$it" },
                trackingSourceUrl = record.media.sourceUrl(record.type),
            )
        }.sortedByDescending(WatchedItem::markedAtEpochMs)

    /** Watched keys of every id the show is known by, numbered as in [watchedItems]. */
    fun watchedKeys(mappingFor: (String) -> MdbListEpisodeMapping?): Set<String> =
        watchedRecords.flatMapTo(linkedSetOf()) { record ->
            val episode = record.addonEpisode(mappingFor) ?: return@flatMapTo emptyList()
            record.media.ids.aliases().flatMap { id ->
                watchedItemKeys(record.type.contentType, id, episode.season, episode.episode)
            }
        }

    private fun MdbListWatchedRecord.addonEpisode(mappingFor: (String) -> MdbListEpisodeMapping?): RecordEpisode? {
        // Specials are not remapped: both sides list them as season 0.
        if (season == null || episode == null || season == 0) return RecordEpisode(season, episode)
        val mapping = mappingFor(media.ids.contentId)?.takeIf { it.remaps } ?: return RecordEpisode(season, episode)
        return mapping.toAddon(season, episode)?.let { RecordEpisode(it.season, it.episode, it.videoId) }
    }

    private data class RecordEpisode(val season: Int?, val episode: Int?, val videoId: String? = null)

    private fun entry(
        type: MdbListItemType,
        media: MdbListMedia,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        percent: Float,
        timestamp: String,
        runtime: Int?,
        source: String,
    ): WatchProgressEntry {
        val duration = (runtime ?: media.runtimeMinutes ?: 0).toLong() * 60_000L
        return WatchProgressEntry(
            contentType = type.contentType,
            parentMetaId = media.ids.contentId,
            parentMetaType = type.contentType,
            title = media.title ?: media.ids.contentId,
            poster = media.poster,
            background = media.backdrop,
            videoId = videoId(media, season, episode),
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
            lastPositionMs = (duration * percent.toDouble() / 100.0).toLong(),
            durationMs = duration,
            lastUpdatedEpochMs = mdbListTimestamp(timestamp),
            progressPercent = percent,
            isCompleted = percent >= COMPLETION_PERCENT,
            source = source,
            trackingProviderId = "mdblist",
            trackingProviderItemId = media.ids.mdblist?.let { "mdblist:$it" },
            trackingSourceUrl = media.sourceUrl(type),
            excludedNextUpSeasons = droppedById[media.ids.contentId].orEmpty().filterNotNull().toSet(),
        )
    }

    private fun videoId(media: MdbListMedia, season: Int?, episode: Int?): String =
        com.nuvio.app.features.watchprogress.buildPlaybackVideoId(media.ids.contentId, season, episode)

    companion object {
        const val COMPLETION_PERCENT = 80f
        val Empty = MdbListProgressProjection(MdbListSyncSnapshot(0))
    }
}

internal val MdbListItemType.contentType: String
    get() = if (this == MdbListItemType.MOVIE) "movie" else "series"

internal fun MdbListMedia.sourceUrl(type: MdbListItemType): String? =
    ids.mdblist?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?.let { "https://mdblist.com/${if (type == MdbListItemType.MOVIE) "movie" else "show"}/$it" }
