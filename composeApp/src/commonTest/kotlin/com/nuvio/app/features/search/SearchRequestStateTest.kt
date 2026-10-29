package com.nuvio.app.features.search

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRequestStateTest {
    @Test
    fun `matching request reuses completed state`() {
        assertTrue(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "query:addons:settings",
                cachedRequestKey = "query:addons:settings",
            ),
        )
    }

    @Test
    fun `changed or forced request reloads state`() {
        assertFalse(
            canReuseRequestState(
                forceRefresh = false,
                requestKey = "new-query",
                cachedRequestKey = "old-query",
            ),
        )
        assertFalse(
            canReuseRequestState(
                forceRefresh = true,
                requestKey = "same-query",
                cachedRequestKey = "same-query",
            ),
        )
    }

    @Test
    fun `preferred discover catalog is restored ahead of current fallback`() {
        val fallback = discoverCatalog(key = "fallback", type = "movie")
        val preferred = discoverCatalog(key = "preferred", type = "series")

        val selected = resolveDiscoverCatalog(
            sources = listOf(fallback, preferred),
            preferredCatalogKey = preferred.key,
            currentCatalogKey = fallback.key,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `current discover catalog remains when preference is unavailable`() {
        val current = discoverCatalog(key = "current", type = "movie")

        val selected = resolveDiscoverCatalog(
            sources = listOf(discoverCatalog(key = "first", type = "movie"), current),
            preferredCatalogKey = "unavailable",
            currentCatalogKey = current.key,
        )

        assertEquals(current, selected)
    }

    @Test
    fun `unresolved addon stays loading before surfacing manifest failure`() {
        val pendingAddon = ManagedAddon(
            manifestUrl = "https://pending.example/manifest.json",
            isRefreshing = true,
        )
        val failedAddon = pendingAddon.copy(
            isRefreshing = false,
            errorMessage = "Timed out",
        )

        try {
            SearchRepository.reset()
            SearchRepository.search(query = "movie", addons = listOf(pendingAddon))
            SearchRepository.refreshDiscover(addons = listOf(pendingAddon))

            assertTrue(SearchRepository.uiState.value.isLoading)
            assertEquals(null, SearchRepository.uiState.value.emptyStateReason)
            assertTrue(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(null, SearchRepository.discoverUiState.value.emptyStateReason)

            SearchRepository.search(query = "movie", addons = listOf(failedAddon))
            SearchRepository.refreshDiscover(addons = listOf(failedAddon))

            assertFalse(SearchRepository.uiState.value.isLoading)
            assertEquals(SearchEmptyStateReason.RequestFailed, SearchRepository.uiState.value.emptyStateReason)
            assertEquals("Timed out", SearchRepository.uiState.value.errorMessage)
            assertFalse(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(
                DiscoverEmptyStateReason.RequestFailed,
                SearchRepository.discoverUiState.value.emptyStateReason,
            )
            assertEquals("Timed out", SearchRepository.discoverUiState.value.errorMessage)
        } finally {
            SearchRepository.reset()
        }
    }

    @Test
    fun `pending addon settles to catalog capability empty states`() {
        val loadedAddon = ManagedAddon(
            manifestUrl = "https://loaded.example/manifest.json",
            manifest = AddonManifest(
                id = "loaded",
                name = "Loaded",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "meta", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = emptyList(),
                transportUrl = "https://loaded.example/manifest.json",
            ),
        )
        val pendingAddon = ManagedAddon(
            manifestUrl = "https://pending.example/manifest.json",
            isRefreshing = true,
        )
        val failedAddon = pendingAddon.copy(
            isRefreshing = false,
            errorMessage = "Timed out",
        )

        try {
            SearchRepository.reset()
            SearchRepository.search(query = "movie", addons = listOf(loadedAddon, pendingAddon))
            SearchRepository.refreshDiscover(addons = listOf(loadedAddon, pendingAddon))

            assertTrue(SearchRepository.uiState.value.isLoading)
            assertTrue(SearchRepository.discoverUiState.value.isLoading)

            SearchRepository.search(query = "movie", addons = listOf(loadedAddon, failedAddon))
            SearchRepository.refreshDiscover(addons = listOf(loadedAddon, failedAddon))

            assertFalse(SearchRepository.uiState.value.isLoading)
            assertEquals(SearchEmptyStateReason.NoSearchCatalogs, SearchRepository.uiState.value.emptyStateReason)
            assertFalse(SearchRepository.discoverUiState.value.isLoading)
            assertEquals(
                DiscoverEmptyStateReason.NoDiscoverCatalogs,
                SearchRepository.discoverUiState.value.emptyStateReason,
            )
        } finally {
            SearchRepository.reset()
        }
    }

    private fun discoverCatalog(key: String, type: String): DiscoverCatalogOption =
        DiscoverCatalogOption(
            key = key,
            addonName = "Addon",
            manifestUrl = "https://example.com/manifest.json",
            type = type,
            catalogId = key,
            catalogName = key,
        )
}
