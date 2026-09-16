package com.nuvio.app.features.collection

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.tmdb.normalizeTmdbLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_editor_tmdb_discover
import nuvio.composeapp.generated.resources.collections_tmdb_collection_not_found
import nuvio.composeapp.generated.resources.collections_tmdb_company_not_found
import nuvio.composeapp.generated.resources.collections_tmdb_discover_no_data
import nuvio.composeapp.generated.resources.collections_tmdb_list_not_found
import nuvio.composeapp.generated.resources.collections_tmdb_missing_collection_id
import nuvio.composeapp.generated.resources.collections_tmdb_missing_list_id
import nuvio.composeapp.generated.resources.collections_tmdb_missing_person_id
import nuvio.composeapp.generated.resources.collections_tmdb_network_not_found
import nuvio.composeapp.generated.resources.collections_tmdb_person_credits_not_found
import nuvio.composeapp.generated.resources.collections_tmdb_person_not_found
import org.jetbrains.compose.resources.getString
import kotlin.math.roundToInt

object TmdbCollectionSourceResolver {
    private val log = Logger.withTag("TmdbCollectionSource")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(source: CollectionSource, page: Int = 1): CatalogPage = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val language = normalizeTmdbLanguage(settings.language)
        val sourceType = source.tmdbType()

        when (sourceType) {
            TmdbCollectionSourceType.LIST -> resolveList(source, apiKey, language, page)
            TmdbCollectionSourceType.COLLECTION -> resolveCollection(source, apiKey, language)
            TmdbCollectionSourceType.PERSON,
            TmdbCollectionSourceType.DIRECTOR -> resolvePersonCredits(source, apiKey, language)
            TmdbCollectionSourceType.COMPANY,
            TmdbCollectionSourceType.NETWORK,
            TmdbCollectionSourceType.DISCOVER -> resolveDiscover(source, apiKey, language, page)
        }
    }

    suspend fun importMetadata(sourceType: TmdbCollectionSourceType, id: Int): TmdbSourceImportMetadata =
        withContext(Dispatchers.Default) {
            val settings = TmdbSettingsRepository.snapshot()
            val apiKey = TmdbSettingsRepository.effectiveApiKey()
            val language = normalizeTmdbLanguage(settings.language)
            when (sourceType) {
                TmdbCollectionSourceType.LIST -> {
                    val body = fetch<TmdbListResponse>(
                        endpoint = "list/$id",
                        apiKey = apiKey,
                        query = mapOf("language" to language, "page" to "1"),
                    ) ?: error(getString(Res.string.collections_tmdb_list_not_found))
                    TmdbSourceImportMetadata(title = body.name?.takeIf { it.isNotBlank() })
                }

                TmdbCollectionSourceType.COLLECTION -> {
                    val body = fetch<TmdbCollectionResponse>(
                        endpoint = "collection/$id",
                        apiKey = apiKey,
                        query = mapOf("language" to language),
                    ) ?: error(getString(Res.string.collections_tmdb_collection_not_found))
                    TmdbSourceImportMetadata(
                        title = body.name?.takeIf { it.isNotBlank() },
                        coverImageUrl = imageUrl(body.posterPath, "w500") ?: imageUrl(body.backdropPath, "w1280"),
                    )
                }

                TmdbCollectionSourceType.COMPANY -> {
                    val body = fetch<TmdbCompanyResponse>(
                        endpoint = "company/$id",
                        apiKey = apiKey,
                    ) ?: error(getString(Res.string.collections_tmdb_company_not_found))
                    TmdbSourceImportMetadata(
                        title = body.name?.takeIf { it.isNotBlank() },
                        coverImageUrl = imageUrl(body.logoPath, "w500"),
                    )
                }

                TmdbCollectionSourceType.NETWORK -> {
                    val body = fetch<TmdbNetworkResponse>(
                        endpoint = "network/$id",
                        apiKey = apiKey,
                    ) ?: error(getString(Res.string.collections_tmdb_network_not_found))
                    TmdbSourceImportMetadata(
                        title = body.name?.takeIf { it.isNotBlank() },
                        coverImageUrl = imageUrl(body.logoPath, "w500"),
                    )
                }

                TmdbCollectionSourceType.PERSON,
                TmdbCollectionSourceType.DIRECTOR -> {
                    val body = fetch<TmdbPersonResponse>(
                        endpoint = "person/$id",
                        apiKey = apiKey,
                        query = mapOf("language" to language),
                    ) ?: error(getString(Res.string.collections_tmdb_person_not_found))
                    TmdbSourceImportMetadata(
                        title = body.name?.takeIf { it.isNotBlank() },
                        coverImageUrl = imageUrl(body.profilePath, "w500"),
                    )
                }

                TmdbCollectionSourceType.DISCOVER -> TmdbSourceImportMetadata(title = getString(Res.string.collections_editor_tmdb_discover))
            }
        }

    suspend fun searchCompanies(query: String): List<TmdbCompanySearchResult> = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        fetch<TmdbCompanySearchResponse>(
            endpoint = "search/company",
            apiKey = apiKey,
            query = mapOf("query" to trimmed),
        )?.results.orEmpty()
    }

    suspend fun searchCollections(query: String): List<TmdbCollectionSearchResult> = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        val settings = TmdbSettingsRepository.snapshot()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val language = normalizeTmdbLanguage(settings.language)
        fetch<TmdbCollectionSearchResponse>(
            endpoint = "search/collection",
            apiKey = apiKey,
            query = mapOf("query" to trimmed, "language" to language),
        )?.results.orEmpty()
    }

    suspend fun searchKeywords(query: String): Map<Int, String> = withContext(Dispatchers.Default) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyMap()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        fetch<TmdbKeywordSearchResponse>(
            endpoint = "search/keyword",
            apiKey = apiKey,
            query = mapOf("query" to trimmed),
        )?.results.orEmpty()
            .mapNotNull { result ->
                val name = result.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                result.id to name
            }
            .toMap()
    }

    suspend fun genres(mediaType: TmdbCollectionMediaType): Map<Int, String> = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val language = normalizeTmdbLanguage(settings.language)
        val endpoint = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> "genre/movie/list"
            TmdbCollectionMediaType.TV -> "genre/tv/list"
        }
        fetch<TmdbGenreResponse>(
            endpoint = endpoint,
            apiKey = apiKey,
            query = mapOf("language" to language),
        )?.genres.orEmpty().associate { it.id to it.name }
    }

    fun parseTmdbId(input: String): Int? {
        val trimmed = input.trim()
        trimmed.toIntOrNull()?.let { return it }
        return Regex("""(?:list|collection|company|network|person)/(\d+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]id=(\d+)""")
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    fun presets(): List<TmdbPresetSource> = listOf(
        TmdbPresetSource("Marvel Studios", company("Marvel Studios", 420)),
        TmdbPresetSource("Walt Disney Pictures", company("Walt Disney Pictures", 2)),
        TmdbPresetSource("Pixar", company("Pixar", 3)),
        TmdbPresetSource("Lucasfilm", company("Lucasfilm", 1)),
        TmdbPresetSource("Warner Bros.", company("Warner Bros.", 174)),
        TmdbPresetSource("Netflix", network("Netflix", 213)),
        TmdbPresetSource("HBO", network("HBO", 49)),
        TmdbPresetSource("Disney+", network("Disney+", 2739)),
        TmdbPresetSource("Prime Video", network("Prime Video", 1024)),
        TmdbPresetSource("Hulu", network("Hulu", 453)),
        TmdbPresetSource("Apple TV+", network("Apple TV+", 2552)),
    )

    private suspend fun resolveList(
        source: CollectionSource,
        apiKey: String,
        language: String,
        page: Int,
    ): CatalogPage {
        val id = source.tmdbId ?: error(getString(Res.string.collections_tmdb_missing_list_id))
        val body = fetch<TmdbListResponse>(
            endpoint = "list/$id",
            apiKey = apiKey,
            query = mapOf("language" to language, "page" to page.toString()),
        ) ?: error(getString(Res.string.collections_tmdb_list_not_found))
        val items = body.items.orEmpty()
            .mapNotNull { it.toPreview() }
            .sortedFor(source.sortBy)
            .distinctBy { "${it.type}:${it.id}" }
        return CatalogPage(
            items = items,
            rawItemCount = items.size,
            nextSkip = if ((body.page ?: page) < (body.totalPages ?: page) && items.isNotEmpty()) page + 1 else null,
        )
    }

    private suspend fun resolveCollection(
        source: CollectionSource,
        apiKey: String,
        language: String,
    ): CatalogPage {
        val id = source.tmdbId ?: error(getString(Res.string.collections_tmdb_missing_collection_id))
        val body = fetch<TmdbCollectionResponse>(
            endpoint = "collection/$id",
            apiKey = apiKey,
            query = mapOf("language" to language),
        ) ?: error(getString(Res.string.collections_tmdb_collection_not_found))
        val items = body.parts.orEmpty()
            .mapNotNull { it.toPreview(TmdbCollectionMediaType.MOVIE) }
            .sortedFor(source.sortBy)
            .distinctBy { it.id }
        return CatalogPage(items = items, rawItemCount = items.size, nextSkip = null)
    }

    private suspend fun resolvePersonCredits(
        source: CollectionSource,
        apiKey: String,
        language: String,
    ): CatalogPage {
        val id = source.tmdbId ?: error(getString(Res.string.collections_tmdb_missing_person_id))
        val mediaType = source.tmdbMediaType()
        val body = fetch<TmdbPersonCreditsResponse>(
            endpoint = "person/$id/combined_credits",
            apiKey = apiKey,
            query = mapOf("language" to language),
        ) ?: error(getString(Res.string.collections_tmdb_person_credits_not_found))
        val items = when (source.tmdbType()) {
            TmdbCollectionSourceType.DIRECTOR -> body.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .mapNotNull { it.toPreview(mediaType) }
            else -> body.cast.orEmpty().mapNotNull { it.toPreview(mediaType) }
        }
            .distinctBy { "${it.type}:${it.id}" }
            .sortedFor(source.sortBy)
        return CatalogPage(items = items, rawItemCount = items.size, nextSkip = null)
    }

    private suspend fun resolveDiscover(
        source: CollectionSource,
        apiKey: String,
        language: String,
        page: Int,
    ): CatalogPage {
        val sourceType = source.tmdbType()
        val mediaType = if (sourceType == TmdbCollectionSourceType.NETWORK) {
            TmdbCollectionMediaType.TV
        } else {
            source.tmdbMediaType()
        }
        val filters = source.filters ?: TmdbCollectionFilters()
        val query = buildDiscoverQuery(
            source = source,
            sourceType = sourceType,
            mediaType = mediaType,
            language = language,
            page = page,
            filters = filters,
        )
        val endpoint = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> "discover/movie"
            TmdbCollectionMediaType.TV -> "discover/tv"
        }
        val body = fetch<TmdbDiscoverResponse>(
            endpoint = endpoint,
            apiKey = apiKey,
            query = query,
        ) ?: error(getString(Res.string.collections_tmdb_discover_no_data))
        val items = body.results.orEmpty()
            .mapNotNull { it.toPreview(mediaType) }
            .distinctBy { it.id }
        return CatalogPage(
            items = items,
            rawItemCount = items.size,
            nextSkip = if ((body.page ?: page) < (body.totalPages ?: page) && items.isNotEmpty()) page + 1 else null,
        )
    }

    internal fun buildDiscoverQuery(
        source: CollectionSource,
        sourceType: TmdbCollectionSourceType,
        mediaType: TmdbCollectionMediaType,
        language: String,
        page: Int,
        filters: TmdbCollectionFilters,
    ): Map<String, String> {
        val sortBy = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> movieSort(source.sortBy)
            TmdbCollectionMediaType.TV -> tvSort(source.sortBy)
        }
        return buildMap {
            put("language", language)
            put("page", page.toString())
            put("sort_by", sortBy)
            val companyId = source.tmdbId?.toString().takeIf { sourceType == TmdbCollectionSourceType.COMPANY }
            val networkId = source.tmdbId?.toString().takeIf { sourceType == TmdbCollectionSourceType.NETWORK }
            putIfNotBlank("with_companies", companyId ?: filters.withCompanies)
            putIfNotBlank("without_companies", filters.withoutCompanies)
            putIfNotBlank("with_networks", networkId ?: filters.withNetworks)
            putIfNotBlank("with_genres", filters.withGenres)
            putIfNotBlank("without_genres", filters.withoutGenres)
            putIfNotBlank("vote_count.gte", filters.voteCountGte?.toString())
            putIfNotBlank("vote_average.gte", filters.voteAverageGte?.toString())
            putIfNotBlank("vote_average.lte", filters.voteAverageLte?.toString())
            putIfNotBlank("with_original_language", filters.withOriginalLanguage)
            putIfNotBlank("with_origin_country", filters.withOriginCountry)
            putIfNotBlank("with_keywords", filters.withKeywords)
            putIfNotBlank("without_keywords", filters.withoutKeywords)
            val withWatchProviders = filters.withWatchProviders?.takeIf { it.isNotBlank() }
            val withoutWatchProviders = filters.withoutWatchProviders?.takeIf { it.isNotBlank() }
            if (withWatchProviders != null || withoutWatchProviders != null) {
                putIfNotBlank("with_watch_providers", withWatchProviders)
                putIfNotBlank("without_watch_providers", withoutWatchProviders)
                put("watch_region", filters.watchRegion?.takeIf { it.isNotBlank() } ?: "US")
                if (withWatchProviders != null) {
                    put("with_watch_monetization_types", "flatrate|free|ads|rent|buy")
                }
            }
            putIfNotBlank("year", filters.year?.takeIf { mediaType == TmdbCollectionMediaType.MOVIE }?.toString())
            putIfNotBlank("first_air_date_year", filters.year?.takeIf { mediaType == TmdbCollectionMediaType.TV }?.toString())
            putIfNotBlank(
                if (mediaType == TmdbCollectionMediaType.MOVIE) "primary_release_date.gte" else "first_air_date.gte",
                filters.releaseDateGte,
            )
            putIfNotBlank(
                if (mediaType == TmdbCollectionMediaType.MOVIE) "primary_release_date.lte" else "first_air_date.lte",
                filters.releaseDateLte,
            )
        }
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
        }.onFailure { error ->
            log.w(error) { "TMDB source request failed for $endpoint" }
        }.getOrNull()
    }

    private fun List<MetaPreview>.sortedFor(sortBy: String?): List<MetaPreview> =
        when (sortBy) {
            TmdbCollectionSort.ORIGINAL.value -> this
            TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.imdbRating?.toDoubleOrNull() ?: -1.0 }
                    .thenByDescending { it.rawReleaseDate ?: it.releaseInfo.orEmpty() },
            )
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> sortedByDescending { it.voteCount ?: 0 }
            TmdbCollectionSort.RELEASE_DATE_DESC.value,
            TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> sortedByDescending { it.rawReleaseDate ?: it.releaseInfo.orEmpty() }
            TmdbCollectionSort.POPULAR_DESC.value,
            null,
            "" -> this
            else -> this
        }

    private fun TmdbListItem.toPreview(): MetaPreview? {
        val media = mediaType?.lowercase()
        val contentType = if (media == "tv") TmdbCollectionMediaType.TV else TmdbCollectionMediaType.MOVIE
        return toPreview(contentType)
    }

    private fun TmdbListItem.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie",
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            banner = imageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate?.take(4)
                TmdbCollectionMediaType.TV -> firstAirDate?.take(4)
            },
            rawReleaseDate = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate
                TmdbCollectionMediaType.TV -> firstAirDate
            },
            popularity = popularity,
            voteCount = voteCount,
            imdbRating = voteAverage?.let { ((it * 10).roundToInt() / 10.0).toString() },
        )
    }

    private fun TmdbCollectionPart.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie",
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            banner = imageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseDate?.take(4),
            rawReleaseDate = releaseDate,
            popularity = popularity,
            voteCount = voteCount,
            imdbRating = voteAverage?.let { ((it * 10).roundToInt() / 10.0).toString() },
        )
    }

    private fun TmdbPersonCreditCast.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        if (!matchesMediaType(mediaType, this.mediaType)) return null
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie",
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            banner = imageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate?.take(4)
                TmdbCollectionMediaType.TV -> firstAirDate?.take(4)
            },
            rawReleaseDate = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate
                TmdbCollectionMediaType.TV -> firstAirDate
            },
            popularity = popularity,
            voteCount = voteCount,
            imdbRating = voteAverage?.let { ((it * 10).roundToInt() / 10.0).toString() },
        )
    }

    private fun TmdbPersonCreditCrew.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        if (!matchesMediaType(mediaType, this.mediaType)) return null
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie",
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            banner = imageUrl(backdropPath, "w1280"),
            posterShape = PosterShape.Poster,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate?.take(4)
                TmdbCollectionMediaType.TV -> firstAirDate?.take(4)
            },
            rawReleaseDate = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate
                TmdbCollectionMediaType.TV -> firstAirDate
            },
            popularity = popularity,
            voteCount = voteCount,
            imdbRating = voteAverage?.let { ((it * 10).roundToInt() / 10.0).toString() },
        )
    }

    private fun CollectionSource.tmdbType(): TmdbCollectionSourceType =
        tmdbSourceType
            ?.let { raw -> runCatching { TmdbCollectionSourceType.valueOf(raw.uppercase()) }.getOrNull() }
            ?: TmdbCollectionSourceType.DISCOVER

    private fun CollectionSource.tmdbMediaType(): TmdbCollectionMediaType =
        TmdbCollectionMediaType.fromString(mediaType)

    private fun matchesMediaType(expected: TmdbCollectionMediaType, actual: String?): Boolean =
        when (expected) {
            TmdbCollectionMediaType.MOVIE -> actual == "movie"
            TmdbCollectionMediaType.TV -> actual == "tv"
        }

    private fun company(title: String, id: Int) = CollectionSource(
        provider = "tmdb",
        tmdbSourceType = TmdbCollectionSourceType.COMPANY.name,
        title = title,
        tmdbId = id,
        mediaType = TmdbCollectionMediaType.MOVIE.name,
        sortBy = TmdbCollectionSort.POPULAR_DESC.value,
    )

    private fun network(title: String, id: Int) = CollectionSource(
        provider = "tmdb",
        tmdbSourceType = TmdbCollectionSourceType.NETWORK.name,
        title = title,
        tmdbId = id,
        mediaType = TmdbCollectionMediaType.TV.name,
        sortBy = TmdbCollectionSort.POPULAR_DESC.value,
    )

    private fun movieSort(sortBy: String?): String =
        when (sortBy) {
            TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> TmdbCollectionSort.RELEASE_DATE_DESC.value
            TmdbCollectionSort.ORIGINAL.value -> TmdbCollectionSort.POPULAR_DESC.value
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> TmdbCollectionSort.VOTE_COUNT_DESC.value
            null, "" -> TmdbCollectionSort.POPULAR_DESC.value
            else -> sortBy
        }

    private fun tvSort(sortBy: String?): String =
        when (sortBy) {
            TmdbCollectionSort.RELEASE_DATE_DESC.value -> TmdbCollectionSort.FIRST_AIR_DATE_DESC.value
            TmdbCollectionSort.ORIGINAL.value -> TmdbCollectionSort.POPULAR_DESC.value
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> TmdbCollectionSort.VOTE_COUNT_DESC.value
            null, "" -> TmdbCollectionSort.POPULAR_DESC.value
            else -> sortBy
        }
}

