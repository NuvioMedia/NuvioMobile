package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDisplaySettingsTest {

    @Test
    fun `local default resolves to recently added while remote trackers preserve provider order`() {
        assertEquals(
            LibrarySortOption.ADDED_DESC,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.LOCAL),
        )
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.TRAKT),
        )
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.SIMKL),
        )

        val input = listOf(
            item("ranked-second", savedAt = 3L, traktRank = 2),
            item("unranked", savedAt = 4L),
            item("ranked-first", savedAt = 1L, traktRank = 1),
            item("ranked-first-newer", savedAt = 2L, traktRank = 1),
        )
        assertEquals(
            listOf("ranked-first-newer", "ranked-first", "ranked-second", "unranked"),
            sortLibraryItems(input, LibrarySortOption.DEFAULT, LibrarySourceMode.TRAKT).map { it.id },
        )
        assertEquals(
            listOf("unranked", "ranked-second", "ranked-first-newer", "ranked-first"),
            sortLibraryItems(input, LibrarySortOption.DEFAULT, LibrarySourceMode.LOCAL).map { it.id },
        )
    }

    @Test
    fun `added sorting works in both directions`() {
        val input = listOf(
            item("middle", savedAt = 2L),
            item("oldest", savedAt = 1L),
            item("newest", savedAt = 3L),
        )

        assertEquals(
            listOf("newest", "middle", "oldest"),
            sortLibraryItems(input, LibrarySortOption.ADDED_DESC, LibrarySourceMode.LOCAL).map { it.id },
        )
        assertEquals(
            listOf("oldest", "middle", "newest"),
            sortLibraryItems(input, LibrarySortOption.ADDED_ASC, LibrarySourceMode.TRAKT).map { it.id },
        )
    }

    @Test
    fun `title sorting preserves leading articles for literal alphabetical order`() {
        val input = listOf(
            item("batman", name = "The Batman"),
            item("arrival", name = "Arrival"),
            item("quiet", name = "A Quiet Place"),
        )

        assertEquals(
            listOf("quiet", "arrival", "batman"),
            sortLibraryItems(input, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL).map { it.id },
        )
        assertEquals(
            listOf("batman", "arrival", "quiet"),
            sortLibraryItems(input, LibrarySortOption.TITLE_DESC, LibrarySourceMode.LOCAL).map { it.id },
        )
    }

    @Test
    fun `horizontal sections sort independently without changing section order`() {
        val sections = listOf(
            LibrarySection(
                type = "movie",
                displayTitle = "Movies",
                items = listOf(item("z", name = "Zulu"), item("a", name = "Alpha")),
            ),
            LibrarySection(
                type = "series",
                displayTitle = "Series",
                items = listOf(item("y", type = "series", name = "Yellow"), item("b", type = "series", name = "Beta")),
            ),
        )

        val sorted = sortLibrarySections(sections, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL)

        assertEquals(listOf("movie", "series"), sorted.map { it.type })
        assertEquals(listOf("a", "z"), sorted[0].items.map { it.id })
        assertEquals(listOf("b", "y"), sorted[1].items.map { it.id })
    }

    @Test
    fun `vertical tracker projection selects one list then filters and sorts its items`() {
        val watchlist = LibrarySection(
            type = "watchlist",
            displayTitle = "Watchlist",
            items = listOf(
                item("z", name = "Zulu"),
                item("series", type = "series", name = "Series"),
                item("a", name = "Alpha"),
            ),
        )
        val personal = LibrarySection(
            type = "personal:1",
            displayTitle = "Favorites",
            items = listOf(item("favorite", name = "Favorite")),
        )

        val projection = buildLibraryVerticalProjection(
            sections = listOf(watchlist, personal),
            sourceMode = LibrarySourceMode.TRAKT,
            selectedSectionKey = "missing",
            selectedType = "movie",
            sortOption = LibrarySortOption.TITLE_ASC,
        )

        assertEquals("watchlist", projection.selectedSectionKey)
        assertEquals(listOf("movie", "series"), projection.availableTypes)
        assertEquals("movie", projection.selectedType)
        assertEquals(listOf("a", "z"), projection.entries.map { it.item.id })
        assertEquals(listOf("watchlist", "watchlist"), projection.entries.map { it.section.type })

        val simklProjection = buildLibraryVerticalProjection(
            sections = listOf(watchlist),
            sourceMode = LibrarySourceMode.SIMKL,
            selectedSectionKey = null,
            selectedType = null,
            sortOption = LibrarySortOption.DEFAULT,
        )
        assertEquals(listOf("watchlist"), simklProjection.availableSections.map { it.type })
        assertEquals("watchlist", simklProjection.selectedSectionKey)
    }

    @Test
    fun `display settings payload round trips and invalid values fall back safely`() {
        val state = LibraryDisplaySettingsUiState(
            layoutMode = LibraryLayoutMode.VERTICAL,
            sortOption = LibrarySortOption.TITLE_DESC,
        )

        assertEquals(state, decodeLibraryDisplaySettings(encodeLibraryDisplaySettings(state)))
        assertEquals(
            LibraryDisplaySettingsUiState(),
            decodeLibraryDisplaySettings("""{"layout_mode":"unknown","sort_option":"unknown"}"""),
        )
    }

    private fun item(
        id: String,
        type: String = "movie",
        name: String = id,
        savedAt: Long = 0L,
        traktRank: Int? = null,
    ): LibraryItem =
        LibraryItem(
            id = id,
            type = type,
            name = name,
            savedAtEpochMs = savedAt,
            traktRank = traktRank,
        )
}
