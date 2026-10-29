package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

internal fun decodeMdbListActivities(body: String): MdbListActivities {
    val payload = mdbListResponseElement(body).objectValue()
    val serverTime = payload.timestamp("server_time") ?: throw MdbListDecodingException()
    if (listOf("watched_at", "episode_watched_at", "journal_at").none(payload::containsKey)) {
        throw MdbListDecodingException()
    }
    return MdbListActivities(
        values = payload.keys.filter { it.endsWith("_at") }.associateWith { key -> payload.timestamp(key) },
        serverTime = serverTime
    )
}

internal fun decodeMdbListPlayback(body: String): List<MdbListPlayback> {
    val payload = mdbListResponseElement(body) as? JsonArray ?: throw MdbListDecodingException()
    return payload.map { item ->
        val row = item.objectValue()
        val type = when (row.text("type")) {
            "movie" -> MdbListItemType.MOVIE
            "episode" -> MdbListItemType.EPISODE
            else -> throw MdbListDecodingException()
        }
        val episode = row.objectValue("episode")
        val media = decodeMdbListMedia(
            row.objectValue(if (type == MdbListItemType.MOVIE) "movie" else "show")
                ?: episode?.objectValue("show") ?: throw MdbListDecodingException()
        )
        val progress = row.text("progress")?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0f..100f } ?: throw MdbListDecodingException()
        val updatedAt = row.timestamp("paused_at", "updated_at")
            ?: throw MdbListDecodingException()
        val season = episode?.integer("season")
        val number = episode?.integer("number", "episode")
        if (type == MdbListItemType.EPISODE && (season == null || season < 0 || number == null || number < 1)) {
            throw MdbListDecodingException()
        }
        MdbListPlayback(
            id = row.number("id")?.takeIf { it > 0L } ?: throw MdbListDecodingException(),
            type = type,
            media = media,
            progress = progress,
            updatedAt = updatedAt,
            season = season,
            episode = number,
            episodeTitle = episode?.text("title", "name"),
            episodeTmdbId = episode?.objectValue("ids")?.number("tmdb", "tmdbid"),
            episodeTvdbId = episode?.objectValue("ids")?.number("tvdb", "tvdbid"),
            runtimeMinutes = row.integer("runtime")?.takeIf { it > 0 }
        )
    }
}

internal fun decodeMdbListDropped(body: String, seasons: Boolean): MdbListPage<MdbListDroppedRecord> {
    val payload = mdbListResponseElement(body).objectValue()
    val name = if (seasons) "season" else "show"
    if (!payload.containsKey("${name}s")) throw MdbListDecodingException()
    val items = payload.arrayValue("${name}s").map { element ->
        val row = element.objectValue()
        val target = row.objectValue(name) ?: throw MdbListDecodingException()
        val parent = if (seasons) row.objectValue("show") ?: target.objectValue("show") else target
        val number = if (seasons) {
            target.integer("number", "season")?.takeIf { it >= 0 } ?: throw MdbListDecodingException()
        } else null
        MdbListDroppedRecord(decodeMdbListIds(parent ?: throw MdbListDecodingException()), number)
    }
    return mdbListPage(payload, items)
}

internal fun decodeMdbListJournal(body: String): MdbListPage<MdbListJournalRecord> {
    val payload = mdbListResponseElement(body).objectValue()
    if (payload.flag("requires_full_sync") == true) {
        return MdbListPage(emptyList(), serverTime = payload.timestamp("server_time"), requiresFullSync = true)
    }
    if (!payload.containsKey("journal") || payload["journal"] == JsonNull) throw MdbListDecodingException()
    val records = payload.arrayValue("journal").mapNotNull { element ->
        val row = element.objectValue()
        when (row.text("category")) {
            "rated" -> return@mapNotNull null
            "watched" -> Unit
            else -> throw MdbListDecodingException()
        }
        val type = MdbListItemType.entries.firstOrNull { it.name.equals(row.text("item_type"), ignoreCase = true) }
            ?: throw MdbListDecodingException()
        val season = row.integer("season")
        val episode = row.integer("episode")
        if (type in setOf(MdbListItemType.SEASON, MdbListItemType.EPISODE) && (season == null || season < 0)) {
            throw MdbListDecodingException()
        }
        if (type == MdbListItemType.EPISODE && (episode == null || episode < 1)) throw MdbListDecodingException()
        val removed = when (row.text("status")) {
            "added" -> false
            "removed" -> true
            else -> throw MdbListDecodingException()
        }
        val watchedAt = row.timestamp("value_at")
        if (!removed && watchedAt == null) throw MdbListDecodingException()
        MdbListJournalRecord(
            type = type,
            ids = decodeMdbListIds(row.objectValue("ids") ?: throw MdbListDecodingException()),
            removed = removed,
            actionAt = row.timestamp("action_at") ?: throw MdbListDecodingException(),
            watchedAt = watchedAt,
            season = season,
            episode = episode,
            episodeTmdbId = row.number("episode_tmdb_id"),
            episodeTvdbId = row.number("episode_tvdb_id")
        )
    }
    return mdbListPage(payload, records)
}
