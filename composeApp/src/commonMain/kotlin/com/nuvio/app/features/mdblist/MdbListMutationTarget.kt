package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class MdbListMutationTarget(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeTmdbId: Long? = null,
    val episodeTvdbId: Long? = null,
    val scrobbleCoordinatesResolved: Boolean = true
) {
    val key: String
        get() = "$type:${media.ids.key}:${season ?: -1}:${episode ?: -1}"

    fun matches(record: MdbListWatchedRecord): Boolean = record.type == type &&
        media.ids.matches(record.media.ids) && (type != MdbListItemType.EPISODE ||
        (episodeTmdbId != null && episodeTmdbId == record.episodeTmdbId) ||
        (episodeTvdbId != null && episodeTvdbId == record.episodeTvdbId) ||
        (season == record.season && episode == record.episode))

    fun matches(record: MdbListPlayback): Boolean = record.type == type &&
        media.ids.matches(record.media.ids) && season == record.season && episode == record.episode

    fun watched(at: String) = MdbListWatchedRecord(
        type, media, at, season, episode, episodeTitle, episodeTmdbId, episodeTvdbId
    )

    fun scrobbleBody(progress: Double? = null): JsonObject = buildJsonObject {
        require(type == MdbListItemType.MOVIE || type == MdbListItemType.EPISODE && scrobbleCoordinatesResolved)
        put(if (type == MdbListItemType.MOVIE) "movie" else "show", buildJsonObject {
            put("ids", media.ids.requestIds())
            if (type == MdbListItemType.EPISODE) {
                put("season", requireNotNull(season))
                put("episode", requireNotNull(episode))
            }
        })
        progress?.let {
            require(it.isFinite() && it in 0.0..100.0)
            val value = if (it < 0.01) 0.0 else it.toString().let { decimal ->
                "${decimal.substringBefore('.')}.${decimal.substringAfter('.', "0").take(2)}".toDouble()
            }
            put("progress", value)
        }
    }
}

internal fun MdbListSyncSnapshot.mutationTarget(reference: TrackingMediaReference): MdbListMutationTarget? {
    val ids = reference.ids.toMdbListIds() ?: return null
    val isMovie = reference.kind == TrackingMediaKind.MOVIE || reference.catalog?.contentType in setOf("movie", "film")
    val type = if (isMovie) MdbListItemType.MOVIE else if (reference.episode != null) MdbListItemType.EPISODE else MdbListItemType.SHOW
    val index = MdbListMediaIndex(this)
    val media = index.resolve(type, ids).merged(MdbListMedia(ids, reference.title, reference.year, reference.posterUrl))
    val episode = reference.episode.takeUnless { isMovie }
    val known = if (episode == null) null else watched.firstOrNull { record ->
        record.type == MdbListItemType.EPISODE && record.media.ids.matches(ids) &&
            ((episode.tmdbId != null && episode.tmdbId == record.episodeTmdbId) ||
                (episode.tvdbId != null && episode.tvdbId == record.episodeTvdbId) ||
                (!episode.usesTvdbSeasonMapping && episode.season == record.season && episode.number == record.episode))
    }
    val season = known?.season ?: episode?.season
    val number = known?.episode ?: episode?.number
    if (type == MdbListItemType.EPISODE && (season == null || season < 0 || number == null || number < 1)) return null
    return MdbListMutationTarget(
        type, media, season, number, known?.episodeTitle ?: episode?.title,
        known?.episodeTmdbId ?: episode?.tmdbId,
        known?.episodeTvdbId ?: episode?.tvdbId,
        scrobbleCoordinatesResolved = episode?.usesTvdbSeasonMapping != true || known != null
    )
}

internal fun TrackingExternalIds.toMdbListIds(): MdbListIds? = MdbListIds(
    imdb = imdb?.takeIf { it.matches(Regex("tt[0-9]+")) },
    tmdb = tmdb?.takeIf { it > 0L },
    tvdb = tvdb?.toLongOrNull()?.takeIf { it > 0L },
    trakt = trakt?.takeIf { it > 0L },
    mdblist = mdblist?.takeIf(String::isNotBlank)
).takeIf { it.aliases().isNotEmpty() }

internal fun MdbListIds.requestIds(): JsonObject = buildJsonObject {
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.let { put("tvdb", it) }
    trakt?.let { put("trakt", it) }
    mdblist?.let { put("mdblist", it) }
}
