package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class SimklScrobbleOutcome {
    START,
    PAUSE,
    SCROBBLE,
}

/**
 * Result of an `allow_rewatch=yes` scrobble. Simkl returns a wider set of values here than
 * `/sync/all-items` does, so the client keeps its own enum rather than sharing one.
 */
internal enum class SimklRewatchStatus {
    ACTIVE,
    COMPLETED,
    CLOSED,
    FIRST_WATCH,
    TOO_SOON,
    NOT_ELIGIBLE,
    PRO_REQUIRED,
    UNKNOWN,
    ;

    companion object {
        fun fromWire(value: String?): SimklRewatchStatus? = when (value?.trim()?.lowercase()) {
            null, "" -> null
            "active" -> ACTIVE
            "completed" -> COMPLETED
            "closed" -> CLOSED
            "first_watch" -> FIRST_WATCH
            "too_soon" -> TOO_SOON
            "not_eligible" -> NOT_ELIGIBLE
            "pro_required" -> PRO_REQUIRED
            else -> UNKNOWN
        }
    }
}

internal data class SimklScrobbleResult(
    val outcome: SimklScrobbleOutcome,
    val playbackId: Long?,
    val progress: Double,
    val mediaType: SimklMediaType,
    val media: SimklMedia,
    val episode: SimklPlaybackEpisode?,
    val watchedAt: String? = null,
    val rewatchId: Long? = null,
    val rewatchStatus: SimklRewatchStatus? = null,
)

internal fun SimklApiResponse.toSimklScrobbleResult(
    requestedAction: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
    json: Json,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
): SimklScrobbleResult {
    val payload = body
        .takeIf(String::isNotBlank)
        ?.let { value -> runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull() }
        ?: JsonObject(emptyMap())
    val responseMediaType = payload.responseMediaType()
    val mediaType = responseMediaType ?: event.media.kind.toSimklMediaType()
    val responseMedia = responseMediaType
        ?.let { type -> payload.media(type, json) }
    val fallbackMedia = event.media.toSimklMedia()
    val episode = payload.episode(json, event)
    val progress = payload.doubleValue("progress")
        ?.coerceIn(0.0, 100.0)
        ?: event.progressPercent.coerceIn(0.0, 100.0)
    val outcome = when {
        isSoftSuccess && status == 409 -> SimklScrobbleOutcome.SCROBBLE
        else -> payload.stringValue("action")
            ?.toSimklScrobbleOutcome()
            ?: requestedAction.fallbackOutcome(progress, completionThresholdPercent)
    }
    return SimklScrobbleResult(
        outcome = outcome,
        playbackId = payload.longValue("id") ?: payload.longValue("sid"),
        progress = progress,
        mediaType = mediaType,
        media = responseMedia?.mergeMissing(fallbackMedia) ?: fallbackMedia,
        episode = episode,
        watchedAt = payload.stringValue("watched_at")
            ?.takeIf { value -> parseSimklUtcEpochMs(value) != null },
        rewatchId = payload.longValue("rewatch_id"),
        rewatchStatus = SimklRewatchStatus.fromWire(payload.stringValue("rewatch_status")),
    )
}

internal fun SimklMedia.mergeMissing(fallback: SimklMedia?): SimklMedia {
    if (fallback == null) return this
    return copy(
        title = title?.takeIf(String::isNotBlank) ?: fallback.title,
        poster = poster?.takeIf(String::isNotBlank) ?: fallback.poster,
        year = year ?: fallback.year,
        runtime = runtime ?: fallback.runtime,
        ids = fallback.ids + ids,
    )
}

private fun JsonObject.responseMediaType(): SimklMediaType? = when {
    get("movie") is JsonObject -> SimklMediaType.MOVIES
    get("anime") is JsonObject -> SimklMediaType.ANIME
    get("show") is JsonObject -> SimklMediaType.SHOWS
    else -> null
}

private fun JsonObject.media(type: SimklMediaType, json: Json): SimklMedia? {
    val key = when (type) {
        SimklMediaType.MOVIES -> "movie"
        SimklMediaType.SHOWS -> "show"
        SimklMediaType.ANIME -> "anime"
    }
    val value = get(key) ?: return null
    return runCatching { json.decodeFromJsonElement<SimklMedia>(value) }.getOrNull()
}

private fun JsonObject.episode(
    json: Json,
    event: TrackingScrobbleEvent,
): SimklPlaybackEpisode? {
    val responseEpisode = get("episode")
        ?.let { value ->
            runCatching { json.decodeFromJsonElement<SimklPlaybackEpisode>(value) }.getOrNull()
        }
    val fallback = event.media.episode?.let { episode ->
        SimklPlaybackEpisode(
            season = episode.season,
            number = episode.number,
            title = episode.title,
        )
    }
    val base = responseEpisode ?: fallback ?: return null
    return base.copy(
        tvdbSeason = base.tvdbSeason ?: intValue("tvdb_season"),
        tvdbNumber = base.tvdbNumber ?: intValue("tvdb_number"),
    )
}

internal fun TrackingMediaReference.toSimklMedia(): SimklMedia = SimklMedia(
    title = title?.takeIf(String::isNotBlank),
    year = year,
    ids = ids.toSimklJsonObjectOrNull()?.toMap().orEmpty(),
)

internal fun TrackingMediaKind.toSimklMediaType(): SimklMediaType = when (this) {
    TrackingMediaKind.MOVIE -> SimklMediaType.MOVIES
    TrackingMediaKind.SHOW -> SimklMediaType.SHOWS
    TrackingMediaKind.ANIME -> SimklMediaType.ANIME
}

private fun TrackingScrobbleAction.fallbackOutcome(
    progress: Double,
    completionThresholdPercent: Double,
): SimklScrobbleOutcome =
    when (this) {
        TrackingScrobbleAction.START -> SimklScrobbleOutcome.START
        TrackingScrobbleAction.PAUSE -> SimklScrobbleOutcome.PAUSE
        TrackingScrobbleAction.STOP -> {
            if (progress >= completionThresholdPercent) {
                SimklScrobbleOutcome.SCROBBLE
            } else {
                SimklScrobbleOutcome.PAUSE
            }
        }
    }

private fun String.toSimklScrobbleOutcome(): SimklScrobbleOutcome? = when (lowercase()) {
    "start" -> SimklScrobbleOutcome.START
    "pause" -> SimklScrobbleOutcome.PAUSE
    "scrobble" -> SimklScrobbleOutcome.SCROBBLE
    else -> null
}

private fun JsonObject.stringValue(key: String): String? =
    runCatching { get(key)?.jsonPrimitive?.content }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.longValue(key: String): Long? = stringValue(key)?.toLongOrNull()

private fun JsonObject.intValue(key: String): Int? = stringValue(key)?.toIntOrNull()

private fun JsonObject.doubleValue(key: String): Double? = stringValue(key)?.toDoubleOrNull()
