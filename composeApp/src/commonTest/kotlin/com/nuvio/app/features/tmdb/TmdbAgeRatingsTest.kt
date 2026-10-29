package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals

class TmdbAgeRatingsTest {
    @Test
    fun languageOnlyLocalesPreferLocalRatings() {
        assertEquals(listOf("FR", "US", "GB"), preferredAgeRatingRegions("fr"))
        assertEquals(listOf("DE", "US", "GB"), preferredAgeRatingRegions("de"))
        assertEquals(listOf("PT", "US", "GB"), preferredAgeRatingRegions("pt"))
        assertEquals(listOf("NO", "US", "GB"), preferredAgeRatingRegions("nb"))
    }

    @Test
    fun explicitRegionOverridesLanguageDefault() {
        assertEquals(listOf("CA", "US", "GB"), preferredAgeRatingRegions("fr-CA"))
        assertEquals(listOf("BR", "US", "GB"), preferredAgeRatingRegions("pt-BR"))
    }

    @Test
    fun regionFallbacksAreNotDuplicated() {
        assertEquals(listOf("GB", "US"), preferredAgeRatingRegions("en-GB"))
        assertEquals(listOf("US", "GB"), preferredAgeRatingRegions("en-US"))
    }

    @Test
    fun unknownLanguageKeepsExistingFallbacks() {
        assertEquals(listOf("US", "GB"), preferredAgeRatingRegions("xx"))
        assertEquals(listOf("US", "GB"), preferredAgeRatingRegions(""))
    }
}
