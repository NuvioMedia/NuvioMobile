package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.MoreLikeThisSource
import com.nuvio.app.features.details.PersonDetail
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object TmdbMetadataService {
    private val log = Logger.withTag("TmdbMetadata")
    private val json = Json { ignoreUnknownKeys = true }

    private val enrichmentCache = mutableMapOf<String, TmdbEnrichment>()
    private val episodeCache = mutableMapOf<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val moreLikeThisCache = mutableMapOf<String, List<MetaPreview>>()
    private val collectionCache = mutableMapOf<String, Pair<String?, List<MetaPreview>>>()
    private val trailerCache = mutableMapOf<String, List<MetaTrailer>>()
    private val personCache = mutableMapOf<String, PersonDetail>()
    private val entityBrowseCache = mutableMapOf<String, TmdbEntityBrowseData>()
    private val entityHeaderCache = mutableMapOf<String, TmdbEntityHeader>()
    private val entityRailCache = mutableMapOf<String, List<MetaPreview>>()

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
    ): PersonDetail? = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val language = normalizeTmdbLanguage(settings.language)
        val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}:$language"
        personCache[cacheKey]?.let { return@withContext it }

        try {
            val (person, credits) = coroutineScope {
                val personDeferred = async {
                    fetch<TmdbPersonResponse>(
                        endpoint = "person/$personId",
                        query = mapOf("language" to language),
                    )
                }
                val creditsDeferred = async {
                    fetch<TmdbPersonCombinedCreditsResponse>(
                        endpoint = "person/$personId/combined_credits",
                        query = mapOf("language" to language),
                    )
                }
                personDeferred.await() to creditsDeferred.await()
            }

            if (person == null) return@withContext null

            val isCjkLanguage = language.startsWith("ja") ||
                language.startsWith("ko") ||
                language.startsWith("zh")
            val shouldFetchEnglishPerson = language != "en" &&
                (
                    person.biography.isNullOrBlank() ||
                        (
                            !isCjkLanguage &&
                                person.name != null &&
                                containsCjkOrHangul(person.name) &&
                                (person.originalName == null || containsCjkOrHangul(person.originalName))
                            )
                    )
            val shouldFetchEnglishCredits = language != "en" &&
                !isCjkLanguage &&
                personCreditsContainCjkTitles(credits)

            val (englishPerson, englishCredits) = if (shouldFetchEnglishPerson || shouldFetchEnglishCredits) {
                coroutineScope {
                    val englishPersonDeferred = async {
                        if (shouldFetchEnglishPerson) {
                            runCatching {
                                fetch<TmdbPersonResponse>(
                                    endpoint = "person/$personId",
                                    query = mapOf("language" to "en"),
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    }
                    val englishCreditsDeferred = async {
                        if (shouldFetchEnglishCredits) {
                            runCatching {
                                fetch<TmdbPersonCombinedCreditsResponse>(
                                    endpoint = "person/$personId/combined_credits",
                                    query = mapOf("language" to "en"),
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    }
                    englishPersonDeferred.await() to englishCreditsDeferred.await()
                }
            } else {
                null to null
            }
            val englishTitlesById = englishCreditTitlesById(englishCredits)

            val biography = if (person.biography.isNullOrBlank() && language != "en") {
                englishPerson?.biography
            } else {
                person.biography
            }?.takeIf { it.isNotBlank() }

            val preferCrew = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

            val (castMovieCredits, crewMovieCredits, castTvCredits, crewTvCredits) = coroutineScope {
                val castMovieDeferred = async {
                    mapPersonMovieCreditsFromCast(
                        cast = credits?.cast.orEmpty(),
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById,
                    )
                }
                val crewMovieDeferred = async {
                    mapPersonMovieCreditsFromCrew(
                        crew = credits?.crew.orEmpty(),
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById,
                    )
                }
                val castTvDeferred = async {
                    mapPersonTvCreditsFromCast(
                        cast = credits?.cast.orEmpty(),
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById,
                    )
                }
                val crewTvDeferred = async {
                    mapPersonTvCreditsFromCrew(
                        crew = credits?.crew.orEmpty(),
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById,
                    )
                }
                PersonCreditBuckets(
                    castMovieCredits = castMovieDeferred.await(),
                    crewMovieCredits = crewMovieDeferred.await(),
                    castTvCredits = castTvDeferred.await(),
                    crewTvCredits = crewTvDeferred.await(),
                )
            }

            val detail = PersonDetail(
                tmdbId = person.id ?: personId,
                name = resolvePersonName(
                    localizedName = person.name,
                    originalName = person.originalName,
                    fallbackEnglishName = englishPerson?.name,
                    preferredLanguage = language,
                ) ?: runBlocking { getString(Res.string.generic_unknown) },
                biography = biography,
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.deathday?.takeIf { it.isNotBlank() },
                placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                profilePhoto = buildImageUrl(person.profilePath, "w500"),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = selectPreferredCredits(
                    preferCrew = preferCrew,
                    castCredits = castMovieCredits,
                    crewCredits = crewMovieCredits,
                ),
                tvCredits = selectPreferredCredits(
                    preferCrew = preferCrew,
                    castCredits = castTvCredits,
                    crewCredits = crewTvCredits,
                ),
            )
            personCache[cacheKey] = detail
            detail
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch person detail for $personId" }
            null
        }
    }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        return department.isNotBlank() && department != "acting" && department != "actors"
    }

    private data class PersonCreditBuckets(
        val castMovieCredits: List<MetaPreview>,
        val crewMovieCredits: List<MetaPreview>,
        val castTvCredits: List<MetaPreview>,
        val crewTvCredits: List<MetaPreview>,
    )

    private fun selectPreferredCredits(
        preferCrew: Boolean,
        castCredits: List<MetaPreview>,
        crewCredits: List<MetaPreview>,
    ): List<MetaPreview> = when {
        preferCrew && crewCredits.isNotEmpty() -> crewCredits
        castCredits.isNotEmpty() -> castCredits
        else -> crewCredits
    }

    private fun mapPersonMovieCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = resolvePersonName(
                    localizedName = credit.title ?: credit.name,
                    originalName = credit.originalTitle ?: credit.originalName,
                    fallbackEnglishName = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage,
                ) ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "movie",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    rawReleaseDate = credit.releaseDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonMovieCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = resolvePersonName(
                    localizedName = credit.title ?: credit.name,
                    originalName = credit.originalTitle ?: credit.originalName,
                    fallbackEnglishName = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage,
                ) ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "movie",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    rawReleaseDate = credit.releaseDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonTvCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = resolvePersonName(
                    localizedName = credit.name ?: credit.title,
                    originalName = credit.originalName ?: credit.originalTitle,
                    fallbackEnglishName = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage,
                ) ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "series",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    rawReleaseDate = credit.firstAirDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonTvCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = resolvePersonName(
                    localizedName = credit.name ?: credit.title,
                    originalName = credit.originalName ?: credit.originalTitle,
                    fallbackEnglishName = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage,
                ) ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "series",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    rawReleaseDate = credit.firstAirDate,
                    popularity = credit.popularity,
                )
            }
    }

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        sourceType: String,
        fallbackName: String? = null,
    ): TmdbEntityBrowseData? = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val language = normalizeTmdbLanguage(settings.language)
        val normalizedSourceType = normalizeEntitySourceType(sourceType)
        val cacheKey = "${entityKind.routeValue}:$entityId:$normalizedSourceType:$language"
        entityBrowseCache[cacheKey]?.let { return@withContext it }

        val (header, rails) = coroutineScope {
            val headerDeferred = async {
                fetchEntityHeader(
                    entityKind = entityKind,
                    entityId = entityId,
                    fallbackName = fallbackName,
                    language = language,
                )
            }
            val railDeferreds = buildEntityMediaOrder(entityKind, normalizedSourceType)
                .flatMap { mediaType ->
                    TmdbEntityRailType.entries.map { railType ->
                        async {
                            val pageResult = fetchEntityRailPage(
                                entityKind = entityKind,
                                entityId = entityId,
                                mediaType = mediaType,
                                railType = railType,
                                language = language,
                                page = 1,
                            )
                            if (pageResult.items.isEmpty()) {
                                null
                            } else {
                                TmdbEntityRail(
                                    mediaType = mediaType,
                                    railType = railType,
                                    items = pageResult.items,
                                    currentPage = 1,
                                    hasMore = pageResult.hasMore,
                                )
                            }
                        }
                    }
                }
            headerDeferred.await() to railDeferreds.awaitAll().filterNotNull()
        }

        if (header == null && rails.isEmpty()) return@withContext null

        val data = TmdbEntityBrowseData(
            header = header ?: TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = fallbackName?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.generic_unknown) },
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null,
            ),
            rails = rails,
        )
        entityBrowseCache[cacheKey] = data
        data
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
    ): TmdbEntityRailPageResult {
        if (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.MOVIE) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        val cacheKey = "${entityKind.routeValue}:$entityId:${mediaType.value}:${railType.value}:$language:page:$page"
        entityRailCache[cacheKey]?.let { cached ->
            return TmdbEntityRailPageResult(items = cached, hasMore = cached.isNotEmpty())
        }

        val voteCountFloor = if (railType == TmdbEntityRailType.TOP_RATED) ENTITY_TOP_RATED_VOTE_FLOOR else null

        val result = try {
            val sortBy = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> when (railType) {
                    TmdbEntityRailType.POPULAR -> "popularity.desc"
                    TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
                    TmdbEntityRailType.RECENT -> "primary_release_date.desc"
                }
                TmdbEntityMediaType.TV -> when (railType) {
                    TmdbEntityRailType.POPULAR -> "popularity.desc"
                    TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
                    TmdbEntityRailType.RECENT -> "first_air_date.desc"
                }
            }

            val endpoint = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> "discover/movie"
                TmdbEntityMediaType.TV -> "discover/tv"
            }

            suspend fun loadDiscover(requestLanguage: String): TmdbDiscoverResponse? {
                val queryParams = buildMap {
                    put("language", requestLanguage)
                    put("page", page.toString())
                    put("sort_by", sortBy)
                    when (mediaType) {
                        TmdbEntityMediaType.MOVIE -> {
                            put("with_companies", entityId.toString())
                        }
                        TmdbEntityMediaType.TV -> {
                            if (entityKind == TmdbEntityKind.COMPANY) put("with_companies", entityId.toString())
                            if (entityKind == TmdbEntityKind.NETWORK) put("with_networks", entityId.toString())
                        }
                    }
                    if (voteCountFloor != null) put("vote_count.gte", voteCountFloor.toString())
                }
                return fetch<TmdbDiscoverResponse>(endpoint = endpoint, query = queryParams)
            }

            val response = loadDiscover(language)
            val results = response?.results.orEmpty()
            val totalPages = response?.totalPages ?: page

            val isCjkLanguage = language.startsWith("ja") ||
                language.startsWith("ko") ||
                language.startsWith("zh")
            val englishTitlesById = if (
                language != "en" &&
                !isCjkLanguage &&
                discoverResultsContainCjkTitles(results)
            ) {
                englishDiscoverTitlesById(loadDiscover("en")?.results.orEmpty())
            } else {
                emptyMap()
            }

            val mappedItems = results
                .filter { it.id > 0 }
                .mapNotNull { item ->
                    mapEntityDiscoverResult(
                        result = item,
                        mediaType = mediaType,
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById,
                    )
                }
                .take(ENTITY_RAIL_MAX_ITEMS)

            TmdbEntityRailPageResult(
                items = mappedItems,
                hasMore = page < totalPages && mappedItems.isNotEmpty(),
            )
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch entity rail ${railType.value}/${mediaType.value} for $entityId" }
            TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        if (result.items.isNotEmpty()) {
            entityRailCache[cacheKey] = result.items
        }
        return result
    }

    private suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int,
        fallbackName: String?,
        language: String,
    ): TmdbEntityHeader? {
        val cacheKey = "${entityKind.routeValue}:$entityId:$language:header"
        entityHeaderCache[cacheKey]?.let { return it }

        val header = try {
            when (entityKind) {
                TmdbEntityKind.COMPANY -> {
                    val body = fetch<TmdbCompanyDetailsResponse>(endpoint = "company/$entityId")
                    body?.let {
                        TmdbEntityHeader(
                            id = it.id,
                            kind = entityKind,
                            name = it.name?.takeIf { n -> n.isNotBlank() }
                                ?: fallbackName?.takeIf { n -> n.isNotBlank() }
                                ?: runBlocking { getString(Res.string.generic_unknown) },
                            logo = buildImageUrl(it.logoPath, "w500"),
                            originCountry = it.originCountry?.takeIf { c -> c.isNotBlank() },
                            secondaryLabel = it.headquarters?.takeIf { h -> h.isNotBlank() },
                            description = it.description?.takeIf { d -> d.isNotBlank() },
                        )
                    }
                }
                TmdbEntityKind.NETWORK -> {
                    val body = fetch<TmdbNetworkDetailsResponse>(endpoint = "network/$entityId")
                    body?.let {
                        TmdbEntityHeader(
                            id = it.id,
                            kind = entityKind,
                            name = it.name?.takeIf { n -> n.isNotBlank() }
                                ?: fallbackName?.takeIf { n -> n.isNotBlank() }
                                ?: runBlocking { getString(Res.string.generic_unknown) },
                            logo = buildImageUrl(it.logoPath, "w500"),
                            originCountry = it.originCountry?.takeIf { c -> c.isNotBlank() },
                            secondaryLabel = it.headquarters?.takeIf { h -> h.isNotBlank() },
                            description = null,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch ${entityKind.routeValue} header for $entityId" }
            null
        } ?: fallbackName?.takeIf { it.isNotBlank() }?.let {
            TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = it,
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null,
            )
        }

        if (header != null) {
            entityHeaderCache[cacheKey] = header
        }
        return header
    }

    private fun mapEntityDiscoverResult(
        result: TmdbDiscoverResult,
        mediaType: TmdbEntityMediaType,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>,
    ): MetaPreview? {
        val title = resolvePersonName(
            localizedName = result.title ?: result.name,
            originalName = result.originalTitle ?: result.originalName,
            fallbackEnglishName = englishTitlesById[result.id],
            preferredLanguage = preferredLanguage,
        ) ?: return null

        val poster = buildImageUrl(result.posterPath, "w500")
            ?: buildImageUrl(result.backdropPath, "w780")
            ?: return null
        val releaseInfo = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate?.take(4)
            TmdbEntityMediaType.TV -> result.firstAirDate?.take(4)
        }
        return MetaPreview(
            id = "tmdb:${result.id}",
            type = if (mediaType == TmdbEntityMediaType.TV) "series" else "movie",
            name = title,
            poster = poster,
            banner = buildImageUrl(result.backdropPath, "w780"),
            logo = null,
            description = result.overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseInfo,
        )
    }

    private fun buildEntityMediaOrder(
        entityKind: TmdbEntityKind,
        sourceType: String,
    ): List<TmdbEntityMediaType> {
        if (entityKind == TmdbEntityKind.NETWORK) {
            return listOf(TmdbEntityMediaType.TV)
        }
        return when (sourceType) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
    }

    private fun normalizeEntitySourceType(sourceType: String): String {
        return when (sourceType.trim().lowercase()) {
            "movie" -> "movie"
            "tv", "series", "show" -> "tv"
            else -> "tv"
        }
    }

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: TmdbSettings,
    ): MetaDetails {
        if (!settings.enabled || !settings.hasApiKey) return meta

        val tmdbType = normalizeMetaType(meta.type)
        val tmdbId = TmdbService.ensureTmdbId(meta.id, tmdbType, title = meta.name, year = meta.releaseInfo)
            ?: TmdbService.ensureTmdbId(fallbackItemId, tmdbType, title = meta.name, year = meta.releaseInfo)
            ?: return meta

        val needsEpisodes = (
            settings.useEpisodes || settings.useReleaseDates || settings.useSeasonPosters
        ) && tmdbType == "tv"
        val (enrichment, episodeMap) = coroutineScope {
            val enrichmentDeferred = async {
                fetchEnrichment(
                    tmdbId = tmdbId,
                    mediaType = tmdbType,
                    language = settings.language,
                    settings = settings,
                )
            }
            val episodeDeferred = if (needsEpisodes) {
                async {
                    val seasons = meta.videos.mapNotNull { it.season }.distinct()
                    fetchEpisodeEnrichment(
                        tmdbId = tmdbId,
                        seasonNumbers = seasons,
                        language = settings.language,
                    )
                }
            } else {
                null
            }
            enrichmentDeferred.await() to episodeDeferred?.await()
        }

        return applyEnrichment(
            meta = meta,
            enrichment = enrichment,
            episodeMap = episodeMap.orEmpty(),
            settings = settings,
        )
    }

    suspend fun enrichLibraryItem(
        item: com.nuvio.app.features.library.LibraryItem,
        settings: TmdbSettings = TmdbSettingsRepository.snapshot(),
    ): com.nuvio.app.features.library.LibraryItem {
        if (!settings.enabled || !settings.hasApiKey) return item

        val tmdbType = normalizeMetaType(item.type)
        val tmdbIdStr = item.tmdbId?.toString()
            ?: TmdbService.ensureTmdbId(item.id, tmdbType, title = item.name, year = item.releaseInfo)
            ?: return item

        val enrichment = fetchEnrichment(
            tmdbId = tmdbIdStr,
            mediaType = tmdbType,
            language = settings.language,
            settings = settings,
        ) ?: return item

        val rawDate = if (settings.useReleaseDates) enrichment.releaseInfo else null
        val rating = if (settings.useBasicInfo) enrichment.rating?.formatRating() else null
        val isMovie = tmdbType == "movie" || item.type.trim().lowercase() == "movie"
        val updatedReleaseInfo = if (isMovie && !rawDate.isNullOrBlank()) {
            rawDate
        } else {
            item.releaseInfo?.takeIf { it.isNotBlank() } ?: rawDate
        }

        return item.copy(
            tmdbId = tmdbIdStr.toIntOrNull() ?: item.tmdbId,
            releaseInfo = updatedReleaseInfo,
            rawReleaseDate = rawDate ?: item.rawReleaseDate,
            imdbRating = item.imdbRating?.takeIf { it.isNotBlank() } ?: rating,
        )
    }

    suspend fun fetchStandaloneMeta(
        type: String,
        id: String,
        settings: TmdbSettings,
    ): MetaDetails? {
        if (!settings.hasApiKey) return null

        val tmdbType = normalizeMetaType(type)
        val tmdbIdStr = TmdbService.ensureTmdbId(id, tmdbType) ?: return null
        val tmdbId = tmdbIdStr.toIntOrNull() ?: return null

        val enrichment = fetchEnrichment(
            tmdbId = tmdbId.toString(),
            mediaType = tmdbType,
            language = settings.language,
            settings = settings,
        ) ?: return null

        return buildStandaloneMeta(
            type = type,
            id = id,
            tmdbId = tmdbId,
            enrichment = enrichment,
        )
    }

    internal fun buildStandaloneMeta(
        type: String,
        id: String,
        tmdbId: Int,
        enrichment: TmdbEnrichment,
    ): MetaDetails =
        MetaDetails(
            id = id,
            type = type,
            name = enrichment.localizedTitle ?: "TMDB $tmdbId",
            poster = enrichment.poster,
            background = enrichment.backdrop,
            logo = enrichment.logo,
            description = enrichment.description,
            releaseInfo = enrichment.releaseInfo,
            lastAirDate = enrichment.lastAirDate,
            status = enrichment.status,
            imdbRating = enrichment.rating?.formatRating(),
            ageRating = enrichment.ageRating,
            runtime = enrichment.runtimeMinutes?.formatRuntime(),
            genres = enrichment.genres,
            director = enrichment.director,
            writer = enrichment.writer,
            cast = enrichment.people,
            productionCompanies = enrichment.productionCompanies,
            networks = enrichment.networks,
            country = enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", "),
            language = enrichment.language,
            moreLikeThis = enrichment.moreLikeThis,
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { enrichment.moreLikeThis.isNotEmpty() },
            collectionName = enrichment.collectionName,
            collectionItems = enrichment.collectionItems,
            trailers = enrichment.trailers,
        )

    internal fun applyEnrichment(
        meta: MetaDetails,
        enrichment: TmdbEnrichment?,
        episodeMap: Map<Pair<Int, Int>, TmdbEpisodeEnrichment>,
        settings: TmdbSettings,
    ): MetaDetails {
        if (enrichment == null && episodeMap.isEmpty()) return meta

        var updated = meta

        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                poster = enrichment.poster ?: updated.poster,
                logo = enrichment.logo ?: updated.logo,
            )
        }

        if (enrichment != null && settings.useBasicInfo) {
            updated = updated.copy(
                name = enrichment.localizedTitle ?: updated.name,
                description = enrichment.description ?: updated.description,
                imdbRating = updated.imdbRating?.takeIf { it.isNotBlank() }
                    ?: enrichment.rating?.formatRating(),
                genres = enrichment.genres.ifEmpty { updated.genres },
            )
        }

        if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                runtime = enrichment.runtimeMinutes?.formatRuntime() ?: updated.runtime,
                country = enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language,
            )
        }

        if (enrichment != null) {
            updated = updated.copy(
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo,
                lastAirDate = enrichment.lastAirDate ?: updated.lastAirDate,
            )
        }

        if (enrichment != null && settings.useCredits) {
            updated = updated.copy(
                director = enrichment.director.ifEmpty { updated.director },
                writer = enrichment.writer.ifEmpty { updated.writer },
                cast = enrichment.people.ifEmpty { updated.cast },
            )
        }

        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = enrichment.networks)
        }

        if (episodeMap.isNotEmpty()) {
            updated = updated.copy(
                videos = meta.videos.map { video ->
                    val key = video.season?.let { season ->
                        video.episode?.let { episode -> season to episode }
                    }
                    val enrichmentForEpisode = key?.let(episodeMap::get)
                    if (enrichmentForEpisode == null) {
                        video
                    } else {
                        video.copy(
                            title = if (settings.useEpisodes) {
                                enrichmentForEpisode.title ?: video.title
                            } else {
                                video.title
                            },
                            overview = if (settings.useEpisodes) {
                                enrichmentForEpisode.overview ?: video.overview
                            } else {
                                video.overview
                            },
                            released = if (settings.useReleaseDates) {
                                enrichmentForEpisode.airDate ?: video.released
                            } else {
                                video.released
                            },
                            thumbnail = if (settings.useEpisodes) {
                                enrichmentForEpisode.thumbnail ?: video.thumbnail
                            } else {
                                video.thumbnail
                            },
                            seasonPoster = if (settings.useSeasonPosters) {
                                enrichmentForEpisode.seasonPoster ?: video.seasonPoster
                            } else {
                                video.seasonPoster
                            },
                            runtime = if (settings.useEpisodes) {
                                enrichmentForEpisode.runtimeMinutes ?: video.runtime
                            } else {
                                video.runtime
                            },
                        )
                    }
                },
            )
        }

        if (enrichment != null && settings.useMoreLikeThis) {
            updated = updated.copy(
                moreLikeThis = enrichment.moreLikeThis,
                moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { enrichment.moreLikeThis.isNotEmpty() },
            )
        }

        if (enrichment != null && settings.useCollections) {
            updated = updated.copy(
                collectionName = enrichment.collectionName,
                collectionItems = enrichment.collectionItems,
            )
        }

        if (enrichment != null && settings.useTrailers && enrichment.trailers.isNotEmpty()) {
            updated = updated.copy(trailers = enrichment.trailers)
        }

        return updated
    }

    private suspend fun fetchEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String,
        settings: TmdbSettings,
    ): TmdbEnrichment? = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:$mediaType:$normalizedLanguage"
        enrichmentCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }

        val response = coroutineScope {
            val details = async {
                fetch<TmdbDetailsResponse>(
                    endpoint = "$mediaType/$numericId",
                    query = mapOf("language" to normalizedLanguage),
                )
            }
            val credits = async {
                if (mediaType == "tv") {
                    fetch<TmdbAggregateCreditsResponse>(
                        endpoint = "tv/$numericId/aggregate_credits",
                        query = mapOf("language" to normalizedLanguage),
                    )?.toStandard()
                } else {
                    fetch<TmdbCreditsResponse>(
                        endpoint = "movie/$numericId/credits",
                        query = mapOf("language" to normalizedLanguage),
                    )
                }
            }
            val images = async {
                fetch<TmdbImagesResponse>(
                    endpoint = "$mediaType/$numericId/images",
                    query = mapOf("include_image_language" to includeImageLanguage),
                )
            }
            val ageRating = async {
                when (mediaType) {
                    "tv" -> fetch<TmdbTvContentRatingsResponse>(
                        endpoint = "tv/$numericId/content_ratings",
                    )?.results.orEmpty().selectTvAgeRating(normalizedLanguage)
                    else -> fetch<TmdbMovieReleaseDatesResponse>(
                        endpoint = "movie/$numericId/release_dates",
                    )?.results.orEmpty().selectMovieAgeRating(normalizedLanguage)
                }
            }
            val moreLikeThis = async {
                if (settings.useMoreLikeThis && (mediaType == "movie" || mediaType == "tv")) {
                    fetchMoreLikeThis(
                        tmdbId = numericId,
                        mediaType = mediaType,
                        language = normalizedLanguage,
                    )
                } else {
                    emptyList()
                }
            }
            val trailers = async {
                if (settings.useTrailers && (mediaType == "movie" || mediaType == "tv")) {
                    fetchTrailers(
                        tmdbId = numericId,
                        mediaType = mediaType,
                        language = normalizedLanguage,
                    )
                } else {
                    emptyList()
                }
            }
            Quadruple(
                first = details.await(),
                second = credits.await(),
                third = images.await(),
                fourth = EnrichmentPayload(
                    ageRating = ageRating.await(),
                    moreLikeThis = moreLikeThis.await(),
                    trailers = trailers.await(),
                ),
            )
        }

        val details = response.first ?: return@withContext null
        val credits = response.second
        val images = response.third
        val englishFallbackNames = fetchEnglishFallbackNames(
            numericId = numericId,
            mediaType = mediaType,
            language = normalizedLanguage,
            details = details,
            credits = credits,
        )

        val genres = details.genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        val description = details.overview?.trim()?.takeIf(String::isNotBlank)
        val releaseInfo = details.releaseDate ?: details.firstAirDate
        val localizedTitle = listOf(details.title, details.name).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
        val people = buildPeople(
            details = details,
            credits = credits,
            mediaType = mediaType,
            preferredLanguage = normalizedLanguage,
            englishFallbackNames = englishFallbackNames,
        )
        val directors = buildDirectors(
            details = details,
            credits = credits,
            mediaType = mediaType,
            preferredLanguage = normalizedLanguage,
            englishFallbackNames = englishFallbackNames,
        )
        val writers = buildWriters(
            credits = credits,
            mediaType = mediaType,
            hasDirectors = directors.isNotEmpty(),
            preferredLanguage = normalizedLanguage,
            englishFallbackNames = englishFallbackNames,
        )
        val lastAirDate = details.lastAirDate?.trim()?.takeIf(String::isNotBlank)
            ?.takeIf { mediaType == "tv" }
        val enrichment = TmdbEnrichment(
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = buildImageUrl(details.backdropPath, "w1280"),
            logo = buildImageUrl(images?.logos.orEmpty().selectBestLocalizedImagePath(normalizedLanguage), "w500"),
            poster = buildImageUrl(details.posterPath, "w500"),
            people = people,
            director = directors,
            writer = writers,
            releaseInfo = releaseInfo,
            lastAirDate = lastAirDate,
            rating = details.voteAverage,
            runtimeMinutes = details.runtime ?: details.episodeRunTime.firstOrNull(),
            ageRating = response.fourth.ageRating,
            status = details.status?.trim()?.takeIf(String::isNotBlank),
            countries = details.productionCountries
                .mapNotNull { it.iso31661?.trim()?.takeIf(String::isNotBlank) }
                .ifEmpty { details.originCountry.filter(String::isNotBlank) },
            language = details.originalLanguage?.trim()?.takeIf(String::isNotBlank),
            productionCompanies = details.productionCompanies.mapNotNull { it.toMetaCompany() },
            networks = details.networks.mapNotNull { it.toMetaCompany() },
            collectionName = details.belongsToCollection?.name?.trim()?.takeIf(String::isNotBlank),
            collectionItems = if (settings.useCollections && details.belongsToCollection?.id != null) {
                fetchCollection(
                    collectionId = details.belongsToCollection.id,
                    language = normalizedLanguage,
                ).second
            } else {
                emptyList()
            },
            moreLikeThis = response.fourth.moreLikeThis,
            trailers = response.fourth.trailers,
        )

        if (!enrichment.hasContent()) return@withContext null
        enrichmentCache[cacheKey] = enrichment
        enrichment
    }

    private suspend fun fetchEnglishFallbackNames(
        numericId: Int,
        mediaType: String,
        language: String,
        details: TmdbDetailsResponse,
        credits: TmdbCreditsResponse?,
    ): Map<Int, String> {
        if (
            language.startsWith("en") ||
            language.startsWith("ja") ||
            language.startsWith("ko") ||
            language.startsWith("zh")
        ) {
            return emptyMap()
        }
        val needsFallback = credits?.cast.orEmpty().any { member ->
            personNameNeedsEnglishFallback(member.name, member.originalName)
        } || credits?.crew.orEmpty().any { member ->
            personNameNeedsEnglishFallback(member.name, member.originalName)
        } || details.createdBy.any { creator ->
            personNameNeedsEnglishFallback(creator.name, creator.originalName)
        }
        if (!needsFallback) return emptyMap()

        return runCatching {
            val englishCredits = fetch<TmdbCreditsResponse>(
                endpoint = "$mediaType/$numericId/credits",
                query = mapOf("language" to "en-US"),
            )
            val englishTvDetails = if (mediaType == "tv" && details.createdBy.isNotEmpty()) {
                fetch<TmdbDetailsResponse>(
                    endpoint = "tv/$numericId",
                    query = mapOf("language" to "en-US"),
                )
            } else {
                null
            }
            buildMap {
                englishCredits?.cast.orEmpty().forEach { member ->
                    val id = member.id
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() }
                    if (id != null && name != null) put(id, name)
                }
                englishCredits?.crew.orEmpty().forEach { member ->
                    val id = member.id
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() }
                    if (id != null && name != null) put(id, name)
                }
                englishTvDetails?.createdBy.orEmpty().forEach { creator ->
                    val id = creator.id
                    val name = creator.name?.trim()?.takeIf { it.isNotBlank() }
                    if (id != null && name != null) put(id, name)
                }
            }
        }.getOrElse { error ->
            log.w(error) { "Failed to fetch English credits fallback for $numericId" }
            emptyMap()
        }
    }

    private suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String,
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val normalizedSeasons = seasonNumbers.distinct().sorted()
        if (normalizedSeasons.isEmpty()) return@withContext emptyMap()

        val cacheKey = "$numericId:${normalizedSeasons.joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }

        val pairs = coroutineScope {
            normalizedSeasons.map { season ->
                async {
                    val details = fetch<TmdbSeasonDetailsResponse>(
                        endpoint = "tv/$numericId/season/$season",
                        query = mapOf("language" to normalizedLanguage),
                    ) ?: return@async emptyMap()

                    details.episodes
                        .mapNotNull { episode ->
                            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                            (season to episodeNumber) to TmdbEpisodeEnrichment(
                                title = episode.name?.trim()?.takeIf(String::isNotBlank),
                                overview = episode.overview?.trim()?.takeIf(String::isNotBlank),
                                thumbnail = buildImageUrl(episode.stillPath, "w500"),
                                seasonPoster = buildImageUrl(details.posterPath, "w500"),
                                airDate = episode.airDate?.trim()?.takeIf(String::isNotBlank),
                                runtimeMinutes = episode.runtime,
                            )
                        }
                        .toMap()
                }
            }.awaitAll()
        }

        val merged = pairs.fold(emptyMap<Pair<Int, Int>, TmdbEpisodeEnrichment>()) { acc, value -> acc + value }
        if (merged.isNotEmpty()) {
            episodeCache[cacheKey] = merged
        }
        merged
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val apiKey = TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank) ?: return null
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(httpGetText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }

    private suspend fun fetchMoreLikeThis(
        tmdbId: Int,
        mediaType: String,
        language: String,
    ): List<MetaPreview> {
        val cacheKey = "$tmdbId:$mediaType:$language:recommendations"
        moreLikeThisCache[cacheKey]?.let { return it }

        val response = fetch<TmdbRecommendationResponse>(
            endpoint = "$mediaType/$tmdbId/recommendations",
            query = mapOf("language" to language),
        ) ?: return emptyList()

        val items = response.results
            .filter { it.id > 0 }
            .mapNotNull { recommendation ->
                val inferredType = when (recommendation.mediaType?.lowercase()) {
                    "tv" -> "series"
                    "movie" -> "movie"
                    else -> if (mediaType == "tv") "series" else "movie"
                }
                val title = recommendation.title
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: recommendation.name?.trim()?.takeIf(String::isNotBlank)
                    ?: recommendation.originalTitle?.trim()?.takeIf(String::isNotBlank)
                    ?: recommendation.originalName?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                MetaPreview(
                    id = "tmdb:${recommendation.id}",
                    type = inferredType,
                    name = title,
                    poster = buildImageUrl(recommendation.posterPath, "w500")
                        ?: buildImageUrl(recommendation.backdropPath, "w780"),
                    banner = buildImageUrl(recommendation.backdropPath, "w1280"),
                    posterShape = PosterShape.Poster,
                    description = recommendation.overview?.trim()?.takeIf(String::isNotBlank),
                    releaseInfo = (recommendation.releaseDate ?: recommendation.firstAirDate)?.take(4),
                    rawReleaseDate = recommendation.releaseDate ?: recommendation.firstAirDate,
                    imdbRating = recommendation.voteAverage?.formatRating(),
                )
            }
            .take(12)

        moreLikeThisCache[cacheKey] = items
        return items
    }

    private suspend fun fetchCollection(
        collectionId: Int,
        language: String,
    ): Pair<String?, List<MetaPreview>> {
        val cacheKey = "$collectionId:$language:collection"
        collectionCache[cacheKey]?.let { return it }

        val response = fetch<TmdbCollectionResponse>(
            endpoint = "collection/$collectionId",
            query = mapOf("language" to language),
        ) ?: return null to emptyList()

        val items = response.parts
            .sortedBy { it.releaseDate ?: "9999" }
            .mapNotNull { part ->
                val title = part.title?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${part.id}",
                    type = "movie",
                    name = title,
                    poster = buildImageUrl(part.backdropPath, "w780")
                        ?: buildImageUrl(part.posterPath, "w500"),
                    banner = buildImageUrl(part.backdropPath, "w1280"),
                    posterShape = PosterShape.Landscape,
                    description = part.overview?.trim()?.takeIf(String::isNotBlank),
                    releaseInfo = part.releaseDate?.take(4),
                    rawReleaseDate = part.releaseDate,
                    imdbRating = part.voteAverage?.formatRating(),
                )
            }

        val result = response.name?.trim()?.takeIf(String::isNotBlank) to items
        collectionCache[cacheKey] = result
        return result
    }

    private suspend fun fetchTrailers(
        tmdbId: Int,
        mediaType: String,
        language: String,
    ): List<MetaTrailer> {
        val cacheKey = "$tmdbId:$mediaType:$language:trailers"
        trailerCache[cacheKey]?.let { return it }

        val allVideos = mutableListOf<MetaTrailer>()

        val primaryVideos = fetchTmdbVideos(
            endpoint = "$mediaType/$tmdbId/videos",
            language = language,
        )
        allVideos += primaryVideos.map { video ->
            video.toMetaTrailer(
                seasonNumber = null,
                displayName = video.name,
            )
        }

        if (mediaType == "tv") {
            val details = fetch<TmdbDetailsResponse>(
                endpoint = "tv/$tmdbId",
                query = mapOf("language" to language),
            )
            val seasonCount = (details?.numberOfSeasons ?: 0).coerceAtLeast(0)
            if (seasonCount > 0) {
                val seasonVideos = coroutineScope {
                    (1..seasonCount).map { seasonNumber ->
                        async {
                            seasonNumber to fetchTmdbVideos(
                                endpoint = "tv/$tmdbId/season/$seasonNumber/videos",
                                language = language,
                            )
                        }
                    }.awaitAll()
                }

                seasonVideos.forEach { (seasonNumber, videos) ->
                    allVideos += videos.map { video ->
                        video.toMetaTrailer(
                            seasonNumber = seasonNumber,
                            displayName = runBlocking {
                                getString(
                                    Res.string.trailer_season_label,
                                    seasonNumber,
                                    video.name.orEmpty(),
                                )
                            },
                        )
                    }
                }
            }
        }

        val preferredLang = language.substringBefore('-').lowercase()

        val byCategory = linkedMapOf<String, MutableList<MetaTrailer>>()
        allVideos
            .asSequence()
            .filter { trailer ->
                trailer.site.equals("YouTube", ignoreCase = true) && trailer.key.isNotBlank()
            }
            .distinctBy { it.key }
            .forEach { trailer ->
                byCategory.getOrPut(
                    trailer.type.ifBlank { runBlocking { getString(Res.string.generic_trailer) } },
                ) { mutableListOf() }
                    .add(trailer)
            }

        byCategory.values.forEach { trailers ->
            trailers.sortWith(
                compareBy<MetaTrailer> {
                    when {
                        it.seasonNumber != null -> 0
                        else -> 1
                    }
                }
                    .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                    .thenBy {
                        val lang = it.iso6391?.trim()?.lowercase()
                        when {
                            lang == preferredLang -> 0
                            lang == "en" -> 1
                            else -> 2
                        }
                    }
                    .thenByDescending { it.official }
                    .thenByDescending { it.publishedAt.orEmpty() }
            )
        }

        val sortedCategories = byCategory.keys.sortedWith(
            compareBy<String> { category ->
                when {
                    category.equals(
                        runBlocking { getString(Res.string.generic_trailer) },
                        ignoreCase = true,
                    ) -> 0
                    byCategory[category].orEmpty().any { it.official } -> 1
                    else -> 2
                }
            }.thenBy { it.lowercase() }
        )

        val result = sortedCategories.flatMap { byCategory[it].orEmpty() }
        trailerCache[cacheKey] = result
        return result
    }

    private suspend fun fetchTmdbVideos(
        endpoint: String,
        language: String,
    ): List<TmdbVideoResult> {
        val response = fetch<TmdbVideosResponse>(
            endpoint = endpoint,
            query = mapOf("language" to language),
        )
        return response?.results.orEmpty()
    }
}

