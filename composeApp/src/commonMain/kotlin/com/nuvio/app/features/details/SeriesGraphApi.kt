package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal object SeriesGraphApi {
    suspend fun getSeasonRatings(tmdbId: Int): List<SeriesGraphSeasonRatingsDto> =
        requestSeasonRatings(
            baseUrl = ImdbEpisodeRatingsConfig.IMDB_RATINGS_API_BASE_URL,
            showId = tmdbId.toString(),
        )
}

internal object ImdbTapframeApi {
    suspend fun getSeasonRatings(imdbId: String): List<SeriesGraphSeasonRatingsDto> =
        requestSeasonRatings(
            baseUrl = ImdbEpisodeRatingsConfig.IMDB_TAPFRAME_API_BASE_URL,
            showId = imdbId,
        )
}

@Serializable
internal data class SeriesGraphEpisodeRatingDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val name: String? = null,
    val tconst: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
internal data class SeriesGraphSeasonRatingsDto(
    val episodes: List<SeriesGraphEpisodeRatingDto>? = null,
)

private val seriesGraphLog = Logger.withTag("SeriesGraphApi")
private val seriesGraphJson = Json { ignoreUnknownKeys = true }

private suspend fun requestSeasonRatings(
    baseUrl: String,
    showId: String,
): List<SeriesGraphSeasonRatingsDto> {
    val resolvedBaseUrl = baseUrl.trim().trimEnd('/')
    if (resolvedBaseUrl.isBlank()) return emptyList()

    return runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "$resolvedBaseUrl/api/shows/$showId/season-ratings",
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299 || response.body.isBlank()) {
            seriesGraphLog.w { "Season ratings request failed for $showId (${response.status})" }
            return emptyList()
        }
        seriesGraphJson.decodeFromString<List<SeriesGraphSeasonRatingsDto>>(response.body)
    }.onFailure { error ->
        seriesGraphLog.w(error) { "Season ratings request failed for $showId" }
    }.getOrDefault(emptyList())
}
