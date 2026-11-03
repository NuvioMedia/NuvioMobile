package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.cloudstream.CloudStreamLoadItem
import com.nuvio.app.features.cloudstream.CloudStreamEpisode
import com.nuvio.app.features.cloudstream.CloudStreamPluginItem
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.CloudStreamSearchItem
import com.nuvio.app.features.cloudstream.CloudStreamSearchRouteIndex
import com.nuvio.app.features.cloudstream.CloudStreamTvType
import com.nuvio.app.features.cloudstream.CloudStreamPlaybackSource
import com.nuvio.app.features.cloudstream.sha256Hex
import com.nuvio.app.features.cloudstream.sortCloudStreamEpisodes
import com.nuvio.app.features.cloudstream.toStreamItem
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_plugin_repository_fallback
import org.jetbrains.compose.resources.getString

private val cloudStreamFetchLog = Logger.withTag("CloudStreamFetch")
private val cloudStreamProviderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal data class InstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: AddonManifest,
)

internal fun ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

internal data class PluginProviderGroup(
    val addonId: String,
    val addonName: String,
    val scrapers: List<PluginScraper>,
)

internal data class CloudStreamProviderGroup(
    val addonId: String,
    val addonName: String,
    val providerName: String,
    val providerId: String,
)

internal data class CloudStreamSearchRequest(
    val title: String,
    val aliases: List<String> = emptyList(),
    val type: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String? = null,
    val externalId: String? = null,
    val genres: List<String> = emptyList(),
) {
    val titleCandidates: List<String> =
        (listOf(title) + aliases)
            .mapNotNull { candidate -> candidate.trim().takeIf(String::isNotBlank) }
            .distinctBy { candidate -> candidate.normalizedCloudStreamSearchTitle() }

    val cacheKey: String =
        "${type.trim().lowercase()}::${titleCandidates.joinToString("|") { it.lowercase() }}::" +
            "${year ?: ""}::${season ?: ""}::${episode ?: ""}::${episodeTitle.orEmpty().trim().lowercase()}::" +
            "${externalId.orEmpty().lowercase()}::${genres.joinToString(",") { it.trim().lowercase() }}"
}

