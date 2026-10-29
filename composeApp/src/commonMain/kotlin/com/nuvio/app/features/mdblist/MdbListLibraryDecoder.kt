package com.nuvio.app.features.mdblist

import kotlin.time.Instant
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
        MdbListLibraryList(
            id = mdbListUserListId(row),
            name = row.text("name") ?: throw MdbListDecodingException(),
            private = row.flag("private") ?: throw MdbListDecodingException(),
            description = row.text("description"),
            mediaType = mediaType,
            updatedAt = row.text("last_updated_at", "updated_at", "updated")
        )
    }.also { lists -> if (lists.map { it.id }.toSet().size != lists.size) throw MdbListDecodingException() }
}

/** Owned lists filled by MDBList filters, read from the same response as the static lists. */
internal fun decodeMdbListDynamicLists(body: String, accountId: Long): List<MdbListDynamicList> {
    val rows = mdbListResponseElement(body) as? JsonArray ?: throw MdbListDecodingException()
    return rows.mapNotNull { element ->
        val row = element.objectValue()
        if (row.flag("dynamic") != true && row.text("type") != "dynamic" ||
            row.number("user_id")?.let { it != accountId } == true) return@mapNotNull null
        val mediaType = row.text("mediatype")?.let(::mdbListLibraryType)
        if (row.text("mediatype") != null && mediaType == null) return@mapNotNull null
        MdbListDynamicList(
            id = mdbListUserListId(row),
            name = row.text("name") ?: throw MdbListDecodingException(),
            description = row.text("description"),
            mediaType = mediaType,
            updatedAt = row.text("last_updated_at", "updated_at", "updated")
        )
    }.also { lists -> if (lists.map { it.id }.toSet().size != lists.size) throw MdbListDecodingException() }
}

private fun mdbListUserListId(row: JsonObject): Long {
    val id = row.number("id") ?: row.arrayValue("ids").singleOrNull()
        ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
    return id?.takeIf { it > 0 } ?: throw MdbListDecodingException()
}

/** Lists of other users the account liked; the account's own lists already come from /lists/user. */
internal fun decodeMdbListLikedLists(body: String, accountId: Long): MdbListPage<MdbListLikedList> {
    val value = mdbListResponseElement(body).objectValue()
    val rows = value["lists"] as? JsonArray ?: throw MdbListDecodingException()
    val lists = rows.mapNotNull { element ->
        val row = element.objectValue()
        if (row.number("user_id") == accountId) return@mapNotNull null
        val mediaType = row.text("mediatype")?.let(::mdbListLibraryType)
        if (row.text("mediatype") != null && mediaType == null) return@mapNotNull null
        MdbListLikedList(
            id = row.number("id")?.takeIf { it > 0 } ?: throw MdbListDecodingException(),
            name = row.text("name") ?: throw MdbListDecodingException(),
            description = row.text("description"),
            mediaType = mediaType,
            updatedAt = row.text("last_updated_at", "updated_at", "updated")
        )
    }
    return mdbListPage(value, lists)
}

internal fun decodeMdbListExternalLists(body: String): List<MdbListExternalList> {
    val rows = mdbListResponseElement(body) as? JsonArray ?: throw MdbListDecodingException()
    return rows.mapNotNull { element ->
        val row = element.objectValue()
        val mediaType = row.text("mediatype")?.let(::mdbListLibraryType)
        if (row.text("mediatype") != null && mediaType == null) return@mapNotNull null
        MdbListExternalList(
            id = row.number("id")?.takeIf { it > 0 } ?: throw MdbListDecodingException(),
            name = row.text("name") ?: throw MdbListDecodingException(),
            source = row.text("source"),
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
        listedAt = row.text("listed_at", "added_at", "watchlist_at")?.let(::mdbListListedAt) ?: 0,
        rank = row.integer("rank")?.takeIf { it >= 0 },
        releaseDate = row.text("release_date", "released")
    )
}

// Only used to sort by recently added, so an unusual date (external lists can carry
// date-only values) must not discard the whole list.
private fun mdbListListedAt(value: String): Long = runCatching { mdbListTimestamp(value) }
    .recoverCatching { Instant.parse("${value.take(10)}T00:00:00Z").toEpochMilliseconds() }
    .getOrDefault(0)

internal fun mdbListLibraryType(type: String): MdbListItemType? = when (type.lowercase()) {
    "movie" -> MdbListItemType.MOVIE
    "show", "series", "tv", "anime" -> MdbListItemType.SHOW
    else -> null
}
