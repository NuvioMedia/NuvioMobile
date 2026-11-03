package com.nuvio.app.features.home

import kotlin.test.Test
import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergedHomeRowsTest {
    @Test
    fun sourceTabsIsTheDefaultDisplayMode() {
        assertEquals(MergedHomeRowDisplayMode.SOURCE_TABS, MergedHomeRowDisplayMode.fromStorage(null))
        assertEquals(MergedHomeRowDisplayMode.SOURCE_TABS.storageValue, MergedHomeRowDefinition(
            id = "default",
            title = "Default",
            sourceKeys = listOf("source-a", "source-b"),
        ).displayMode)
    }

    @Test
    fun allMoviesSeriesDisplayModeRoundTrips() {
        assertEquals(
            MergedHomeRowDisplayMode.TYPE_TABS,
            MergedHomeRowDisplayMode.fromStorage(MergedHomeRowDisplayMode.TYPE_TABS.storageValue),
        )
    }

    @Test
    fun typeTabsConsolidateAllMoviesAndSeriesAcrossEveryProvider() {
        val sources = listOf(
            listOf(item("movie-a", "movie"), item("series-a", "series")),
            listOf(item("movie-b", "movie"), item("series-b", "tv")),
        )
        val all = mergeHomeRowItems(
            sourceItems = sources,
            deduplicate = true,
            blendMode = MergedHomeRowBlendMode.INTERLEAVE,
        )

        val tabs = buildMergedHomeTypeTabs(
            sourceItems = sources,
            allItems = all,
            deduplicate = true,
            blendMode = MergedHomeRowBlendMode.INTERLEAVE,
            sortMode = MergedHomeRowSortMode.OFF,
            hideTitlesWithoutPosters = false,
            hasMore = true,
        )

        assertEquals(listOf("all", "movies", "series"), tabs.map { it.key })
        assertEquals(listOf("movie-a", "movie-b", "series-a", "series-b"), tabs[0].items.map { it.id })
        assertEquals(listOf("movie-a", "movie-b"), tabs[1].items.map { it.id })
        assertEquals(listOf("series-a", "series-b"), tabs[2].items.map { it.id })
        assertTrue(tabs.all { it.hasMore })
    }

    @Test
    fun mergedSourceLabelsAreShortAndRemoveRedundantCatalogTypes() {
        assertEquals("Kisskh", cleanMergedSourceTabTitle("Kisskh (other) - Other"))
        assertEquals("Marvel", cleanMergedSourceTabTitle("Marvel (other) - Other"))
        assertEquals("MovieBox", cleanMergedSourceTabTitle("MovieBoxIN (other) - Other"))
        assertEquals("Movies4u", cleanMergedSourceTabTitle("Movies4u (other) - Other"))
        assertEquals("Prime Video", cleanMergedSourceTabTitle("Prime Video (other) - Other"))
        assertEquals("Revenge Action", cleanMergedSourceTabTitle("Revenge Action - Movies"))
        assertEquals("Romance Remake", cleanMergedSourceTabTitle("Romance Remake - Movies"))
        assertEquals("BanglaPlex 2026", cleanMergedSourceTabTitle("BanglaPlex · 2026 - Movies"))
        assertEquals("BanglaPlex Latest", cleanMergedSourceTabTitle("BanglaPlex · Latest - Movies"))
        assertEquals("Anime Mix", cleanMergedSourceTabTitle("Anime - Mixed"))
        assertEquals("Global Releases", cleanMergedSourceTabTitle("Latest Releases Global - Mixed"))
        assertEquals("Indian Releases", cleanMergedSourceTabTitle("New Indian Releases - Mixed"))
        assertEquals("AI Recommendations", cleanMergedSourceTabTitle("AI Recommendations (movie/aicat_list_125836)"))
        assertEquals("All", cleanMergedSourceTabTitle("All"))
        assertEquals("Movies", cleanMergedSourceTabTitle("Movies"))
        assertEquals("Series", cleanMergedSourceTabTitle("Series"))
    }

    @Test
    fun sourceTabsAndUnifiedRowsUseTheSavedContentFilter() {
        val moviesOnly = MergedHomeRowDefinition(
            id = "movies",
            title = "Movies",
            sourceKeys = listOf("a", "b"),
            displayMode = MergedHomeRowDisplayMode.SOURCE_TABS.storageValue,
            contentType = MergedHomeRowContentType.MOVIES.storageValue,
        )
        val seriesOnly = moviesOnly.copy(
            displayMode = MergedHomeRowDisplayMode.UNIFIED_CAROUSEL.storageValue,
            contentType = MergedHomeRowContentType.SERIES.storageValue,
        )

        assertEquals(MergedHomeRowContentType.MOVIES, moviesOnly.contentTypeForDisplay())
        assertEquals(MergedHomeRowContentType.SERIES, seriesOnly.contentTypeForDisplay())
    }

    @Test
    fun typeTabsAlwaysExposeAllThreeCategoriesInsteadOfApplyingARowFilter() {
        val row = MergedHomeRowDefinition(
            id = "types",
            title = "Types",
            sourceKeys = listOf("a", "b"),
            displayMode = MergedHomeRowDisplayMode.TYPE_TABS.storageValue,
            contentType = MergedHomeRowContentType.MOVIES.storageValue,
        )

        assertEquals(MergedHomeRowContentType.ALL, row.contentTypeForDisplay())
    }

    private fun item(id: String, type: String = "movie", name: String = id) =
        MetaPreview(id = id, type = type, name = name)

    @Test
    fun mergedRowsCanOpenEvenWhenTheirPreviewIsShorterThanTheShelfLimit() {
        val section = HomeCatalogSection(
            key = mergedHomeRowKey("test"),
            title = "Latest Indian",
            subtitle = "Merged row",
            addonName = "Merged row",
            target = CatalogTarget.MergedRow(rowId = "test"),
            items = listOf(item("one")),
            isMergedRow = true,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

    @Test
    fun mergeInterleavesSourcesRatherThanExhaustingFirstSource() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("a1"), item("a2"), item("a3")),
                listOf(item("b1"), item("b2")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), merged.map { it.id })
    }

    @Test
    fun mergeDeduplicatesByMediaTypeAndIdNotTitle() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("tt1", name = "Original title"), item("tt2", name = "Same title")),
                listOf(item("tt1", name = "Localized title"), item("tt3", name = "Same title")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("tt1", "tt2", "tt3"), merged.map { it.id })
    }

    @Test
    fun mergeKeepsSameIdAcrossDifferentMediaTypes() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("same-id", type = "movie")),
                listOf(item("same-id", type = "series")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("movie:same-id", "series:same-id"), merged.map { it.stableKey() })
    }

    @Test
    fun mergeDeduplicatesAcrossPreviouslyLoadedSourcePages() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("a1"), item("a2")),
                listOf(item("b1"), item("a1"), item("b2")),
            ),
            deduplicate = true,
            maxItems = Int.MAX_VALUE,
        )

        assertEquals(listOf("a1", "b1", "a2", "b2"), merged.map { it.id })
    }

    @Test
    fun mergeCanPreserveDuplicatesWhenDeduplicationIsDisabled() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("tt1")),
                listOf(item("tt1")),
            ),
            deduplicate = false,
        )

        assertEquals(listOf("tt1", "tt1"), merged.map { it.id })
    }

    @Test
    fun legacyRowsKeepInterleaveAndOffDefaults() {
        val row = MergedHomeRowDefinition(
            id = "legacy",
            title = "Legacy",
            sourceKeys = listOf("a", "b"),
        )
        assertEquals(MergedHomeRowBlendMode.INTERLEAVE, row.resolvedBlendMode())
        assertEquals(MergedHomeRowSortMode.OFF, row.resolvedSortMode())
        assertEquals(MergedHomeRowContentType.ALL, row.resolvedContentType())
    }

    @Test
    fun smartBlendPrioritisesTitlesThatAreBothNewerAndPopular() {
        val olderPopular = item("old-popular").copy(
            rawReleaseDate = "2023-04-01",
            popularity = 950.0,
            imdbRating = "7.2",
        )
        val recentPopular = item("new-popular").copy(
            rawReleaseDate = "2026-10-01",
            popularity = 900.0,
            imdbRating = "8.6",
        )
        val middle = item("middle").copy(
            rawReleaseDate = "2025-03-01",
            popularity = 300.0,
            imdbRating = "7.8",
        )

        val merged = mergeHomeRowItems(
            sourceItems = listOf(listOf(olderPopular, middle), listOf(recentPopular)),
            deduplicate = true,
            blendMode = MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY,
        )

        assertEquals("new-popular", merged.first().id)
    }

    @Test
    fun allBlendModesReturnUniqueItemsAndRespectTheRequestedLimit() {
        val sources = listOf(
            listOf(
                item("a1").copy(rawReleaseDate = "2026-01-01", popularity = 80.0, imdbRating = "8.5"),
                item("a2").copy(rawReleaseDate = "2024-01-01", popularity = 20.0, imdbRating = "6.0"),
            ),
            listOf(
                item("b1").copy(rawReleaseDate = "2025-01-01", popularity = 60.0, imdbRating = "9.0"),
                item("b2").copy(rawReleaseDate = "2023-01-01", popularity = 10.0, imdbRating = "7.0"),
            ),
        )
        MergedHomeRowBlendMode.entries.forEach { mode ->
            val merged = mergeHomeRowItems(
                sourceItems = sources,
                deduplicate = true,
                maxItems = 3,
                blendMode = mode,
                sortMode = MergedHomeRowSortMode.NEWEST,
            )
            assertEquals(3, merged.size, "mode=$mode")
            assertEquals(3, merged.map { it.stableKey() }.toSet().size, "mode=$mode")
        }
    }

    @Test
    fun explicitSortModeNoLongerOverridesBlendOrder() {
        val sources = listOf(
            listOf(item("a1").copy(rawReleaseDate = "2023-01-01"), item("a2").copy(rawReleaseDate = "2026-01-01")),
            listOf(item("b1").copy(rawReleaseDate = "2025-01-01")),
        )
        val merged = mergeHomeRowItems(
            sourceItems = sources,
            deduplicate = true,
            blendMode = MergedHomeRowBlendMode.INTERLEAVE,
            sortMode = MergedHomeRowSortMode.NEWEST,
        )
        assertEquals(listOf("a1", "b1", "a2"), merged.map { it.id })
    }

    @Test
    fun mergedContentTypeSeparatesMoviesAndSeries() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("movie-1", type = "movie"), item("series-1", type = "series")),
                listOf(item("movie-2", type = "movie"), item("series-2", type = "tv")),
            ),
            deduplicate = true,
            contentType = MergedHomeRowContentType.MOVIES,
        )

        assertEquals(listOf("movie-1", "movie-2"), merged.map { it.id })
    }

    @Test
    fun seriesFilterIncludesTvTypeAndExcludesMovies() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("movie-1", type = "movie"), item("series-1", type = "series")),
                listOf(item("tv-1", type = "tv")),
            ),
            deduplicate = true,
            contentType = MergedHomeRowContentType.SERIES,
        )

        assertEquals(listOf("series-1", "tv-1"), merged.map { it.id })
    }

    @Test
    fun posterlessFilterKeepsTitlesWithEitherCustomOrOriginalPoster() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(
                    item("has-poster").copy(poster = "https://example.com/poster.jpg"),
                    item("missing-poster"),
                    item("has-original").copy(rawPosterUrl = "https://example.com/original.jpg"),
                ),
            ),
            deduplicate = true,
            hideTitlesWithoutPosters = true,
        )

        assertEquals(listOf("has-poster", "has-original"), merged.map { it.id })
    }

    @Test
    fun legacySortArgumentDoesNotOverrideInterleaveBlend() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(
                    item("older").copy(rawReleaseDate = "2024-01-01"),
                    item("newer").copy(rawReleaseDate = "2026-06-01"),
                ),
            ),
            deduplicate = true,
            sortMode = MergedHomeRowSortMode.NEWEST,
        )

        assertEquals(listOf("older", "newer"), merged.map { it.id })
    }

    @Test
    fun mergedSourceLabelsDropInternalIdsAndTypeSuffixes() {
        assertEquals(
            "AI Recommendations",
            cleanMergedSourceTabTitle("AI Recommendations (movie/aicat_list_125836)"),
        )
        assertEquals(
            "BanglaPlex 2026",
            cleanMergedSourceTabTitle("BanglaPlex · 2026 - Movies"),
        )
        assertEquals(
            "Seerr Next",
            cleanMergedSourceTabTitle("Seerr · For you · Movies · Next"),
        )
    }
}
