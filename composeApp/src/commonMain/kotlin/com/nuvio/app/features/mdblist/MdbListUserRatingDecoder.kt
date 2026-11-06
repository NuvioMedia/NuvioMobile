package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun decodeMdbListUserRatings(body: String): MdbListPage<MdbListRatingRecord> {
    val payload = mdbListResponseElement(body).objectValue()
    if (listOf("movies", "shows", "seasons", "episodes").none(payload::containsKey)) {
        throw MdbListDecodingException()
    }
    val records = MdbListItemType.entries.flatMap { type ->
        payload.arrayValue("${type.name.lowercase()}s").mapNotNull { element ->
            runCatching { ratingRecord(type, element.objectValue()) }.getOrNull()
        }
    }
    return mdbListPage(payload, records)
}

internal fun mdbListRatingFailures(body: String): Int {
    val payload = mdbListResponseElement(body).objectValue()
    val notFound = (payload["not_found"] as? JsonObject)?.values.orEmpty().sumOf { value ->
        when (value) {
            is JsonArray -> value.size
            is JsonPrimitive -> value.content.toIntOrNull() ?: 0
            else -> 0
        }
    }
    return notFound + ((payload["errors"] as? JsonArray)?.size ?: 0)
}

private fun ratingRecord(type: MdbListItemType, row: JsonObject): MdbListRatingRecord {
    val target = row.objectValue(type.name.lowercase()) ?: row
    val parent = when (type) {
        MdbListItemType.MOVIE, MdbListItemType.SHOW -> target
        else -> row.objectValue("show") ?: target.objectValue("show") ?: throw MdbListDecodingException()
    }
    val rating = row.text("rating_precise", "rating")?.toDoubleOrNull()
        ?.takeIf { it > 0.0 && it <= 10.0 } ?: throw MdbListDecodingException()
    val season = when (type) {
        MdbListItemType.SEASON -> target.integer("number", "season")
        MdbListItemType.EPISODE -> target.integer("season")
        else -> null
    }
    val episode = if (type == MdbListItemType.EPISODE) target.integer("number", "episode") else null
    if (type in setOf(MdbListItemType.SEASON, MdbListItemType.EPISODE) && (season == null || season < 0)) {
        throw MdbListDecodingException()
    }
    if (type == MdbListItemType.EPISODE && (episode == null || episode < 1)) throw MdbListDecodingException()
    val ids = target.objectValue("ids").takeIf { type == MdbListItemType.EPISODE }
    return MdbListRatingRecord(
        type = type,
        media = decodeMdbListMedia(parent),
        rating = rating,
        ratedAt = row.timestamp("rated_at") ?: throw MdbListDecodingException(),
        season = season,
        episode = episode,
        episodeTmdbId = ids?.number("tmdb", "tmdbid"),
        episodeTvdbId = ids?.number("tvdb", "tvdbid")
    )
}
