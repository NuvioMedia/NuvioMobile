package com.nuvio.app.features.mdblist

interface MdbListSyncRemote {
    suspend fun activities(): MdbListActivities
    suspend fun watched(): List<MdbListWatchedRecord>
    suspend fun journal(since: String): MdbListPage<MdbListJournalRecord>
    suspend fun playback(): List<MdbListPlayback>
    suspend fun dropped(): List<MdbListDroppedRecord>
}

class MdbListHttpSyncRemote(
    private val api: MdbListApiClient,
    private val scope: MdbListAuthScope
) : MdbListSyncRemote {
    override suspend fun activities(): MdbListActivities =
        decodeMdbListActivities(api.get("/sync/last_activities", scope = scope).body)

    override suspend fun watched(): List<MdbListWatchedRecord> = pages(
        "/sync/watched", mapOf("append_to_response" to "poster"), ::decodeMdbListWatched
    ).items

    override suspend fun journal(since: String): MdbListPage<MdbListJournalRecord> =
        pages("/sync/journal", mapOf("since" to since), ::decodeMdbListJournal)

    override suspend fun playback(): List<MdbListPlayback> =
        decodeMdbListPlayback(api.get("/sync/playback", scope = scope).body)

    override suspend fun dropped(): List<MdbListDroppedRecord> =
        pages("/sync/dropped") { decodeMdbListDropped(it, seasons = false) }.items +
            pages("/sync/seasons/dropped") { decodeMdbListDropped(it, seasons = true) }.items

    private suspend fun <T> pages(
        path: String,
        initialQuery: Map<String, String> = emptyMap(),
        decode: (String) -> MdbListPage<T>
    ): MdbListPage<T> {
        val results = mutableListOf<T>()
        val visited = mutableSetOf<Map<String, String>>()
        var query = initialQuery + ("limit" to "1000")
        var serverTime: String? = null
        repeat(1_000) {
            if (!visited.add(query)) throw MdbListDecodingException()
            val response = api.get(
                path, query, scope,
                acceptedStatuses = if (path == "/sync/journal") setOf(409) else emptySet()
            )
            val page = decode(response.body)
            if (page.requiresFullSync) return page.copy(items = emptyList())
            if (response.status == 409) throw MdbListApiException(response.status, response.errorCode())
            results += page.items
            serverTime = serverTime ?: page.serverTime
            query = when {
                page.nextCursor != null -> initialQuery.minus("since") +
                    mapOf("limit" to "1000", "cursor" to page.nextCursor)
                page.nextOffset != null && path != "/sync/journal" -> initialQuery +
                    mapOf("limit" to "1000", "offset" to page.nextOffset.toString())
                page.nextOffset != null -> throw MdbListDecodingException()
                else -> return MdbListPage(results, serverTime = serverTime)
            }
        }
        throw MdbListDecodingException()
    }
}
