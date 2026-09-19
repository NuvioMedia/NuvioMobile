package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySortOption
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogStateTest {
    @Test
    fun `open catalog updates after removal including the last item`() = runBlocking {
        val first = item("first")
        val second = item("second")
        val library = MutableStateFlow(state(listOf(first, second)))
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(target()).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("first", "second"), emissions.last().items.map { it.id })

            library.value = state(listOf(second))
            yield()
            assertEquals(listOf("second"), emissions.last().items.map { it.id })

            library.value = LibraryUiState(isLoaded = true)
            yield()
            assertTrue(emissions.last().items.isEmpty())
            assertFalse(emissions.last().isLoading)
            assertFalse(emissions.last().canLoadMore)
            assertEquals(3, emissions.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `live tracking list updates keep section identity and selected sort`() = runBlocking {
        val alpha = item("alpha")
        val zulu = item("zulu")
        val sectionKey = "trakt:watchlist"
        val otherSection = LibrarySection("trakt:collection", "Collection", listOf(zulu))
        val library = MutableStateFlow(
            state(listOf(alpha, zulu, alpha), sectionKey).copy(
                sourceMode = LibrarySourceMode.TRAKT,
            ).let { it.copy(sections = it.sections + otherSection) },
        )
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(
                target(sectionKey).copy(sortOption = LibrarySortOption.TITLE_DESC),
            ).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("zulu", "alpha"), emissions.last().items.map { it.id })

            library.value = library.value.copy(
                sections = listOf(
                    LibrarySection(sectionKey, "Watchlist", listOf(alpha, item("bravo"))),
                    otherSection,
                ),
            )
            yield()
            assertEquals(listOf("bravo", "alpha"), emissions.last().items.map { it.id })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `mdblist catalog follows the selected list ranks during live updates`() = runBlocking {
        val sectionKey = "mdblist:list:1"
        val otherSectionKey = "mdblist:list:2"
        val alpha = item("alpha").copy(listRanks = mapOf(sectionKey to 2, otherSectionKey to 1))
        val zulu = item("zulu").copy(listRanks = mapOf(sectionKey to 1, otherSectionKey to 2))
        val library = MutableStateFlow(
            state(listOf(alpha, zulu), sectionKey).copy(sourceMode = LibrarySourceMode.MDBLIST),
        )
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(
                target(sectionKey).copy(sortOption = LibrarySortOption.DEFAULT),
            ).collect { emissions.add(it) }
        }
        try {
            assertEquals(listOf("zulu", "alpha"), emissions.last().items.map { it.id })

            library.value = state(
                listOf(
                    alpha.copy(listRanks = alpha.listRanks + (sectionKey to 1)),
                    zulu.copy(listRanks = zulu.listRanks + (sectionKey to 2)),
                ),
                sectionKey,
            ).copy(sourceMode = LibrarySourceMode.MDBLIST)
            yield()

            assertEquals(listOf("alpha", "zulu"), emissions.last().items.map { it.id })
            assertEquals(2, emissions.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `catalog follows library loading and recovery without reopening`() = runBlocking {
        val library = MutableStateFlow(LibraryUiState(isLoading = true))
        val emissions = mutableListOf<CatalogUiState>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            library.libraryCatalogStates(target()).collect { emissions.add(it) }
        }
        try {
            assertTrue(emissions.last().isLoading)

            library.value = LibraryUiState(errorMessage = "Unable to load library")
            yield()
            assertFalse(emissions.last().isLoading)
            assertEquals("Unable to load library", emissions.last().errorMessage)

            library.value = state(listOf(item("recovered")))
            yield()
            assertEquals(listOf("recovered"), emissions.last().items.map { it.id })
            assertNull(emissions.last().errorMessage)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun target(sectionType: String = "movie") = CatalogTarget.Library(
        contentType = "movie",
        sectionType = sectionType,
        sortOption = LibrarySortOption.TITLE_ASC,
    )

    private fun item(id: String) = LibraryItem(
        id = id,
        type = "movie",
        name = id,
        savedAtEpochMs = 1L,
    )

    private fun state(items: List<LibraryItem>, sectionType: String = "movie") = LibraryUiState(
        items = items,
        sections = listOf(LibrarySection(sectionType, "Library", items)),
        isLoaded = true,
    )
}
