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
     * The account's rewatch sessions. This is a separate read on purpose: a session comes back as a
     * sidecar row with the same show as the canonical row, so feeding it into [fetchAllItems] would
     * let rewatch progress replace the canonical watch position.
     */
    suspend fun fetchRewatchSessions(): List<SimklLibraryEntry>
}

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

    override suspend fun fetchRewatchSessions(): List<SimklLibraryEntry> =
        client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/all-items/${SimklMediaType.SHOWS.apiValue}",
                query = mapOf(
                    "extended" to "full",
                    "allow_rewatch" to "yes",
                    "episode_watched_at" to "yes",
                    "language" to "en",
                ),
            ),
        ).body.decodeAllItems().entriesFor(SimklMediaType.SHOWS)

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
