package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MdbListRatingsRepository(
    private val client: MdbListRatingsClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class RequestKey(
        val mediaType: String,
        val imdbId: String,
        val credential: MdbListRatingsCredential,
    )

    private val log = Logger.withTag("MdbListRatings")
    private val lock = SynchronizedObject()
    private val cache = mutableMapOf<RequestKey, List<MetaExternalRating>>()
    private val inFlight = mutableMapOf<RequestKey, CompletableDeferred<List<MetaExternalRating>>>()
    private val pending = mutableMapOf<RequestKey, CompletableDeferred<List<MetaExternalRating>>>()
    private var batchScheduled = false

    suspend fun getRatings(
        mediaType: String,
        imdbId: String,
        credential: MdbListRatingsCredential,
        providers: List<String>,
    ): List<MetaExternalRating> {
        if (providers.isEmpty()) return emptyList()
        client.checkCredential(credential)
        val key = RequestKey(mediaType, imdbId, credential)
        val deferred = synchronized(lock) {
            cache[key]?.let { return selectRatings(it, providers) }
            inFlight[key] ?: CompletableDeferred<List<MetaExternalRating>>().also { created ->
                inFlight[key] = created
                pending[key] = created
                if (!batchScheduled) {
                    batchScheduled = true
                    scope.launch {
                        delay(BATCH_WINDOW_MS)
                        flushPending()
                    }
                }
            }
        }
        val ratings = deferred.await()
        client.checkCredential(credential)
        return selectRatings(ratings, providers)
    }

    fun clearCache() {
        synchronized(lock) {
            cache.clear()
            pending.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
    }

    private suspend fun flushPending() {
        val requests = synchronized(lock) {
            batchScheduled = false
            pending.toList().also { pending.clear() }
        }
        requests.groupBy { (key, _) -> key.mediaType to key.credential }.values.forEach { group ->
            group.chunked(MAX_BATCH_SIZE).forEach { fetchBatch(it) }
        }
    }

    private suspend fun fetchBatch(requests: List<Pair<RequestKey, CompletableDeferred<List<MetaExternalRating>>>>) {
        val batch = synchronized(lock) { requests.filter { (key, deferred) -> inFlight[key] === deferred } }
        val first = batch.firstOrNull()?.first ?: return
        try {
            client.checkCredential(first.credential)
            val ratings = if (batch.size == 1) {
                mapOf(first.imdbId to parseMdbListRatings(client.getMedia(first.mediaType, first.imdbId, first.credential)))
            } else {
                parseMdbListRatingsBatch(client.getMediaBatch(first.mediaType, batch.map { it.first.imdbId }, first.credential))
            }
            client.checkCredential(first.credential)
            synchronized(lock) {
                batch.forEach { (key, deferred) ->
                    if (inFlight[key] === deferred) {
                        val result = ratings[key.imdbId].orEmpty()
                        cache[key] = result
                        inFlight.remove(key)
                        deferred.complete(result)
                    }
                }
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) log.w { "MDBList ratings request failed for ${batch.size} items" }
            synchronized(lock) {
                batch.forEach { (key, deferred) ->
                    if (inFlight[key] === deferred) inFlight.remove(key)
                    if (error is CancellationException) deferred.completeExceptionally(error)
                    else deferred.complete(emptyList())
                }
            }
        }
    }

    private fun selectRatings(ratings: List<MetaExternalRating>, providers: List<String>): List<MetaExternalRating> =
        providers.mapNotNull { provider -> ratings.firstOrNull { it.source == provider } }

    private companion object {
        const val MAX_BATCH_SIZE = 200
        const val BATCH_WINDOW_MS = 50L
    }
}