internal data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val people: List<MetaPerson>,
    val director: List<String>,
    val writer: List<String>,
    val releaseInfo: String?,
    val lastAirDate: String? = null,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>,
    val language: String?,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val collectionName: String? = null,
    val collectionItems: List<MetaPreview> = emptyList(),
    val moreLikeThis: List<MetaPreview> = emptyList(),
    val trailers: List<MetaTrailer> = emptyList(),
) {
    fun hasContent(): Boolean =
        localizedTitle != null ||
            description != null ||
            genres.isNotEmpty() ||
            backdrop != null ||
            logo != null ||
            poster != null ||
            people.isNotEmpty() ||
            director.isNotEmpty() ||
            writer.isNotEmpty() ||
            releaseInfo != null ||
            lastAirDate != null ||
            rating != null ||
            runtimeMinutes != null ||
            ageRating != null ||
            status != null ||
            countries.isNotEmpty() ||
            language != null ||
            productionCompanies.isNotEmpty() ||
            networks.isNotEmpty() ||
            collectionItems.isNotEmpty() ||
            moreLikeThis.isNotEmpty() ||
            trailers.isNotEmpty()
}

private data class EnrichmentPayload(
    val ageRating: String?,
    val moreLikeThis: List<MetaPreview>,
    val trailers: List<MetaTrailer>,
)

