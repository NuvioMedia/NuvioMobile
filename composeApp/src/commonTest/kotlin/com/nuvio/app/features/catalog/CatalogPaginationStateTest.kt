package com.nuvio.app.features.catalog

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogPaginationStateTest {
    @Test
    fun `CloudStream page cursor maps zero based skip to one based page numbers`() {
        assertEquals(1, cloudStreamPageNumber(0))
        assertEquals(2, cloudStreamPageNumber(1))
        assertEquals(3, cloudStreamPageNumber(2))
    }

    @Test
    fun `CloudStream exposes another page after non empty results and stops on empty page`() {
        assertEquals(1, cloudStreamNextPageSkip(0, 8))
        assertEquals(2, cloudStreamNextPageSkip(1, 12))
        assertNull(cloudStreamNextPageSkip(2, 0))
    }

    @Test
    fun `pagination advances by returned raw item count`() {
        val state = nextCatalogPaginationState(
            supportsPagination = true,
            requestedSkip = 11,
            page = page(rawItemCount = 17, nextSkip = 28),
            loadedNewItems = true,
            consecutiveDuplicatePages = 0,
        )

        assertEquals(28, state.nextSkip)
        assertEquals(0, state.consecutiveDuplicatePages)
    }

    @Test
    fun `pagination advances through duplicate page with returned next skip`() {
        val state = nextCatalogPaginationState(
            supportsPagination = true,
            requestedSkip = 45,
            page = page(rawItemCount = 18, nextSkip = 63),
            loadedNewItems = false,
            consecutiveDuplicatePages = 0,
        )

        assertEquals(63, state.nextSkip)
        assertEquals(1, state.consecutiveDuplicatePages)
    }

    @Test
    fun `pagination stops after duplicate page limit`() {
        val state = nextCatalogPaginationState(
            supportsPagination = true,
            requestedSkip = 81,
            page = page(rawItemCount = 18, nextSkip = 99),
            loadedNewItems = false,
            consecutiveDuplicatePages = 2,
        )

        assertNull(state.nextSkip)
        assertEquals(3, state.consecutiveDuplicatePages)
    }

    @Test
    fun `merged sources advance their pagination cursors independently`() {
        val movies = nextCatalogPaginationState(
            supportsPagination = true,
            requestedSkip = 0,
            page = page(rawItemCount = 100, nextSkip = 100),
            loadedNewItems = true,
            consecutiveDuplicatePages = 0,
        )
        val series = nextCatalogPaginationState(
            supportsPagination = true,
            requestedSkip = 0,
            page = page(rawItemCount = 37, nextSkip = 37),
            loadedNewItems = true,
            consecutiveDuplicatePages = 0,
        )

        assertEquals(100, movies.nextSkip)
        assertEquals(37, series.nextSkip)
    }

    @Test
    fun `pagination support matches skip extra case insensitively`() {
        val catalog = AddonCatalog(
            type = "movie",
            id = "popular",
            name = "Popular",
            extra = listOf(AddonExtraProperty(name = "Skip")),
        )

        assertTrue(catalog.supportsPagination())
    }

    private fun page(
        rawItemCount: Int,
        nextSkip: Int?,
    ): CatalogPage =
        CatalogPage(
            items = listOf(
                MetaPreview(
                    id = "tt$rawItemCount",
                    type = "movie",
                    name = "Movie $rawItemCount",
                ),
            ),
            rawItemCount = rawItemCount,
            nextSkip = nextSkip,
        )
}
