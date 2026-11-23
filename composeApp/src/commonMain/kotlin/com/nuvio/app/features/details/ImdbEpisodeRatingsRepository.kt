package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ImdbEpisodeRatingsRepository {
    private data class CacheEntry(
        val episodes: List<ExternalEpisodeRating>,
        val expiresAtMs: Long,
    )

    private val log = Logger.withTag("ImdbEpisodeRatingsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, Deferred<List<ExternalEpisodeRating>>>()

    internal suspend fun getEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?,
        addonEpisodes: List<AddonEpisodeAirDate>,
    ): Map<Pair<Int, Int>, Double> =
        alignEpisodeRatings(addonEpisodes, getExternalEpisodes(imdbId, tmdbId))

    private suspend fun getExternalEpisodes(
        imdbId: String?,
        tmdbId: Int?,
    ): List<ExternalEpisodeRating> {
        val normalizedImdbId = normalizeImdbId(imdbId)
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedImdbId == null && normalizedTmdbId == null) return emptyList()

        val cacheKey = normalizedImdbId?.let { "imdb:$it" } ?: "tmdb:$normalizedTmdbId"
        val now = currentTimeMs()
        mutex.withLock {
            cache[cacheKey]?.let { cached ->
                if (cached.expiresAtMs > now) return cached.episodes
                cache.remove(cacheKey)
            }
        }

        val deferred = mutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchEpisodeRatings(
                        imdbId = normalizedImdbId,
                        tmdbId = normalizedTmdbId,
                    ).also { episodes ->
                        mutex.withLock {
                            cache[cacheKey] = CacheEntry(
                                episodes = episodes,
                                expiresAtMs = currentTimeMs() + CACHE_TTL_MS,
                            )
                        }
                    }
                } finally {
                    mutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    fun clearCache() {
        cache.clear()
        inFlight.clear()
    }

    private suspend fun fetchEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?,
    ): List<ExternalEpisodeRating> {
        if (!imdbId.isNullOrBlank()) {
            val primary = toEpisodeRatings(ImdbTapframeApi.getSeasonRatings(imdbId))
            if (primary.any { it.rating != null }) return primary
            log.w { "Primary episode ratings empty for imdbId=$imdbId, trying fallback" }
        }

        if (tmdbId != null) {
            return toEpisodeRatings(SeriesGraphApi.getSeasonRatings(tmdbId))
        }

        return emptyList()
    }

    private fun toEpisodeRatings(payload: List<SeriesGraphSeasonRatingsDto>): List<ExternalEpisodeRating> =
        payload.flatMap { season ->
            season.episodes.orEmpty().mapNotNull { episode ->
                ExternalEpisodeRating(
                    season = episode.seasonNumber ?: return@mapNotNull null,
                    episode = episode.episodeNumber ?: return@mapNotNull null,
                    airDate = episode.airDate,
                    rating = episode.voteAverage?.takeIf { it > 0.0 },
                )
            }
        }

    private fun normalizeImdbId(value: String?): String? =
        value
            ?.trim()
            ?.substringBefore(':')
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }

    private fun currentTimeMs(): Long = LibraryClock.nowEpochMs()

    private const val CACHE_TTL_MS = 30L * 60L * 1000L
}