internal data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val seasonPoster: String? = null,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

private fun normalizeMetaType(type: String): String =
    when (type.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> "tv"
        else -> "movie"
    }

internal fun normalizeTmdbLanguage(language: String?): String {
    val raw = language
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace('_', '-')
        ?: return "en"
    val parts = raw.split("-")
    val normalized = if (parts.size == 2) {
        "${parts[0].lowercase()}-${parts[1].uppercase()}"
    } else {
        raw.lowercase()
    }
    return when (normalized) {
        "es-419" -> "es-MX"
        else -> normalized
    }
}

internal fun discoverResultsContainCjkTitles(results: List<TmdbDiscoverResult>): Boolean {
    return results.any { result ->
        containsCjkOrHangul(result.title ?: result.name ?: return@any false)
    }
}

internal fun englishDiscoverTitlesById(results: List<TmdbDiscoverResult>): Map<Int, String> {
    val titles = linkedMapOf<Int, String>()
    results.forEach { result ->
        val text = result.title?.trim()?.takeIf { it.isNotBlank() }
            ?: result.name?.trim()?.takeIf { it.isNotBlank() }
            ?: return@forEach
        if (!containsCjkOrHangul(text)) {
            titles.getOrPut(result.id) { text }
        }
    }
    return titles
}

