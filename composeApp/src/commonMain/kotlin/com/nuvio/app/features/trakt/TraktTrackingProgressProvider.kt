package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object TraktTrackingProgressProvider : TrackingProgressProvider {
    private val log = Logger.withTag("TraktProgressPort")

    override val providerId: TrackingProviderId = TrackingProviderId.TRAKT
    override val changes: Flow<Unit> = TraktProgressRepository.uiState.map { Unit }
    override val providesCompleteMetadata: Boolean = true
    override val ownsCompletedHistoryProjection: Boolean = true
    override fun showIdSiblings() = TraktProgressRepository.getShowIdSiblings()

    override fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? {
        val normalizedDaysCap = normalizeTraktContinueWatchingDaysCap(daysCap)
        if (normalizedDaysCap == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) return null
        return nowEpochMs - normalizedDaysCap.toLong() * MILLIS_PER_DAY
    }

    override fun shouldUseAsNextUpSeed(entry: WatchProgressEntry, nowEpochMs: Long): Boolean {
        if (!entry.shouldUseAsCompletedSeedForContinueWatching()) return false
        if (entry.source != WatchProgressSourceTraktPlayback) return true

        val explicitPercent = entry.normalizedProgressPercent ?: return false
        if (explicitPercent < TRAKT_PLAYBACK_NEXT_UP_SEED_PERCENT_THRESHOLD) return false

        val ageMs = nowEpochMs - entry.lastUpdatedEpochMs
        return ageMs in 0..OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
    }

    override suspend fun prepareNextUpProgressEntries(
        entries: List<WatchProgressEntry>,
        contentId: String,
    ): List<WatchProgressEntry> = entries.map { entry ->
        if (entry.parentMetaId != contentId) {
            entry
        } else {
            val mapping = TraktEpisodeMappingService.resolveAddonEpisodeMapping(
                contentId = entry.parentMetaId,
                contentType = entry.contentType,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
            )
            if (mapping == null) {
                entry
            } else {
                entry.copy(
                    seasonNumber = mapping.season,
                    episodeNumber = mapping.episode,
                    videoId = buildPlaybackVideoId(
                        parentMetaId = entry.parentMetaId,
                        seasonNumber = mapping.season,
                        episodeNumber = mapping.episode,
                        fallbackVideoId = entry.videoId,
                    ),
                    episodeTitle = mapping.title ?: entry.episodeTitle,
                )
            }
        }
    }

    override fun ensureLoaded() = TraktProgressRepository.ensureLoaded()

    override fun onProfileChanged() = TraktProgressRepository.onProfileChanged()

    override fun clearLocalState() = TraktProgressRepository.clearLocalState()

    override fun onActivated() = TraktProgressRepository.clearLocalState()

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) {
        if (force || sourceChanged) {
            TraktProgressRepository.invalidateAndRefresh()
        } else {
            TraktProgressRepository.refreshNow()
        }
    }

    override fun snapshot(): TrackingProgressSnapshot {
        val state = TraktProgressRepository.uiState.value
        return TrackingProgressSnapshot(
            entries = state.entries,
            hasLoadedRemoteProgress = state.hasLoadedRemoteProgress,
            errorMessage = state.errorMessage,
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        entries
            .filter { entry -> isTraktCompatibleId(entry.parentMetaId) }
            .distinctBy { entry -> Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber) }
            .forEach { entry ->
                try {
                    TraktProgressRepository.removeProgress(
                        contentId = entry.parentMetaId,
                        seasonNumber = entry.seasonNumber,
                        episodeNumber = entry.episodeNumber,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.e(error) { "Failed to remove Trakt progress for ${entry.parentMetaId}" }
                }
            }
    }

    override fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) {
        entries
            .distinctBy { entry -> Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber) }
            .forEach { entry ->
                TraktProgressRepository.applyOptimisticRemoval(
                    contentId = entry.parentMetaId,
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber,
                )
            }
    }

    override fun applyOptimisticProgress(entry: WatchProgressEntry) =
        TraktProgressRepository.applyOptimisticProgress(entry)

    override fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        resolveEffectiveContentId(parentContentId, videoId)

    override suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) =
        TraktProgressRepository.refreshEpisodeProgress(contentId, forceRefresh)

    override fun isHiddenFromProgress(contentId: String): Boolean =
        TraktProgressRepository.isShowHiddenFromProgress(contentId)
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3L * 60L * 1_000L
private const val TRAKT_PLAYBACK_NEXT_UP_SEED_PERCENT_THRESHOLD = 95f
