package com.nuvio.app.features.mdblist

internal class MdbListMediaIndex(snapshot: MdbListSyncSnapshot) {
    private val byAlias = mutableMapOf<Pair<Boolean, String>, MdbListMedia>()

    init {
        snapshot.watched.forEach { add(it.type, it.media) }
        snapshot.playback.forEach { add(it.type, it.media) }
    }

    fun add(type: MdbListItemType, media: MdbListMedia): MdbListMedia {
        val movie = type == MdbListItemType.MOVIE
        val merged = media.ids.aliases().mapNotNull { byAlias[movie to it] }
            .fold(media, MdbListMedia::merged)
        merged.ids.aliases().forEach { byAlias[movie to it] = merged }
        return merged
    }

    fun resolve(type: MdbListItemType, ids: MdbListIds): MdbListMedia =
        ids.aliases().firstNotNullOfOrNull { byAlias[(type == MdbListItemType.MOVIE) to it] }
            ?.let { it.copy(ids = it.ids.merged(ids)) } ?: MdbListMedia(ids)
}

internal fun MdbListSyncSnapshot.normalizeMedia(): MdbListSyncSnapshot {
    val index = MdbListMediaIndex(this)
    return copy(
        watched = watched.map { it.copy(media = index.resolve(it.type, it.media.ids)) }
            .groupBy(MdbListWatchedRecord::key)
            .map { (_, records) -> records.maxBy { mdbListTimestamp(it.watchedAt) } },
        playback = playback.map { it.copy(media = index.resolve(it.type, it.media.ids)) }
            .groupBy { it.id?.let { id -> "id:$id" } ?: "${it.type}:${it.media.ids.key}:${it.season}:${it.episode}" }
            .map { (_, records) -> records.maxBy { mdbListTimestamp(it.updatedAt) } }
    )
}