private fun personCreditsContainCjkTitles(credits: TmdbPersonCombinedCreditsResponse?): Boolean {
    if (credits == null) return false
    return credits.cast.any { containsCjkOrHangul(it.title ?: it.name ?: return@any false) } ||
        credits.crew.any { containsCjkOrHangul(it.title ?: it.name ?: return@any false) }
}

private fun englishCreditTitlesById(credits: TmdbPersonCombinedCreditsResponse?): Map<Int, String> {
    if (credits == null) return emptyMap()
    val titles = linkedMapOf<Int, String>()
    fun putTitle(id: Int, title: String?, name: String?) {
        val text = title?.trim()?.takeIf { it.isNotBlank() }
            ?: name?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        if (!containsCjkOrHangul(text)) {
            titles.getOrPut(id) { text }
        }
    }
    credits.cast.forEach { putTitle(it.id, it.title, it.name) }
    credits.crew.forEach { putTitle(it.id, it.title, it.name) }
    return titles
}

internal fun containsCjkOrHangul(text: String): Boolean {
    return text.any { ch ->
        ch in '\u3040'..'\u30FF' ||  // Hiragana + Katakana
            ch in '\u4E00'..'\u9FFF' ||  // CJK Unified Ideographs
            ch in '\u3400'..'\u4DBF' ||  // CJK Extension A
            ch in '\uAC00'..'\uD7AF' ||  // Hangul Syllables
            ch in '\u1100'..'\u11FF' ||  // Hangul Jamo
            ch in '\u3130'..'\u318F'     // Hangul Compatibility Jamo
    }
}

