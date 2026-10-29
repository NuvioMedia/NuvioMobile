package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonObject

internal fun decodeMdbListWatched(body: String): MdbListPage<MdbListWatchedRecord> {
    val payload = mdbListResponseElement(body).objectValue()
    if (listOf("movies", "shows", "seasons", "episodes").none(payload::containsKey)) {
        throw MdbListDecodingException()
    }
    val records = buildList {
        for (type in MdbListItemType.entries) {
            val name = type.name.lowercase()
            payload.arrayValue("${name}s").forEach { item ->
                val row = item.objectValue()
                val target = row.objectValue(name) ?: throw MdbListDecodingException()
                val parent = if (type in setOf(MdbListItemType.SEASON, MdbListItemType.EPISODE)) {
                    row.objectValue("show") ?: target.objectValue("show") ?: throw MdbListDecodingException()
                } else target
                val media = decodeMdbListMedia(parent)
                watchedRecord(type, row, target, media)?.let(::add)
                if (type == MdbListItemType.SHOW) addAll(nestedEpisodes(row, media))
            }
        }
    }
    return mdbListPage(payload, records.distinctBy(MdbListWatchedRecord::key))
}

private fun watchedRecord(
    type: MdbListItemType,
    row: JsonObject,
    target: JsonObject,
    media: MdbListMedia,
    seasonOverride: Int? = null
): MdbListWatchedRecord? {
    val watchedAt = row.timestamp("last_watched_at", "watched_at") ?: return null
    val season = when (type) {
        MdbListItemType.SEASON -> target.integer("number", "season")
        MdbListItemType.EPISODE -> seasonOverride ?: target.integer("season")
        else -> null
    }
    val episode = if (type == MdbListItemType.EPISODE) target.integer("number", "episode") else null
    if (type in setOf(MdbListItemType.SEASON, MdbListItemType.EPISODE) && (season == null || season < 0)) {
        throw MdbListDecodingException()
    }
    if (type == MdbListItemType.EPISODE && (episode == null || episode < 1)) throw MdbListDecodingException()
    val ids = target.objectValue("ids")
    return MdbListWatchedRecord(
        type, media, watchedAt, season, episode,
        episodeTitle = target.text("title", "name").takeIf { type == MdbListItemType.EPISODE },
        episodeTmdbId = ids?.number("tmdb", "tmdbid").takeIf { type == MdbListItemType.EPISODE },
        episodeTvdbId = ids?.number("tvdb", "tvdbid").takeIf { type == MdbListItemType.EPISODE }
    )
}

private fun nestedEpisodes(row: JsonObject, media: MdbListMedia): List<MdbListWatchedRecord> = buildList {
    row.arrayValue("seasons").forEach { element ->
        val season = element.objectValue()
        val number = season.integer("number", "season") ?: throw MdbListDecodingException()
        season.arrayValue("episodes").forEach { episode ->
            val target = episode.objectValue()
            watchedRecord(MdbListItemType.EPISODE, target, target, media, number)?.let(::add)
        }
    }
}
