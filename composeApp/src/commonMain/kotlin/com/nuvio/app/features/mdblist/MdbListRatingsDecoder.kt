package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_LETTERBOXD
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_MAL
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_METACRITIC
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TRAKT
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val ratingJson = Json { ignoreUnknownKeys = true }

internal fun parseMdbListRatings(payload: String): List<MetaExternalRating> =
    ratingJson.decodeFromString<MediaRatings>(payload).toRatings()

internal fun parseMdbListRatingsBatch(payload: String): Map<String, List<MetaExternalRating>> =
    ratingJson.decodeFromString<List<MediaRatings>>(payload).mapNotNull { media ->
        val imdbId = media.imdbId ?: media.imdbid ?: media.ids?.get("imdb")?.contentOrNull
        imdbId?.let { it to media.toRatings() }
    }.toMap()

@Serializable
private data class MediaRatings(
    @SerialName("imdb_id") val imdbId: String? = null,
    val imdbid: String? = null,
    val ids: Map<String, JsonPrimitive>? = null,
    val ratings: List<SourceRating>? = null,
    val keywords: List<JsonElement>? = null,
) {
    fun toRatings(): List<MetaExternalRating> {
        val keywordNames = keywords.orEmpty().mapNotNull { keyword ->
            val name = when (keyword) {
                is JsonObject -> keyword["name"] as? JsonPrimitive
                is JsonPrimitive -> keyword
                else -> null
            }
            name?.contentOrNull?.substringAfterLast('.')
        }.toSet()
        return ratings.orEmpty().mapNotNull { rating ->
            val source = when (rating.source) {
                "popcorn", "audience", "tomatoesaudience" -> PROVIDER_AUDIENCE
                "myanimelist", "mal" -> PROVIDER_MAL
                PROVIDER_IMDB, PROVIDER_TMDB, PROVIDER_TOMATOES, PROVIDER_METACRITIC,
                PROVIDER_TRAKT, PROVIDER_LETTERBOXD -> rating.source
                else -> return@mapNotNull null
            }
            val maximum = when (source) {
                PROVIDER_IMDB, PROVIDER_MAL -> 10.0
                PROVIDER_LETTERBOXD -> 5.0
                else -> 100.0
            }
            val rawValue = rating.value?.takeIf { it >= 0 } ?: return@mapNotNull null
            val value = if (source == PROVIDER_LETTERBOXD && rating.score != null) rating.score / 20.0 else rawValue
            if (value !in 0.0..maximum) return@mapNotNull null
            MetaExternalRating(
                source = source,
                value = value,
                isCertified = when (source) {
                    PROVIDER_TOMATOES -> "certified-fresh" in keywordNames
                    PROVIDER_AUDIENCE -> "certified-hot" in keywordNames
                    else -> false
                },
            )
        }.distinctBy { it.source }
    }
}

@Serializable
private data class SourceRating(
    val source: String,
    val value: Double? = null,
    val score: Double? = null,
)
