package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class MdbListWatchedSyncAdapter(
    private val sync: MdbListSyncRepository,
    private val history: MdbListHistoryService,
    auth: MdbListAuthStore,
    private val activeProfile: StateFlow<Int>,
) : TrackingWatchedProvider {
    override val providerId = TrackingProviderId.MDBLIST
    private val changes = combine(sync.state, auth.state, activeProfile) { _, _, _ -> Unit }

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        val scope = scope(profileId)
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        if (sync.currentScope() != scope) throw CancellationException("MDBList account changed")
        val state = sync.state.value
        if (!state.hasLoaded && state.error != null) throw MdbListApiException(code = "sync_unavailable")
        return sync.currentProjection().watchedItems
    }

    override suspend fun pullExtraWatchedKeys(profileId: Int): Set<String> {
        scope(profileId)
        return sync.currentProjection().watchedKeys
    }

    override fun observeExtraWatchedKeys(profileId: Int) = combine(changes, activeProfile) { _, active ->
        if (profileId == active) sync.currentProjection().watchedKeys else emptySet()
    }.distinctUntilChanged()

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        val result = history.add(scope(profileId), items.map { TrackingHistoryItem(it.reference(), it.markedAtEpochMs) })
        check(result.isComplete) { "MDBList could not match ${result.notFoundCount} watched items" }
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        val result = history.remove(scope(profileId), items.map { it.reference() })
        check(result.isComplete) { "MDBList could not match ${result.notFoundCount} watched items" }
    }

    private fun scope(profileId: Int) = sync.currentScope().also {
        if (it.profileId != profileId) throw CancellationException("MDBList profile changed")
    }

    private fun WatchedItem.reference() = buildTrackingMediaReference(
        contentType = type, parentMetaId = id, videoId = videoId, title = name, releaseInfo = releaseInfo,
        seasonNumber = season, episodeNumber = episode,
    )
}