internal fun resolvePersonName(
    localizedName: String?,
    originalName: String?,
    fallbackEnglishName: String? = null,
    preferredLanguage: String,
): String? {
    val name = localizedName?.trim()?.takeIf { it.isNotBlank() }
    val original = originalName?.trim()?.takeIf { it.isNotBlank() }
    val fallback = fallbackEnglishName?.trim()?.takeIf { it.isNotBlank() }

    if (name == null) return original ?: fallback
    val lang = preferredLanguage.lowercase()

    if (lang.startsWith("ja") || lang.startsWith("ko") || lang.startsWith("zh")) {
        return name
    }

    if (!containsCjkOrHangul(name)) {
        return name
    }

    if (original != null && !containsCjkOrHangul(original)) {
        return original
    }

    if (fallback != null && !containsCjkOrHangul(fallback)) {
        return fallback
    }

    return fallback ?: original ?: name
}

private fun personNameNeedsEnglishFallback(name: String?, originalName: String?): Boolean {
    return !name.isNullOrBlank() &&
        containsCjkOrHangul(name) &&
        (originalName.isNullOrBlank() || containsCjkOrHangul(originalName))
}

private fun resolvedPersonDisplayName(
    localizedName: String?,
    originalName: String?,
    personId: Int?,
    preferredLanguage: String,
    englishFallbackNames: Map<Int, String>,
): String? = resolvePersonName(
    localizedName = localizedName,
    originalName = originalName,
    fallbackEnglishName = personId?.let { englishFallbackNames[it] },
    preferredLanguage = preferredLanguage,
)?.takeIf { it.isNotBlank() }

