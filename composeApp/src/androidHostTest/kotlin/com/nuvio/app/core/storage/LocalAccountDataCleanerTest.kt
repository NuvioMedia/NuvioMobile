package com.nuvio.app.core.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalAccountDataCleanerTest {
    private lateinit var preferences: SharedPreferences

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("nuvio_cw_enrichment", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        ContinueWatchingEnrichmentStorage.initialize(context)
        PlatformLocalAccountDataCleaner.initialize(context)
        ContinueWatchingEnrichmentCache.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        ContinueWatchingEnrichmentCache.clearLocalState()
        preferences.edit().clear().commit()
    }

    @Test
    fun storageWipeClearsSnapshotsReloadedDuringAccountCleanup() {
        val initialGeneration = ContinueWatchingEnrichmentCache.generation.value
        assertTrue(save("old account", initialGeneration))
        ContinueWatchingEnrichmentCache.clearLocalState()
        val cleanupGeneration = ContinueWatchingEnrichmentCache.generation.value

        LocalAccountDataCleaner.wipePlatformStorage {
            assertEquals("old account", snapshots().single().name)
            PlatformLocalAccountDataCleaner.wipe()
        }

        assertTrue(snapshots().isEmpty())
        assertFalse(save("old resolver", initialGeneration))
        assertFalse(save("cleanup resolver", cleanupGeneration))
        assertTrue(save("new account", ContinueWatchingEnrichmentCache.generation.value))
        assertEquals("new account", snapshots().single().name)
    }

    @Test
    fun failedStorageWipeStillInvalidatesReloadedSnapshotsAndResolverGenerations() {
        assertTrue(save("old account", ContinueWatchingEnrichmentCache.generation.value))
        ContinueWatchingEnrichmentCache.clearLocalState()
        val cleanupGeneration = ContinueWatchingEnrichmentCache.generation.value
        val failure = IllegalStateException("Remaining storage cleanup failed")

        val thrown = assertFailsWith<IllegalStateException> {
            LocalAccountDataCleaner.wipePlatformStorage {
                assertEquals("old account", snapshots().single().name)
                PlatformLocalAccountDataCleaner.wipe()
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertTrue(snapshots().isEmpty())
        assertFalse(save("cleanup resolver", cleanupGeneration))
    }

    private fun snapshots(): List<CachedNextUpItem> =
        ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL)

    private fun save(name: String, generation: Int): Boolean = ContinueWatchingEnrichmentCache.saveSnapshots(
        profileId = 1,
        source = WatchProgressSource.SIMKL,
        generation = generation,
        nextUp = listOf(
            CachedNextUpItem(
                contentId = "show",
                contentType = "series",
                name = name,
                videoId = "show:1:2",
                lastWatched = 1L,
                sortTimestamp = 2L,
            ),
        ),
        inProgress = emptyList(),
    )
}
