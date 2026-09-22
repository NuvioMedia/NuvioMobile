package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.simkl.SIMKL_API_BASE_URL
import com.nuvio.app.features.simkl.SimklConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal object SimklIdResolver {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ResolvedIds(
        val simklId: Long,
        val type: String,
        val mal: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
        val tvdbSeason: Int? = null
    )

    data class EpisodeMapping(
        val animeEpisode: Int,
        val tvdbSeason: Int,
        val tvdbEpisode: Int
    )

    private val idsCache = HashMap<String, ResolvedIds?>()
    private val episodeCache = HashMap<Long, List<EpisodeMapping>>()
    private val animeSeasonCache = HashMap<String, List<AnimeSeasonEntry>>()

    private fun commonParams(): String {
        val clientId = SimklConfig.CLIENT_ID
        val appName = SimklConfig.APP_NAME
        return "client_id=$clientId&app-name=$appName&app-version=1.0"
    }

    suspend fun resolveIds(source: String, id: String): ResolvedIds? {
        val cacheKey = "$source:$id"
        idsCache[cacheKey]?.let { return it }
        if (SimklConfig.CLIENT_ID.isBlank()) return null

        return try {
            val searchText = httpGetText("$SIMKL_API_BASE_URL/search/id?$source=$id&${commonParams()}")
            val results = json.parseToJsonElement(searchText).jsonArray
            if (results.isEmpty()) return null
            val simklId = results[0].jsonObject["ids"]?.jsonObject?.get("simkl")?.jsonPrimitive?.long ?: return null

            val type = results[0].jsonObject["type"]?.jsonPrimitive?.content ?: "anime"
            val mediaType = when (type) {
                "movie" -> "movies"
                "show" -> "tv"
                else -> "anime"
            }

            val detailsText = httpGetText("$SIMKL_API_BASE_URL/$mediaType/$simklId?extended=full&${commonParams()}")
            val details = json.parseToJsonElement(detailsText).jsonObject
            val ids = details["ids"]?.jsonObject

            ResolvedIds(
                simklId = simklId,
                type = mediaType,
                mal = ids?.get("mal")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                anilist = ids?.get("anilist")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                kitsu = ids?.get("kitsu")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                imdb = ids?.get("imdb")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                tvdbSeason = details["season"]?.jsonPrimitive?.int?.takeIf { it > 0 }
            ).also { idsCache[cacheKey] = it }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getEpisodeMapping(simklId: Long, type: String = "anime"): List<EpisodeMapping> {
        episodeCache[simklId]?.let { return it }
        if (SimklConfig.CLIENT_ID.isBlank()) return emptyList()

        return try {
            val text = httpGetText("$SIMKL_API_BASE_URL/$type/episodes/$simklId?${commonParams()}")
            val episodes = json.parseToJsonElement(text).jsonArray
            val mapping = mutableListOf<EpisodeMapping>()
            for (ep in episodes) {
                val obj = ep.jsonObject
                val epNum = obj["episode"]?.jsonPrimitive?.int ?: continue
                val tvdb = obj["tvdb"]?.jsonObject ?: continue
                val tvdbSeason = tvdb["season"]?.jsonPrimitive?.int ?: continue
                val tvdbEpisode = tvdb["episode"]?.jsonPrimitive?.int ?: continue
                if (epNum > 0 && tvdbSeason > 0 && tvdbEpisode > 0) {
                    mapping.add(EpisodeMapping(epNum, tvdbSeason, tvdbEpisode))
                }
            }
            mapping.also { episodeCache[simklId] = it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolveEpisodeTvdb(source: String, id: String, episode: Int): Pair<Int, Int>? {
        val ids = resolveIds(source, id) ?: return null
        val entry = getEpisodeMapping(ids.simklId, ids.type).firstOrNull { it.animeEpisode == episode }
        return entry?.let { it.tvdbSeason to it.tvdbEpisode }
    }

    /**
     * Given an IMDB ID and a TVDB-style season+episode, resolve the anime-specific IDs
     * (MAL, AniList, Kitsu) for the correct season entry.
     */
    suspend fun resolveIdsForImdbEpisode(
        imdbId: String,
        season: Int,
        episode: Int
    ): ResolvedIds? {
        val base = resolveIds("imdb", imdbId) ?: return null
        if (base.type != "anime") return base

        // If the parent entry already owns this season, no sibling lookup needed.
        val baseSeason = base.tvdbSeason
        if (baseSeason != null && baseSeason == season) return base

        // Fetch the parent with full_anime_seasons to discover per-season entries.
        val seasonSimklId = resolveSeasonSimklId(base.simklId, base.type, season)
        if (seasonSimklId != null && seasonSimklId != base.simklId) {
            val siblingIds = resolveIdsBySimklId(seasonSimklId, base.type)
            if (siblingIds != null) return siblingIds
        }

        return base
    }

    private suspend fun resolveSeasonSimklId(parentSimklId: Long, type: String, tvdbSeason: Int): Long? {
        val cacheKey = "anime_seasons:$parentSimklId"
        animeSeasonCache[cacheKey]?.let { seasons ->
            return seasons.firstOrNull { it.tvdbSeason == tvdbSeason }?.simklId
        }

        return try {
            val text = httpGetText(
                "$SIMKL_API_BASE_URL/$type/$parentSimklId?extended=full_anime_seasons&${commonParams()}"
            )
            val details = json.parseToJsonElement(text).jsonObject
            val seasonsArray = details["mapped_tvdb_seasons"]?.jsonArray ?: return null

            val seasons = mutableListOf<AnimeSeasonEntry>()
            for (entry in seasonsArray) {
                val obj = entry.jsonObject
                val simklId = obj["simkl_id"]?.jsonPrimitive?.long?.takeIf { it > 0 } ?: continue
                val mappedSeason = obj["tvdb_season"]?.jsonPrimitive?.int?.takeIf { it > 0 } ?: continue
                seasons.add(AnimeSeasonEntry(simklId, mappedSeason))
            }
            animeSeasonCache[cacheKey] = seasons
            seasons.firstOrNull { it.tvdbSeason == tvdbSeason }?.simklId
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveIdsBySimklId(simklId: Long, type: String): ResolvedIds? {
        val cacheKey = "simkl:$simklId"
        idsCache[cacheKey]?.let { return it }

        return try {
            val text = httpGetText("$SIMKL_API_BASE_URL/$type/$simklId?extended=full&${commonParams()}")
            val details = json.parseToJsonElement(text).jsonObject
            val ids = details["ids"]?.jsonObject

            ResolvedIds(
                simklId = simklId,
                type = type,
                mal = ids?.get("mal")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                anilist = ids?.get("anilist")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                kitsu = ids?.get("kitsu")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                imdb = ids?.get("imdb")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                tvdbSeason = details["season"]?.jsonPrimitive?.int?.takeIf { it > 0 }
            ).also { idsCache[cacheKey] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() {
        idsCache.clear()
        episodeCache.clear()
        animeSeasonCache.clear()
    }
}

private data class AnimeSeasonEntry(val simklId: Long, val tvdbSeason: Int)
