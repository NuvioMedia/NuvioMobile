package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val ratingJson = Json { ignoreUnknownKeys = true }

internal enum class RottenTomatoesStatus {
    FRESH,
    ROTTEN,
    CERTIFIED_FRESH,
    HOT,
    STALE,
    VERIFIED_HOT,
}

internal val MetaExternalRating.rottenTomatoesStatus: RottenTomatoesStatus?
    get() = when (source) {
        PROVIDER_TOMATOES -> when {
            isCertified && value >= 70 -> RottenTomatoesStatus.CERTIFIED_FRESH
            value >= 60 -> RottenTomatoesStatus.FRESH
            else -> RottenTomatoesStatus.ROTTEN
        }
        PROVIDER_AUDIENCE -> when {
            isCertified && value >= 80 -> RottenTomatoesStatus.VERIFIED_HOT
            value >= 60 -> RottenTomatoesStatus.HOT
            else -> RottenTomatoesStatus.STALE
        }
        else -> null
    }

internal fun parseRottenTomatoesRatings(payload: String): List<MetaExternalRating> {
    val response = ratingJson.decodeFromString<RottenTomatoesResponse>(payload)
    val keywords = response.keywords.orEmpty().mapNotNull { keyword ->
        val name = when (keyword) {
            is JsonObject -> keyword["name"] as? JsonPrimitive
            is JsonPrimitive -> keyword
            else -> null
        }
        name?.contentOrNull?.substringAfterLast('.')
    }.toSet()
    return response.ratings.orEmpty().mapNotNull { rating ->
        val source = when (rating.source) {
            "tomatoes" -> PROVIDER_TOMATOES
            "popcorn", "audience", "tomatoesaudience" -> PROVIDER_AUDIENCE
            else -> return@mapNotNull null
        }
        val value = rating.value?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
        MetaExternalRating(
            source = source,
            value = value,
            isCertified = when (source) {
                PROVIDER_TOMATOES -> "certified-fresh" in keywords
                else -> "certified-hot" in keywords
            },
        )
    }
}

@Serializable
private data class RottenTomatoesResponse(
    val ratings: List<RottenTomatoesRating>? = null,
    val keywords: List<JsonElement>? = null,
)

@Serializable
private data class RottenTomatoesRating(
    val source: String,
    val value: Double? = null,
)
