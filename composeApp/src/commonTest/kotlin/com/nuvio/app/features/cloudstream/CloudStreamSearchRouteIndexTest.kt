package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudStreamSearchRouteIndexTest {
    @Test
    fun cachedRoutesMustMatchRequestedContentType() {
        val providerId = "route-index-type-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "The Office",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "movie-route",
                    name = "The Office",
                    type = CloudStreamTvType.Movie,
                    year = 1993,
                ),
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "series-route",
                    name = "The Office",
                    type = CloudStreamTvType.TvSeries,
                    year = 2005,
                ),
            ),
        )

        assertEquals(
            listOf("movie-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("The Office"),
                type = "movie",
                year = 1993,
            ).map { it.data },
        )
        assertEquals(
            listOf("series-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("The Office"),
                type = "tv",
                year = 2005,
            ).map { it.data },
        )
    }

    @Test
    fun cachedRoutesMustMatchFullCanonicalTitleRatherThanPrefix() {
        val providerId = "route-index-prefix-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "Breaking",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "breaking-bad-route",
                    name = "Breaking Bad",
                    type = CloudStreamTvType.TvSeries,
                ),
            ),
        )

        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("Breaking"),
                type = "series",
                year = null,
            ),
        )
    }

    @Test
    fun clearingOneProviderDoesNotRemoveOtherProviderRoutes() {
        CloudStreamSearchRouteIndex.remember(
            providerId = "provider-one",
            query = "Movie",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = "provider-one",
                    data = "one-route",
                    name = "Movie",
                    type = CloudStreamTvType.Movie,
                ),
            ),
        )
        CloudStreamSearchRouteIndex.remember(
            providerId = "provider-two",
            query = "Movie",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = "provider-two",
                    data = "two-route",
                    name = "Movie",
                    type = CloudStreamTvType.Movie,
                ),
            ),
        )

        CloudStreamSearchRouteIndex.clearProvider("provider-one")

        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find("provider-one", listOf("Movie"), "movie", null),
        )
        assertEquals(
            listOf("two-route"),
            CloudStreamSearchRouteIndex.find("provider-two", listOf("Movie"), "movie", null).map { it.data },
        )
        CloudStreamSearchRouteIndex.clear()
        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find("provider-two", listOf("Movie"), "movie", null),
        )
    }

    @Test
    fun cachedRoutesNormalizeArticlesAndTrailingYears() {
        val providerId = "route-index-canonical-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "Tank",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "the-tank-2023-route",
                    name = "The Tank 2023",
                    type = CloudStreamTvType.Movie,
                    year = 2023,
                ),
            ),
        )

        assertEquals(
            listOf("the-tank-2023-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("Tank"),
                type = "movie",
                year = 2023,
            ).map { it.data },
        )
    }

    @Test
    fun cachedRouteWithMissingYearDoesNotMatchKnownRequestedYear() {
        val providerId = "route-index-missing-year"
        CloudStreamSearchRouteIndex.clear()
        CloudStreamSearchRouteIndex.remember(
            providerId,
            "The Office",
            listOf(CloudStreamSearchItem(providerId, "yearless", "The Office", CloudStreamTvType.TvSeries)),
        )
        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find(providerId, listOf("The Office"), "series", 2005),
        )
        CloudStreamSearchRouteIndex.clear()
    }

    @Test
    fun cachedRoutesWithMultipleReleaseYearsAreRejectedWhenYearIsUnknown() {
        val providerId = "route-index-ambiguous-years"
        CloudStreamSearchRouteIndex.clear()
        CloudStreamSearchRouteIndex.remember(
            providerId,
            "The Office",
            listOf(
                CloudStreamSearchItem(providerId, "uk-office", "The Office 2001", CloudStreamTvType.TvSeries, year = 2001),
                CloudStreamSearchItem(providerId, "us-office", "The Office 2005", CloudStreamTvType.TvSeries, year = 2005),
            ),
        )
        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find(providerId, listOf("The Office"), "series", null),
        )
        CloudStreamSearchRouteIndex.clear()
    }
}
