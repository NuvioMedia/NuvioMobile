package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlin.time.Instant

class MdbListHistoryService(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository
) {
    suspend fun add(scope: MdbListAuthScope, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        change(scope, items.toList(), remove = false)

    suspend fun remove(scope: MdbListAuthScope, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        change(scope, items.map { TrackingHistoryItem(it) }, remove = true)

    private suspend fun change(
        scope: MdbListAuthScope,
        items: List<TrackingHistoryItem>,
        remove: Boolean
    ): TrackingMutationResult {
        var failed = 0
        val resolvable = items.filter { item -> item.media.ids.toMdbListIds() != null }
        failed += items.size - resolvable.size
        for (batch in resolvable.chunked(100)) {
            val result = sync.write(scope, setOf(MdbListSyncBucket.WATCHED)) { snapshot ->
                val mapped = batch.mapNotNull { item ->
                    snapshot.mutationTarget(item.media)?.let { target ->
                        MdbListHistoryChange(target, if (remove) null else Instant.fromEpochMilliseconds(
                            item.watchedAtEpochMs ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
                        ).toString())
                    }
                }
                val changes = mapped.distinctBy { it.target.key }
                if (changes.isEmpty()) return@write snapshot to TrackingMutationResult(batch.size, batch.size)
                val response = api.post(
                    if (remove) "/sync/watched/remove" else "/sync/watched",
                    mdbListHistoryPayload(changes), scope,
                    query = if (remove) emptyMap() else mapOf("report_added" to "true")
                )
                val receipt = decodeMdbListHistoryReceipt(response.body, changes, remove)
                val watched = if (remove && receipt.complete) {
                    snapshot.watched.filterNot { record -> changes.any { change ->
                        change.target.matches(record) || change.target.type == MdbListItemType.SHOW &&
                            record.type != MdbListItemType.MOVIE && change.target.media.ids.matches(record.media.ids)
                    } }
                } else {
                    val confirmedTargets = receipt.confirmed.map { record ->
                        MdbListMutationTarget(record.type, record.media, record.season, record.episode,
                            record.episodeTitle, record.episodeTmdbId, record.episodeTvdbId)
                    }
                    snapshot.watched.filterNot { record -> confirmedTargets.any { it.matches(record) } } + receipt.confirmed
                }
                val next = snapshot.copy(
                    watched = watched,
                    invalidatedBuckets = snapshot.invalidatedBuckets +
                        if (receipt.needsSnapshot) setOf(MdbListSyncBucket.WATCHED) else emptySet()
                ).normalizeMedia()
                next to TrackingMutationResult(
                    attemptedCount = batch.size,
                    notFoundCount = (batch.size - mapped.size + receipt.notFoundCount).coerceAtMost(batch.size)
                )
            }
            failed += result.notFoundCount
        }
        if (sync.currentSnapshot()?.invalidatedBuckets?.isNotEmpty() == true) sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
        return TrackingMutationResult(items.size, failed)
    }
}
