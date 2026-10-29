package com.nuvio.app.features.mdblist

import com.nuvio.app.features.library.LibraryItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun LibraryItem.mdbListLibraryItem(): MdbListLibraryItem {
    val type = requireNotNull(mdbListLibraryType(type)) { "MDBList lists support movies and shows" }
    val ids = decodeMdbListIds(buildJsonObject {
        when {
            id.startsWith("tt") -> put("imdb", id)
            id.startsWith("imdb:") -> put("imdb", id.removePrefix("imdb:"))
            id.startsWith("tmdb:") -> put("tmdb", id.removePrefix("tmdb:"))
            id.startsWith("tvdb:") -> put("tvdb", id.removePrefix("tvdb:"))
            id.startsWith("trakt:") -> put("trakt", id.removePrefix("trakt:"))
        }
        imdbId?.let { put("imdb", it) }
        tmdbId?.let { put("tmdb", it) }
        traktId?.let { put("trakt", it) }
    })
    return MdbListLibraryItem(type, MdbListMedia(ids, name, com.nuvio.app.features.tracking.extractTrackingYear(releaseInfo), poster, banner), description, genres)
}

internal fun MdbListLibraryItem.membershipBody(watchlist: Boolean): String {
    val ids = buildJsonObject {
        media.ids.imdb?.let { put("imdb", it) }
        media.ids.tmdb?.let { put("tmdb", it) }
        media.ids.tvdb?.let { put("tvdb", it) }
        media.ids.trakt?.let { put("trakt", it) }
    }
    require(ids.isNotEmpty()) { "An external movie or show ID is required" }
    return buildJsonObject {
        put(if (type == MdbListItemType.MOVIE) "movies" else "shows", JsonArray(listOf(
            if (watchlist) buildJsonObject { put("ids", ids) } else ids
        )))
    }.toString()
}

internal fun verifyMdbListMembershipResponse(body: String, type: MdbListItemType, adding: Boolean) {
    val value = mdbListResponseElement(body).objectValue()
    val bucket = if (type == MdbListItemType.MOVIE) "movies" else "shows"
    fun count(key: String): Long? = (value[key] as? JsonObject)?.number(bucket) ?: value.number(key)
    val changed = count(if (adding) "added" else "removed") ?: throw MdbListDecodingException()
    val unchanged = count(if (adding) "existing" else "not_found") ?: 0
    if (changed < 0 || unchanged < 0 || changed + unchanged != 1L || adding && (count("not_found") ?: 0) != 0L) {
        throw MdbListDecodingException()
    }
}
