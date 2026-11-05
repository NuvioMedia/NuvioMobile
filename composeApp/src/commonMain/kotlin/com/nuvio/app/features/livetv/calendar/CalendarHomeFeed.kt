package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle
import kotlin.time.Instant

/**
 * The Calendar on Home, using Nuvio's own Continue Watching cards:
 *  - a "new episode" card for shows you follow whose next episode is out and unwatched, and
 *  - the next episode of each followed show in the Upcoming row (when it hasn't aired yet).
 * A show Continue Watching already has a card for keeps its own card.
 */
internal object CalendarHomeFeed {
    private const val DAY = 24 * 3_600_000L

    fun items(
        entries: List<CalendarEntry>,
        nowMs: Long,
        newEpisodeCards: Boolean,
        upcomingRow: Boolean,
        skipShows: Set<String>,
        dismissed: Set<String>,
    ): List<ContinueWatchingItem> {
        if (!newEpisodeCards && !upcomingRow) return emptyList()
        return entries.asSequence()
            .filter { it.kind == CalendarKind.EPISODE && it.followed && !it.watched && it.season != null && it.episode != null }
            .filter { (it.airMs ?: it.dayMs) >= nowMs - 14 * DAY }
            .sortedBy { it.airMs ?: it.dayMs }
            .distinctBy { it.contentId }
            .filter { it.contentId !in skipShows && !it.contentId.startsWith("calendar:") }
            .mapNotNull { e ->
                val at = e.airMs ?: e.dayMs
                val aired = at <= nowMs
                if (aired && !newEpisodeCards) return@mapNotNull null
                if (!aired && !upcomingRow) return@mapNotNull null
                val key = "${e.contentId.trim()}|${e.season}|${e.episode}"
                if (key in dismissed) return@mapNotNull null
                val videoId = e.videoId ?: "${e.contentId}:${e.season}:${e.episode}"
                val title = if (e.spoiler) null else e.episodeTitle
                val thumb = if (e.spoiler) null else e.episodeImage
                ContinueWatchingItem(
                    parentMetaId = e.contentId,
                    parentMetaType = e.type,
                    videoId = videoId,
                    title = e.title,
                    subtitle = buildContinueWatchingEpisodeSubtitle(e.season, e.episode, title),
                    imageUrl = thumb ?: e.background ?: e.poster,
                    poster = e.poster,
                    background = e.background,
                    seasonNumber = e.season,
                    episodeNumber = e.episode,
                    episodeTitle = title,
                    episodeThumbnail = thumb,
                    released = Instant.fromEpochMilliseconds(at).toString(),
                    isNextUp = true,
                    resumePositionMs = 0L,
                    durationMs = 0L,
                    progressFraction = 0f,
                    isReleaseAlert = aired,
                )
            }
            .toList()
    }
}
