package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingListStatus
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResolution
import com.nuvio.app.features.tracking.TrackingMutationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class SimklMutationReceipt(
    val result: TrackingMutationResult,
    val mutation: SimklCommittedMutation,
    val requiresReconciliation: Boolean = false,
)

internal sealed interface SimklCommittedMutation {
    data class MoveToList(
        val items: List<SimklResolvedListMutation>,
    ) : SimklCommittedMutation

    data class AddToHistory(
        val items: List<SimklResolvedHistoryMutation>,
    ) : SimklCommittedMutation

    data class RemoveFromHistory(
        val items: List<TrackingMediaReference>,
        val deleted: SimklDeletedCounts,
    ) : SimklCommittedMutation
}

internal data class SimklResolvedListMutation(
    val request: TrackingMediaReference,
    val status: SimklListStatus,
    val responseMedia: SimklMedia,
    val resolvedMediaType: SimklMediaType? = null,
    val animeType: String? = null,
)

internal data class SimklResolvedHistoryMutation(
    val request: TrackingHistoryItem,
    val status: SimklListStatus?,
    val mediaType: SimklMediaType?,
    val animeType: String?,
    val rewatchId: Long? = null,
    val rewatchStatus: String? = null,
)

internal data class SimklDeletedCounts(
    val movies: Int = 0,
    val shows: Int = 0,
    val episodes: Int = 0,
)

internal fun SimklApiResponse.toListMutationReceipt(
    candidates: List<TrackingMediaReference>,
    json: Json,
): SimklMutationReceipt {
    val payload = payload(json)
    val result = payload.toTrackingMutationResult(candidates.size)
    val unmatched = candidates.toMutableList()
    val accepted = buildList {
        payload.objectValue("added")?.forEach { (bucket, value) ->
            val expectedMovie = bucket == "movies"
            (value as? JsonArray).orEmpty().forEach { element ->
                val response = runCatching { element.jsonObject }.getOrNull()
                    ?: return@forEach
                val status = response.stringValue("to")?.toSimklListStatus()
                    ?: return@forEach
                val responseMedia = response.toResponseMedia()
                val animeType = response.stringValue("anime_type")
                val resolvedMediaType = if (animeType != null) {
                    SimklMediaType.ANIME
                } else {
                    response.stringValue("type")?.toSimklMediaType()
                }
                val index = unmatched.indexOfFirst { candidate ->
                    (candidate.kind == TrackingMediaKind.MOVIE) == expectedMovie &&
                        responseMedia.matchesTarget(candidate.toSimklMedia())
                }
                if (index >= 0) {
                    add(
                        SimklResolvedListMutation(
                            request = unmatched.removeAt(index),
                            status = status,
                            responseMedia = responseMedia,
                            resolvedMediaType = resolvedMediaType,
                            animeType = animeType,
                        ),
                    )
                }
            }
        }
    }
    return SimklMutationReceipt(
        result = result,
        mutation = SimklCommittedMutation.MoveToList(accepted),
        requiresReconciliation = accepted.size + result.notFoundCount < candidates.size,
    )
}

internal fun SimklApiResponse.toHistoryMutationReceipt(
    candidates: List<TrackingHistoryItem>,
    json: Json,
): SimklMutationReceipt {
    val payload = payload(json)
    val result = payload.toTrackingMutationResult(candidates.size)
    val notFound = payload.notFoundTargets()
    val statuses = payload.historyStatuses()
    val accepted = candidates
        .filterNot { candidate -> notFound.any { target -> target.matches(candidate.media) } }
        .map { candidate ->
            val status = statuses.firstOrNull { record -> record.target.matches(candidate.media) }
            SimklResolvedHistoryMutation(
                request = candidate,
                status = status?.status,
                mediaType = status?.mediaType,
                animeType = status?.animeType,
                rewatchId = status?.rewatchId,
                rewatchStatus = status?.rewatchStatus,
            )
        }
    return SimklMutationReceipt(
        result = result,
        mutation = SimklCommittedMutation.AddToHistory(accepted),
        requiresReconciliation = accepted.any { resolved ->
            resolved.status == null ||
                resolved.request.media.kind != TrackingMediaKind.MOVIE
        },
    )
}

internal fun SimklApiResponse.toHistoryRemovalReceipt(
    candidates: List<TrackingMediaReference>,
    json: Json,
): SimklMutationReceipt {
    val payload = payload(json)
    val result = payload.toTrackingMutationResult(candidates.size)
    val notFound = payload.notFoundTargets()
    val accepted = candidates.filterNot { candidate ->
        notFound.any { target -> target.matches(candidate) }
    }
    val deleted = payload.objectValue("deleted")
    return SimklMutationReceipt(
        result = result,
        mutation = SimklCommittedMutation.RemoveFromHistory(
            items = accepted,
            deleted = SimklDeletedCounts(
                movies = deleted?.intValue("movies") ?: 0,
                shows = deleted?.intValue("shows") ?: 0,
                episodes = deleted?.intValue("episodes") ?: 0,
            ),
        ),
        requiresReconciliation = accepted.any { candidate ->
            candidate.kind != TrackingMediaKind.MOVIE && candidate.episode != null
        },
    )
}

