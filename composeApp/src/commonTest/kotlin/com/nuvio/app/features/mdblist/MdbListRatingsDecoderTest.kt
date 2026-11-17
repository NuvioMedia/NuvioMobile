package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import kotlin.test.Test
import kotlin.test.assertEquals

class MdbListRatingsDecoderTest {
    @Test
    fun invalidValuesAndUnknownSourcesAreSkippedWhileZeroAndNativeScalesArePreserved() {
        val ratings = parseMdbListRatings(
            """{"ratings":[
                {"source":"imdb","value":null},
                {"source":"imdb","value":11},
                {"source":"imdb","value":0,"score":99},
                {"source":"tmdb","value":101},
                {"source":"tmdb","value":76,"score":90},
                {"source":"trakt","value":-1},
                {"source":"trakt","value":80},
                {"source":"letterboxd","value":5.1},
                {"source":"letterboxd","value":-1,"score":82},
                {"source":"letterboxd","value":4.1,"score":82},
                {"source":"myanimelist","value":10.1},
                {"source":"myanimelist","value":8.3},
                {"source":"metacritic","score":75},
                {"source":"metacriticuser","value":8.5}
            ]}""",
        )

        assertEquals(listOf(
            MetaExternalRating("imdb", 0.0),
            MetaExternalRating("tmdb", 76.0),
            MetaExternalRating("trakt", 80.0),
            MetaExternalRating("letterboxd", 4.1),
            MetaExternalRating("mal", 8.3),
        ), ratings)
    }

    @Test
    fun responseIdsSupportCurrentAndLegacyFormatsWithoutUsingTheNumericMediaId() {
        val ratings = parseMdbListRatingsBatch(
            """[
                {"imdb_id":"tt1","ratings":[{"source":"imdb","value":1}]},
                {"imdbid":"tt2","ratings":[{"source":"imdb","value":2}]},
                {"ids":{"imdb":"tt3","tmdb":3,"mal":null},"ratings":[{"source":"imdb","value":3}]},
                {"id":4,"ratings":[{"source":"imdb","value":4}]}
            ]""",
        )

        assertEquals(mapOf(
            "tt1" to listOf(MetaExternalRating("imdb", 1.0)),
            "tt2" to listOf(MetaExternalRating("imdb", 2.0)),
            "tt3" to listOf(MetaExternalRating("imdb", 3.0)),
        ), ratings)
    }

    @Test
    fun sourceAliasesDoNotProduceDuplicateRatings() {
        val ratings = parseMdbListRatings(
            """{"ratings":[
                {"source":"popcorn","value":81},
                {"source":"tomatoesaudience","value":81},
                {"source":"audience","value":81},
                {"source":"mal","value":8.0},
                {"source":"myanimelist","value":8.0}
            ]}""",
        )

        assertEquals(listOf(MetaExternalRating("audience", 81.0), MetaExternalRating("mal", 8.0)), ratings)
    }

    @Test
    fun letterboxdUsesItsFivePointScaleForBothMediaResponseFormats() {
        for ((value, score, expected) in listOf(Triple(8.0, 80, 4.0), Triple(2.4, 24, 1.2), Triple(4.0, 80, 4.0))) {
            val ratings = parseMdbListRatings(
                """{"ratings":[{"source":"letterboxd","value":$value,"score":$score}]}""",
            )
            assertEquals(listOf(MetaExternalRating("letterboxd", expected)), ratings)
        }
    }

    @Test
    fun liveSingleAndBatchLetterboxdFormatsProduceTheSameRating() {
        val single = parseMdbListRatings(
            """{"ids":{"imdb":"tt0073195"},"ratings":[{"source":"letterboxd","value":3.9,"score":78}]}""",
        )
        val batch = parseMdbListRatingsBatch(
            """[{"ids":{"imdb":"tt0073195"},"ratings":[{"source":"letterboxd","value":7.8,"score":78}]}]""",
        )

        assertEquals(listOf(MetaExternalRating("letterboxd", 3.9)), single)
        assertEquals(single, batch.getValue("tt0073195"))
    }
}
