package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import com.nuvio.app.features.library.watchlistSectionType
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeWatchlistRowTest {
    @Test
    fun `trakt uses the watchlist section in rank order`() {
        val selection = LibraryUiState(
            sourceMode = LibrarySourceMode.TRAKT,
            sections = listOf(
                LibrarySection("trakt:list:1", "Favourites", listOf(item("other"))),
                LibrarySection(
                    "trakt:watchlist",
                    "Watchlist",
                    listOf(item("second").copy(traktRank = 2), item("first").copy(traktRank = 1)),
                ),
            ),
            watchlistSectionType = "trakt:watchlist",
        ).homeWatchlistSelection()

        assertEquals("trakt:watchlist", selection?.sectionType)
        assertEquals(listOf("first", "second"), selection?.items?.map { it.id })
    }

    @Test
    fun `simkl and mdblist follow the section marked as watchlist`() {
        listOf(
            LibrarySourceMode.SIMKL to "simkl:status:plantowatch",
            LibrarySourceMode.MDBLIST to "mdblist:watchlist",
        ).forEach { (sourceMode, sectionType) ->
            val selection = LibraryUiState(
                sourceMode = sourceMode,
                sections = listOf(
                    LibrarySection("other", "Other", listOf(item("other"))),
                    LibrarySection(sectionType, "Watchlist", listOf(item("wanted"))),
                ),
                watchlistSectionType = sectionType,
            ).homeWatchlistSelection()

            assertEquals(listOf("wanted"), selection?.items?.map { it.id })
        }
    }

    @Test
    fun `local uses all saved items newest first`() {
        val older = item("older").copy(savedAtEpochMs = 1L)
        val newer = item("newer").copy(type = "series", savedAtEpochMs = 2L)
        val selection = LibraryUiState(
            sourceMode = LibrarySourceMode.LOCAL,
            items = listOf(older, newer),
            sections = listOf(
                LibrarySection("movie", "Movie", listOf(older)),
                LibrarySection("series", "Series", listOf(newer)),
            ),
        ).homeWatchlistSelection()

        assertEquals(listOf("newer", "older"), selection?.items?.map { it.id })
    }

    @Test
    fun `remote source without a watchlist section shows no row`() {
        val otherSections = listOf(LibrarySection("trakt:list:1", "Favourites", listOf(item("other"))))

        assertNull(
            LibraryUiState(
                sourceMode = LibrarySourceMode.TRAKT,
                items = listOf(item("other")),
                sections = otherSections,
            ).homeWatchlistSelection(),
        )
        assertNull(
            LibraryUiState(
                sourceMode = LibrarySourceMode.TRAKT,
                items = listOf(item("other")),
                sections = otherSections,
                watchlistSectionType = "trakt:watchlist",
            ).homeWatchlistSelection(),
        )
    }

    @Test
    fun `empty library shows no row`() {
        assertNull(LibraryUiState().homeWatchlistSelection())
        assertNull(LibraryUiState().homeWatchlistSection(title = "Watchlist", posterPattern = ""))
    }

    @Test
    fun `section removes duplicates and opens the selected library list`() {
        val section = LibraryUiState(
            sourceMode = LibrarySourceMode.TRAKT,
            sections = listOf(
                LibrarySection("trakt:watchlist", "Watchlist", listOf(item("only"), item("only"))),
            ),
            watchlistSectionType = "trakt:watchlist",
        ).homeWatchlistSection(title = "Watchlist", posterPattern = "")

        assertEquals("Watchlist", section?.title)
        assertEquals(listOf("only"), section?.items?.map { it.id })
        assertEquals("trakt:watchlist", (section?.target as CatalogTarget.Library).sectionType)
    }

    @Test
    fun `watchlist section type comes from the tab kind`() {
        val tabs = listOf(
            tab("simkl:status:watching", TrackingLibraryTabKind.STATUS),
            tab("simkl:status:plantowatch", TrackingLibraryTabKind.WATCHLIST),
        )

        assertEquals("simkl:status:plantowatch", watchlistSectionType(tabs))
        assertNull(watchlistSectionType(tabs.take(1)))
    }

    private fun item(id: String) = LibraryItem(
        id = id,
        type = "movie",
        name = id,
        savedAtEpochMs = 1L,
    )

    private fun tab(key: String, kind: TrackingLibraryTabKind) = TrackingLibraryTab(
        key = key,
        title = key,
        providerId = null,
        kind = kind,
    )
}