private fun MutableMap<String, String>.putIfNotBlank(key: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(key, value)
    }
}

private fun imageUrl(path: String?, size: String): String? {
    val clean = path?.takeIf { it.isNotBlank() } ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}

@Serializable
private data class TmdbListResponse(
    val name: String? = null,
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    val items: List<TmdbListItem>? = null,
)

@Serializable
private data class TmdbCollectionResponse(
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val parts: List<TmdbCollectionPart>? = null,
)

@Serializable
private data class TmdbDiscoverResponse(
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    val results: List<TmdbListItem>? = null,
)

@Serializable
private data class TmdbCompanyResponse(
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class TmdbNetworkResponse(
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class TmdbPersonResponse(
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCompanySearchResult(
    val id: Int,
    val name: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbCompanySearchResponse(
    val results: List<TmdbCompanySearchResult>? = null,
)

@Serializable
data class TmdbCollectionSearchResult(
    val id: Int,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
private data class TmdbCollectionSearchResponse(
    val results: List<TmdbCollectionSearchResult>? = null,
)

@Serializable
private data class TmdbKeywordSearchResponse(
    val results: List<TmdbKeywordSearchResult>? = null,
)

@Serializable
private data class TmdbKeywordSearchResult(
    val id: Int,
    val name: String? = null,
)

@Serializable
private data class TmdbGenreResponse(
    val genres: List<TmdbGenreItem>? = null,
)

@Serializable
private data class TmdbGenreItem(
    val id: Int,
    val name: String,
)

@Serializable
private data class TmdbPersonCreditsResponse(
    val cast: List<TmdbPersonCreditCast>? = null,
    val crew: List<TmdbPersonCreditCrew>? = null,
)

@Serializable
private data class TmdbPersonCreditCast(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)

@Serializable
private data class TmdbPersonCreditCrew(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val job: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)

@Serializable
private data class TmdbListItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)

@Serializable
private data class TmdbCollectionPart(
    val id: Int,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
)
