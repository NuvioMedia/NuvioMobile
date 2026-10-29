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
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.DEFAULT, LibrarySourceMode.MDBLIST),
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
    fun `MDBList provider order follows the saved list order rather than added dates`() {
        val listKey = "mdblist:list:1"
        val items = listOf(
            item("alpha", name = "The Alpha", savedAt = 2L, releaseInfo = "2024").copy(listRanks = mapOf(listKey to 2)),
            item("zulu", name = "Zulu", savedAt = 3L, releaseInfo = "1999").copy(listRanks = mapOf(listKey to 0)),
            item("bravo", name = "Bravo", savedAt = 1L, releaseInfo = "2010").copy(listRanks = mapOf(listKey to 1)),
        )
        val expected = mapOf(
            LibrarySortOption.DEFAULT to listOf("zulu", "bravo", "alpha"),
            LibrarySortOption.ADDED_DESC to listOf("zulu", "alpha", "bravo"),
            LibrarySortOption.ADDED_ASC to listOf("bravo", "alpha", "zulu"),
            LibrarySortOption.RELEASED_DESC to listOf("alpha", "bravo", "zulu"),
            LibrarySortOption.RELEASED_ASC to listOf("zulu", "bravo", "alpha"),
            LibrarySortOption.TITLE_ASC to listOf("alpha", "bravo", "zulu"),
            LibrarySortOption.TITLE_DESC to listOf("zulu", "bravo", "alpha"),
        )
        assertEquals(LibrarySortOption.entries.toSet(), expected.keys)
        for ((option, ids) in expected) {
            assertEquals(ids, sortLibraryItems(items, option, LibrarySourceMode.MDBLIST, listKey).map { it.id }, option.name)
        }
    }

    @Test
    fun `release year sorting keeps items without a year last in both directions`() {
        val items = listOf(
            item("unknown"),
            item("old", releaseInfo = "1994"),
            item("range", releaseInfo = "2008-2013"),
        )
        assertEquals(
            listOf("range", "old", "unknown"),
            sortLibraryItems(items, LibrarySortOption.RELEASED_DESC, LibrarySourceMode.MDBLIST).map { it.id },
        )
        assertEquals(
            listOf("old", "range", "unknown"),
            sortLibraryItems(items, LibrarySortOption.RELEASED_ASC, LibrarySourceMode.MDBLIST).map { it.id },
        )
    }

    @Test
    fun `release sorts are offered only for MDBList and fall back elsewhere`() {
        val released = setOf(LibrarySortOption.RELEASED_DESC, LibrarySortOption.RELEASED_ASC)
        assertEquals(released, availableLibrarySortOptions(LibrarySourceMode.MDBLIST).toSet() intersect released)
        for (source in listOf(LibrarySourceMode.LOCAL, LibrarySourceMode.TRAKT, LibrarySourceMode.SIMKL)) {
            assertEquals(emptySet(), availableLibrarySortOptions(source).toSet() intersect released)
        }
        assertEquals(
            LibrarySortOption.RELEASED_ASC,
            effectiveLibrarySortOption(LibrarySortOption.RELEASED_ASC, LibrarySourceMode.MDBLIST),
        )
        assertEquals(
            LibrarySortOption.DEFAULT,
            effectiveLibrarySortOption(LibrarySortOption.RELEASED_DESC, LibrarySourceMode.TRAKT),
        )
        assertEquals(
            LibrarySortOption.ADDED_DESC,
            effectiveLibrarySortOption(LibrarySortOption.RELEASED_DESC, LibrarySourceMode.LOCAL),
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
    fun `title sorting ignores leading English articles`() {
        val input = listOf(
            item("batman", name = "The Batman"),
            item("arrival", name = "Arrival"),
            item("quiet", name = "A Quiet Place"),
        )

        assertEquals(
            listOf("arrival", "batman", "quiet"),
            sortLibraryItems(input, LibrarySortOption.TITLE_ASC, LibrarySourceMode.LOCAL).map { it.id },
        )
        assertEquals(
            listOf("quiet", "batman", "arrival"),
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
        releaseInfo: String? = null,
    ): LibraryItem =
        LibraryItem(
            id = id,
            type = type,
            name = name,
            releaseInfo = releaseInfo,
            savedAtEpochMs = savedAt,
            traktRank = traktRank,
        )
}
