package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun decodeMdbListLibraryLists(body: String, accountId: Long): List<MdbListLibraryList> {
    val rows = mdbListResponseElement(body) as? JsonArray ?: throw MdbListDecodingException()
    return rows.mapNotNull { element ->
        val row = element.objectValue()
        if (row.text("type") != "static" || row.flag("dynamic") == true ||
            row.number("user_id")?.let { it != accountId } == true) return@mapNotNull null
        val mediaType = row.text("mediatype")?.let(::mdbListLibraryType)
        if (row.text("mediatype") != null && mediaType == null) return@mapNotNull null
        val id = row.number("id") ?: row.arrayValue("ids").singleOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        MdbListLibraryList(
            id = id?.takeIf { it > 0 } ?: throw MdbListDecodingException(),
            name = row.text("name") ?: throw MdbListDecodingException(),
            private = row.flag("private") ?: throw MdbListDecodingException(),
            description = row.text("description"),
            mediaType = mediaType,
            updatedAt = row.text("last_updated_at", "updated_at", "updated")
        )
    }.also { lists -> if (lists.map { it.id }.toSet().size != lists.size) throw MdbListDecodingException() }
}

internal fun decodeMdbListLibraryPage(body: String): MdbListPage<MdbListLibraryItem> {
    val root = mdbListResponseElement(body)
    if (root is JsonArray) return MdbListPage(root.mapNotNull { decodeLibraryItem(it.objectValue()) })
    val value = root.objectValue()
    val items = if (value["items"] is JsonArray) {
        value.arrayValue("items").mapNotNull { decodeLibraryItem(it.objectValue()) }
    } else {
        if (listOf("movies", "shows", "seasons", "episodes").none(value::containsKey)) throw MdbListDecodingException()
        value.arrayValue("movies").mapNotNull { decodeLibraryItem(it.objectValue(), MdbListItemType.MOVIE) } +
            value.arrayValue("shows").mapNotNull { decodeLibraryItem(it.objectValue(), MdbListItemType.SHOW) }
    }
    return mdbListPage(value, items)
}

private fun decodeLibraryItem(row: JsonObject, bucket: MdbListItemType? = null): MdbListLibraryItem? {
    val declaredType = row.text("mediatype", "type")
    val type = declaredType?.let(::mdbListLibraryType) ?: bucket
    if (declaredType in listOf("season", "episode")) return null
    if (type == null || declaredType != null && mdbListLibraryType(declaredType) == null || bucket != null && type != bucket) throw MdbListDecodingException()
    val nested = row.objectValue("ids")
    val ids = decodeMdbListIds(buildJsonObject {
        row.number("id")?.let { put("tmdb", it) }
        row.text("imdb_id")?.let { put("imdb", it) }
        row.number("tvdb_id")?.let { put("tvdb", it) }
        nested?.forEach { (key, value) -> if (value != kotlinx.serialization.json.JsonNull) put(key, value) }
    })
    return MdbListLibraryItem(
        type = type,
        media = MdbListMedia(
            ids = ids,
            title = row.text("title", "name"),
            year = row.integer("release_year", "year"),
            poster = mdbListImageUrl(row.text("poster"), "w500"),
            backdrop = mdbListImageUrl(row.text("backdrop", "background"), "w1280")
        ),
        description = row.text("description", "overview"),
        genres = row.arrayValue("genres").mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("name", "slug")
        }.distinct(),
        listedAt = row.timestamp("listed_at", "added_at")?.let(::mdbListTimestamp) ?: 0,
        rank = row.integer("rank")?.takeIf { it > 0 }
    )
}

internal fun mdbListLibraryType(type: String): MdbListItemType? = when (type.lowercase()) {
    "movie" -> MdbListItemType.MOVIE
    "show", "series", "tv", "anime" -> MdbListItemType.SHOW
    else -> null
}
