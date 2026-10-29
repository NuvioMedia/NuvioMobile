package com.nuvio.app.features.simkl

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

internal sealed interface SimklAllItemsRequest {
    val type: SimklMediaType?

    data class Bootstrap(
        override val type: SimklMediaType,
    ) : SimklAllItemsRequest

    data class Changes(
        val dateFrom: String,
    ) : SimklAllItemsRequest {
        override val type: SimklMediaType? = null
    }

    data object CurrentIds : SimklAllItemsRequest {
        override val type: SimklMediaType? = null
    }
}

internal interface SimklSyncRemote {
    suspend fun fetchActivities(): SimklActivities
    suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse
    suspend fun fetchPlayback(): List<SimklPlaybackSession>

    /**
     * The account's rewatch sessions, for every type a run can come from. This is a separate read
     * on purpose: a session comes back as a sidecar row with the same show as the canonical row, so
     * feeding it into [fetchAllItems] would let rewatch progress replace the canonical watch
     * position.
     *
     * [dateFrom] pairs the read with the sync watermark the way the rewatches guide asks: the
     * server then returns the items changed since it, with their session rows, and the caller
     * merges that delta into the sessions the snapshot already holds. Null reads the whole
     * library, which only the initial sync (and a recovery read) may do.
     */
    suspend fun fetchRewatchSessions(dateFrom: String?): List<SimklLibraryEntry>
}

/**
 * The types a rewatch session can come from: shows and anime. Anime is its own type in the API, so
 * reading shows alone left a rewatch the account holds for an anime without a run here. A movie
 * session carries no episodes, so it never becomes a run, and reading the movies library would be a
 * whole library call for nothing.
 */
private val REWATCH_SESSION_TYPES = listOf(
    SimklMediaType.SHOWS,
    SimklMediaType.ANIME,
)

internal class SimklApiSyncRemote(
    private val client: SimklApiClient = SimklApi.client,
) : SimklSyncRemote {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun fetchActivities(): SimklActivities =
        client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/activities",
            ),
        ).body.decode()

    override suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse {
        val path = request.type?.let { type -> "/sync/all-items/${type.apiValue}" }
            ?: "/sync/all-items"
        val query = when (request) {
            is SimklAllItemsRequest.Bootstrap -> mapOf(
                "extended" to "full_anime_seasons",
                "episode_watched_at" to "yes",
                "episode_tvdb_id" to "yes",
                "include_all_episodes" to "yes",
                "language" to "en",
            )
            is SimklAllItemsRequest.Changes -> mapOf(
                "date_from" to request.dateFrom,
                "extended" to "full_anime_seasons",
                "episode_watched_at" to "yes",
                "episode_tvdb_id" to "yes",
                "include_all_episodes" to "yes",
                "language" to "en",
            )
            SimklAllItemsRequest.CurrentIds -> mapOf(
                "extended" to "simkl_ids_only",
            )
        }
        return client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = path,
                query = query,
            ),
        ).body.decodeAllItems()
    }

    override suspend fun fetchPlayback(): List<SimklPlaybackSession> =
        client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/playback",
            ),
        ).body.decode()

    override suspend fun fetchRewatchSessions(dateFrom: String?): List<SimklLibraryEntry> =
        REWATCH_SESSION_TYPES.flatMap { type -> fetchRewatchSessions(type, dateFrom) }

    private suspend fun fetchRewatchSessions(
        type: SimklMediaType,
        dateFrom: String?,
    ): List<SimklLibraryEntry> =
        client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/all-items/${type.apiValue}",
                query = buildMap {
                    put("extended", "full")
                    put("allow_rewatch", "yes")
                    put("episode_watched_at", "yes")
                    put("language", "en")
                    // The guide's rule for this read: always pair it with date_from outside the
                    // initial sync. The delta carries the session rows of the changed items only,
                    // so every caller outside the initial sync hands one in and merges the result.
                    dateFrom?.let { value -> put("date_from", value) }
                },
            ),
        ).body.decodeAllItems().entriesFor(type)

    private inline fun <reified T> String.decode(): T = json.decodeFromString(this)

    private fun String.decodeAllItems(): SimklAllItemsResponse {
        val element = json.parseToJsonElement(this)
        return when {
            element is JsonNull -> SimklAllItemsResponse()
            element is JsonArray && element.isEmpty() -> SimklAllItemsResponse()
            else -> json.decodeFromJsonElement(element)
        }
    }
}
