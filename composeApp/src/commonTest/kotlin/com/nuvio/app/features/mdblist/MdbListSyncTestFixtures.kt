package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MDBLIST_TEST_TIME = "2026-09-06T00:00:00Z"

internal class MdbListTestSyncStorage : MdbListSyncStorage {
    val profiles = mutableMapOf<Int, String>()
    var failSave = false
    var failLoad = false
    var beforeSave: suspend () -> Unit = {}

    override suspend fun load(profileId: Int): String? {
        if (failLoad) throw IOException("Disk unavailable")
        return profiles[profileId]
    }
    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        beforeSave()
        checkScope()
        if (failSave) throw IOException("Disk unavailable")
        profiles[profileId] = payload
    }
    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        checkScope()
        profiles.remove(profileId)
    }
}

internal class MdbListTestSyncRemote : MdbListSyncRemote {
    val calls = mutableListOf<String>()
    var beforeActivities: suspend () -> Unit = {}
    var activities = MdbListActivities(mapOf("journal_at" to null), MDBLIST_TEST_TIME)
    var watched = emptyList<MdbListWatchedRecord>()
    var playback = emptyList<MdbListPlayback>()
    var dropped = emptyList<MdbListDroppedRecord>()
    var error: Exception? = null

    override suspend fun activities(): MdbListActivities {
        calls += "activities"
        beforeActivities()
        error?.let { throw it }
        return activities
    }
    override suspend fun watched() = watched.also { calls += "watched" }
    override suspend fun playback() = playback.also { calls += "playback" }
    override suspend fun dropped() = dropped.also { calls += "dropped" }
    override suspend fun journal(since: String) = MdbListPage<MdbListJournalRecord>(emptyList()).also { calls += "journal" }
}

internal class MdbListSyncTestHarness(coroutineScope: CoroutineScope) {
    val http = MdbListTestHarness().apply {
        now = mdbListTimestamp(MDBLIST_TEST_TIME)
        connected(expiresIn = 30L * 24 * 60 * 60 * 1_000)
        store.saveUser(MdbListUser(42, "viewer"), store.scope())
    }
    val activeProfile = MutableStateFlow(1)
    val storage = MdbListTestSyncStorage()
    val remote = MdbListTestSyncRemote()
    val repository = MdbListSyncRepository(
        storage, http.store, http.api, activeProfile, coroutineScope, { http.now },
        coroutineScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    ) { remote }

    fun seed(snapshot: MdbListSyncSnapshot = snapshot(), profileId: Int = 1) {
        storage.profiles[profileId] = Json.encodeToString(snapshot)
    }

    fun snapshot(accountId: Long = 42) = MdbListSyncSnapshot(
        accountId, watched = listOf(mdbListTestMovie()), activities = remote.activities,
        watermark = MDBLIST_TEST_TIME, checkedAtEpochMs = http.now, isInitialized = true
    )

    fun switch(profileId: Int, accountId: Long? = null) {
        activeProfile.value = profileId
        http.store.selectProfile(profileId)
        if (accountId != null) {
            http.connected()
            http.store.saveUser(MdbListUser(accountId, "viewer-$accountId"), http.store.scope())
        }
    }
}

internal fun mdbListTestMovie(id: Long = 1) = MdbListWatchedRecord(
    MdbListItemType.MOVIE, MdbListMedia(MdbListIds(imdb = "tt$id", tmdb = id), "Movie $id"), MDBLIST_TEST_TIME
)

internal fun mdbListTestEpisode(season: Int = 1, episode: Int = 1, watchedAt: String = MDBLIST_TEST_TIME) =
    mdbListTestMovie().copy(type = MdbListItemType.EPISODE, season = season, episode = episode, watchedAt = watchedAt)

internal fun mdbListTestPlayback(progress: Float = 40f, timestamp: String = MDBLIST_TEST_TIME) = MdbListPlayback(
    7, MdbListItemType.MOVIE, mdbListTestMovie().media, progress, timestamp
)
