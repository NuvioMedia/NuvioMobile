package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJson
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MdbListMetadataService {
    const val PROVIDER_IMDB = "imdb"
    const val PROVIDER_TMDB = "tmdb"
    const val PROVIDER_TOMATOES = "tomatoes"
    const val PROVIDER_METACRITIC = "metacritic"
    const val PROVIDER_TRAKT = "trakt"
    const val PROVIDER_LETTERBOXD = "letterboxd"
    const val PROVIDER_AUDIENCE = "audience"
    const val PROVIDER_MAL = "mal"

    val PROVIDER_PRIORITY_ORDER = listOf(
        PROVIDER_IMDB,
        PROVIDER_TMDB,
        PROVIDER_TOMATOES,
        PROVIDER_METACRITIC,
        PROVIDER_TRAKT,
        PROVIDER_LETTERBOXD,
        PROVIDER_AUDIENCE,
        PROVIDER_MAL,
    )

    private val log = Logger.withTag("MdbListMetadata")
    private val json = Json { ignoreUnknownKeys = true }
    private val ratingsCache = mutableMapOf<String, List<MetaExternalRating>>()
    private val imdbRegex = Regex("tt\\d+")

    fun shouldFetchForMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): Boolean {
        if (!settings.enabled) return false
        if (settings.apiKey.trim().isBlank()) return false
        if (settings.enabledProvidersInPriorityOrder().isEmpty()) return false
        return extractImdbId(meta.id) != null || extractImdbId(fallbackItemId) != null || extractImdbId(meta.imdbId) != null
    }

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): MetaDetails {
        if (!shouldFetchForMeta(meta, fallbackItemId, settings)) {
            return meta.copy(externalRatings = emptyList())
        }
        val apiKey = settings.apiKey.trim()

        val imdbId = extractImdbId(meta.id)
            ?: extractImdbId(fallbackItemId)
            ?: extractImdbId(meta.imdbId)
            ?: return meta.copy(externalRatings = emptyList())
        val mediaType = toMdbListMediaType(meta.type)
        val enabledProviders = settings.enabledProvidersInPriorityOrder()

        val ratings = fetchRatings(
            imdbId = imdbId,
            mediaType = mediaType,
            apiKey = apiKey,
            providers = enabledProviders,
        )

        return meta.copy(externalRatings = ratings)
    }

    fun clearCache() {
        ratingsCache.clear()
    }

    private suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String,
        providers: List<String>,
    ): List<MetaExternalRating> = withContext(Dispatchers.Default) {
        val cacheKey = "$mediaType:$imdbId:$apiKey:${providers.joinToString(",")}"
        ratingsCache[cacheKey]?.let { return@withContext it }

        val ratings = coroutineScope {
            val rottenTomatoesRatings = if (providers.any { it == PROVIDER_TOMATOES || it == PROVIDER_AUDIENCE }) {
                async { fetchRottenTomatoesRatings(imdbId, mediaType, apiKey) }
            } else {
                null
            }
            providers.map { providerId ->
                async {
                    if (providerId == PROVIDER_TOMATOES || providerId == PROVIDER_AUDIENCE) {
                        rottenTomatoesRatings?.await()?.firstOrNull { it.source == providerId }
                            ?.let { return@async it }
                    }
                    fetchProviderRating(
                        imdbId = imdbId,
                        mediaType = mediaType,
                        providerId = providerId,
                        apiKey = apiKey,
                    )
                }
            }.awaitAll().filterNotNull()
        }

        ratingsCache[cacheKey] = ratings
        ratings
    }

    private suspend fun fetchRottenTomatoesRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String,
    ): List<MetaExternalRating> {
        val url = "https://api.mdblist.com/imdb/$mediaType/$imdbId?apikey=$apiKey&append_to_response=keyword"
        return runCatching {
            parseRottenTomatoesRatings(httpGetText(url))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "MDBList Rotten Tomatoes request failed for $imdbId: ${error.message}" }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchProviderRating(
        imdbId: String,
        mediaType: String,
        providerId: String,
        apiKey: String,
    ): MetaExternalRating? {
        val url = "https://api.mdblist.com/rating/$mediaType/$providerId?apikey=$apiKey"
        val requestBody = json.encodeToString(
            RatingRequest(
                ids = listOf(imdbId),
                provider = PROVIDER_IMDB,
            ),
        )

        return runCatching {
            val payload = httpPostJson(url = url, body = requestBody)
            val parsed = json.decodeFromString<RatingResponse>(payload)
            val rating = parsed.ratings.firstOrNull()?.rating ?: return@runCatching null
            MetaExternalRating(source = providerId, value = rating)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "MDBList request failed for $providerId/$imdbId: ${error.message}" }
        }.getOrNull()
    }

    private fun extractImdbId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return imdbRegex.find(value)?.value
    }

    private fun toMdbListMediaType(metaType: String): String {
        val normalized = metaType.trim().lowercase()
        return if (normalized == "movie") "movie" else "show"
    }
}

@Serializable
private data class RatingRequest(
    val ids: List<String>,
    val provider: String,
)

@Serializable
private data class RatingResponse(
    val ratings: List<RatingItem> = emptyList(),
)

@Serializable
private data class RatingItem(
    val rating: Double? = null,
)
