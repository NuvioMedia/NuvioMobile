package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TmdbService {
    private val log = Logger.withTag("TmdbService")
    private val json = Json { ignoreUnknownKeys = true }
    private val imdbToTmdbCache = linkedMapOf<String, String>()
    private val tmdbToImdbCache = linkedMapOf<String, String>()
    private val cacheMutex = Mutex()
    private val imdbToTmdbInFlight = mutableMapOf<String, CompletableDeferred<String?>>()

    suspend fun ensureTmdbId(videoId: String, mediaType: String, fallbackImdbId: String? = null): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()

        val normalized = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        if (normalized.isBlank()) return null
        if (normalized.all { it in '0'..'9' }) {
            return normalized.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
        }
        if (normalized.isValidImdbId()) {
            return imdbToTmdb(imdbId = normalized.lowercase(), mediaType = mediaType, apiKey = apiKey)
        }

        // Fallback: use the IMDB ID supplied by the addon's meta response
        val normalizedFallback = fallbackImdbId
            ?.trim()
            ?.substringBefore(':')
            ?.takeIf(String::isValidImdbId)
            ?.lowercase()
        if (normalizedFallback != null) {
            return imdbToTmdb(imdbId = normalizedFallback, mediaType = mediaType, apiKey = apiKey)
        }

        return null
    }

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()

        val cacheKey = "$tmdbId:${normalizeMediaType(mediaType)}"
        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey]?.let { return it }
        }

        val endpoint = when (normalizeMediaType(mediaType)) {
            "tv" -> "tv/$tmdbId/external_ids"
            else -> "movie/$tmdbId/external_ids"
        }
        val body = fetch<TmdbExternalIdsResponse>(endpoint = endpoint, apiKey = apiKey) ?: return null
        val imdbId = body.imdbId?.trim()?.takeIf(String::isNotBlank) ?: return null

        cacheMutex.withLock {
            tmdbToImdbCache[cacheKey] = imdbId
            imdbToTmdbCache["$imdbId:${normalizeMediaType(mediaType)}"] = tmdbId.toString()
        }
        return imdbId
    }

    private suspend fun imdbToTmdb(imdbId: String, mediaType: String, apiKey: String): String? {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"

        // Coalesce concurrent requests for the same IMDb ID. One provider owns
        // the network lookup; all other providers await the same result instead
        // of serially repeating a failing or rate-limited TMDB request.
        val (lookup, isOwner) = cacheMutex.withLock {
            imdbToTmdbCache[cacheKey]?.let { return it }
            val existing = imdbToTmdbInFlight[cacheKey]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<String?>().also {
                    imdbToTmdbInFlight[cacheKey] = it
                } to true
            }
        }

        if (!isOwner) return lookup.await()

        try {
            val body = fetch<TmdbFindResponse>(
                endpoint = "find/$imdbId",
                apiKey = apiKey,
                query = mapOf("external_source" to "imdb_id"),
            )
            val resultId = body?.let {
                when (normalizedType) {
                    "movie" -> it.movieResults.firstOrNull()?.id
                    "tv" -> it.tvResults.firstOrNull()?.id
                    else -> it.movieResults.firstOrNull()?.id ?: it.tvResults.firstOrNull()?.id
                }?.takeIf { id -> id > 0 }?.toString()
            }

            cacheMutex.withLock {
                if (resultId != null) {
                    imdbToTmdbCache[cacheKey] = resultId
                    tmdbToImdbCache["$resultId:$normalizedType"] = imdbId
                } else {
                    log.d { "No TMDB ID found for $imdbId ($normalizedType)" }
                }
                imdbToTmdbInFlight.remove(cacheKey)
                lookup.complete(resultId)
            }
            return resultId
        } catch (cancelled: CancellationException) {
            cacheMutex.withLock {
                imdbToTmdbInFlight.remove(cacheKey)
                // The owner's cancellation should not cancel independent waiters.
                // They receive a cache miss; their own coroutine cancellation still propagates.
                lookup.complete(null)
            }
            throw cancelled
        } catch (error: Exception) {
            log.w(error) { "TMDB ID lookup failed for $imdbId ($normalizedType)" }
            cacheMutex.withLock {
                imdbToTmdbInFlight.remove(cacheKey)
                lookup.complete(null)
            }
            return null
        }
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return try {
            json.decodeFromString<T>(httpGetText(url))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
            null
        }
    }

    internal fun normalizeMediaType(mediaType: String): String =
        when (mediaType.trim().lowercase()) {
            "movie", "film" -> "movie"
            "tv", "series", "show", "tvshow" -> "tv"
            else -> mediaType.trim().lowercase()
        }
}

private val QUERY_COMPONENT_HEX = "0123456789ABCDEF"

private fun encodeTmdbQueryComponent(value: String): String = buildString(value.length) {
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xff
        val unreserved = code in 0x41..0x5a || code in 0x61..0x7a ||
            code in 0x30..0x39 || code == 0x2d || code == 0x2e ||
            code == 0x5f || code == 0x7e
        if (unreserved) {
            append(code.toChar())
        } else {
            append('%')
            append(QUERY_COMPONENT_HEX[code ushr 4])
            append(QUERY_COMPONENT_HEX[code and 0x0f])
        }
    }
}

private fun String.isValidImdbId(): Boolean =
    length >= 7 && startsWith("tt", ignoreCase = true) && drop(2).all { it in '0'..'9' }

internal fun buildTmdbUrl(
    endpoint: String,
    apiKey: String,
    query: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf("api_key" to apiKey)
    query.forEach { (key, value) ->
        if (value.isNotBlank()) {
            params[key] = value
        }
    }
    return buildString {
        append("https://api.themoviedb.org/3/")
        append(endpoint.removePrefix("/"))
        if (params.isNotEmpty()) {
            append("?")
            append(params.entries.joinToString("&") { (key, value) ->
                "${encodeTmdbQueryComponent(key)}=${encodeTmdbQueryComponent(value)}"
            })
        }
    }
}

@Serializable
private data class TmdbFindResponse(
    @SerialName("movie_results") val movieResults: List<TmdbExternalResult> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbExternalResult> = emptyList(),
)

@Serializable
private data class TmdbExternalResult(
    val id: Int,
)

@Serializable
private data class TmdbExternalIdsResponse(
    @SerialName("imdb_id") val imdbId: String? = null,
)