private fun buildPeople(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
    preferredLanguage: String,
    englishFallbackNames: Map<Int, String>,
): List<MetaPerson> {
    val creators = if (mediaType == "tv") {
        details.createdBy.mapNotNull { creator ->
            val name = resolvedPersonDisplayName(
                localizedName = creator.name,
                originalName = creator.originalName,
                personId = creator.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            ) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_creator) },
                photo = buildImageUrl(creator.profilePath, "w500"),
                tmdbId = creator.id,
            )
        }
    } else {
        emptyList()
    }

    val directors = credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { crew ->
            val name = resolvedPersonDisplayName(
                localizedName = crew.name,
                originalName = crew.originalName,
                personId = crew.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            ) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_director) },
                photo = buildImageUrl(crew.profilePath, "w500"),
                tmdbId = crew.id,
            )
        }

    val writers = credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { crew ->
            val name = resolvedPersonDisplayName(
                localizedName = crew.name,
                originalName = crew.originalName,
                personId = crew.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            ) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_writer) },
                photo = buildImageUrl(crew.profilePath, "w500"),
                tmdbId = crew.id,
            )
        }

    val cast = credits?.cast.orEmpty()
        .mapNotNull { castMember ->
            val name = resolvedPersonDisplayName(
                localizedName = castMember.name,
                originalName = castMember.originalName,
                personId = castMember.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            ) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = castMember.character?.trim()?.takeIf(String::isNotBlank),
                photo = buildImageUrl(castMember.profilePath, "w500"),
                tmdbId = castMember.id,
            )
        }

    val primaryCrew = when {
        mediaType == "tv" && creators.isNotEmpty() -> creators
        mediaType != "tv" && directors.isNotEmpty() -> directors
        else -> writers
    }

    return (primaryCrew + cast)
        .dedupePeople()
}

