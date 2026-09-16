package com.nuvio.app.features.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimklAdapterProjectionCacheTest {
    @Test
    fun `reopening library and changing request status reuse the snapshot projection`() {
        var projections = 0
        val cache = SimklSnapshotProjectionCache { snapshot ->
            projections++
            snapshot.toSimklLibraryProjection()
        }
        val state = SimklSyncUiState(snapshot = snapshot("tt0000001"), hasLoaded = true)
        val initial = cache.get(state)

        repeat(20) { assertSame(initial, cache.get(state)) }
        assertSame(initial, cache.get(state.copy(isLoading = true)))
        assertSame(initial, cache.get(state.copy(errorMessage = "offline")))
        assertEquals(listOf("tt0000001"), initial.items.map { it.id })
        assertEquals(1, projections)
    }

    @Test
    fun `playback updates replace progress while request status keeps existing entries`() {
        var projections = 0
        val cache = SimklSnapshotProjectionCache { snapshot ->
            projections++
            snapshot.toSimklProgressEntries()
        }
        val state = SimklSyncUiState(snapshot = snapshot("tt0000001"), hasLoaded = true)
        val initial = cache.get(state)
        assertSame(initial, cache.get(state.copy(isLoading = true)))
        val updated = cache.get(
            state.copy(
                snapshot = state.snapshot.copy(
                    playback = state.snapshot.playback.map { it.copy(progress = 75.0) },
                ),
            ),
        )

        assertNotSame(initial, updated)
        assertEquals(25f, initial.single().progressPercent)
        assertEquals(75f, updated.single().progressPercent)
        assertEquals(2, projections)
    }

    @Test
    fun `projection invalidation recomputes an unchanged snapshot`() {
        var projections = 0
        val cache = SimklSnapshotProjectionCache { snapshot ->
            projections++
            snapshot.toSimklLibraryProjection()
        }
        val state = SimklSyncUiState(snapshot = snapshot("tt0000001"), hasLoaded = true)
        val initial = cache.get(state)
        val invalidated = cache.get(state.copy(projectionVersion = 1L))

        assertNotSame(initial, invalidated)
        assertEquals(initial, invalidated)
        assertSame(invalidated, cache.get(state.copy(projectionVersion = 1L, isLoading = true)))
        assertEquals(2, projections)
    }

    @Test
    fun `reset and profile replacement discard the previous library and progress`() {
        val library = SimklSnapshotProjectionCache(SimklSyncSnapshot::toSimklLibraryProjection)
        val progress = SimklSnapshotProjectionCache(SimklSyncSnapshot::toSimklProgressEntries)
        val firstProfile = SimklSyncUiState(snapshot = snapshot("tt0000001"), hasLoaded = true)
        library.get(firstProfile)
        progress.get(firstProfile)

        val reset = SimklSyncUiState()
        assertTrue(library.get(reset).items.isEmpty())
        assertTrue(progress.get(reset).isEmpty())

        val secondProfile = SimklSyncUiState(snapshot = snapshot("tt0000002"), hasLoaded = true)
        assertEquals(listOf("tt0000002"), library.get(secondProfile).items.map { it.id })
        assertEquals(listOf("tt0000002"), progress.get(secondProfile).map { it.parentMetaId })
    }

    private fun snapshot(contentId: String): SimklSyncSnapshot {
        val media = SimklMedia(
            title = contentId,
            runtime = 100,
            ids = buildJsonObject { put("imdb", contentId) },
        )
        return SimklSyncSnapshot(
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.MOVIES,
                    status = SimklListStatus.PLAN_TO_WATCH,
                    movie = media,
                ),
            ),
            playback = listOf(SimklPlaybackSession(id = 1L, progress = 25.0, movie = media)),
        )
    }
}
