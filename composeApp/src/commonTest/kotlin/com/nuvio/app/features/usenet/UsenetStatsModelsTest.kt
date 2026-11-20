package com.nuvio.app.features.usenet

import com.nuvio.app.features.settings.dataSize
import com.nuvio.app.features.settings.decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NuvioTV's tests of the statistics card figures, and the page's number format. */
class UsenetStatsModelsTest {
    @Test
    fun searchYieldCountsAnsweredSearchesOnly() {
        // 10 searches: 2 failed and 1 paused say nothing; 7 answered, 1 of them empty.
        val counts = IndexerCounts(searches = 10, failedSearches = 2, pausedSearches = 1, emptySearches = 1, releases = 185)
        assertEquals(SearchYield(percent = 86, averageReleases = 31), counts.searchYield())
        assertEquals(SearchYield(0, 0), IndexerCounts(searches = 2, emptySearches = 2).searchYield())
        assertNull(IndexerCounts(searches = 3, failedSearches = 2, pausedSearches = 1).searchYield())
        assertNull(IndexerCounts().searchYield())
    }

    @Test
    fun averageResponseNeedsAnAnsweredRequest() {
        assertEquals(640L, IndexerCounts(answered = 3, responseMs = 1920).averageResponseMs())
        assertNull(IndexerCounts(requests = 4, failedRequests = 4).averageResponseMs())
    }

    @Test
    fun barsGoLargestFirstAsAShareOfTheLargestAndOnlyWhenTheyCompare() {
        val bars = statsBars(
            listOf(StatsBarSource("a", "beta", 3), StatsBarSource("b", "Alpha", 3), StatsBarSource("c", "Gamma", 12), StatsBarSource("d", "Delta", 0)),
        )
        assertEquals(listOf("c", "b", "a", "d"), bars.map { it.id })
        assertEquals(listOf(1f, 0.25f, 0.25f, 0f), bars.map { it.fraction })
        assertTrue(statsBars(listOf(StatsBarSource("a", "A", 5))).isEmpty())
        assertTrue(statsBars(listOf(StatsBarSource("a", "A", 0), StatsBarSource("b", "B", 0))).isEmpty())
        assertTrue(statsBars(emptyList()).isEmpty())
    }

    @Test
    fun completionIsTheShareOfAskedArticlesAProviderHad() {
        assertEquals(99.2, ProviderCounts(articles = 992, missing = 8, errors = 50).completion())
        assertEquals(0.0, ProviderCounts(missing = 3).completion())
        assertNull(ProviderCounts(errors = 4).completion())
        assertEquals(35L, ProviderCounts(ttfbMs = 70, ttfbSessions = 2).averageFirstByteMs())
        assertNull(ProviderCounts(sessions = 3).averageFirstByteMs())
    }

    @Test
    fun contentKindsFollowTheSearchRule() {
        assertEquals(ContentKind.MOVIE, contentKindOf("Movie"))
        listOf("series", "tv", "show").forEach { assertEquals(ContentKind.SHOW, contentKindOf(it)) }
        assertNull(contentKindOf(""))
        assertNull(contentKindOf(null))
    }

    @Test
    fun decimalsAreFixedWithoutGrouping() {
        assertEquals("99.2", decimal(99.2, 1))
        assertEquals("0.05", decimal(0.049, 2))
        assertEquals("1234.50", decimal(1234.5, 2))
        assertEquals("3", decimal(2.6, 0))
    }

    @Test
    fun tuningDataReadsAsMegabytesOrGigabytes() {
        assertEquals("812 MB", dataSize(812_400_000))
        assertEquals("1.05 GB", dataSize(1_048_576_000))
        assertEquals("0 MB", dataSize(0))
    }
}
