package com.nuvio.app.features.watching.domain

import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.daysUntilEpisodeRelease
import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.core.time.isoEpochDay as coreIsoEpochDay
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate

private const val CompletionThresholdFraction = 0.90
private const val ProgressStoreThresholdMs = 1_000L

/**
 * How far before the credits marker a playback is treated as finished.
 *
 * The marker comes from submissions, and a different release can shift the credits by a few seconds,
 * so a playback that stopped just short of it is treated as finished rather than as an unfinished
 * watch. One percentage point of the video, which is around 27 seconds of a 45 minute episode.
 */
internal const val ContentEndTolerancePercent = 1.0
private const val UpcomingNextSeasonWindowDays = 7

/**
 * Streams shorter than this are treated as error/placeholder clips (e.g. debrid
 * cache-sync placeholders, "service unavailable" error videos, RAR-only torrents),
 * not real episodes. Mirrors the internal-player guard in NuvioTV.
 */
private const val MinRealContentDurationMs = 121_000L

fun watchedKey(
    content: WatchingContentRef,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String = "${content.type.trim()}:${content.id.trim()}:${seasonNumber ?: -1}:${episodeNumber ?: -1}"

fun shouldStoreProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean = positionMs >= ProgressStoreThresholdMs

/**
 * Where a playback counts as finished as a fraction, from the credits marker of the release being
 * played, read one point early so a timestamp that is a few seconds off still ends the playback.
 *
 * Null when nothing is known about the credits, and the percentage the app has always used decides.
 */
internal fun completionFractionFor(contentEndPercent: Double?): Double? =
    contentEndPercent
        ?.takeIf { percent -> percent.isFinite() }
        ?.let { percent -> (percent - ContentEndTolerancePercent) / 100.0 }
        ?.takeIf { fraction -> fraction in 0.0..1.0 }

/**
 * [completionFraction], when the caller knows it, is where the content really ends. It replaces the
 * usual percentage, so a playback that stopped before the credits is not a finished one, whatever
 * percentage it happens to sit at.
 */
fun isProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
    completionFraction: Double? = null,
): Boolean {
    if (isEnded && isShortPlaceholderDuration(durationMs)) return false
    if (isEnded) return true
    if (durationMs <= 0L) return false

    val watchedFraction = positionMs.toDouble() / durationMs.toDouble()
    return watchedFraction >= (completionFraction ?: CompletionThresholdFraction)
}

/**
 * Returns `true` when the duration looks like an error clip or debrid cache-sync
 * placeholder rather than real content. A zero/negative duration is left to the
 * normal path so that players which only report "ended" still work.
 */
fun isShortPlaceholderDuration(durationMs: Long): Boolean =
    durationMs in 1 until MinRealContentDurationMs

fun isReleasedBy(
    todayIsoDate: String,
    releasedDate: String?,
    available: Boolean = true,
    nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
): Boolean {
    if (!available) return false
    return isEpisodeReleaseAired(releasedDate, nowEpochMs) ?: true
}

internal fun shouldSurfaceNextEpisode(
    watchedSeasonNumber: Int?,
    candidateSeasonNumber: Int?,
    todayIsoDate: String,
    releasedDate: String?,
    showUnairedNextUp: Boolean,
    available: Boolean = true,
    nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
): Boolean {
    val isSeasonRollover = normalizeSeasonNumber(candidateSeasonNumber) != normalizeSeasonNumber(watchedSeasonNumber)
    if (!available) {
        val daysUntilRelease = daysUntilExplicitRelease(
            todayIsoDate = todayIsoDate,
            releasedDate = releasedDate,
        ) ?: return false
        if (daysUntilRelease <= 0) return true
        if (!showUnairedNextUp) return false
        return !isSeasonRollover || daysUntilRelease <= UpcomingNextSeasonWindowDays
    }
    if (!isSeasonRollover) {
        if (showUnairedNextUp) return true
        return isReleasedBy(
            todayIsoDate = todayIsoDate,
            releasedDate = releasedDate,
            nowEpochMs = nowEpochMs,
        )
    }

    if (isExplicitlyReleasedBy(releasedDate = releasedDate, nowEpochMs = nowEpochMs)) {
        return true
    }
    if (!showUnairedNextUp) {
        return false
    }

    val daysUntilRelease = daysUntilExplicitRelease(
        todayIsoDate = todayIsoDate,
        releasedDate = releasedDate,
    ) ?: return false
    return daysUntilRelease in 0..UpcomingNextSeasonWindowDays
}

