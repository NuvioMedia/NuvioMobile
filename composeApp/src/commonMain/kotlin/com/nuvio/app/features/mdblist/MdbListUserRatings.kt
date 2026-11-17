package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

class MdbListUserRatings(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    private val now: () -> String = { Clock.System.now().toString() }
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val changes: Flow<Any?> = sync.state

    override fun supports(media: TrackingMediaReference): Boolean = media.ids.toMdbListIds() != null

    override fun rating(media: TrackingMediaReference): Double? = sync.currentSnapshot()?.userRating(media)

    override suspend fun rate(profileId: Int, media: TrackingMediaReference, rating: Int?): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        return sync.write(scope, setOf(MdbListSyncBucket.RATINGS)) { snapshot ->
            val target = snapshot.mutationTarget(media)
                ?: return@write snapshot to TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
            val response = api.post(
                if (rating == null) "/sync/ratings/remove" else "/sync/ratings",
                mdbListRatingPayload(target, rating),
                scope
            )
            val failed = mdbListRatingFailures(response.body) > 0
            val ratings = snapshot.ratings
            val next = when {
                failed -> snapshot.copy(invalidatedBuckets = snapshot.invalidatedBuckets + MdbListSyncBucket.RATINGS)
                ratings == null -> snapshot
                else -> snapshot.copy(
                    ratings = ratings.filterNot { it.matches(target) } + listOfNotNull(rating?.let { target.rated(it.toDouble(), now()) })
                )
            }
            next to TrackingMutationResult(attemptedCount = 1, notFoundCount = if (failed) 1 else 0)
        }
    }

    override fun onEnabled() {
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
    }
}

internal fun MdbListSyncSnapshot.userRating(reference: TrackingMediaReference): Double? {
    val ratings = ratings ?: return null
    val target = mutationTarget(reference) ?: return null
    return ratings.firstOrNull { it.matches(target) }?.rating
}

internal fun MdbListRatingRecord.matches(target: MdbListMutationTarget): Boolean =
    type == target.type && media.ids.matches(target.media.ids) && (type != MdbListItemType.EPISODE ||
        (episodeTmdbId != null && episodeTmdbId == target.episodeTmdbId) ||
        (episodeTvdbId != null && episodeTvdbId == target.episodeTvdbId) ||
        (season == target.season && episode == target.episode))

internal fun mdbListRatingPayload(target: MdbListMutationTarget, rating: Int?): String = buildJsonObject {
    val episodeIds = buildJsonObject {
        target.episodeTmdbId?.let { put("tmdb", it) }
        target.episodeTvdbId?.let { put("tvdb", it) }
    }
    when {
        target.type == MdbListItemType.MOVIE -> put("movies", ratingItems(target.media.ids.requestIds(), rating))
        target.type != MdbListItemType.EPISODE -> put("shows", ratingItems(target.media.ids.requestIds(), rating))
        episodeIds.isNotEmpty() -> put("episodes", ratingItems(episodeIds, rating))
        else -> put("shows", JsonArray(listOf(buildJsonObject {
            put("ids", target.media.ids.requestIds())
            put("seasons", JsonArray(listOf(buildJsonObject {
                put("number", requireNotNull(target.season))
                put("episodes", JsonArray(listOf(buildJsonObject {
                    put("number", requireNotNull(target.episode))
                    rating?.let { put("rating", it) }
                })))
            })))
        })))
    }
}.toString()

private fun ratingItems(ids: JsonObject, rating: Int?): JsonArray = JsonArray(listOf(buildJsonObject {
    put("ids", ids)
    rating?.let { put("rating", it) }
}))

private fun MdbListMutationTarget.rated(rating: Double, at: String) = MdbListRatingRecord(
    type, media, rating, at, season, episode, episodeTmdbId, episodeTvdbId
)
