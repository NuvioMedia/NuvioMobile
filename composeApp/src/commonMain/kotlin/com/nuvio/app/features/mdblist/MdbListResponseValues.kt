package com.nuvio.app.features.mdblist

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun mdbListResponseElement(body: String): JsonElement =
    runCatching { Json.parseToJsonElement(body) }.getOrElse { throw MdbListDecodingException() }

internal fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: throw MdbListDecodingException()
internal fun JsonObject.objectValue(name: String): JsonObject? = when (val value = get(name)) {
    null, JsonNull -> null
    else -> value.objectValue()
}

internal fun JsonObject.arrayValue(name: String): JsonArray = when (val value = get(name)) {
    null, JsonNull -> JsonArray(emptyList())
    is JsonArray -> value
    else -> throw MdbListDecodingException()
}

internal fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}

internal fun JsonObject.number(vararg names: String): Long? = text(*names)?.toLongOrNull()
internal fun JsonObject.integer(vararg names: String): Int? = number(*names)
    ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
internal fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

internal fun mdbListTimestamp(value: String): Long = runCatching { Instant.parse(value).toEpochMilliseconds() }
    .getOrElse { throw MdbListDecodingException() }

internal fun JsonObject.timestamp(vararg names: String): String? = text(*names)?.also(::mdbListTimestamp)

internal fun decodeMdbListIds(value: JsonObject): MdbListIds {
    val ids = value.objectValue("ids") ?: value
    return MdbListIds(
        imdb = ids.text("imdb", "imdbid")?.takeIf { it.matches(Regex("tt[0-9]+")) },
        tmdb = ids.number("tmdb", "tmdbid")?.takeIf { it > 0L },
        tvdb = ids.number("tvdb", "tvdbid")?.takeIf { it > 0L },
        trakt = ids.number("trakt", "traktid")?.takeIf { it > 0L },
        mdblist = ids.text("mdblist")
    ).also { it.key }
}

internal fun decodeMdbListMedia(value: JsonObject): MdbListMedia = MdbListMedia(
    ids = decodeMdbListIds(value),
    title = value.text("title", "name"),
    year = value.integer("year"),
    poster = mdbListImageUrl(value.text("poster"), "w500"),
    backdrop = mdbListImageUrl(value.text("backdrop", "background"), "w1280"),
    runtimeMinutes = value.number("runtime")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
)

internal fun mdbListImageUrl(value: String?, size: String): String? = when {
    value == null -> null
    value.startsWith("https://") || value.startsWith("http://") -> value
    value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
    else -> null
}

internal fun <T> mdbListPage(value: JsonObject, items: List<T>): MdbListPage<T> {
    val pagination = value.objectValue("pagination") ?: value
    val cursor = pagination.text("next_cursor")
    val offset = pagination.number("offset")
    val limit = pagination.number("limit")
    val total = pagination.number("total")
    val hasMore = pagination.flag("has_more") ?: if (offset != null && limit != null && total != null) {
        offset + limit < total
    } else false
    val nextOffset = if (hasMore == true && cursor == null) {
        if (offset == null || offset < 0L || limit == null || limit < 1L || offset + limit > Int.MAX_VALUE) {
            throw MdbListDecodingException()
        }
        (offset + limit).toInt()
    } else null
    return MdbListPage(items, cursor, nextOffset, value.timestamp("server_time"))
}
