package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class MdbListTrackingProgressProvider(
    private val sync: MdbListSyncRepository,
    private val scrobble: MdbListScrobbleService,
    auth: MdbListAuthStore,
    activeProfile: StateFlow<Int>,
    private val ensureAccountLoaded: () -> Unit,
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val changes = combine(sync.state, auth.state, activeProfile) { _, _, _ -> Unit }
    override val ownsCompletedHistoryProjection = true
    override fun showIdSiblings() = sync.currentProjection().showIdSiblings

    override fun ensureLoaded() = ensureAccountLoaded()

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) {
        ensureAccountLoaded()
        sync.refresh(if (force) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
    }

    override fun snapshot(): TrackingProgressSnapshot {
        val projection = sync.currentProjection()
        val state = sync.state.value.takeIf { it.scope == runCatching { sync.currentScope() }.getOrNull() }
        return TrackingProgressSnapshot(
            entries = projection.progress + projection.nextUpSeeds,
            hiddenContentIds = projection.hiddenContentIds,
            hasLoadedRemoteProgress = state?.hasLoaded == true,
            errorMessage = state?.error?.localizedMdbListMessage(),
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        val scope = sync.currentScope()
        entries.distinctBy { Triple(it.parentMetaId, it.seasonNumber, it.episodeNumber) }.forEach { entry ->
            scrobble.clear(scope, entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        }
    }

    override fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        sync.currentProjection().canonicalContentId(parentContentId) ?: parentContentId

    override fun isHiddenFromProgress(contentId: String): Boolean = sync.currentProjection().isHidden(contentId)

    override suspend fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean) =
        sync.refresh(if (forceRefresh) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC)
}
