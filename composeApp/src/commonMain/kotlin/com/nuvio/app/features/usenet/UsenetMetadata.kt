package com.nuvio.app.features.usenet

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** The year a series last aired; release names give its years. */
    val lastYear: Int? = null,
    /** A series' origin countries (US, GB), which some release names give, as in The.Office.US. */
    val countries: Set<String> = emptySet(),
    /** A series' vote count, which another series of its name is weighed against. */
    val votes: Int? = null,
    /** The series TMDB finds by a series' title, itself among them; null when TMDB did not answer. */
    val sameTitleSeries: List<UsenetTitledSeries>? = null,
)

/** A series TMDB found by a title: its id, names, first air year, origin countries and vote count. */
internal data class UsenetTitledSeries(
    val tmdbId: String,
    val name: String? = null,
    val originalName: String? = null,
    val year: Int? = null,
    val countries: Set<String> = emptySet(),
    val votes: Int = 0,
)

/**
 * A series' TMDB id and details, read once per search: its metadata and its seasons' episode
 * counts both come from them. The details are empty when TMDB did not give them.
 */
internal data class UsenetSeriesDetails(
    val tmdbId: String,
    val name: String? = null,
    val originalName: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val countries: Set<String> = emptySet(),
    val votes: Int? = null,
    val seasons: List<UsenetSeasonSummary> = emptyList(),
) {
    /** Each season's episode count, for a season pack's size per episode; null when unknown. */
    val episodeCounts: Map<Int, Int>?
        get() = seasons.mapNotNull { season -> season.episodes?.takeIf { it > 0 }?.let { season.number to it } }
            .toMap().takeIf { it.isNotEmpty() }

    /** The year [season] first aired; null when unknown. */
    fun seasonYear(season: Int?): Int? = seasons.firstOrNull { it.number == season }?.airDate?.take(4)?.toIntOrNull()
}

internal data class UsenetSeasonSummary(val number: Int, val episodes: Int? = null, val airDate: String? = null)

/** TMDB lookups of the built-in Usenet search. Failures read as unknown, never as errors. */
internal object UsenetMetadata {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureTmdbId(videoId: String, type: String): String? =
        TmdbService.ensureTmdbId(videoId, type)

    /** A series' TMDB id and details; null when its TMDB id could not be looked up. */
    suspend fun series(tmdbId: String?, videoId: String): UsenetSeriesDetails? {
        val id = tmdbId ?: TmdbService.ensureTmdbId(videoId, "series") ?: return null
        val details = id.toIntOrNull()?.let { fetch<TvDetails>("tv/$it") } ?: return UsenetSeriesDetails(id)
        return UsenetSeriesDetails(
            tmdbId = id,
            name = details.name,
            originalName = details.originalName,
            firstAirDate = details.firstAirDate,
            lastAirDate = details.lastAirDate,
            countries = details.originCountry.orEmpty().toSet(),
            votes = details.voteCount,
            seasons = details.seasons.orEmpty().mapNotNull { season ->
                season.seasonNumber?.let { UsenetSeasonSummary(it, season.episodeCount, season.airDate) }
            },
        )
    }

    /** [details], a series', give its names and years; a movie's are looked up here. */
    suspend fun title(
        imdbId: String?,
        tmdbId: String?,
        videoId: String,
        series: Boolean,
        details: UsenetSeriesDetails? = null,
    ): UsenetTitleMetadata {
        val type = if (series) "series" else "movie"
        if (series) {
            val lookup = details ?: error("TMDB id unavailable")
            val numericId = lookup.tmdbId.toIntOrNull()
            val imdb = imdbId ?: numericId?.let { TmdbService.tmdbToImdb(it, type) }
            if (numericId == null) return UsenetTitleMetadata(imdbId = imdb, tmdbId = lookup.tmdbId)
            val title = lookup.name
            return coroutineScope {
                // Other series of its name, asked for alongside its TVDB id.
                val sameTitle = title?.let { async { seriesTitled(it) } }
                val tvdb = fetch<ExternalIds>("tv/$numericId/external_ids")?.tvdbId?.toString()
                UsenetTitleMetadata(
                    imdbId = imdb, tmdbId = lookup.tmdbId, tvdbId = tvdb, title = title,
                    originalTitle = lookup.originalName?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                    year = lookup.firstAirDate?.take(4)?.toIntOrNull(),
                    lastYear = lookup.lastAirDate?.take(4)?.toIntOrNull(),
                    countries = lookup.countries,
                    votes = lookup.votes,
                    sameTitleSeries = sameTitle?.await(),
                )
            }
        }
        val id = tmdbId ?: TmdbService.ensureTmdbId(videoId, type)
        val numericId = id?.toIntOrNull()
        val imdb = imdbId ?: numericId?.let { TmdbService.tmdbToImdb(it, type) }
        if (numericId == null) return UsenetTitleMetadata(imdbId = imdb, tmdbId = id)
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

    /** The series TMDB's TV search finds by [title]; null when it did not answer. */
    private suspend fun seriesTitled(title: String): List<UsenetTitledSeries>? =
        fetch<TvSearch>("search/tv", mapOf("query" to title))?.results?.map {
            UsenetTitledSeries(
                tmdbId = it.id.toString(),
                name = it.name,
                originalName = it.originalName,
                year = it.firstAirDate?.take(4)?.toIntOrNull(),
                countries = it.originCountry.orEmpty().toSet(),
                votes = it.voteCount ?: 0,
            )
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
        @SerialName("last_air_date") val lastAirDate: String? = null,
        @SerialName("origin_country") val originCountry: List<String>? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
        val seasons: List<Season>? = null,
    )

    @Serializable
    private data class Season(
        @SerialName("season_number") val seasonNumber: Int? = null,
        @SerialName("episode_count") val episodeCount: Int? = null,
        @SerialName("air_date") val airDate: String? = null,
    )

    @Serializable
    private data class TvSearch(val results: List<TvSearchResult>? = null)

    @Serializable
    private data class TvSearchResult(
        val id: Int,
        val name: String? = null,
        @SerialName("original_name") val originalName: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        @SerialName("origin_country") val originCountry: List<String>? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
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
