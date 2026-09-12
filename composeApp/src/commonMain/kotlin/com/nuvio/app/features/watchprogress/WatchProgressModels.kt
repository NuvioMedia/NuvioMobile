package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryProviderPosterUrl
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.TrackingAttributedItem
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlinx.serialization.Serializable

internal const val WatchProgressCompletionPercentThreshold = 90f
internal const val WatchProgressSourceLocal = "local"
internal const val WatchProgressSourceTraktPlayback = "trakt_playback"
internal const val WatchProgressSourceTraktHistory = "trakt_history"
internal const val WatchProgressSourceTraktShowProgress = "trakt_show_progress"
internal const val WatchProgressSourceSimklPlayback = "simkl_playback"

@Serializable
enum class ContinueWatchingSectionStyle {
    Card,
    Wide,
    Poster,
}

@Serializable
enum class ContinueWatchingSortMode {
    DEFAULT,
    STREAMING_STYLE,
    SPLIT_UPCOMING,
}

@Serializable
data class WatchProgressEntry(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val pauseDescription: String? = null,
    val lastSourceUrl: String? = null,
    val isCompleted: Boolean = false,
    val progressPercent: Float? = null,
    val source: String = WatchProgressSourceLocal,
    override val trackingProviderId: String? = null,
    override val trackingProviderItemId: String? = null,
    override val trackingSourceUrl: String? = null,
    /** Stable server/storage identity. [videoId] remains the playback identity. */
    val progressKey: String? = null,
    val excludedNextUpSeasons: Set<Int> = emptySet(),
) : TrackingAttributedItem {
    override val trackingContentId: String
        get() = parentMetaId

    val normalizedProgressPercent: Float?
        get() = progressPercent?.coerceIn(0f, 100f)

    val isEffectivelyCompleted: Boolean
        get() = isCompleted ||
            (normalizedProgressPercent?.let { it >= WatchProgressCompletionPercentThreshold } == true) ||
            (durationMs > 0L && isWatchProgressComplete(lastPositionMs, durationMs, false))

    val progressFraction: Float
        get() {
            normalizedProgressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (durationMs > 0L) {
                (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isResumable: Boolean
        get() = !isEffectivelyCompleted

    fun normalizedCompletion(): WatchProgressEntry {
        val completed = isEffectivelyCompleted
        // Preserve the upstream position. Completion is a state derived at the
        // 90% threshold, not evidence that playback reached the exact duration.
        // Rewriting it to duration made a pulled 94% row oscillate between 94%
        // and 100% across reloads.
        val normalizedPositionMs = lastPositionMs.coerceAtLeast(0L)
        val normalizedPercent = when {
            normalizedProgressPercent != null -> normalizedProgressPercent
            completed && durationMs <= 0L -> 100f
            else -> null
        }

        return if (
            completed == isCompleted &&
            normalizedPositionMs == lastPositionMs &&
            normalizedPercent == progressPercent
        ) {
            this
        } else {
            copy(
                lastPositionMs = normalizedPositionMs,
                isCompleted = completed,
                progressPercent = normalizedPercent,
            )
        }
    }

    fun resolveResumePosition(actualDurationMs: Long): Long {
        if (actualDurationMs <= 0L) return lastPositionMs.coerceAtLeast(0L)
        if (durationMs > 0L && lastPositionMs > 0L) {
            return lastPositionMs.coerceIn(0L, actualDurationMs)
        }
        normalizedProgressPercent?.let { percent ->
            val fraction = (percent / 100f).coerceIn(0f, 1f)
            return (actualDurationMs * fraction).toLong()
        }
        return lastPositionMs.coerceAtLeast(0L)
    }
}

data class WatchProgressUiState(
    val source: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
    val entries: List<WatchProgressEntry> = emptyList(),
    val hiddenContentIds: Set<String> = emptySet(),
    val hasLoadedRemoteProgress: Boolean = false,
) {
    val byProgressKey: Map<String, WatchProgressEntry>
        get() = entries.newestByProgressKey()

    /** Secondary compatibility lookup; multiple server rows may share a video id. */
    val byVideoId: Map<String, WatchProgressEntry>
        get() = entries
            .groupBy(WatchProgressEntry::videoId)
            .mapNotNull { (videoId, candidates) ->
                candidates.resolveProgressForVideo(videoId)?.let { entry -> videoId to entry }
            }
            .toMap()

    fun byVideoIdForContent(parentMetaId: String): Map<String, WatchProgressEntry> =
        entries
            .filter { entry -> entry.parentMetaId == parentMetaId }
            .groupBy(WatchProgressEntry::videoId)
            .mapValues { (_, candidates) -> candidates.maxWith(watchProgressEntryFreshnessComparator) }

    fun progressForVideo(
        videoId: String,
        parentMetaId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): WatchProgressEntry? = entries.resolveProgressForVideo(
        videoId = videoId,
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    val continueWatchingEntries: List<WatchProgressEntry>
        get() = entries.continueWatchingEntries(limit = ContinueWatchingLimit)
}

data class WatchProgressPlaybackSession(
    val profileId: Int,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val pauseDescription: String? = null,
    val lastSourceUrl: String? = null,
)

data class ContinueWatchingItem(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val released: String? = null,
    val isNextUp: Boolean = false,
    val nextUpSeedSeasonNumber: Int? = null,
    val nextUpSeedEpisodeNumber: Int? = null,
    val resumePositionMs: Long,
    val resumeProgressFraction: Float? = null,
    val durationMs: Long,
    val progressFraction: Float,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
)

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    val season = item.seasonNumber ?: -1
    val episode = item.episodeNumber ?: -1
    return "${item.parentMetaId}:$season:$episode"
}

data class ContinueWatchingPreferencesUiState(
    val isVisible: Boolean = true,
    val style: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
    val upNextFromFurthestEpisode: Boolean = true,
    val useEpisodeThumbnails: Boolean = true,
    val showUnairedNextUp: Boolean = true,
    val blurNextUp: Boolean = false,
    val dismissedNextUpKeys: Set<String> = emptySet(),
    val showResumePromptOnLaunch: Boolean = true,
    val sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
)

internal fun nextUpDismissKey(
    contentId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = buildString {
    append(contentId.trim())
    append("|")
    append(seasonNumber ?: -1)
    append("|")
    append(episodeNumber ?: -1)
}

internal fun WatchProgressEntry.toContinueWatchingItem(): ContinueWatchingItem {
    val normalizedEntry = normalizedCompletion()
    val cloudPosterUrl = normalizedEntry.cloudLibraryPosterFallbackUrl().nonBlankOrNull()
    val resolvedPoster = normalizedEntry.poster.nonBlankOrNull() ?: cloudPosterUrl
    val resolvedBackground = normalizedEntry.background.nonBlankOrNull()
    val resolvedEpisodeThumbnail = normalizedEntry.episodeThumbnail.nonBlankOrNull()
    val explicitResumeProgressFraction = normalizedEntry.normalizedProgressPercent
        ?.takeIf { durationMs <= 0L && it > 0f }
        ?.let { explicitPercent -> (explicitPercent / 100f).coerceIn(0f, 1f) }

    return ContinueWatchingItem(
        parentMetaId = normalizedEntry.parentMetaId,
        parentMetaType = normalizedEntry.parentMetaType,
        videoId = normalizedEntry.videoId,
        title = normalizedEntry.title,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = normalizedEntry.seasonNumber,
            episodeNumber = normalizedEntry.episodeNumber,
            episodeTitle = normalizedEntry.episodeTitle,
        ),
        imageUrl = resolvedEpisodeThumbnail ?: resolvedBackground ?: resolvedPoster,
        logo = normalizedEntry.logo.nonBlankOrNull(),
        poster = resolvedPoster,
        background = resolvedBackground,
        seasonNumber = normalizedEntry.seasonNumber,
        episodeNumber = normalizedEntry.episodeNumber,
        episodeTitle = normalizedEntry.episodeTitle.nonBlankOrNull(),
        episodeThumbnail = resolvedEpisodeThumbnail,
        pauseDescription = normalizedEntry.pauseDescription.nonBlankOrNull(),
        released = null,
        isNextUp = false,
        nextUpSeedSeasonNumber = null,
        nextUpSeedEpisodeNumber = null,
        resumePositionMs = if (explicitResumeProgressFraction != null) 0L else normalizedEntry.lastPositionMs,
        resumeProgressFraction = explicitResumeProgressFraction,
        durationMs = normalizedEntry.durationMs,
        progressFraction = normalizedEntry.progressFraction,
        isReleaseAlert = false,
        isNewSeasonRelease = false,
    )
}

private fun WatchProgressEntry.cloudLibraryPosterFallbackUrl(): String? {
    if (!contentType.equals(CloudLibraryContentType, ignoreCase = true) &&
        !parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)
    ) {
        return null
    }
    return cloudLibraryProviderPosterUrl(parentMetaId)
        ?: cloudLibraryProviderPosterUrl(providerAddonId)
}

internal fun WatchProgressEntry.toUpNextContinueWatchingItem(
    nextEpisode: MetaVideo,
): ContinueWatchingItem {
    val alertState = calculateReleaseAlertState(
        seedLastUpdatedEpochMs = lastUpdatedEpochMs,
        seedSeasonNumber = seasonNumber,
        nextSeasonNumber = nextEpisode.season,
        releasedIso = nextEpisode.released,
    )
    val resolvedPoster = poster.nonBlankOrNull()
    val resolvedBackground = background.nonBlankOrNull()
    val resolvedCurrentEpisodeThumbnail = episodeThumbnail.nonBlankOrNull()
    val resolvedNextEpisodeThumbnail = nextEpisode.thumbnail.nonBlankOrNull()
    return ContinueWatchingItem(
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = nextEpisode.id.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = nextEpisode.season,
            episodeNumber = nextEpisode.episode,
            fallbackVideoId = nextEpisode.id,
        ),
        title = title,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = nextEpisode.season,
            episodeNumber = nextEpisode.episode,
            episodeTitle = nextEpisode.title,
        ),
        imageUrl = resolvedNextEpisodeThumbnail
            ?: resolvedCurrentEpisodeThumbnail
            ?: resolvedBackground
            ?: resolvedPoster,
        logo = logo.nonBlankOrNull(),
        poster = resolvedPoster,
        background = resolvedBackground,
        seasonNumber = nextEpisode.season,
        episodeNumber = nextEpisode.episode,
        episodeTitle = nextEpisode.title.nonBlankOrNull(),
        episodeThumbnail = resolvedNextEpisodeThumbnail,
        pauseDescription = nextEpisode.overview.nonBlankOrNull(),
        released = nextEpisode.released.nonBlankOrNull(),
        isNextUp = true,
        nextUpSeedSeasonNumber = seasonNumber,
        nextUpSeedEpisodeNumber = episodeNumber,
        resumePositionMs = 0L,
        resumeProgressFraction = null,
        durationMs = 0L,
        progressFraction = 0f,
        isReleaseAlert = alertState.isReleaseAlert,
        isNewSeasonRelease = alertState.isNewSeasonRelease,
    )
}

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

internal fun buildContinueWatchingEpisodeSubtitle(
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String {
    val episodeCode = when {
        seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
        episodeNumber != null -> "E${episodeNumber}"
        else -> null
    }
    val title = episodeTitle.orEmpty()
    return listOfNotNull(episodeCode, title.takeIf { it.isNotBlank() }).joinToString(" • ")
}

fun buildPlaybackVideoId(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    fallbackVideoId: String? = null,
): String = com.nuvio.app.features.watching.domain.buildPlaybackVideoId(
    content = WatchingContentRef(type = "", id = parentMetaId),
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    fallbackVideoId = fallbackVideoId,
)
