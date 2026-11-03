package com.nuvio.app.features.streams

import com.nuvio.app.features.cloudstream.CloudStreamEpisode
import com.nuvio.app.features.cloudstream.CloudStreamSearchItem
import com.nuvio.app.features.cloudstream.CloudStreamLoadItem
import com.nuvio.app.features.cloudstream.CloudStreamTvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudStreamMatchTest {
    @Test
    fun doesNotSelectPrefixOnlyMovieForShortTitle() {
        val match = listOf(
            CloudStreamSearchItem(
                providerId = "provider",
                data = "tank-girl",
                name = "Tank Girl",
                type = CloudStreamTvType.Movie,
                year = 1995,
            ),
        ).bestCloudStreamMatch(
            searchTitle = "Tank",
            type = "movie",
        )

        assertNull(match)
    }

    @Test
    fun matchesLeadingArticleDifference() {
        val match = listOf(
            CloudStreamSearchItem(
                providerId = "provider",
                data = "the-tank",
                name = "The Tank",
                type = CloudStreamTvType.Movie,
                year = 2023,
            ),
        ).bestCloudStreamMatch(
            searchTitle = "Tank",
            type = "movie",
        )

        assertEquals("the-tank", match?.data)
    }

    @Test
    fun matchesTrailingYearDifference() {
        val match = listOf(
            CloudStreamSearchItem(
                providerId = "provider",
                data = "tank-2023",
                name = "Tank 2023",
                type = CloudStreamTvType.Movie,
            ),
        ).bestCloudStreamMatch(
            searchTitle = "Tank",
            type = "movie",
        )

        assertEquals("tank-2023", match?.data)
    }

    @Test
    fun excludesExplicitWrongMovieYear() {
        val matches = listOf(
            CloudStreamSearchItem(
                providerId = "provider",
                data = "fury-1936",
                name = "Fury",
                type = CloudStreamTvType.Movie,
                year = 1936,
            ),
            CloudStreamSearchItem(
                providerId = "provider",
                data = "fury-2014",
                name = "Fury",
                type = CloudStreamTvType.Movie,
                year = 2014,
            ),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(
                title = "Fury",
                type = "movie",
                year = 2014,
                season = null,
                episode = null,
            ),
        )

        assertEquals(listOf("fury-2014"), matches.map { it.data })
    }

    @Test
    fun matchesTmdbAliasTitleCandidate() {
        val matches = listOf(
            CloudStreamSearchItem(
                providerId = "provider",
                data = "fury-2014",
                name = "Fury",
                type = CloudStreamTvType.Movie,
                year = 2014,
            ),
        ).bestCloudStreamMatches(
            CloudStreamSearchRequest(
                title = "Hiddet",
                aliases = listOf("Fury"),
                type = "movie",
                year = 2014,
                season = null,
                episode = null,
            ),
        )

        assertEquals(listOf("fury-2014"), matches.map { it.data })
    }

    @Test
    fun rejectsUnverifiedMovieYearWhenRequestedYearIsKnown() {
        val request = CloudStreamSearchRequest(
            title = "Fury",
            type = "movie",
            year = 2014,
            season = null,
            episode = null,
        )
        val searchItem = CloudStreamSearchItem(
            providerId = "provider",
            data = "fury",
            name = "Fury",
            type = CloudStreamTvType.Movie,
        )
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "fury",
            name = "Fury",
            type = CloudStreamTvType.Movie,
        )

        assertFalse(loaded.matchesCloudStreamRequest(request, searchItem))
    }

    @Test
    fun acceptsProviderRankedLocalizedTitleWhenMovieYearMatches() {
        val request = CloudStreamSearchRequest(
            title = "The Shawshank Redemption",
            type = "movie",
            year = 1994,
            season = null,
            episode = null,
        )
        val searchItem = CloudStreamSearchItem(
            providerId = "provider",
            data = "shawshank",
            name = "Les Evades",
            type = CloudStreamTvType.Movie,
        )
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "shawshank",
            name = "Les Evades",
            type = CloudStreamTvType.Movie,
            year = 1994,
        )

        assertTrue(
            loaded.matchesCloudStreamRequest(
                request = request,
                searchItem = searchItem,
                allowProviderRankedFallback = true,
            ),
        )
    }

    @Test
    fun rejectsProviderRankedLocalizedTitleWhenMovieYearDiffers() {
        val request = CloudStreamSearchRequest(
            title = "The Shawshank Redemption",
            type = "movie",
            year = 1994,
            season = null,
            episode = null,
        )
        val searchItem = CloudStreamSearchItem(
            providerId = "provider",
            data = "wrong",
            name = "Un autre film",
            type = CloudStreamTvType.Movie,
            year = 2001,
        )
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "wrong",
            name = "Un autre film",
            type = CloudStreamTvType.Movie,
            year = 2001,
        )

        assertFalse(
            loaded.matchesCloudStreamRequest(
                request = request,
                searchItem = searchItem,
                allowProviderRankedFallback = true,
            ),
        )
    }

    @Test
    fun doesNotSelectEpisodeOnlyFromWrongSeason() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "show",
            name = "Show",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "s1e1", name = "Episode 1", season = 1, episode = 1),
                CloudStreamEpisode(data = "s2e2", name = "Episode 2", season = 2, episode = 2),
            ),
        )
        val request = CloudStreamSearchRequest(
            title = "Show",
            type = "series",
            year = null,
            season = 2,
            episode = 1,
        )

        assertNull(loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun doesNotUseEpisodeTitleToCrossAnExplicitSeasonBoundary() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "show",
            name = "Show",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "s1e1", name = "Pilot", season = 1, episode = 1),
                CloudStreamEpisode(data = "s2e2", name = "Another Episode", season = 2, episode = 2),
            ),
        )
        val request = CloudStreamSearchRequest(
            title = "Show",
            type = "series",
            year = null,
            season = 2,
            episode = 1,
            episodeTitle = "Pilot",
        )

        assertNull(loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun selectsUnknownSeasonEpisodeOnlyForFirstSeasonFallback() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "show",
            name = "Show",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "e1", name = "Episode 1", episode = 1),
            ),
        )
        val request = CloudStreamSearchRequest(
            title = "Show",
            type = "series",
            year = null,
            season = 1,
            episode = 1,
        )

        assertEquals("e1", loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun rejectsProviderRankedUnrelatedSeriesWhenYearCannotVerifyIdentity() {
        val request = CloudStreamSearchRequest("Breaking Bad", type = "series", year = 2008, season = null, episode = null)
        val item = CloudStreamSearchItem("provider", "wrong", "Unrelated Series", CloudStreamTvType.TvSeries)
        val loaded = CloudStreamLoadItem("provider", "wrong", "Unrelated Series", CloudStreamTvType.TvSeries)
        assertFalse(loaded.matchesCloudStreamRequest(request, item, allowProviderRankedFallback = true))
    }

    @Test
    fun acceptsLocalizedProviderRankedTitleOnlyWhenLoadedYearMatches() {
        val request = CloudStreamSearchRequest("The Shawshank Redemption", type = "movie", year = 1994, season = null, episode = null)
        val item = CloudStreamSearchItem("provider", "localized", "Les Evades", CloudStreamTvType.Movie)
        val loaded = CloudStreamLoadItem("provider", "localized", "Les Evades", CloudStreamTvType.Movie, year = 1994)
        assertTrue(loaded.matchesCloudStreamRequest(request, item, allowProviderRankedFallback = true))
    }

    @Test
    fun rejectsExternalIdResultWithWrongTitleAndNoVerifyingYear() {
        val request = CloudStreamSearchRequest("The Office", type = "series", year = 2005, season = null, episode = null)
        val loaded = CloudStreamLoadItem("provider", "wrong-id", "Another Office", CloudStreamTvType.TvSeries)
        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun rejectsExternalIdResultWithWrongYear() {
        val request = CloudStreamSearchRequest("The Office", type = "series", year = 2005, season = null, episode = null)
        val loaded = CloudStreamLoadItem("provider", "wrong-year", "The Office", CloudStreamTvType.TvSeries, year = 1993)
        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun acceptsLocalizedExternalIdResultWhenTypeAndYearMatch() {
        val request = CloudStreamSearchRequest("The Shawshank Redemption", type = "movie", year = 1994, season = null, episode = null)
        val loaded = CloudStreamLoadItem("provider", "localized-id", "Les Evades", CloudStreamTvType.Movie, year = 1994)
        assertTrue(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun doesNotChooseFirstEpisodeWhenSeasonIsAmbiguous() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider", data = "show", name = "Show", type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "s1e2", name = "Episode 2", season = 1, episode = 2),
                CloudStreamEpisode(data = "s2e2", name = "Episode 2", season = 2, episode = 2),
            ),
        )
        val request = CloudStreamSearchRequest("Show", type = "series", year = null, season = null, episode = 2)
        assertNull(loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun rejectsExternalIdExactTitleWhenRequestedYearIsMissingFromLoadedResponse() {
        val request = CloudStreamSearchRequest("The Office", type = "series", year = 2005, season = null, episode = null)
        val loaded = CloudStreamLoadItem("provider", "yearless", "The Office", CloudStreamTvType.TvSeries)
        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun episodeTitleDoesNotResolveDuplicateEpisodeAcrossSeasons() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider", data = "show", name = "Show", type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "s1e1", name = "Pilot", season = 1, episode = 1),
                CloudStreamEpisode(data = "s2e1", name = "Pilot", season = 2, episode = 1),
            ),
        )
        val request = CloudStreamSearchRequest(
            "Show", type = "series", year = null, season = null, episode = 1, episodeTitle = "Pilot",
        )
        assertNull(loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun rejectsProviderRankedUnrelatedSeriesEvenWhenReleaseYearMatches() {
        val request = CloudStreamSearchRequest(
            "Breaking Bad", type = "series", year = 2008, season = 1, episode = 1,
        )
        val item = CloudStreamSearchItem(
            "provider", "wrong-series", "Another Show", CloudStreamTvType.TvSeries, year = 2008,
        )
        val loaded = CloudStreamLoadItem(
            "provider", "wrong-series", "Another Show", CloudStreamTvType.TvSeries, year = 2008,
        )

        assertFalse(loaded.matchesCloudStreamRequest(request, item, allowProviderRankedFallback = true))
    }

    @Test
    fun rejectsExternalIdUnrelatedSeriesEvenWhenReleaseYearMatches() {
        val request = CloudStreamSearchRequest(
            "The Office", type = "series", year = 2005, season = null, episode = null,
        )
        val loaded = CloudStreamLoadItem(
            "provider", "wrong-series", "Another Office", CloudStreamTvType.TvSeries, year = 2005,
        )

        assertFalse(loaded.matchesCloudStreamExternalIdRequest(request))
    }

    @Test
    fun rejectsNumberedEpisodeWhenProviderTitleConflictsWithRequestedTitle() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "show",
            name = "Show",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "wrong-s1e1", name = "A Different Story", season = 1, episode = 1),
            ),
        )
        val request = CloudStreamSearchRequest(
            "Show", type = "series", year = null, season = 1, episode = 1,
            episodeTitle = "The Beginning",
        )

        assertNull(loaded.linkDataForCloudStreamRequest(request))
    }

    @Test
    fun acceptsNumberedEpisodeWhenProviderOnlyHasGenericEpisodeTitle() {
        val loaded = CloudStreamLoadItem(
            providerId = "provider",
            data = "show",
            name = "Show",
            type = CloudStreamTvType.TvSeries,
            episodes = listOf(
                CloudStreamEpisode(data = "s1e1", name = "Episode 1", season = 1, episode = 1),
            ),
        )
        val request = CloudStreamSearchRequest(
            "Show", type = "series", year = null, season = 1, episode = 1,
            episodeTitle = "The Beginning",
        )

        assertEquals("s1e1", loaded.linkDataForCloudStreamRequest(request))
    }
}
