package com.nuvio.app.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncManagerTest {

    @Test
    fun `source prerequisites finish before source dependent pulls`() = runBlocking {
        val events = mutableListOf<String>()
        var credentialsApplied = false

        runOrderedProfileSync(
            profileId = 7,
            pluginsEnabled = true,
            operations = ProfileSyncOperations(
                pullAddons = { events += "addons" },
                pullPlugins = { events += "plugins" },
                pullProfileSettings = {
                    assertTrue(credentialsApplied)
                    events += "settings:start"
                    yield()
                    events += "settings:end"
                },
                syncProviderCredentials = {
                    credentialsApplied = true
                    events += "credentials"
                },
                pullLibrary = {
                    assertTrue(credentialsApplied)
                    events += "library"
                },
                refreshActiveWatchSource = {
                    assertTrue(credentialsApplied)
                    events += "active-watch-source"
                },
                pullCollections = { events += "collections" },
                pullHomeCatalogSettings = { events += "home-settings" },
            ),
            onFailure = { _, error -> throw error },
        )

        assertTrue(events.indexOf("credentials") < events.indexOf("settings:start"))
        val lastPrerequisite = events.indexOf("settings:end")
        assertTrue(events.indexOf("addons") > lastPrerequisite)
        assertTrue(events.indexOf("plugins") > lastPrerequisite)
        assertTrue(events.indexOf("library") > lastPrerequisite)
        assertTrue(events.indexOf("active-watch-source") > lastPrerequisite)
        assertEquals(1, events.count { it == "active-watch-source" })
    }

    @Test
    fun `disabled plugins are skipped without changing sync ordering`() = runBlocking {
        val events = mutableListOf<String>()

        runOrderedProfileSync(
            profileId = 2,
            pluginsEnabled = false,
            operations = recordingOperations(events),
            onFailure = { _, error -> throw error },
        )

        assertTrue("plugins" !in events)
        assertTrue(events.indexOf("credentials") < events.indexOf("settings"))
        assertTrue(events.indexOf("settings") < events.indexOf("library"))
        assertTrue(events.indexOf("credentials") < events.indexOf("library"))
        assertTrue(events.indexOf("settings") < events.indexOf("active-watch-source"))
    }

    @Test
    fun `duplicate active request for one profile is coalesced`() = runBlocking {
        val gate = ProfileSyncRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var runCount = 0

        val first = gate.launch(this, profileId = 4) {
            runCount += 1
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        firstStarted.await()

        val duplicate = gate.launch(this, profileId = 4) {
            runCount += 1
        }

        assertEquals(ProfileSyncRequestResult.Started, first)
        assertEquals(ProfileSyncRequestResult.Coalesced, duplicate)
        assertEquals(1, runCount)

        releaseFirst.complete(Unit)
        yield()
        gate.cancel()
    }

    @Test
    fun `new profile replaces stale in flight request`() = runBlocking {
        val gate = ProfileSyncRequestGate()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()

        gate.launch(this, profileId = 1) {
            firstStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        firstStarted.await()

        val replacement = gate.launch(this, profileId = 2) {
            secondCompleted.complete(Unit)
        }

        assertEquals(ProfileSyncRequestResult.Replaced, replacement)
        firstCancelled.await()
        secondCompleted.await()
        gate.cancel()
    }

    @Test
    fun `full request replaces activity request for the same profile`() = runBlocking {
        val gate = ProfileSyncRequestGate()
        val activityStarted = CompletableDeferred<Unit>()
        val activityCancelled = CompletableDeferred<Unit>()
        val fullCompleted = CompletableDeferred<Unit>()

        gate.launch(this, profileId = 1, kind = ProfileSyncRequestKind.Activity) {
            activityStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                activityCancelled.complete(Unit)
            }
        }
        activityStarted.await()

        val replacement = gate.launch(this, profileId = 1, kind = ProfileSyncRequestKind.Full) {
            fullCompleted.complete(Unit)
        }

        assertEquals(ProfileSyncRequestResult.Replaced, replacement)
        activityCancelled.await()
        fullCompleted.await()
        gate.cancel()
    }

    @Test
    fun `activity sync runs only requested domains`() = runBlocking {
        val events = mutableListOf<String>()
        val operations = ProfileActivitySyncOperations(
            pullLibrary = { events += "library" },
            pullWatchActivity = { events += "watch" },
        )

        val libraryOnly = runActivityProfileSync(
            profileId = 1,
            pullLibrary = true,
            pullWatchActivity = false,
            operations = operations,
        )
        val watchOnly = runActivityProfileSync(
            profileId = 1,
            pullLibrary = false,
            pullWatchActivity = true,
            operations = operations,
        )

        assertTrue(libraryOnly.succeeded)
        assertTrue(watchOnly.succeeded)
        assertEquals(listOf("library", "watch"), events)
    }

    @Test
    fun `failed step is reported by ordered sync result`() = runBlocking {
        val result = runOrderedProfileSync(
            profileId = 3,
            pluginsEnabled = false,
            operations = recordingOperations(mutableListOf()).copy(
                refreshActiveWatchSource = { error("source refresh failed") },
            ),
        )

        assertFalse(result.succeeded)
        assertEquals(setOf(ProfileSyncStep.ActiveWatchSource), result.failedSteps)
    }

    @Test
    fun `failed profile sync does not advance foreground freshness`() {
        val previous = ProfilePullFreshness(
            profileId = 3,
            completedAtEpochMs = 1_000L,
        )
        val failed = previous.recordIfSuccessful(
            profileId = 3,
            completedAtEpochMs = 2_000L,
            result = ProfileSyncResult(setOf(ProfileSyncStep.ActiveWatchSource)),
        )
        val succeeded = previous.recordIfSuccessful(
            profileId = 3,
            completedAtEpochMs = 2_000L,
            result = ProfileSyncResult(emptySet()),
        )

        assertEquals(previous, failed)
        assertEquals(2_000L, succeeded.completedAtEpochMs)
        assertFalse(
            ProfilePullFreshness()
                .recordIfSuccessful(
                    profileId = 3,
                    completedAtEpochMs = 2_000L,
                    result = ProfileSyncResult(setOf(ProfileSyncStep.ActiveWatchSource)),
                )
                .isRecent(profileId = 3, nowEpochMs = 2_001L, minIntervalMs = 1_000L),
        )
        assertTrue(
            succeeded.isRecent(profileId = 3, nowEpochMs = 2_001L, minIntervalMs = 1_000L),
        )
    }

    private fun recordingOperations(events: MutableList<String>): ProfileSyncOperations =
        ProfileSyncOperations(
            pullAddons = { events += "addons" },
            pullPlugins = { events += "plugins" },
            pullProfileSettings = { events += "settings" },
            syncProviderCredentials = { events += "credentials" },
            pullLibrary = { events += "library" },
            refreshActiveWatchSource = { events += "active-watch-source" },
            pullCollections = { events += "collections" },
            pullHomeCatalogSettings = { events += "home-settings" },
        )
}
