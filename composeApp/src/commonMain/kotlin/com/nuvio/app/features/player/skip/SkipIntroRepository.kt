package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object SkipIntroRepository {

    private val cache = HashMap<String, List<SkipInterval>>()
    private val animeSkipShowIdCache = HashMap<String, String>()
    private const val NO_ID = "__none__"

    private val introDbConfigured: Boolean
        get() = IntroDbConfig.URL.isNotBlank()

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
    ): List<SkipInterval> = coroutineScope {
        if (imdbId == null) return@coroutineScope emptyList()
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
        }
        // Resolve IMDB -> season-specific MAL/AniList via Simkl full_anime_seasons
        val simklIdsDeferred = async { SimklIdResolver.resolveIdsForImdbEpisode(imdbId, season, episode) }
        val simklIds = simklIdsDeferred.await()
        val malId = simklIds?.mal
        val anilistId = simklIds?.anilist

        // Remap the TVDB episode number to the anime-entry-local episode number.
        val animeEpisode = if (simklIds != null) {
            val mapping = SimklIdResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        return@coroutineScope mergeByPriority(
            introDbDeferred.await(),
            animeSkipDeferred.await(),
            aniSkipDeferred.await(),
        ).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode) }

        val simklIdsDeferred = async { SimklIdResolver.resolveIds("mal", malId) }
        val simklIds = simklIdsDeferred.await()
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                SimklIdResolver.resolveEpisodeTvdb("mal", malId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = introDbDeferred.await()
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val simklIdsDeferred = async { SimklIdResolver.resolveIds("kitsu", kitsuId) }
        val simklIds = simklIdsDeferred.await()
        val malIdStr = simklIds?.mal
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val aniSkipDeferred = async {
            if (malIdStr != null) fetchFromAniSkip(malIdStr, episode) else emptyList()
        }

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                SimklIdResolver.resolveEpisodeTvdb("kitsu", kitsuId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = introDbDeferred.await()
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                if (category !in chosen) chosen[category] = interval
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending" -> "ending"
        "recap" -> "recap"
        else -> null
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val data = SkipIntroApi.getIntroDbSegments(imdbId, season, episode)
            if (data == null) return emptyList()
            listOfNotNull(
                data.intro.toSkipIntervalOrNull("intro"),
                data.recap.toSkipIntervalOrNull("recap"),
                data.outro.toSkipIntervalOrNull("outro"),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val response = SkipIntroApi.getAniSkipTimes(malId, episode)
            if (response == null) return emptyList()
            if (!response.found) return emptyList()
            response.results?.map { result ->
                SkipInterval(
                    startTime = result.interval.startTime,
                    endTime = result.interval.endTime,
                    type = result.skipType,
                    provider = "aniskip",
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        val clientId = settings.animeSkipClientId.trim()
        if (clientId.isBlank()) return emptyList()
        if (!settings.animeSkipEnabled) return emptyList()

        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                val response = SkipIntroApi.queryAnimeSkip(clientId, query) ?: continue
                val episodes = response.data?.findEpisodesByShowId ?: continue

                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits" -> "ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
        val showIds = try {
            SkipIntroApi.queryAnimeSkip(clientId, query)
                ?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    suspend fun submitIntro(
        imdbId: String,
        season: Int,
        episode: Int,
        startSec: Double,
        endSec: Double,
        segmentType: String,
    ): Boolean {
        val settings = PlayerSettingsRepository.uiState.value
        val apiKey = settings.introDbApiKey.trim()
        if (!settings.introSubmitEnabled || apiKey.isBlank()) return false

        val request = SubmitIntroRequest(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            startMs = (startSec * 1000).toLong(),
            endMs = (endSec * 1000).toLong(),
            segmentType = segmentType,
        )

        return SkipIntroApi.submitIntro(apiKey, request)
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        return SkipIntroApi.verifyIntroDbApiKey(apiKey)
    }

    fun clearCache() {
        cache.clear()
        animeSkipShowIdCache.clear()
        SimklIdResolver.clearCache()
    }
}
