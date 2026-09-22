package com.nuvio.app.features.watchprogress

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nuvio.app.features.tracking.WatchProgressSource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContinueWatchingEnrichmentStorageTest {
    private lateinit var preferences: RecordingPreferences

    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("nuvio_cw_enrichment", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        preferences = RecordingPreferences(stored)
        ContinueWatchingEnrichmentStorage.initialize(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        })
        ContinueWatchingEnrichmentCache.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        ContinueWatchingEnrichmentCache.clearLocalState()
    }

    @Test
    fun loadedSnapshotsReuseDecodedListsWithoutStorageWork() {
        assertTrue(save(item("cached")))
        ContinueWatchingEnrichmentCache.clearLocalState()
        val first = ContinueWatchingEnrichmentCache.getSnapshots(1, WatchProgressSource.SIMKL)
        val readCount = preferences.readCount
        val editCount = preferences.editCount

        val second = ContinueWatchingEnrichmentCache.getSnapshots(1, WatchProgressSource.SIMKL)

        assertEquals("cached", first.first.single().name)
        assertSame(first.first, second.first)
        assertSame(first.second, second.second)
        assertSame(first.first, ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL))
        assertSame(first.second, ContinueWatchingEnrichmentCache.getInProgressSnapshot(1, WatchProgressSource.SIMKL))
        assertEquals(readCount, preferences.readCount)
        assertEquals(editCount, preferences.editCount)
    }

    @Test
    fun absentAndMalformedSnapshotsAreLoadedOnlyOnce() {
        val malformedKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(2, WatchProgressSource.SIMKL)
        preferences.edit().putString(malformedKey, "invalid json").commit()
        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).isEmpty())
        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(2, WatchProgressSource.SIMKL).isEmpty())
        val readCount = preferences.readCount
        val editCount = preferences.editCount

        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).isEmpty())
        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(2, WatchProgressSource.SIMKL).isEmpty())

        assertEquals(readCount, preferences.readCount)
        assertEquals(editCount, preferences.editCount)
        assertFalse(preferences.contains(malformedKey))
    }

    @Test
    fun switchingProfilesAndSourcesKeepsTheirSnapshotsSeparate() {
        assertTrue(save(item("simkl")))
        assertTrue(save(item("trakt"), source = WatchProgressSource.TRAKT))
        assertTrue(save(item("other profile"), profileId = 2))

        assertEquals("trakt", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.TRAKT).single().name)
        assertEquals("simkl", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).single().name)
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value
        val readCount = preferences.readCount
        ContinueWatchingEnrichmentCache.onProfileChanged()

        assertFalse(save(item("stale"), generation = staleGeneration))
        assertEquals("other profile", ContinueWatchingEnrichmentCache.getNextUpSnapshot(2, WatchProgressSource.SIMKL).single().name)
        assertEquals(readCount + 1, preferences.readCount)
        assertEquals("simkl", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).single().name)
    }

    @Test
    fun invalidationAndProfileClearingRemoveOnlyTheirSnapshots() {
        assertTrue(save(item("simkl")))
        assertTrue(save(item("trakt"), source = WatchProgressSource.TRAKT))
        assertTrue(save(item("other profile"), profileId = 2))
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.invalidate(1, WatchProgressSource.SIMKL)

        assertFalse(save(item("stale"), generation = staleGeneration))
        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).isEmpty())
        assertEquals("trakt", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.TRAKT).single().name)

        ContinueWatchingEnrichmentCache.clearAll(1)

        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.TRAKT).isEmpty())
        assertEquals("other profile", ContinueWatchingEnrichmentCache.getNextUpSnapshot(2, WatchProgressSource.SIMKL).single().name)
    }

    @Test
    fun accountClearingDropsCachedItemsAndRejectsOldResolverWrites() {
        assertTrue(save(item("old account")))
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.clearLocalState()
        preferences.edit().clear().commit()

        assertFalse(save(item("stale"), generation = staleGeneration))
        assertTrue(ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).isEmpty())
        assertTrue(save(item("new account")))
        assertEquals("new account", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).single().name)
    }

    @Test
    fun equalSnapshotsSkipWritesAndForceStillPersists() {
        val nextUp = item("cached")
        assertTrue(save(nextUp))
        val editCount = preferences.editCount

        assertTrue(save(nextUp.copy()))

        assertEquals(editCount, preferences.editCount)
        assertTrue(save(nextUp, force = true))
        assertTrue(preferences.editCount > editCount)
    }

    @Test
    fun differentItemsWithTheSameHashArePersisted() {
        val first = item("Aa")
        val second = item("BB")
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(save(first))
        assertTrue(save(second))

        ContinueWatchingEnrichmentCache.clearLocalState()

        assertEquals("BB", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).single().name)
    }

    @Test
    fun savedSnapshotsDoNotRetainMutableInputLists() {
        val nextUp = mutableListOf(item("saved"))
        assertTrue(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = 1,
                source = WatchProgressSource.SIMKL,
                generation = ContinueWatchingEnrichmentCache.generation.value,
                nextUp = nextUp,
                inProgress = emptyList(),
            ),
        )

        nextUp.clear()

        assertEquals("saved", ContinueWatchingEnrichmentCache.getNextUpSnapshot(1, WatchProgressSource.SIMKL).single().name)
    }

    private fun save(
        nextUp: CachedNextUpItem,
        profileId: Int = 1,
        source: WatchProgressSource = WatchProgressSource.SIMKL,
        generation: Int = ContinueWatchingEnrichmentCache.generation.value,
        force: Boolean = false,
    ): Boolean = ContinueWatchingEnrichmentCache.saveSnapshots(
        profileId = profileId,
        source = source,
        generation = generation,
        nextUp = listOf(nextUp),
        inProgress = emptyList(),
        force = force,
    )

    private fun item(name: String) = CachedNextUpItem(
        contentId = "show",
        contentType = "series",
        name = name,
        videoId = "show:1:2",
        lastWatched = 1L,
        sortTimestamp = 2L,
    )

    private class RecordingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var readCount = 0
        var editCount = 0

        override fun getString(key: String, defValue: String?): String? {
            readCount += 1
            return delegate.getString(key, defValue)
        }

        override fun edit(): SharedPreferences.Editor {
            editCount += 1
            return delegate.edit()
        }
    }
}