private fun buildDirectors(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
    preferredLanguage: String,
    englishFallbackNames: Map<Int, String>,
): List<String> {
    if (mediaType == "tv") {
        return details.createdBy
            .mapNotNull { creator ->
                resolvedPersonDisplayName(
                    localizedName = creator.name,
                    originalName = creator.originalName,
                    personId = creator.id,
                    preferredLanguage = preferredLanguage,
                    englishFallbackNames = englishFallbackNames,
                )
            }
            .distinct()
    }

    return credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { crew ->
            resolvedPersonDisplayName(
                localizedName = crew.name,
                originalName = crew.originalName,
                personId = crew.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            )
        }
        .distinct()
}

private fun buildWriters(
    credits: TmdbCreditsResponse?,
    mediaType: String,
    hasDirectors: Boolean,
    preferredLanguage: String,
    englishFallbackNames: Map<Int, String>,
): List<String> {
    if (hasDirectors) {
        return emptyList()
    }

    return credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { crew ->
            resolvedPersonDisplayName(
                localizedName = crew.name,
                originalName = crew.originalName,
                personId = crew.id,
                preferredLanguage = preferredLanguage,
                englishFallbackNames = englishFallbackNames,
            )
        }
        .distinct()
}

private fun List<MetaPerson>.dedupePeople(): List<MetaPerson> {
    val merged = linkedMapOf<String, MetaPerson>()
    forEach { person ->
        val key = person.name.lowercase() + "|" + person.role.orEmpty().lowercase()
        val existing = merged[key]
        merged[key] = if (existing == null) {
            person
        } else {
            existing.copy(photo = existing.photo ?: person.photo)
        }
    }
    return merged.values.toList()
}

private fun buildImageUrl(path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf(String::isNotBlank) ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}

private fun List<TmdbImage>.selectBestLocalizedImagePath(normalizedLanguage: String): String? {
    if (isEmpty()) return null
    val languageCode = normalizedLanguage.substringBefore("-")
    val regionCode = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
        ?: defaultLanguageRegions[languageCode]
    return sortedWith(
        compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
            .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
            .thenByDescending { it.iso6391 == languageCode }
            .thenByDescending { it.iso6391 == "en" }
            .thenByDescending { it.iso6391 == null },
    ).firstOrNull()?.filePath
}

