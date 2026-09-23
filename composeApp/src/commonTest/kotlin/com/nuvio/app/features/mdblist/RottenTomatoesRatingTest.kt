package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RottenTomatoesRatingTest {
    @Test
    fun standardIconsSwitchAtSixtyPercent() {
        for (score in listOf(0.0, 59.0, 59.9)) {
            assertEquals(RottenTomatoesStatus.ROTTEN, rating(PROVIDER_TOMATOES, score).rottenTomatoesStatus)
            assertEquals(RottenTomatoesStatus.STALE, rating(PROVIDER_AUDIENCE, score).rottenTomatoesStatus)
        }
        for (score in listOf(60.0, 75.0, 90.0, 100.0)) {
            assertEquals(RottenTomatoesStatus.FRESH, rating(PROVIDER_TOMATOES, score).rottenTomatoesStatus)
            assertEquals(RottenTomatoesStatus.HOT, rating(PROVIDER_AUDIENCE, score).rottenTomatoesStatus)
        }
    }

    @Test
    fun existingCertificationsUseRetentionThresholds() {
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, rating(PROVIDER_TOMATOES, 70.0, true).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.FRESH, rating(PROVIDER_TOMATOES, 69.0, true).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, rating(PROVIDER_AUDIENCE, 80.0, true).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.HOT, rating(PROVIDER_AUDIENCE, 79.0, true).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.ROTTEN, rating(PROVIDER_TOMATOES, 59.0, true).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.STALE, rating(PROVIDER_AUDIENCE, 59.0, true).rottenTomatoesStatus)
    }

    @Test
    fun titleResponseMapsBothScoresAndCertificationKeywords() {
        val ratings = parseMdbListRatings(
            """
            {
                "title": "Example",
                "ratings": [
                    {"source": "imdb", "value": 8.1},
                    {"source": "tomatoes", "value": 72, "score": 72, "votes": 100},
                    {"source": "popcorn", "value": 85, "score": 85, "votes": 1000}
                ],
                "keywords": [
                    {"id": 19631, "name": "certified-fresh"},
                    {"name": "mdblist.certified-hot"}
                ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(PROVIDER_IMDB, PROVIDER_TOMATOES, PROVIDER_AUDIENCE), ratings.map { it.source })
        assertEquals(listOf(8.1, 72.0, 85.0), ratings.map { it.value })
        assertEquals(
            listOf(null, RottenTomatoesStatus.CERTIFIED_FRESH, RottenTomatoesStatus.VERIFIED_HOT),
            ratings.map { it.rottenTomatoesStatus },
        )
    }

    @Test
    fun highScoresAndVoteCountsDoNotGrantCertification() {
        val ratings = parseMdbListRatings(
            """
            {
                "ratings": [
                    {"source": "tomatoes", "value": 100, "votes": 500},
                    {"source": "popcorn", "value": 100, "votes": 10000}
                ]
            }
            """.trimIndent(),
        )

        assertTrue(ratings.all { !it.isCertified })
        assertEquals(listOf(RottenTomatoesStatus.FRESH, RottenTomatoesStatus.HOT), ratings.map { it.rottenTomatoesStatus })
    }

    @Test
    fun missingAndInvalidScoresAreSkippedButZeroIsPreserved() {
        val ratings = parseMdbListRatings(
            """
            {
                "ratings": [
                    {"source": "tomatoes", "value": null},
                    {"source": "tomatoes"},
                    {"source": "tomatoes", "value": -1},
                    {"source": "popcorn", "value": 101},
                    {"source": "popcorn", "value": 0}
                ],
                "keywords": null
            }
            """.trimIndent(),
        )

        assertEquals(listOf(rating(PROVIDER_AUDIENCE, 0.0)), ratings)
        assertTrue(parseMdbListRatings("{}").isEmpty())
        assertTrue(parseMdbListRatings("""{"ratings":null}""").isEmpty())
    }

    @Test
    fun audienceAliasesAndStringKeywordsAreAccepted() {
        for (source in listOf("audience", "tomatoesaudience")) {
            val ratings = parseMdbListRatings(
                """{"ratings":[{"source":"$source","value":91}],"keywords":["certified-hot"]}""",
            )
            assertEquals(listOf(rating(PROVIDER_AUDIENCE, 91.0, true)), ratings)
        }
    }

    @Test
    fun unrelatedKeywordsAndProvidersKeepTheirStandardIcons() {
        val ratings = parseMdbListRatings(
            """{"ratings":[{"source":"tomatoes","value":90}],"keywords":[{"name":"fresh"}]}""",
        )

        assertFalse(ratings.single().isCertified)
        assertNull(rating(PROVIDER_IMDB, 8.0).rottenTomatoesStatus)
    }

    private fun rating(source: String, value: Double, certified: Boolean = false) =
        MetaExternalRating(source = source, value = value, isCertified = certified)
}
