package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <T> MdbListSyncRepository.write(
    scope: MdbListAuthScope,
    buckets: Set<MdbListSyncBucket>,
    block: suspend (MdbListSyncSnapshot) -> Pair<MdbListSyncSnapshot, T>
): T = try {
    mutate(scope, block)
} catch (error: CancellationException) {
    withContext(NonCancellable) {
        try {
            invalidate(scope, buckets)
        } catch (_: Exception) {
        }
    }
    throw error
} catch (error: Exception) {
    try {
        invalidate(scope, buckets)
        refreshAsync(TrackingRefreshIntent.INVALIDATED)
    } catch (_: Exception) {
    }
    throw error
}