private val defaultLanguageRegions = mapOf(
    "pt" to "PT",
    "es" to "ES",
)

private fun Double.formatRating(): String =
    if (this == 0.0) {
        "0.0"
    } else {
        (kotlin.math.round(this * 10.0) / 10.0).toString()
    }

private fun Int.formatRuntime(): String = "${this}m"

private fun List<TmdbMovieReleaseDateCountry>.selectMovieAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull(String::isNotBlank)
        if (!rating.isNullOrBlank()) return rating
    }
    return asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull(String::isNotBlank)
}

private fun List<TmdbTvContentRating>.selectTvAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return mapNotNull { it.rating?.trim() }.firstOrNull(String::isNotBlank)
}

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val directRegion = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
    return buildList {
        if (!directRegion.isNullOrBlank()) add(directRegion)
        add("US")
        add("GB")
    }.distinct()
}

private fun TmdbCompany.toMetaCompany(): MetaCompany? {
    val name = name?.trim()?.takeIf(String::isNotBlank) ?: return null
    return MetaCompany(
        name = name,
        logo = buildImageUrl(logoPath, "w300"),
        tmdbId = id,
    )
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

@Serializable
private data class TmdbDetailsResponse(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("production_countries") val productionCountries: List<TmdbProductionCountry> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val genres: List<TmdbNamedItem> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    val networks: List<TmdbCompany> = emptyList(),
    @SerialName("belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
)

@Serializable
private data class TmdbVideosResponse(
    val results: List<TmdbVideoResult> = emptyList(),
)

@Serializable
private data class TmdbVideoResult(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val size: Int? = null,
    val type: String? = null,
    val official: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
)

private fun TmdbVideoResult.toMetaTrailer(
    seasonNumber: Int?,
    displayName: String?,
): MetaTrailer {
    val videoKey = key?.trim().orEmpty()
    val videoName = name?.trim().takeUnless { it.isNullOrBlank() } ?: runBlocking { getString(Res.string.generic_trailer) }
    val trailerId = id?.trim().takeUnless { it.isNullOrBlank() } ?: videoKey
    return MetaTrailer(
        id = trailerId,
        key = videoKey,
        name = videoName,
        site = site?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube",
        size = size,
        type = type?.trim().takeUnless { it.isNullOrBlank() } ?: runBlocking { getString(Res.string.generic_trailer) },
        official = official == true,
        publishedAt = publishedAt,
        seasonNumber = seasonNumber,
        displayName = displayName,
        iso6391 = iso6391?.trim()?.takeIf { it.isNotBlank() },
    )
}

@Serializable
private data class TmdbNamedItem(
    val name: String? = null,
)

@Serializable
private data class TmdbProductionCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
)

@Serializable
private data class TmdbCreator(
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val id: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
private data class TmdbAggregateCreditsResponse(
    val cast: List<TmdbAggregateCastMember> = emptyList(),
    val crew: List<TmdbAggregateCrewMember> = emptyList(),
) {
    fun toStandard(): TmdbCreditsResponse = TmdbCreditsResponse(
        cast = cast.map { member ->
            TmdbCastMember(
                id = member.id,
                name = member.name,
                character = member.roles.firstOrNull()?.character,
                profilePath = member.profilePath,
            )
        },
        crew = crew.flatMap { member ->
            member.jobs.map { job ->
                TmdbCrewMember(
                    id = member.id,
                    name = member.name,
                    job = job.job,
                    profilePath = member.profilePath,
                )
            }
        },
    )
}

@Serializable
private data class TmdbAggregateCastMember(
    val id: Int? = null,
    val name: String? = null,
    val roles: List<TmdbAggregateRole> = emptyList(),
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbAggregateRole(
    val character: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbAggregateCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val department: String? = null,
    val jobs: List<TmdbAggregateJob> = emptyList(),
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbAggregateJob(
    val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbCastMember(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbCrewMember(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbImagesResponse(
    val logos: List<TmdbImage> = emptyList(),
)

@Serializable
private data class TmdbImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
)

@Serializable
private data class TmdbMovieReleaseDatesResponse(
    val results: List<TmdbMovieReleaseDateCountry> = emptyList(),
)

@Serializable
private data class TmdbMovieReleaseDateCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
private data class TmdbReleaseDate(
    val certification: String? = null,
)

@Serializable
private data class TmdbTvContentRatingsResponse(
    val results: List<TmdbTvContentRating> = emptyList(),
)

@Serializable
private data class TmdbTvContentRating(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val rating: String? = null,
)

@Serializable
private data class TmdbCompany(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class TmdbCollectionRef(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
private data class TmdbRecommendationResponse(
    val results: List<TmdbRecommendationItem> = emptyList(),
)

@Serializable
private data class TmdbRecommendationItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

@Serializable
private data class TmdbCollectionResponse(
    val name: String? = null,
    val parts: List<TmdbCollectionPart> = emptyList(),
)

@Serializable
private data class TmdbCollectionPart(
    val id: Int,
    val title: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
private data class TmdbSeasonDetailsResponse(
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisodeResponse> = emptyList(),
)

@Serializable
private data class TmdbEpisodeResponse(
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

// ─── Person Detail Models ───

@Serializable
private data class TmdbPersonResponse(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)

@Serializable
private data class TmdbPersonCombinedCreditsResponse(
    val cast: List<TmdbPersonCreditCast> = emptyList(),
    val crew: List<TmdbPersonCreditCrew> = emptyList(),
)

@Serializable
private data class TmdbPersonCreditCast(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val character: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
)

@Serializable
private data class TmdbPersonCreditCrew(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val job: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
)

// ─── Entity Browse (Company / Network) Models ───

private const val ENTITY_RAIL_MAX_ITEMS = 20
private const val ENTITY_TOP_RATED_VOTE_FLOOR = 200

enum class TmdbEntityKind(val routeValue: String) {
    COMPANY("company"),
    NETWORK("network");

    companion object {
        fun fromRouteValue(value: String): TmdbEntityKind = when (value.trim().lowercase()) {
            "network" -> NETWORK
            else -> COMPANY
        }
    }
}

enum class TmdbEntityMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv"),
}

enum class TmdbEntityRailType(val value: String) {
    POPULAR("popular"),
    TOP_RATED("top_rated"),
    RECENT("recent"),
}

data class TmdbEntityHeader(
    val id: Int,
    val kind: TmdbEntityKind,
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val secondaryLabel: String?,
    val description: String?,
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader,
    val rails: List<TmdbEntityRail>,
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean,
)

@Serializable
private data class TmdbCompanyDetailsResponse(
    val id: Int,
    val name: String? = null,
    val description: String? = null,
    val headquarters: String? = null,
    val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbNetworkDetailsResponse(
    val id: Int,
    val name: String? = null,
    val headquarters: String? = null,
    val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbDiscoverResponse(
    val page: Int? = null,
    val results: List<TmdbDiscoverResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("total_results") val totalResults: Int? = null,
)

@Serializable
internal data class TmdbDiscoverResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
)
