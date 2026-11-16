package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachedSourceRefreshCandidateTest {
    private val failedUrl = "https://cdn.example.com/dld/old?token=expired"

    @Test
    fun unresolvedDebridStreamOfTheSameAddonIsACandidate() {
        val debrid = StreamItem(name = "4K", infoHash = "abc", addonName = "Torrents", addonId = "addon:torrents")

        val candidate = find(listOf(debrid), canResolveDebrid = { it === debrid })

        assertEquals(debrid, candidate)
    }

    @Test
    fun streamWithoutUrlThatCannotBeResolvedIsSkipped() {
        val torrent = StreamItem(name = "4K", infoHash = "abc", addonName = "Torrents", addonId = "addon:torrents")

        assertNull(find(listOf(torrent), canResolveDebrid = { false }))
    }

    @Test
    fun preparedDebridStreamWithTheFailedUrlIsStillACandidate() {
        val prepared = StreamItem(
            name = "4K",
            url = failedUrl,
            addonName = "Torrents",
            addonId = "addon:torrents",
            debridResolved = true,
        )

        assertEquals(prepared, find(listOf(prepared), canResolveDebrid = { false }))
    }

    @Test
    fun sameTorrentWinsOverAnotherStreamWithTheSameName() {
        val other = StreamItem(name = "4K", infoHash = "other", addonName = "Torrents", addonId = "addon:torrents")
        val same = StreamItem(
            name = "4K HDR",
            infoHash = "abc",
            addonName = "Torrents",
            addonId = "addon:torrents",
            behaviorHints = StreamBehaviorHints(filename = "Movie.mkv"),
        )

        val candidate = find(
            listOf(other, same),
            canResolveDebrid = { true },
            expectedInfoHash = "abc",
            expectedFilename = "Movie.mkv",
        )

        assertEquals(same, candidate)
    }

    @Test
    fun otherAddonIsNeverACandidate() {
        val other = StreamItem(name = "4K", url = "https://other.example/new.mkv", addonName = "Other", addonId = "addon:other")

        assertNull(find(listOf(other), canResolveDebrid = { true }))
    }

    private fun find(
        streams: List<StreamItem>,
        canResolveDebrid: (StreamItem) -> Boolean,
        expectedInfoHash: String? = null,
        expectedFilename: String? = null,
    ) = findCredentialRefreshCandidate(
        streams = streams,
        failedUrl = failedUrl,
        expectedProviderAddonId = "addon:torrents",
        expectedProviderName = "Torrents",
        expectedStreamTitle = "4K",
        expectedBingeGroup = null,
        expectedInfoHash = expectedInfoHash,
        expectedFilename = expectedFilename,
        canResolveDebrid = canResolveDebrid,
    )
}
