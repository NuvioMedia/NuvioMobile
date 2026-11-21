package com.nuvio.app.features.usenet

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a title search needs from TMDB: names, year and the series' TVDB id. */
internal data class UsenetTitleMetadata(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val title: String? = null,
    /** Set when it differs from [title]. */
    val originalTitle: String? = null,
    /** A movie's alternative titles and translations; null unless both lists came. */
    val otherTitles: List<String>? = null,
    val year: Int? = null,
)

/** TMDB lookups of the built-in Usenet search. Failures read as unknown, never as errors. */
internal object UsenetMetadata {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureTmdbId(videoId: String, type: String): String? =
        TmdbService.ensureTmdbId(videoId, type)

    suspend fun title(imdbId: String?, tmdbId: String?, videoId: String, series: Boolean): UsenetTitleMetadata {
        val type = if (series) "series" else "movie"
        val id = tmdbId ?: TmdbService.ensureTmdbId(videoId, type)
        val numericId = id?.toIntOrNull()
        val imdb = imdbId ?: numericId?.let { TmdbService.tmdbToImdb(it, type) }
        if (numericId == null) return UsenetTitleMetadata(imdbId = imdb, tmdbId = id)
        if (series) {
            val details = fetch<TvDetails>("tv/$numericId")
            val tvdb = fetch<ExternalIds>("tv/$numericId/external_ids")?.tvdbId?.toString()
            val title = details?.name
            return UsenetTitleMetadata(
                imdbId = imdb, tmdbId = id, tvdbId = tvdb, title = title,
                originalTitle = details?.originalName?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                year = details?.firstAirDate?.take(4)?.toIntOrNull(),
            )
        }
        val movie = fetch<MovieNames>("movie/$numericId", mapOf("append_to_response" to "alternative_titles,translations"))
        val title = movie?.title
        // Unknown unless both lists came: the movie's own titles alone would turn away its foreign releases.
        val others = movie?.let { names ->
            val alternative = names.alternativeTitles?.titles ?: return@let null
            val translated = names.translations?.translations ?: return@let null
            (alternative.mapNotNull { it.title } + translated.mapNotNull { it.data?.title })
                .filter { it.isNotBlank() }.distinct()
        }
        return UsenetTitleMetadata(
            imdbId = imdb, tmdbId = id, title = title,
            originalTitle = movie?.originalTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
            otherTitles = others,
            year = movie?.releaseDate?.take(4)?.toIntOrNull(),
        )
    }

    /** Each season's episode count, for a season pack's size per episode; null when unknown. */
    suspend fun seasonEpisodes(tmdbId: String?, videoId: String): Map<Int, Int>? {
        val id = (tmdbId ?: TmdbService.ensureTmdbId(videoId, "series"))?.toIntOrNull() ?: return null
        return fetch<TvDetails>("tv/$id")?.seasons?.mapNotNull { season ->
            val number = season.seasonNumber ?: return@mapNotNull null
            season.episodeCount?.takeIf { it > 0 }?.let { number to it }
        }?.toMap()?.takeIf { it.isNotEmpty() }
    }

    private suspend inline fun <reified T> fetch(endpoint: String, query: Map<String, String> = emptyMap()): T? = try {
        val url = buildTmdbUrl(endpoint, TmdbSettingsRepository.effectiveApiKey(), query)
        json.decodeFromString<T>(httpGetText(url))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    @Serializable
    private data class TvDetails(
        val name: String? = null,
        @SerialName("original_name") val originalName: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val seasons: List<Season>? = null,
    )

    @Serializable
    private data class Season(
        @SerialName("season_number") val seasonNumber: Int? = null,
        @SerialName("episode_count") val episodeCount: Int? = null,
    )

    @Serializable
    private data class ExternalIds(@SerialName("tvdb_id") val tvdbId: Int? = null)

    @Serializable
    private data class MovieNames(
        val title: String? = null,
        @SerialName("original_title") val originalTitle: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("alternative_titles") val alternativeTitles: AlternativeTitles? = null,
        val translations: Translations? = null,
    )

    @Serializable
    private data class AlternativeTitles(val titles: List<NamedTitle>? = null)

    @Serializable
    private data class NamedTitle(val title: String? = null)

    @Serializable
    private data class Translations(val translations: List<Translation>? = null)

    @Serializable
    private data class Translation(val data: NamedTitle? = null)
}
