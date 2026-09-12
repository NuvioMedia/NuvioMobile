package com.nuvio.app.features.mdblist

import kotlinx.coroutines.CoroutineScope

internal const val MDBLIST_TEST_LIST_KEY = "mdblist:list:7"
internal const val MDBLIST_EMPTY_LIBRARY_PAGE = """{"movies":[],"shows":[],"pagination":{"offset":0,"limit":1000,"total":0,"has_more":false}}"""
internal const val MDBLIST_LIBRARY_MOVIE_PAGE = """{"movies":[{"id":278,"imdb_id":"tt0111161","title":"Shawshank","release_year":1994,"rank":1}],"shows":[]}"""

internal fun mdbListLibraryListsBody(version: String? = "v1", count: Int = 0) = """[
    {"id":7,"user_id":42,"name":"Favourites","type":"static","private":true,"items":$count,"last_updated_at":${version?.let { "\"$it\"" } ?: "null"}}
]"""

internal fun mdbListLibrarySnapshot(now: Long) = MdbListLibrarySnapshot(
    lists = listOf(MdbListLibraryList(7, "Favourites", true, updatedAt = "v1")),
    itemsByList = mapOf(MDBLIST_WATCHLIST_KEY to emptyList(), MDBLIST_TEST_LIST_KEY to emptyList()),
    checkedAtEpochMs = now
)

internal fun MdbListSyncTestHarness.libraryService(coroutineScope: CoroutineScope) = MdbListLibraryService(
    http.api, repository, http.store, activeProfile, coroutineScope, { http.now }
)

internal fun MdbListSyncTestHarness.seedLibrary(library: MdbListLibrarySnapshot = mdbListLibrarySnapshot(http.now)) {
    seed(snapshot().copy(library = library))
}
