package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
)

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val hasAired: Boolean,
    val isWatched: Boolean,
    val unairedMessage: String?,
)

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END,
}

// --- IntroDb API response models ---

@Serializable
data class IntroDbSegmentsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("intro") val intro: IntroDbSegment? = null,
    @SerialName("recap") val recap: IntroDbSegment? = null,
    @SerialName("outro") val outro: IntroDbSegment? = null,
    @SerialName("post_credits") val postCredits: IntroDbSegment? = null,
)

internal fun IntroDbSegmentsResponse.movieSkipIntervals(): List<SkipInterval> {
    val credits = outro.movieIntervalOrNull("movie-credits")
    val scene = postCredits.movieIntervalOrNull("post-credits")
    // End-credit skipping must stop before the post-credits scene, even if data overlaps.
    val safeCredits = if (credits != null && scene != null &&
        scene.startTime < credits.endTime && scene.endTime > credits.startTime
    ) {
        credits.copy(endTime = scene.startTime).takeIf { it.endTime > it.startTime }
    } else credits
    return listOfNotNull(safeCredits, scene)
}

private fun IntroDbSegment?.movieIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startSec ?: startMs?.let { it / 1000.0 } ?: return null
    val end = endSec ?: endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(start, end, type, "introdb")
}

/**
 * Where the content ends, in percent of the video it plays in, taken from the credits marker.
 *
 * Only the marker is read here, never the skip settings: a playback that reached the credits is over
 * for the tracker as well. A start that is not a real position inside the video is not usable, and a
 * null lets the caller fall back to the percentage the user set.
 */
internal fun List<SkipInterval>.contentEndPercent(durationMs: Long): Double? {
    if (durationMs <= 0L) return null
    val creditsStartSeconds = filter { interval -> interval.type in ContentEndSegmentTypes }
        .minOfOrNull { interval -> interval.startTime }
        ?: return null
    if (!creditsStartSeconds.isFinite() || creditsStartSeconds <= 0.0) return null
    val creditsStartMs = creditsStartSeconds * 1_000.0
    if (creditsStartMs >= durationMs) return null
    return creditsStartMs / durationMs.toDouble() * 100.0
}

/** Segment types that mark where the content ends: the series outro and the film credits. */
internal val ContentEndSegmentTypes: Set<String> =
    PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES + setOf("movie-credits")

@Serializable
data class IntroDbSegment(
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SubmitIntroRequest(
    @SerialName("imdb_id") val imdbId: String,
    @SerialName("season") val season: Int,
    @SerialName("episode") val episode: Int,
    @SerialName("start_sec") val startSec: Double,
    @SerialName("end_sec") val endSec: Double,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("segment_type") val segmentType: String,
)

// --- AniSkip API response models ---

@Serializable
data class AniSkipResponse(
    @SerialName("found") val found: Boolean = false,
    @SerialName("results") val results: List<AniSkipResult>? = null,
)

@Serializable
data class AniSkipResult(
    @SerialName("interval") val interval: AniSkipInterval,
    @SerialName("skipType") val skipType: String,
    @SerialName("skipId") val skipId: String? = null,
)

@Serializable
data class AniSkipInterval(
    @SerialName("startTime") val startTime: Double,
    @SerialName("endTime") val endTime: Double,
)

// --- Anime-Skip GraphQL API response models ---

@Serializable
data class AnimeSkipGraphqlResponse(
    @SerialName("data") val data: AnimeSkipData? = null,
)

@Serializable
data class AnimeSkipData(
    @SerialName("findShowsByExternalId") val findShowsByExternalId: List<AnimeSkipShow>? = null,
    @SerialName("findEpisodesByShowId") val findEpisodesByShowId: List<AnimeSkipEpisode>? = null,
)

@Serializable
data class AnimeSkipShow(
    @SerialName("id") val id: String,
)

@Serializable
data class AnimeSkipEpisode(
    @SerialName("season") val season: String? = null,
    @SerialName("number") val number: String? = null,
    @SerialName("timestamps") val timestamps: List<AnimeSkipTimestamp>? = null,
)

@Serializable
data class AnimeSkipTimestamp(
    @SerialName("at") val at: Double,
    @SerialName("type") val type: AnimeSkipTimestampType,
)

@Serializable
data class AnimeSkipTimestampType(
    @SerialName("name") val name: String,
)
