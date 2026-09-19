package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.sortLibrarySections
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.membershipTitle
import kotlin.test.Test
import kotlin.test.assertEquals

class MdbListMobileParityTest {
    @Test
    fun libraryRanksAreSpecificToEachList() {
        val first = LibraryItem("tt1", "movie", "First", listRanks = mapOf("a" to 1, "b" to 2), savedAtEpochMs = 0)
        val second = LibraryItem("tt2", "movie", "Second", listRanks = mapOf("a" to 2, "b" to 1), savedAtEpochMs = 0)
        val sections = listOf(LibrarySection("a", "A", listOf(first, second)), LibrarySection("b", "B", listOf(first, second)))
        val sorted = sortLibrarySections(sections, LibrarySortOption.DEFAULT, LibrarySourceMode.MDBLIST)
        assertEquals(listOf("tt1", "tt2"), sorted[0].items.map { it.id })
        assertEquals(listOf("tt2", "tt1"), sorted[1].items.map { it.id })
    }

    @Test
    fun sourcesRemainIndependentAndSameNamedDestinationsIdentifyProvider() {
        assertEquals(LibrarySourceMode.MDBLIST, effectiveLibrarySourceMode(LibrarySourceMode.MDBLIST) { it == TrackingProviderId.MDBLIST })
        assertEquals(WatchProgressSource.NUVIO_SYNC, effectiveWatchProgressSource(WatchProgressSource.MDBLIST) { false })
        assertEquals(WatchProgressSource.MDBLIST, effectiveWatchProgressSource(WatchProgressSource.MDBLIST) { it == TrackingProviderId.MDBLIST })
        val names = listOf(TrackingProviderId.TRAKT, TrackingProviderId.MDBLIST).map {
            TrackingLibraryTab(it.storageId, "Watchlist", it, TrackingLibraryTabKind.WATCHLIST).membershipTitle()
        }
        assertEquals(listOf("Trakt · Watchlist", "MDBList · Watchlist"), names)
    }

    @Test
    fun nextUpSkipsDroppedSeasonAndUsesSharedReleasePolicy() {
        val record = mdbListTestEpisode()
        val projection = MdbListProgressProjection(MdbListSyncSnapshot(42, watched = listOf(record),
            dropped = listOf(MdbListDroppedRecord(record.media.ids, 2))))
        val meta = MetaDetails(id = "tt1", type = "series", name = "Show", videos = listOf(
            MetaVideo("tt1:1:1", "First", season = 1, episode = 1, released = "2026-01-01"),
            MetaVideo("tt1:2:1", "Dropped", season = 2, episode = 1, released = "2026-02-01"),
            MetaVideo("tt1:3:1", "Next", season = 3, episode = 1, released = "2026-03-01"),
        ))
        val action = meta.seriesPrimaryAction(projection.nextUpSeeds, projection.watchedItems, "2026-09-06")
        assertEquals(3, action?.seasonNumber)
        assertEquals(1, action?.episodeNumber)
    }
}
