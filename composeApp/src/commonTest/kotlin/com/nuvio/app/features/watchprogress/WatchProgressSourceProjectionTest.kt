package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressSourceProjectionTest {
    @Test
    fun `Simkl source excludes every Nuvio progress entry`() {
        val nuvioEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 200L),
            entry(parentMetaId = "nuvio-only", updatedAt = 300L),
        )
        val providerEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 100L),
        )

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.SIMKL,
            nuvioEntries = nuvioEntries,
            providerEntries = providerEntries,
        )

        assertEquals(providerEntries, projected)
    }

    @Test
    fun `newer matching local progress wins over Trakt and retains its opaque key`() {
        val localEntry = entry(parentMetaId = "shared", updatedAt = 200L)
        val traktEntry = entry(parentMetaId = "shared", updatedAt = 100L)
            .copy(progressKey = "trakt:123")

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(localEntry),
            providerEntries = listOf(traktEntry),
        )

        assertEquals(listOf(localEntry.copy(progressKey = "trakt:123")), projected)
    }

    @Test
    fun `newer Trakt progress wins over matching local progress`() {
        val localEntry = entry(parentMetaId = "shared", updatedAt = 100L)
        val traktEntry = entry(parentMetaId = "shared", updatedAt = 200L)

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(localEntry),
            providerEntries = listOf(traktEntry),
        )

        assertEquals(listOf(traktEntry), projected)
    }

    @Test
    fun `equal timestamps keep Trakt progress`() {
        val localEntry = entry(parentMetaId = "shared", updatedAt = 100L)
            .copy(lastPositionMs = 20L)
        val traktEntry = entry(parentMetaId = "shared", updatedAt = 100L)

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(localEntry),
            providerEntries = listOf(traktEntry),
        )

        assertEquals(listOf(traktEntry), projected)
    }

    @Test
    fun `missing Trakt row does not resurrect local progress`() {
        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(entry(parentMetaId = "local-only")),
            providerEntries = emptyList(),
        )

        assertEquals(emptyList(), projected)
    }

    @Test
    fun `unmatched local progress is not injected into Trakt rows`() {
        val traktEntry = entry(parentMetaId = "shared", updatedAt = 100L)

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = listOf(entry(parentMetaId = "local-only", updatedAt = 200L)),
            providerEntries = listOf(traktEntry),
        )

        assertEquals(listOf(traktEntry), projected)
    }

    @Test
    fun `Nuvio source excludes every provider progress entry`() {
        val nuvioEntries = listOf(entry(parentMetaId = "nuvio"))
        val providerEntries = listOf(entry(parentMetaId = "provider"))

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.NUVIO_SYNC,
            nuvioEntries = nuvioEntries,
            providerEntries = providerEntries,
        )

        assertEquals(nuvioEntries, projected)
    }

    private fun entry(
        parentMetaId: String,
        updatedAt: Long = 1L,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "$parentMetaId:1:1",
        title = parentMetaId,
        seasonNumber = 1,
        episodeNumber = 1,
        lastPositionMs = 10L,
        durationMs = 100L,
        lastUpdatedEpochMs = updatedAt,
    )
}
