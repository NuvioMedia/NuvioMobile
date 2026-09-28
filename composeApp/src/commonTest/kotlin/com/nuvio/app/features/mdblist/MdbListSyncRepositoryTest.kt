package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingRefreshIntent
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListSyncRepositoryTest {
    @Test
    fun `cached account loads without network and survives process recreation`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.repository.ensureLoaded()
        assertEquals(listOf(mdbListTestMovie()), harness.repository.currentSnapshot()?.watched)
        assertTrue(harness.repository.state.value.hasLoaded)
        assertTrue(harness.http.engine.requests.isEmpty())
        assertTrue(harness.remote.calls.isEmpty())
    }

    @Test
    fun `cached data from another account is never exposed`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed(harness.snapshot(999))
        harness.repository.ensureLoaded()
        assertEquals(42L, harness.repository.currentSnapshot()?.accountId)
        assertTrue(harness.repository.currentProjection().watchedItems.isEmpty())
        assertFalse(harness.repository.state.value.hasLoaded)
    }

    @Test
    fun `corrupt snapshot restarts bootstrap without disconnecting the account`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.storage.profiles[1] = "broken"
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertTrue(harness.repository.state.value.hasLoaded)
        assertTrue(harness.http.store.state.value.isAuthenticated)
        assertEquals(listOf("activities", "watched", "playback", "dropped"), harness.remote.calls)
    }

    @Test
    fun `overlapping refreshes join one sync including manual refreshes`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val release = CompletableDeferred<Unit>()
        harness.remote.beforeActivities = { release.await() }
        val first = async { harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        val second = async { harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, harness.remote.calls.count { it == "activities" })
    }

    @Test
    fun `automatic checks are limited to fifteen minutes while manual refresh can run sooner`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        harness.http.now += MdbListSyncRepository.AUTOMATIC_INTERVAL_MS - 1
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(1, harness.remote.calls.count { it == "activities" })
        harness.http.now++
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(3, harness.remote.calls.count { it == "activities" })
    }

    @Test
    fun `offline failures preserve cached history and avoid retry storms`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.remote.error = IOException("Offline")
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        repeat(5) { harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        assertEquals(1, harness.remote.calls.size)
        assertTrue(harness.repository.state.value.hasLoaded)
        assertEquals(listOf(mdbListTestMovie()), harness.repository.currentSnapshot()?.watched)
        assertEquals(MdbListSyncError.UNAVAILABLE, harness.repository.state.value.error)
        harness.http.now += MdbListSyncRepository.ERROR_RETRY_MS
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(2, harness.remote.calls.size)
    }

    @Test
    fun `quota reset suppresses both automatic and manual attempts`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val reset = harness.http.now + 60_000
        harness.remote.error = MdbListApiException(429, retryAtEpochMs = reset)
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(1, harness.remote.calls.size)
        harness.remote.error = null
        harness.http.now = reset
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertTrue(harness.repository.state.value.hasLoaded)
    }

    @Test
    fun `disk failure never publishes unpersisted history or advances its watermark`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.repository.ensureLoaded()
        val original = harness.storage.profiles[1]
        harness.storage.failSave = true
        harness.remote.activities = harness.remote.activities.copy(serverTime = "2026-09-06T01:00:00Z")
        harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED)
        assertEquals(original, harness.storage.profiles[1])
        assertEquals(MDBLIST_TEST_TIME, harness.repository.currentSnapshot()?.watermark)
        assertFalse(harness.repository.state.value.isLoading)
        assertEquals(MdbListSyncError.UNAVAILABLE, harness.repository.state.value.error)
    }

    @Test
    fun `late response after switching away and back cannot overwrite either profile`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        val release = CompletableDeferred<Unit>()
        harness.remote.beforeActivities = { release.await() }
        val refresh = async { harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        val original = harness.storage.profiles[1]
        harness.switch(2, 84)
        harness.switch(1)
        release.complete(Unit)
        expectMdbListFailure<CancellationException> { refresh.await() }
        harness.repository.ensureLoaded()
        assertEquals(original, harness.storage.profiles[1])
        assertNull(harness.storage.profiles[2])
        assertEquals(42L, harness.repository.currentSnapshot()?.accountId)
    }

    @Test
    fun `profile change while disk write is queued prevents the stale commit`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.storage.beforeSave = { harness.switch(2, 84) }
        expectMdbListFailure<CancellationException> { harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        assertNull(harness.storage.profiles[1])
        assertNull(harness.repository.currentSnapshot())
    }

    @Test
    fun `disconnect clears cached data and projections`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.repository.ensureLoaded()
        harness.http.store.clearAuth()
        runCurrent()
        assertNull(harness.repository.currentSnapshot())
        assertNull(harness.storage.profiles[1])
        assertTrue(harness.repository.currentProjection().watchedItems.isEmpty())
    }

    @Test
    fun `profile switch blocks writes even before auth observer has caught up`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        val scope = harness.repository.currentScope()
        harness.activeProfile.value = 2
        expectMdbListFailure<CancellationException> { harness.repository.mutate(scope) { error("Must not dispatch") } }
        assertTrue(harness.http.engine.requests.isEmpty())
    }

    @Test
    fun `canceled refresh clears its loading state`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.remote.beforeActivities = { CompletableDeferred<Unit>().await() }
        val job = launch { harness.repository.refresh(TrackingRefreshIntent.USER_INITIATED) }
        runCurrent()
        assertTrue(harness.repository.state.value.isLoading)
        job.cancel()
        job.join()
        assertFalse(harness.repository.state.value.isLoading)
    }

    @Test
    fun `invalidated bucket bypasses freshness and only refetches the affected data`() = runTest {
        val harness = MdbListSyncTestHarness(backgroundScope)
        harness.seed()
        harness.repository.invalidate(harness.repository.currentScope(), setOf(MdbListSyncBucket.PLAYBACK))
        harness.repository.refresh(TrackingRefreshIntent.AUTOMATIC)
        assertEquals(listOf("activities", "playback"), harness.remote.calls)
        assertTrue(harness.repository.currentSnapshot()?.invalidatedBuckets?.isEmpty() == true)
    }
}
