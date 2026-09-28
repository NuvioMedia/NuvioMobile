package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class MdbListHistoryChange(val target: MdbListMutationTarget, val watchedAt: String?)

internal fun mdbListHistoryPayload(changes: List<MdbListHistoryChange>): String = buildJsonObject {
    val groups = changes.groupBy { change ->
        when {
            change.target.type == MdbListItemType.MOVIE -> "movies"
            change.target.type == MdbListItemType.EPISODE &&
                (change.target.episodeTmdbId != null || change.target.episodeTvdbId != null) -> "episodes"
            else -> "shows"
        }
    }
    groups.forEach { (group, items) ->
        put(group, JsonArray(items.map { change ->
            val target = change.target
            buildJsonObject {
                put("ids", if (group == "episodes") buildJsonObject {
                    target.episodeTmdbId?.let { put("tmdb", it) }
                    target.episodeTvdbId?.let { put("tvdb", it) }
                } else target.media.ids.requestIds())
                if (group == "shows" && target.type == MdbListItemType.EPISODE) {
                    put("seasons", JsonArray(listOf(buildJsonObject {
                        put("number", requireNotNull(target.season))
                        put("episodes", JsonArray(listOf(buildJsonObject {
                            put("number", requireNotNull(target.episode))
                            change.watchedAt?.let { put("watched_at", it) }
                        })))
                    })))
                } else change.watchedAt?.let { put("watched_at", it) }
            }
        }))
    }
}.toString()

internal data class MdbListHistoryReceipt(
    val confirmed: List<MdbListWatchedRecord>,
    val complete: Boolean,
    val needsSnapshot: Boolean,
    val notFoundCount: Int
)

internal fun decodeMdbListHistoryReceipt(
    body: String,
    changes: List<MdbListHistoryChange>,
    remove: Boolean
): MdbListHistoryReceipt {
    val payload = mdbListResponseElement(body).objectValue()
    val counts = payload.objectValue(if (remove) "deleted" else "updated")
        ?: (if (remove) payload.objectValue("removed") else null)
        ?: throw MdbListDecodingException()
    val failures = payload.objectValue("not_found")?.values.orEmpty().sumOf { value ->
        when (value) {
            is JsonArray -> value.size
            else -> value.toString().toIntOrNull()?.coerceAtLeast(0) ?: throw MdbListDecodingException()
        }
    }
    val errors = payload.arrayValue("errors").size
    val complete = failures == 0 && errors == 0 && changes.groupingBy { it.target.type }.eachCount().all { (type, requested) ->
        (counts.integer("${type.name.lowercase()}s") ?: 0) >= requested
    }
    val plays = if (remove) emptyList() else payload.arrayValue("plays").map { element -> decodePlay(element.objectValue(), changes) }
    val confirmed = if (plays.isNotEmpty()) plays else if (complete && !remove) {
        changes.filter { it.target.type != MdbListItemType.SHOW }.map { it.target.watched(requireNotNull(it.watchedAt)) }
    } else emptyList()
    val hasExpansion = changes.any { it.target.type == MdbListItemType.SHOW }
    return MdbListHistoryReceipt(
        confirmed = confirmed,
        complete = complete,
        needsSnapshot = !complete || hasExpansion || confirmed.size != changes.size && !remove,
        notFoundCount = if (complete) 0 else maxOf(failures, errors, changes.size - confirmed.size, 1)
    )
}

private fun decodePlay(row: JsonObject, changes: List<MdbListHistoryChange>): MdbListWatchedRecord {
    val type = when (row.text("type")) {
        "movie" -> MdbListItemType.MOVIE
        "episode" -> MdbListItemType.EPISODE
        else -> throw MdbListDecodingException()
    }
    val ids = decodeMdbListIds(if (type == MdbListItemType.MOVIE) row else row.objectValue("show") ?: throw MdbListDecodingException())
    val season = if (type == MdbListItemType.EPISODE) row.integer("season")?.takeIf { it >= 0 } ?: throw MdbListDecodingException() else null
    val episode = if (type == MdbListItemType.EPISODE) row.integer("number", "episode")?.takeIf { it > 0 } ?: throw MdbListDecodingException() else null
    val record = MdbListWatchedRecord(
        type, MdbListMedia(ids), row.timestamp("watched_at") ?: throw MdbListDecodingException(), season, episode,
        episodeTmdbId = row.objectValue("ids")?.number("tmdb").takeIf { type == MdbListItemType.EPISODE },
        episodeTvdbId = row.objectValue("ids")?.number("tvdb").takeIf { type == MdbListItemType.EPISODE }
    )
    val requested = changes.firstOrNull { it.target.matches(record) }?.target
        ?: changes.firstOrNull { it.target.type == MdbListItemType.SHOW && it.target.media.ids.matches(ids) }?.target
        ?: throw MdbListDecodingException()
    return record.copy(media = record.media.merged(requested.media), episodeTitle = requested.episodeTitle)
}
