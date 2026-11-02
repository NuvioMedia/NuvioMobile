package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.ondemand.VodSort
import com.nuvio.app.features.livetv.ondemand.VodTitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VodTitlesTest {
    private val latest = 2027

    @Test
    fun dottedFileNamesReadAsTitles() {
        assertEquals("Mad Max Fury Road", VodTitles.displayTitle("EN-TOP -181.Mad.Max.Fury.Road.2015"))
        assertEquals(2015, VodTitles.releaseYearInName("EN-TOP -181.Mad.Max.Fury.Road.2015", latest))
    }

    @Test
    fun providerTagsYearAndQualityComeOff() {
        assertEquals("Coyote vs. Acme", VodTitles.displayTitle("EN  - Coyote vs. Acme (2026) 4K"))
        assertEquals("Roommates", VodTitles.displayTitle("4K-NF - Roommates"))
        assertEquals("Dune Part Two", VodTitles.displayTitle("[MULTI-SUB] Dune Part Two 2160p HDR"))
        assertEquals("Spider-Man", VodTitles.displayTitle("Spider-Man"))
    }

    @Test
    fun learnedPrefixesAreStripped() {
        val before = VodTitles.knownPrefixes
        try {
            VodTitles.knownPrefixes = setOf("my provider")
            assertEquals("Heat", VodTitles.displayTitle("My Provider - Heat"))
        } finally {
            VodTitles.knownPrefixes = before
        }
    }

    @Test
    fun yearsThatArePartOfATitleAreNotReleaseYears() {
        assertNull(VodTitles.releaseYearInName("2038 Origin Unknown", latest))
        assertNull(VodTitles.releaseYearInName("1917", latest))
        assertNull(VodTitles.releaseYearInName("2001: A Space Odyssey", latest))
        assertEquals(2018, VodTitles.releaseYearInName("Movie (2018)", latest))
        assertEquals(2018, VodTitles.releaseYearInName("Movie - 2018", latest))
        assertEquals(2018, VodTitles.releaseYearInName("Movie 2018 1080p", latest))
        // A year still to come isn't one.
        assertNull(VodTitles.releaseYearInName("Movie (2999)", latest))
    }

    @Test
    fun yearFromProviderFields() {
        assertEquals(2015, VodTitles.yearOf("2015-06-12"))
        assertEquals(1999, VodTitles.yearOf("1999"))
        assertNull(VodTitles.yearOf(""))
        assertNull(VodTitles.yearOf(null))
    }

    @Test
    fun normalizedKeysIgnoreTagsAndYears() {
        assertEquals("thematrix", VodTitles.normalize("The Matrix (1999) 4K"))
        assertEquals("bigbrother", VodTitles.coreKey("EN - Big Brother UK 2023"))
        assertEquals("bigbrother", VodTitles.coreKey("Big Brother (2023)"))
    }

    @Test
    fun learnedPrefixesNeedManyTitles() {
        val names = (1..40).map { "EN - Film $it" } + (1..3).map { "FR - Film $it" } + listOf("Plain title")
        assertEquals(setOf("en"), VodTitles.learnPrefixes(names.asSequence()))
    }

    @Test
    fun savedSortsReadBackIncludingTheTvShuffleForm() {
        assertEquals(VodSort.NAME_AZ, VodSort.parse("NAME_AZ"))
        assertNull(VodSort.parse("SHUFFLE:1234"))
        assertNull(VodSort.parse(""))
        assertNull(VodSort.parse("nonsense"))
    }
}
