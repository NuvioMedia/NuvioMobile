package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    private data class CacheKey(
        val mediaType: String,
        val imdbId: String,
        val credential: MdbListRatingsCredential,
        val providers: List<String>,
    )

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
    private val ratingsCache = mutableMapOf<CacheKey, List<MetaExternalRating>>()
    private val cacheLock = SynchronizedObject()
    private val client get() = MdbListTracker.ratings
    private val imdbRegex = Regex("tt\\d+")

    fun shouldFetchForMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): Boolean {
        if (!settings.isActive) return false
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
        val credential = settings.credential ?: return meta.copy(externalRatings = emptyList())

        val imdbId = extractImdbId(meta.id)
            ?: extractImdbId(fallbackItemId)
            ?: extractImdbId(meta.imdbId)
            ?: return meta.copy(externalRatings = emptyList())
        val mediaType = toMdbListMediaType(meta.type)
        val enabledProviders = settings.enabledProvidersInPriorityOrder()

        val ratings = fetchRatings(
            imdbId = imdbId,
            mediaType = mediaType,
            credential = credential,
            providers = enabledProviders,
        )

        return meta.copy(externalRatings = ratings)
    }

    fun clearCache() {
        synchronized(cacheLock) { ratingsCache.clear() }
    }

    private suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        credential: MdbListRatingsCredential,
        providers: List<String>,
    ): List<MetaExternalRating> = withContext(Dispatchers.Default) {
        client.checkCredential(credential)
        val cacheKey = CacheKey(mediaType, imdbId, credential, providers)
        synchronized(cacheLock) { ratingsCache[cacheKey] }?.let { return@withContext it }

        val ratings = coroutineScope {
            val rottenTomatoesRatings = if (providers.any { it == PROVIDER_TOMATOES || it == PROVIDER_AUDIENCE }) {
                async { fetchRottenTomatoesRatings(imdbId, mediaType, credential) }
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
                        credential = credential,
                    )
                }
            }.awaitAll().filterNotNull()
        }

        client.checkCredential(credential)
        synchronized(cacheLock) { ratingsCache[cacheKey] = ratings }
        ratings
    }

    private suspend fun fetchRottenTomatoesRatings(
        imdbId: String,
        mediaType: String,
        credential: MdbListRatingsCredential,
    ): List<MetaExternalRating> {
        return runCatching {
            parseRottenTomatoesRatings(client.getMedia(mediaType, imdbId, credential))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "MDBList Rotten Tomatoes request failed for $imdbId: ${error.message}" }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchProviderRating(
        imdbId: String,
        mediaType: String,
        providerId: String,
        credential: MdbListRatingsCredential,
    ): MetaExternalRating? {
        val requestBody = json.encodeToString(
            RatingRequest(
                ids = listOf(imdbId),
                provider = PROVIDER_IMDB,
            ),
        )

        return runCatching {
            val payload = client.getRating(mediaType, providerId, credential, requestBody)
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