internal sealed interface StreamLoadCompletion {
    data class Addon(val group: AddonStreamGroup) : StreamLoadCompletion
    data class PluginScraper(
        val addonId: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
    data class CloudStreamProvider(
        val addonId: String,
        val addonName: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
}

internal fun cloudStreamAddonId(providerId: String): String =
    "cloudstream:${sha256Hex(providerId.encodeToByteArray()).take(16)}"

internal fun cloudStreamProviderGroupsForRequest(
    type: String,
    request: CloudStreamSearchRequest?,
): List<CloudStreamProviderGroup> {
    CloudStreamRepository.initialize()
    val state = CloudStreamRepository.uiState.value
    val repositoryNames = state.repositories.associate { repository ->
        repository.manifest.sourceUrl to repository.manifest.name
    }
    return state.plugins
        .filter(CloudStreamPluginItem::isRunnable)
        .filter { plugin -> plugin.supportsCloudStreamRequest(type, request) }
        .sortedWith(
            compareBy<CloudStreamPluginItem> { plugin ->
                plugin.cloudStreamTypePriority(type)
            }.thenBy { plugin ->
                plugin.metadata.language?.lowercase() != "tr"
            }.thenBy { plugin ->
                plugin.metadata.name.lowercase()
            },
        )
        .map { plugin ->
            val repositoryUrl = plugin.metadata.repositoryManifestUrl
            val repositoryName = repositoryNames[repositoryUrl]
                ?.takeIf { it.isNotBlank() }
                ?: repositoryUrl.substringAfter("://", repositoryUrl).substringBefore('/').ifBlank { "CloudStream" }
            val providerName = plugin.metadata.name.ifBlank { plugin.metadata.internalName }
            if (state.groupStreamsByRepository) {
                CloudStreamProviderGroup(
                    addonId = "cloudstream-repo:${repositoryUrl.lowercase()}",
                    addonName = repositoryName,
                    providerName = providerName,
                    providerId = plugin.metadata.id.value,
                )
            } else {
                CloudStreamProviderGroup(
                    addonId = cloudStreamAddonId(plugin.metadata.id.value),
                    addonName = providerName,
                    providerName = providerName,
                    providerId = plugin.metadata.id.value,
                )
            }
        }
        .distinctBy { it.providerId }
}

internal suspend fun resolveCloudStreamDirectRoute(
    providerId: String,
    providerName: String,
    data: String,
): Result<List<StreamItem>> {
    val sourceResult = withTimeoutOrNull(CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS) {
        CloudStreamRepository.loadLinks(providerId, data)
    } ?: return Result.failure(IllegalStateException("CloudStream provider timed out"))
    val sources = sourceResult.getOrElse { return Result.failure(it) }
    return Result.success(
        cloudStreamSourcesToStreamItems(
            providerId = providerId,
            providerName = providerName,
            sources = sources,
        ),
    )
}

internal suspend fun resolveCloudStreamProviderStreams(
    providerGroup: CloudStreamProviderGroup,
    request: CloudStreamSearchRequest?,
): AddonStreamGroup {
    val searchRequest = request?.takeIf { it.title.isNotBlank() }
        ?: return providerGroup.toCloudStreamGroup(
            streams = emptyList(),
            error = "CloudStream search title is unavailable",
        )

    // Third-party plugins can execute blocking code that ignores coroutine cancellation.
    // Run them in a detached supervisor so a timed-out plugin cannot retain the caller's
    // semaphore permit and prevent every provider behind it from being scanned.
    val providerTask = cloudStreamProviderScope.async {
        runCatchingUnlessCancelled {
            var lastError: Throwable? = null
            suspend fun resolveMatch(
                match: CloudStreamSearchItem,
                allowProviderRankedFallback: Boolean,
            ): List<StreamItem>? {
                val loaded = runCatchingUnlessCancelled {
                    CloudStreamRepository.load(providerGroup.providerId, match.data).getOrThrow()
                }.onFailure { error -> lastError = error }
                    .getOrNull()
                    ?: return null
                if (!loaded.matchesCloudStreamRequest(
                        request = searchRequest,
                        searchItem = match,
                        allowProviderRankedFallback = allowProviderRankedFallback,
                    )
                ) return null

                val linkData = loaded.linkDataForCloudStreamRequest(searchRequest)
                if (linkData == null) {
                    lastError = IllegalStateException("CloudStream episode not found")
                    return null
                }

                val sources = runCatchingUnlessCancelled {
                    CloudStreamRepository.loadLinks(providerGroup.providerId, linkData).getOrThrow()
                }.onFailure { error -> lastError = error }
                    .getOrNull()
                    ?: return null
                return cloudStreamSourcesToStreamItems(
                    providerId = providerGroup.providerId,
                    providerName = providerGroup.providerName,
                    sources = sources,
                ).takeIf { streams -> streams.isNotEmpty() }
                    ?: run {
                        lastError = IllegalStateException("No links found")
                        null
                    }
            }

            val indexedRoutes = CloudStreamSearchRouteIndex.find(
                providerId = providerGroup.providerId,
                titles = searchRequest.titleCandidates,
                type = searchRequest.type,
                year = searchRequest.year,
            )
            for (route in indexedRoutes) {
                // A cached route was originally discovered by a title search. Do not
                // downgrade it to provider-order matching: stale/ambiguous cache entries
                // must pass the same title validation as fresh strict search results.
                resolveMatch(route, allowProviderRankedFallback = false)?.let {
                    return@runCatchingUnlessCancelled it
                }
            }

            searchRequest.externalId?.let { externalId ->
                val loaded = runCatchingUnlessCancelled {
                    CloudStreamRepository.loadByExternalId(providerGroup.providerId, externalId).getOrThrow()
                }.onFailure { error -> lastError = error }
                    .getOrNull()
                if (loaded != null && loaded.matchesCloudStreamExternalIdRequest(searchRequest)) {
                    val linkData = loaded.linkDataForCloudStreamRequest(searchRequest)
                    if (linkData != null) {
                        val directSources = runCatchingUnlessCancelled {
                            CloudStreamRepository.loadLinks(providerGroup.providerId, linkData).getOrThrow()
                        }.onFailure { error -> lastError = error }
                            .getOrNull()
                        if (directSources != null) {
                            val directStreams = cloudStreamSourcesToStreamItems(
                                providerId = providerGroup.providerId,
                                providerName = providerGroup.providerName,
                                sources = directSources,
                            )
                            if (directStreams.isNotEmpty()) return@runCatchingUnlessCancelled directStreams
                        }
                    }
                }
            }
            for (query in searchRequest.searchQueries()) {
                val searchResults = runCatchingUnlessCancelled {
                    CloudStreamRepository.search(query, providerGroup.providerId)
                        .firstOrNull()
                        ?.getOrThrow()
                        .orEmpty()
                        .distinctBy { item -> item.type to item.data }
                }.onFailure { error -> lastError = error }
                    .getOrDefault(emptyList())

                val strictMatches = searchResults.bestCloudStreamMatches(searchRequest)
                val matches = strictMatches.ifEmpty {
                    searchResults.providerRankedCloudStreamFallbacks(searchRequest)
                }
                if (matches.isEmpty()) continue
                val isProviderRankedFallback = strictMatches.isEmpty()

                for (match in matches) {
                    resolveMatch(match, isProviderRankedFallback)?.let {
                        return@runCatchingUnlessCancelled it
                    }
                }
            }

            throw lastError ?: IllegalStateException("No verified CloudStream search result found")
        }
    }
    val resolved = try {
        withTimeoutOrNull(CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS) {
            providerTask.await()
        } ?: Result.failure(IllegalStateException("CloudStream provider timed out"))
    } finally {
        if (!providerTask.isCompleted) providerTask.cancel()
    }

    return resolved.fold(
        onSuccess = { streams ->
            cloudStreamFetchLog.i { "Resolved ${streams.size} stream(s) provider=${providerGroup.addonName}" }
            providerGroup.toCloudStreamGroup(
                streams = streams,
                error = if (streams.isEmpty()) "No links found" else null,
            )
        },
        onFailure = { error ->
            cloudStreamFetchLog.w(error) { "Provider produced no streams provider=${providerGroup.addonName}" }
            providerGroup.toCloudStreamGroup(
                streams = emptyList(),
                error = error.message ?: "CloudStream link resolution failed",
            )
        },
    )
}

internal suspend fun cloudStreamSourcesToStreamItems(
    providerId: String,
    providerName: String,
    sources: List<CloudStreamPlaybackSource>,
): List<StreamItem> =
    sources
        .map { source ->
            source.toStreamItem(
                providerId = providerId,
                providerName = providerName,
            )
        }
        .distinctBy(::cloudStreamStreamIdentity)

/**
 * The same URL can represent different playable variants when providers attach
 * different request headers or subtitle tracks. Keep those variants separate.
 */
internal fun cloudStreamStreamIdentity(stream: StreamItem): String {
    val requestHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        .entries
        .sortedWith(compareBy({ it.key.lowercase() }, { it.value }))
        .joinToString("\u001f") { "${it.key.lowercase()}=${it.value}" }
    val subtitleIdentity = stream.externalSubtitles.joinToString("\u001e") { subtitle ->
        listOf(
            subtitle.url,
            subtitle.language,
            subtitle.name.orEmpty(),
            subtitle.headers.orEmpty().entries
                .sortedWith(compareBy({ it.key.lowercase() }, { it.value }))
                .joinToString("\u001f") { "${it.key.lowercase()}=${it.value}" },
        ).joinToString("\u001d")
    }
    return listOf(
        stream.addonId,
        stream.sourceName.orEmpty(),
        stream.streamLabel,
        stream.playableDirectUrl.orEmpty(),
        stream.streamType.orEmpty(),
        requestHeaders,
        subtitleIdentity,
    ).joinToString("\u001c")
}

internal fun List<PluginScraper>.toPluginProviderGroups(
    repositories: List<PluginRepositoryItem>,
    groupByRepository: Boolean,
): List<PluginProviderGroup> {
    if (!groupByRepository) {
        return map { scraper ->
            PluginProviderGroup(
                addonId = "plugin:${scraper.id}",
                addonName = scraper.name,
                scrapers = listOf(scraper),
            )
        }
    }

    val repoNameByUrl = repositories.associate { it.manifestUrl to it.name }
    return groupBy { it.repositoryUrl }
        .map { (repositoryUrl, scrapers) ->
            PluginProviderGroup(
                addonId = "plugin-repo:${repositoryUrl.lowercase()}",
                addonName = repoNameByUrl[repositoryUrl].orEmpty().ifBlank { repositoryUrl.fallbackRepositoryLabel() },
                scrapers = scrapers.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.addonName.lowercase() }
}

internal fun List<AddonStreamGroup>.toEmptyStateReason(anyLoading: Boolean): StreamsEmptyStateReason? {
    if (anyLoading || any { it.streams.isNotEmpty() }) {
        return null
    }

    return if (isNotEmpty() && all { !it.error.isNullOrBlank() }) {
        StreamsEmptyStateReason.StreamFetchFailed
    } else {
        StreamsEmptyStateReason.NoStreamsFound
    }
}

internal fun buildCloudStreamSearchRequest(
    type: String,
    videoId: String,
    parentMetaId: String?,
    parentMetaType: String?,
    season: Int?,
    episode: Int?,
    searchTitle: String?,
): CloudStreamSearchRequest? {
    val metaLookupType = parentMetaType?.takeIf { it.isNotBlank() } ?: type
    val metaLookupId = parentMetaId?.takeIf { it.isNotBlank() } ?: videoId
    val meta = MetaDetailsRepository.peek(metaLookupType, metaLookupId)
    val title = searchTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: meta?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val aliases = listOfNotNull(meta?.name, searchTitle)
        .cleanCloudStreamRequestAliases(primaryTitle = title)
    val year = meta?.releaseInfo.cloudStreamReleaseYear()
        ?: title.cloudStreamReleaseYear()
    val episodeTitle = meta
        ?.videos
        ?.firstOrNull { video ->
            (season == null || video.season == season) &&
                (episode == null || video.episode == episode)
        }
        ?.title
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val externalId = sequenceOf(videoId, parentMetaId, metaLookupId)
        .filterNotNull()
        .map(String::trim)
        .firstOrNull { id -> id.matches(Regex("^tt\\d{5,12}$", RegexOption.IGNORE_CASE)) }

    return CloudStreamSearchRequest(
        title = title,
        aliases = aliases,
        type = type,
        year = year,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        externalId = externalId,
        genres = meta?.genres.orEmpty(),
    )
}

private fun List<String>.cleanCloudStreamRequestAliases(primaryTitle: String): List<String> {
    val primary = primaryTitle.trim()
    return mapNotNull { title ->
        title.trim().takeIf(String::isNotBlank)
    }
        .filterNot { title -> primary.isNotBlank() && title.equals(primary, ignoreCase = true) }
        .distinctBy { title -> title.lowercase() }
}

internal suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

// CloudStream providers own stage-specific timeout hints. The host timeout only
// prevents a permanently stuck provider; it must leave enough room for the
// callback-based loadLinks contract, whose upstream default is measured in minutes.
private const val CLOUDSTREAM_PROVIDER_STREAM_TIMEOUT_MS = 120_000L
private const val CLOUDSTREAM_SEARCH_CANDIDATE_LIMIT = 3
// Provider-ranked candidates need a wider scan because the first few cards may be
// unrelated; each candidate is still independently verified after load().
private const val CLOUDSTREAM_PROVIDER_RANKED_CANDIDATE_LIMIT = 10

internal fun PluginRuntimeResult.toStreamItem(
    scraper: PluginScraper,
    addonName: String = scraper.name,
    addonId: String = "plugin:${scraper.id}",
    includeScraperNameInSubtitle: Boolean = false,
): StreamItem {
    val subtitleParts = listOfNotNull(
        scraper.name.takeIf { includeScraperNameInSubtitle && it.isNotBlank() },
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val headerName = key.trim()
            val headerValue = value.trim()
            if (headerName.isBlank() || headerValue.isBlank() || headerName.equals("Range", ignoreCase = true)) {
                null
            } else {
                headerName to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        title = title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        sourceName = provider?.takeIf { it.isNotBlank() } ?: scraper.name,
        addonName = addonName,
        addonId = addonId,
        streamType = normalizeStreamType(type),
        behaviorHints = StreamBehaviorHints(
            filename = filename?.takeIf { it.isNotBlank() },
            notWebReady = requestHeaders.isNotEmpty(),
            proxyHeaders = requestHeaders.takeIf { it.isNotEmpty() }?.let { StreamProxyHeaders(request = it) },
        ),
        externalSubtitles = subtitles?.map {
            StreamSubtitle(
                url = it.url,
                language = it.language,
                name = it.name,
                headers = it.headers
            )
        } ?: emptyList()
    )
}

/**
 * Identity for native plugin results across providers. Provider labels are deliberately
 * excluded so mirrors of the same file are collapsed, while different request headers,
 * subtitles, hashes, or filenames remain independently playable.
 */
internal fun pluginStreamIdentity(stream: StreamItem): String =
    crossProviderStreamIdentity(stream)

/**
 * Stable playable identity shared across providers. Provider names, repository IDs,
 * display labels and subtitle metadata are intentionally excluded: they describe
 * attribution, not a different video source. Request headers remain part of identity
 * because the same URL can require different credentials or referers.
 */
internal fun crossProviderStreamIdentity(stream: StreamItem): String {
    val requestHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        .entries
        .sortedWith(compareBy({ it.key.lowercase() }, { it.value }))
        .joinToString("\u001f") { "${it.key.lowercase()}=${it.value}" }
    val playableUrl = stream.playableDirectUrl.orEmpty().trim()
    val fallbackUrl = stream.url.orEmpty().trim()
    val infoHash = stream.infoHash?.trim()?.lowercase().orEmpty()
    val externalUrl = stream.externalUrl?.trim().orEmpty()
    val primaryIdentity = (playableUrl.ifBlank { fallbackUrl }).trimEnd('/')
    if (primaryIdentity.isBlank() && infoHash.isBlank() && externalUrl.isBlank()) {
        // Metadata-only results cannot safely be collapsed across providers.
        return "unresolved:${stream.sourceName.orEmpty()}:${stream.name}"
    }
    return listOf(
        primaryIdentity,
        infoHash,
        externalUrl,
        requestHeaders,
    ).joinToString("\u001c")
}

internal fun List<StreamItem>.sortedForGroupedDisplay(): List<StreamItem> =
    sortedWith(
        compareBy<StreamItem>(
            { it.sourceName.orEmpty().lowercase() },
            { it.streamLabel.lowercase() },
            { it.streamSubtitle.orEmpty().lowercase() },
        ),
    )

private fun String.fallbackRepositoryLabel(): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank {
            runBlocking { getString(Res.string.streams_plugin_repository_fallback) }
        }
    }
}

private fun CloudStreamPluginItem.supportsCloudStreamRequest(
    type: String,
    request: CloudStreamSearchRequest?,
): Boolean {
    val requestedType = normalizeCloudStreamNuvioType(type)
    val supportedTypes = metadata.tvTypes.toSet()
    if (supportedTypes.isEmpty() || CloudStreamTvType.Other in supportedTypes) return true

    val normalizedGenres = request?.genres.orEmpty().map { it.trim().lowercase() }.toSet()
    val isAnime = normalizedGenres.any { genre -> genre == "anime" }
    val isAnimation = normalizedGenres.any { genre -> genre == "animation" || genre == "animasyon" }
    val isDocumentary = normalizedGenres.any { genre -> genre == "documentary" || genre == "belgesel" }

    return when (requestedType) {
        "movie" -> when {
            isAnime -> supportedTypes.any { it == CloudStreamTvType.AnimeMovie || it == CloudStreamTvType.Movie }
            isAnimation -> supportedTypes.any {
                it == CloudStreamTvType.AnimeMovie || it == CloudStreamTvType.Cartoon || it == CloudStreamTvType.Movie
            }
            isDocumentary -> supportedTypes.any { it == CloudStreamTvType.Documentary || it == CloudStreamTvType.Movie }
            else -> CloudStreamTvType.Movie in supportedTypes
        }
        "series" -> when {
            isAnime -> supportedTypes.any {
                it == CloudStreamTvType.Anime || it == CloudStreamTvType.Ova || it == CloudStreamTvType.TvSeries
            }
            isAnimation -> supportedTypes.any {
                it == CloudStreamTvType.Anime || it == CloudStreamTvType.Ova ||
                    it == CloudStreamTvType.Cartoon || it == CloudStreamTvType.TvSeries
            }
            else -> CloudStreamTvType.TvSeries in supportedTypes
        }
        "live" -> CloudStreamTvType.Live in supportedTypes || CloudStreamTvType.Music in supportedTypes
        else -> supportedTypes.any { cloudType -> normalizeCloudStreamNuvioType(cloudType.nuvioType) == requestedType }
    }
}

private fun CloudStreamPluginItem.cloudStreamTypePriority(type: String): Int {
    val requestedType = normalizeCloudStreamNuvioType(type)
    val types = metadata.tvTypes.toSet()
    return when (requestedType) {
        "movie" -> when {
            CloudStreamTvType.Movie in types -> 0
            CloudStreamTvType.AnimeMovie in types -> 1
            CloudStreamTvType.Documentary in types -> 2
            types.isEmpty() || CloudStreamTvType.Other in types -> 3
            else -> 4
        }
        "series" -> when {
            CloudStreamTvType.TvSeries in types -> 0
            CloudStreamTvType.AsianDrama in types -> 1
            CloudStreamTvType.Anime in types || CloudStreamTvType.Cartoon in types || CloudStreamTvType.Ova in types -> 2
            types.isEmpty() || CloudStreamTvType.Other in types -> 3
            else -> 4
        }
        else -> if (types.isEmpty() || types.any { it.nuvioType == requestedType }) 0 else 1
    }
}

private fun CloudStreamProviderGroup.toCloudStreamGroup(
    streams: List<StreamItem>,
    error: String? = null,
): AddonStreamGroup = AddonStreamGroup(
    addonName = addonName,
    addonId = addonId,
    streams = streams.sortedForGroupedDisplay(),
    isLoading = false,
    error = error,
)

internal fun List<CloudStreamSearchItem>.bestCloudStreamMatch(
    searchTitle: String,
    type: String,
): CloudStreamSearchItem? {
    return bestCloudStreamMatches(
        CloudStreamSearchRequest(
            title = searchTitle,
            type = type,
            year = searchTitle.cloudStreamReleaseYear(),
            season = null,
            episode = null,
        ),
    ).firstOrNull()
}

internal fun List<CloudStreamSearchItem>.bestCloudStreamMatches(
    request: CloudStreamSearchRequest,
): List<CloudStreamSearchItem> {
    val requestedType = normalizeCloudStreamNuvioType(request.type)
    val typedCandidates = filter { item -> item.type.matchesCloudStreamType(requestedType) }
    val candidates = typedCandidates.ifEmpty { this }
    val requestedTitles = request.titleCandidates
        .map { title -> title.normalizedCloudStreamSearchTitle() }
        .filter { title -> title.isNotBlank() }
        .distinct()
    return candidates
        .mapNotNull { item ->
            requestedTitles
                .mapIndexedNotNull { index, requestedTitle ->
                    item.matchScore(requestedTitle, request.year)?.let { score ->
                        score + if (index == 0) 5 else 0
                    }
                }
                .maxOrNull()
                ?.let { score -> item to score }
        }
        .sortedWith(
            compareByDescending<Pair<CloudStreamSearchItem, Int>> { (_, score) -> score }
                .thenBy { (item, _) -> item.name.length },
        )
        .map { (item, _) -> item }
        .distinctBy { item -> item.data }
        .take(CLOUDSTREAM_SEARCH_CANDIDATE_LIMIT)
}

private fun List<CloudStreamSearchItem>.providerRankedCloudStreamFallbacks(
    request: CloudStreamSearchRequest,
): List<CloudStreamSearchItem> {
    val requestedType = normalizeCloudStreamNuvioType(request.type)
    val typedCandidates = filter { item -> item.type.matchesCloudStreamType(requestedType) }
    // A significant number of real CloudStream providers label every search card as
    // Movie and only reveal TvSeries from load(url). This mirrors CloudStream itself:
    // trust provider search ranking here, then validate the actual LoadResponse type.
    val candidates = typedCandidates.ifEmpty { this }
    return candidates
        .filter { item ->
            val candidateYear = item.year
                ?: item.name.normalizedCloudStreamSearchTitle().trailingCloudStreamReleaseYear()
            request.year == null || candidateYear == null || candidateYear == request.year
        }
        // Provider search endpoints already return relevance-ranked results. A localized
        // title (for example "Les Evades") must not be discarded solely because it is
        // different from the metadata title used for the query.
        .distinctBy { item -> item.data }
        .take(CLOUDSTREAM_PROVIDER_RANKED_CANDIDATE_LIMIT)
}

private fun CloudStreamSearchItem.matchScore(requestedTitle: String, requestedYear: Int?): Int? {
    val candidateTitle = name.normalizedCloudStreamSearchTitle()
    val requestedCanonical = requestedTitle.cloudStreamCanonicalTitle()
    val candidateCanonical = candidateTitle.cloudStreamCanonicalTitle()
    if (requestedCanonical.isBlank() || candidateCanonical.isBlank()) return null

    val candidateYear = year ?: candidateTitle.trailingCloudStreamReleaseYear()
    if (requestedYear != null && candidateYear != null && requestedYear != candidateYear) return null

    val yearBonus = when {
        requestedYear != null && candidateYear == requestedYear -> 25
        candidateYear != null -> 1
        else -> 0
    }
    return when {
        candidateTitle == requestedTitle -> 100 + yearBonus
        candidateCanonical == requestedCanonical -> 90 + yearBonus
        else -> null
    }
}

internal fun CloudStreamLoadItem.linkDataForCloudStreamRequest(
    request: CloudStreamSearchRequest,
): String? {
    val requestedType = normalizeCloudStreamNuvioType(request.type)
    val sortedEpisodes = sortCloudStreamEpisodes(episodes)
    if (requestedType != "series" || sortedEpisodes.isEmpty()) return data

    if (request.season == null && request.episode == null) {
        return sortedEpisodes.firstOrNull()?.data
    }

    if (request.season != null && request.episode != null) {
        val exactEpisodes = sortedEpisodes.filter { item ->
            item.season == request.season && item.episode == request.episode
        }
        val exactTitleMatch = exactEpisodes.bestEpisodeTitleMatch(request)
        if (exactTitleMatch != null) return exactTitleMatch.data

        // A numbered episode is not enough when metadata supplies a meaningful title.
        // Only fall back to the numeric S/E identity when the provider's episode title
        // is generic/missing, or when there is no meaningful requested title.
        val exactNumberMatch = exactEpisodes.singleOrNull()
        if (exactNumberMatch != null &&
            exactNumberMatch.hasNoSpecificEpisodeTitle(request)
        ) {
            return exactNumberMatch.data
        }

        // Unknown-season fallback is deliberately last and must independently prove
        // that this episode is safe; never cross an explicit season boundary.
        return if (exactEpisodes.isEmpty()) {
            sortedEpisodes.safeUnknownSeasonEpisodeMatch(request)?.data
        } else {
            null
        }
    }

    if (request.episode != null) {
        val episodeMatches = sortedEpisodes.filter { item -> item.episode == request.episode }
        // Episode numbers repeat across seasons. Without an explicit season, only a
        // title match or a unique candidate is safe; never silently choose the first.
        episodeMatches.bestEpisodeTitleMatch(request)?.let { return it.data }
        val uniqueEpisode = episodeMatches.singleOrNull()
        return uniqueEpisode
            ?.takeIf { it.hasNoSpecificEpisodeTitle(request) }
            ?.data
    }

    if (request.season != null) {
        val seasonMatches = sortedEpisodes.filter { item -> item.season == request.season }
        return seasonMatches.bestEpisodeTitleMatch(request)?.data
            ?: seasonMatches.firstOrNull()?.data
    }

    return null
}

private fun CloudStreamEpisode.hasNoSpecificEpisodeTitle(
    request: CloudStreamSearchRequest,
): Boolean {
    val requestedTitle = request.episodeTitle
        ?.normalizedCloudStreamSearchTitle()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it.isGenericCloudStreamEpisodeTitle() }
        ?: return true
    val providerTitle = name.normalizedCloudStreamSearchTitle()
    return providerTitle.isBlank() || providerTitle.isGenericCloudStreamEpisodeTitle() ||
        providerTitle == requestedTitle
}

private fun List<CloudStreamEpisode>.bestEpisodeTitleMatch(
    request: CloudStreamSearchRequest,
): CloudStreamEpisode? {
    val requestedTitle = request.episodeTitle
        ?.normalizedCloudStreamSearchTitle()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless { it.isGenericCloudStreamEpisodeTitle() }
        ?: return null

    val matches = filter { item ->
        val candidateTitle = item.name.normalizedCloudStreamSearchTitle()
        candidateTitle.matchesRequestedCloudStreamEpisodeTitle(requestedTitle)
    }
    if (request.season == null && matches.map(CloudStreamEpisode::season).distinct().size > 1) {
        return null
    }
    return matches.firstOrNull()
}

private fun List<CloudStreamEpisode>.safeUnknownSeasonEpisodeMatch(
    request: CloudStreamSearchRequest,
): CloudStreamEpisode? {
    val episodeNumber = request.episode ?: return null
    val unknownSeasonMatches = filter { item ->
        item.season == null && item.episode == episodeNumber
    }
    if (unknownSeasonMatches.isEmpty()) return null

    unknownSeasonMatches.bestEpisodeTitleMatch(request)?.let { return it }

    val explicitSeasons = mapNotNull { item -> item.season?.takeIf { it > 0 } }.toSet()
    if (explicitSeasons.size > 1) return null

    val requestedSeason = request.season
    return unknownSeasonMatches.singleOrNull()
        ?.takeIf { it.hasNoSpecificEpisodeTitle(request) }
        ?.takeIf { requestedSeason == null || requestedSeason == 1 || requestedSeason in explicitSeasons }
}

internal fun CloudStreamLoadItem.matchesCloudStreamRequest(
    request: CloudStreamSearchRequest,
    searchItem: CloudStreamSearchItem,
    allowProviderRankedFallback: Boolean = false,
): Boolean {
    val requestedType = normalizeCloudStreamNuvioType(request.type)
    if (!type.matchesCloudStreamType(requestedType)) return false

    val requestedTitles = request.titleCandidates
        .map { title -> title.normalizedCloudStreamSearchTitle() }
        .filter { title -> title.isNotBlank() }
        .distinct()
    val loadedTitle = name.normalizedCloudStreamSearchTitle()
    val titleMatches = requestedTitles.any { requestedTitle ->
        loadedTitle.matchesRequestedCloudStreamTitle(requestedTitle)
    }
    if (!titleMatches && !allowProviderRankedFallback) {
        return false
    }

    val requestedYear = request.year
    val searchYear = searchItem.year ?: searchItem.name.normalizedCloudStreamSearchTitle().trailingCloudStreamReleaseYear()
    val loadedYear = year ?: loadedTitle.trailingCloudStreamReleaseYear()
    if (requestedYear != null) {
        if (searchYear != null && searchYear != requestedYear) return false
        if (loadedYear != null && loadedYear != requestedYear) return false
        if (requestedType == "movie" && searchYear != requestedYear && loadedYear != requestedYear) return false
    }

    if (!titleMatches && allowProviderRankedFallback) {
        // Search rank plus a matching year is insufficient to identify a TV series:
        // many unrelated series share the same release year. Keep localized-title
        // fallback for movies, where an exact release-year match is a useful second
        // identity signal, but never use it to accept an unrelated series.
        if (requestedType == "series") return false
        if (requestedYear == null || loadedYear != requestedYear) return false
    }

    return titleMatches || (allowProviderRankedFallback && requestedType != "series")
}

internal fun CloudStreamLoadItem.matchesCloudStreamExternalIdRequest(
    request: CloudStreamSearchRequest,
): Boolean {
    val requestedType = normalizeCloudStreamNuvioType(request.type)
    if (!type.matchesCloudStreamType(requestedType)) return false

    val requestedTitles = request.titleCandidates
        .map { it.normalizedCloudStreamSearchTitle() }
        .filter(String::isNotBlank)
    val loadedTitle = name.normalizedCloudStreamSearchTitle()
    val titleMatches = requestedTitles.any { requested ->
        loadedTitle.matchesRequestedCloudStreamTitle(requested)
    }
    val requestedYear = request.year
    val loadedYear = year ?: loadedTitle.trailingCloudStreamReleaseYear()
    if (requestedYear != null && loadedYear != requestedYear) return false
    if (titleMatches) return true

    // External-ID lookups may legitimately return localized movie titles. For
    // series, however, many unrelated shows share a year, so a year-only match is
    // not enough to authorize loading their episodes.
    if (requestedType == "series") return false
    return requestedYear != null && loadedYear == requestedYear
}

private fun CloudStreamTvType.matchesCloudStreamType(type: String): Boolean =
    normalizeCloudStreamNuvioType(nuvioType) == type

private fun normalizeCloudStreamNuvioType(type: String): String = when (type.trim().lowercase()) {
    "tv", "show" -> "series"
    "livetv", "channel" -> "live"
    else -> type.trim().lowercase().ifBlank { "other" }
}

internal fun String?.cloudStreamReleaseYear(): Int? =
    this
        ?.let { cloudStreamReleaseYearRegex.find(it) }
        ?.value
        ?.toIntOrNull()
        ?.takeIf { it in 1900..2099 }

private fun CloudStreamSearchRequest.searchQueries(): List<String> {
    return titleCandidates.flatMap { candidate ->
        val title = candidate.trim()
        val yearQuery = year?.let { "$title $it" }
        // Most CloudStream providers implement literal site searches. The unmodified
        // title must be attempted first; adding a year often turns a valid result into
        // an empty page on WordPress-style providers such as Bollyflix.
        listOfNotNull(title, yearQuery)
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.normalizedCloudStreamSearchTitle() }
}

private fun String.normalizedCloudStreamSearchTitle(): String =
    lowercase()
        .map { char -> if (char.isLetterOrDigit()) char else ' ' }
        .joinToString("")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun String.cloudStreamCanonicalTitle(): String =
    withoutTrailingCloudStreamReleaseYear()
        .withoutLeadingCloudStreamArticle()

private fun String.matchesRequestedCloudStreamTitle(requestedTitle: String): Boolean =
    this == requestedTitle || cloudStreamCanonicalTitle() == requestedTitle.cloudStreamCanonicalTitle()

private fun String.matchesRequestedCloudStreamEpisodeTitle(requestedTitle: String): Boolean {
    val candidate = cloudStreamCanonicalTitle()
    val requested = requestedTitle.cloudStreamCanonicalTitle()
    if (candidate.isBlank() || requested.isBlank()) return false
    return candidate == requested ||
        candidate.startsWith("$requested ") ||
        candidate.endsWith(" $requested")
}

private fun String.isGenericCloudStreamEpisodeTitle(): Boolean =
    matches(cloudStreamGenericEpisodeTitleRegex)

private fun String.withoutTrailingCloudStreamReleaseYear(): String {
    val tokens = split(' ').filter { it.isNotBlank() }
    if (tokens.size <= 1) return this
    val last = tokens.last()
    val year = last.toIntOrNull()
    return if (year != null && year in 1900..2099) {
        tokens.dropLast(1).joinToString(" ")
    } else {
        this
    }
}

private fun String.withoutLeadingCloudStreamArticle(): String {
    val tokens = split(' ').filter { it.isNotBlank() }
    if (tokens.size <= 1) return this
    return if (tokens.first() in cloudStreamLeadingArticles) {
        tokens.drop(1).joinToString(" ")
    } else {
        this
    }
}

private fun String.trailingCloudStreamReleaseYear(): Int? {
    val tokens = split(' ').filter { it.isNotBlank() }
    if (tokens.size <= 1) return null
    return tokens.last().toIntOrNull()?.takeIf { it in 1900..2099 }
}

private val cloudStreamLeadingArticles = setOf("a", "an", "the")
private val cloudStreamReleaseYearRegex = Regex("""\b(?:19|20)\d{2}\b""")
private val cloudStreamGenericEpisodeTitleRegex = Regex("""(?i)(?:s\d{1,2}\s*e\d{1,3}|(?:episode|ep|bolum|bölüm)\s*\d{1,4})""")
