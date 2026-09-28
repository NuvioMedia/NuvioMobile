package com.nuvio.app.features.mdblist

import kotlinx.serialization.Serializable

@Serializable
data class MdbListIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val trakt: Long? = null,
    val mdblist: String? = null
) {
    val key: String
        get() = mdblist?.let { "mdblist:$it" } ?: imdb ?: tmdb?.let { "tmdb:$it" }
            ?: tvdb?.let { "tvdb:$it" } ?: trakt?.let { "trakt:$it" }
            ?: throw MdbListDecodingException()

    val contentId: String
        get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" }
            ?: trakt?.let { "trakt:$it" } ?: key

    fun matches(other: MdbListIds): Boolean =
        (mdblist != null && mdblist == other.mdblist) || (imdb != null && imdb == other.imdb) ||
            (tmdb != null && tmdb == other.tmdb) || (tvdb != null && tvdb == other.tvdb) ||
            (trakt != null && trakt == other.trakt)

    fun merged(other: MdbListIds) = MdbListIds(
        imdb ?: other.imdb, tmdb ?: other.tmdb, tvdb ?: other.tvdb,
        trakt ?: other.trakt, mdblist ?: other.mdblist
    )

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add(it); add("imdb:$it") }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        trakt?.let { add("trakt:$it") }
        mdblist?.let { add("mdblist:$it") }
    }
}

@Serializable
data class MdbListMedia(
    val ids: MdbListIds,
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val runtimeMinutes: Int? = null
) {
    fun merged(other: MdbListMedia) = MdbListMedia(
        ids.merged(other.ids), title ?: other.title, year ?: other.year,
        poster ?: other.poster, backdrop ?: other.backdrop, runtimeMinutes ?: other.runtimeMinutes
    )
}

@Serializable
enum class MdbListItemType { MOVIE, SHOW, SEASON, EPISODE }

@Serializable
data class MdbListWatchedRecord(
    val type: MdbListItemType,
    val media: MdbListMedia,
    val watchedAt: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeTmdbId: Long? = null,
    val episodeTvdbId: Long? = null
) {
    val key: String
        get() = "$type:${media.ids.key}:${season ?: -1}:${episode ?: -1}"
}

@Serializable
data class MdbListPlayback(
    val id: Long?,
    val type: MdbListItemType,
    val media: MdbListMedia,
    val progress: Float,
    val updatedAt: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeTmdbId: Long? = null,
    val episodeTvdbId: Long? = null,
    val runtimeMinutes: Int? = null
)

@Serializable
data class MdbListDroppedRecord(val ids: MdbListIds, val season: Int? = null)

@Serializable
data class MdbListActivities(val values: Map<String, String?>, val serverTime: String) {
    fun watchedChanged(previous: MdbListActivities): Boolean = changed(previous) {
        it.contains("watched") || it == "journal_at"
    }

    fun playbackChanged(previous: MdbListActivities): Boolean = changed(previous) { it.contains("paused") }
    fun droppedChanged(previous: MdbListActivities): Boolean = changed(previous) { it.contains("dropped") }

    private fun changed(previous: MdbListActivities, includes: (String) -> Boolean): Boolean =
        (values.keys + previous.values.keys).filter(includes).any { values[it] != previous.values[it] }
}

data class MdbListJournalRecord(
    val type: MdbListItemType,
    val ids: MdbListIds,
    val removed: Boolean,
    val actionAt: String,
    val watchedAt: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTmdbId: Long? = null,
    val episodeTvdbId: Long? = null
)

data class MdbListPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val nextOffset: Int? = null,
    val serverTime: String? = null,
    val requiresFullSync: Boolean = false
)

@Serializable
data class MdbListSyncSnapshot(
    val accountId: Long,
    val watched: List<MdbListWatchedRecord> = emptyList(),
    val playback: List<MdbListPlayback> = emptyList(),
    val dropped: List<MdbListDroppedRecord> = emptyList(),
    val activities: MdbListActivities? = null,
    val watermark: String? = null,
    val checkedAtEpochMs: Long? = null,
    val isInitialized: Boolean = false,
    val invalidatedBuckets: Set<MdbListSyncBucket> = emptySet(),
    val library: MdbListLibrarySnapshot? = null
)

@Serializable
enum class MdbListSyncBucket { WATCHED, PLAYBACK, DROPPED }

class MdbListDecodingException : Exception("MDBList returned an incomplete response")