private fun isExplicitlyReleasedBy(
    releasedDate: String?,
    nowEpochMs: Long,
): Boolean {
    return isEpisodeReleaseAired(releasedDate, nowEpochMs) ?: false
}

internal fun daysUntilExplicitRelease(
    todayIsoDate: String,
    releasedDate: String?,
): Int? {
    return daysUntilEpisodeRelease(todayIsoDate, releasedDate)
}

internal fun isoCalendarDateOrNull(value: String?): String? = parseEpisodeReleaseLocalDate(value)

internal fun isoEpochDay(date: String): Long = coreIsoEpochDay(date)

fun releasedEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
    nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
): List<WatchingReleasedEpisode> = episodes.filter { episode ->
    isReleasedBy(
        todayIsoDate = todayIsoDate,
        releasedDate = episode.releasedDate,
        available = episode.available,
        nowEpochMs = nowEpochMs,
    )
}

fun releasedMainSeasonEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
    nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
): List<WatchingReleasedEpisode> = releasedEpisodes(
    episodes = episodes,
    todayIsoDate = todayIsoDate,
    nowEpochMs = nowEpochMs,
).filter { episode ->
    normalizeSeasonNumber(episode.seasonNumber) > 0
}

fun hasWatchedAllMainSeasonEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
    nowEpochMs: Long = EpisodeReleaseDatePlatform.nowEpochMs(),
    isEpisodeWatched: (WatchingReleasedEpisode) -> Boolean,
): Boolean {
    val mainSeasonEpisodes = releasedMainSeasonEpisodes(
        episodes = episodes,
        todayIsoDate = todayIsoDate,
        nowEpochMs = nowEpochMs,
    )
    return mainSeasonEpisodes.isNotEmpty() && mainSeasonEpisodes.all(isEpisodeWatched)
}

fun latestCompletedSeriesEpisode(
    content: WatchingContentRef,
    progressRecords: List<WatchingProgressRecord>,
    watchedRecords: List<WatchingWatchedRecord>,
    preferFurthestEpisode: Boolean = true,
): WatchingCompletedEpisode? {
    val ordering = if (preferFurthestEpisode) {
        compareBy<WatchingCompletedEpisode>(
            { normalizeSeasonNumber(it.seasonNumber) },
            { it.episodeNumber },
            { it.markedAtEpochMs },
        )
    } else {
        compareBy<WatchingCompletedEpisode>(
            { it.markedAtEpochMs },
            { normalizeSeasonNumber(it.seasonNumber) },
            { it.episodeNumber },
        )
    }
    val allMarkers = buildList {
        progressRecords
            .asSequence()
            .filter { record ->
                record.content == content &&
                    record.isCompleted &&
                    record.seasonNumber != null &&
                    record.episodeNumber != null
            }
            .mapNotNullTo(this) { record ->
                val seasonNumber = record.seasonNumber ?: return@mapNotNullTo null
                val episodeNumber = record.episodeNumber ?: return@mapNotNullTo null
                WatchingCompletedEpisode(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    markedAtEpochMs = record.lastUpdatedEpochMs,
                )
            }
        watchedRecords
            .asSequence()
            .filter { record ->
                record.content == content &&
                    record.seasonNumber != null &&
                    record.episodeNumber != null
            }
            .mapNotNullTo(this) { record ->
                val seasonNumber = record.seasonNumber ?: return@mapNotNullTo null
                val episodeNumber = record.episodeNumber ?: return@mapNotNullTo null
                WatchingCompletedEpisode(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    markedAtEpochMs = record.markedAtEpochMs,
                )
            }
    }
    return allMarkers.maxWithOrNull(ordering)
}

fun normalizeSeasonNumber(seasonNumber: Int?): Int = seasonNumber?.coerceAtLeast(0) ?: 0