private data class SimklResponseTarget(
    val media: SimklMedia,
) {
    fun matches(reference: TrackingMediaReference): Boolean =
        media.matchesTarget(reference.toSimklMedia())
}

private data class SimklHistoryStatus(
    val target: SimklResponseTarget,
    val status: SimklListStatus?,
    val mediaType: SimklMediaType?,
    val animeType: String?,
    val rewatchId: Long? = null,
    /**
     * What the account did with a write that asked for a repeat viewing: `active` for a run the write
     * joined or opened, `completed`, `first_watch` for a write it read as a plain watch, `too_soon` for
     * one folded into a watch less than two days old, and `pro_required` for a plan without rewatches.
     */
    val rewatchStatus: String? = null,
)

private fun SimklApiResponse.payload(json: Json): JsonObject =
    body
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() }
        ?: JsonObject(emptyMap())

private fun JsonObject.toTrackingMutationResult(attemptedCount: Int): TrackingMutationResult {
    val notFoundCount = objectValue("not_found")
        ?.values
        ?.sumOf { value -> (value as? JsonArray)?.size ?: 0 }
        ?: 0
    return TrackingMutationResult(
        attemptedCount = attemptedCount,
        notFoundCount = notFoundCount,
        resolutions = objectValue("added")?.toMutationResolutions().orEmpty(),
    )
}

private fun JsonObject.notFoundTargets(): List<SimklResponseTarget> =
    objectValue("not_found")
        ?.values
        ?.flatMap { value ->
            (value as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching { element.jsonObject }.getOrNull()
                    ?.toResponseMedia()
                    ?.let(::SimklResponseTarget)
            }
        }
        .orEmpty()

private fun JsonObject.historyStatuses(): List<SimklHistoryStatus> =
    (objectValue("added")?.get("statuses") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull()
                ?: return@mapNotNull null
            val request = item.objectValue("request") ?: return@mapNotNull null
            val response = item.objectValue("response") ?: return@mapNotNull null
            SimklHistoryStatus(
                target = SimklResponseTarget(request.toResponseMedia()),
                status = response.stringValue("status")?.toSimklListStatus(),
                mediaType = response.stringValue("simkl_type")?.toSimklMediaType(),
                animeType = response.stringValue("anime_type"),
                rewatchId = response.intValue("rewatch_id")?.toLong(),
                rewatchStatus = response.stringValue("rewatch_status"),
            )
        }

private fun JsonObject.toMutationResolutions(): List<TrackingMutationResolution> {
    val historyResolutions = (get("statuses") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val response = runCatching { element.jsonObject["response"]?.jsonObject }.getOrNull()
                ?: return@mapNotNull null
            response.toMutationResolution(statusKey = "status")
        }
    if (historyResolutions.isNotEmpty()) return historyResolutions

    return entries.flatMap { (bucket, value) ->
        (value as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()
                ?.toMutationResolution(statusKey = "to", fallbackKind = bucket.toTrackingMediaKind())
        }
    }
}

private fun JsonObject.toMutationResolution(
    statusKey: String,
    fallbackKind: TrackingMediaKind? = null,
): TrackingMutationResolution? {
    val status = TrackingListStatus.fromWireValue(stringValue(statusKey))
    val mediaKind = stringValue("simkl_type")?.toTrackingMediaKind()
        ?: stringValue("type")?.toTrackingMediaKind()
        ?: fallbackKind
    val providerSubtype = stringValue("anime_type")
    val rewatchStatus = stringValue("rewatch_status")
    if (status == null && mediaKind == null && providerSubtype == null && rewatchStatus == null) {
        return null
    }
    return TrackingMutationResolution(
        listStatus = status,
        mediaKind = mediaKind,
        providerSubtype = providerSubtype,
        rewatchStatus = rewatchStatus,
    )
}

private fun JsonObject.toResponseMedia(): SimklMedia = SimklMedia(
    title = stringValue("title"),
    year = intValue("year"),
    ids = objectValue("ids")?.toMap().orEmpty(),
)

private fun JsonObject.objectValue(key: String): JsonObject? =
    runCatching { get(key)?.jsonObject }.getOrNull()

private fun JsonObject.stringValue(key: String): String? =
    runCatching { get(key)?.jsonPrimitive }
        .getOrNull()
        ?.takeIf { primitive -> primitive !is kotlinx.serialization.json.JsonNull }
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.intValue(key: String): Int? = stringValue(key)?.toIntOrNull()

private fun String.toSimklListStatus(): SimklListStatus? =
    SimklListStatus.entries.firstOrNull { status -> status.apiValue == lowercase() }

private fun String.toSimklMediaType(): SimklMediaType? = when (lowercase()) {
    "movie", "movies" -> SimklMediaType.MOVIES
    "anime" -> SimklMediaType.ANIME
    "tv", "show", "shows" -> SimklMediaType.SHOWS
    else -> null
}

private fun String.toTrackingMediaKind(): TrackingMediaKind? = when (lowercase()) {
    "movie", "movies" -> TrackingMediaKind.MOVIE
    "anime" -> TrackingMediaKind.ANIME
    "tv", "show", "shows" -> TrackingMediaKind.SHOW
    else -> null
}
