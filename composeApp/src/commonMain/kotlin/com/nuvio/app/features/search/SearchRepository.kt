package com.nuvio.app.features.search

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.firstEnabledManifestError
import com.nuvio.app.features.addons.hasPendingEnabledManifests
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.catalog.CATALOG_PAGE_SIZE
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.buildCatalogUrl
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.features.catalog.nextCatalogPaginationState
import com.nuvio.app.features.catalog.supportsPagination
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal fun <T> canReuseRequestState(
    forceRefresh: Boolean,
    requestKey: T,
    cachedRequestKey: T?,
): Boolean = !forceRefresh && requestKey == cachedRequestKey

internal fun resolveDiscoverCatalog(
    sources: List<DiscoverCatalogOption>,
    preferredCatalogKey: String?,
    currentCatalogKey: String?,
): DiscoverCatalogOption? =
    sources.firstOrNull { it.key == preferredCatalogKey }
        ?: sources.firstOrNull { it.key == currentCatalogKey }
        ?: sources.firstOrNull()

private data class DiscoverRequestKey(
    val sources: List<DiscoverCatalogOption>,
    val hideUnreleasedContent: Boolean,
    val hasPendingAddonManifests: Boolean,
)

object SearchRepository {
    private val log = Logger.withTag("SearchRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val _discoverUiState = MutableStateFlow(DiscoverUiState())
    val discoverUiState: StateFlow<DiscoverUiState> = _discoverUiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeDiscoverJob: Job? = null
    private var lastRequestKey: String? = null
    private var discoverSources: List<DiscoverCatalogOption> = emptyList()
    private var lastDiscoverRequestKey: DiscoverRequestKey? = null

    fun search(
        query: String,
        addons: List<ManagedAddon>,
        forceRefresh: Boolean = false,
    ) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            clear()
            return
        }

        val enabledAddons = addons.enabledAddons()
        val hasPendingAddonManifests = enabledAddons.hasPendingEnabledManifests()
        val addonManifestErrorMessage = enabledAddons.firstEnabledManifestError()
        val activeAddons = enabledAddons.filter { it.manifest != null }
        val serverLibraries = ServerCatalog.titleLibraries()
        if (activeAddons.isEmpty() && serverLibraries.isEmpty()) {
            activeJob?.cancel()
            lastRequestKey = null
            _uiState.value = SearchUiState(
                isLoading = hasPendingAddonManifests,
                emptyStateReason = when {
                    hasPendingAddonManifests -> null
                    addonManifestErrorMessage != null -> SearchEmptyStateReason.RequestFailed
                    else -> SearchEmptyStateReason.NoActiveAddons
                },
                errorMessage = addonManifestErrorMessage,
            )
            return
        }

        val requests = buildSearchRequests(
            addons = activeAddons,
            query = normalizedQuery,
        )
        if (requests.isEmpty() && serverLibraries.isEmpty()) {
            activeJob?.cancel()
            lastRequestKey = null
            _uiState.value = SearchUiState(
                isLoading = hasPendingAddonManifests,
                emptyStateReason = if (hasPendingAddonManifests) null else SearchEmptyStateReason.NoSearchCatalogs,
            )
            return
        }

        val requestKey = buildString {
            append(normalizedQuery.lowercase())
            append('|')
            append(HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent)
            append('|')
            append(hasPendingAddonManifests)
            append('|')
            append(
                requests.joinToString(separator = "|") { request ->
                    "${request.addon.manifestUrl}:${request.type}:${request.catalogId}"
                },
            )
            serverLibraries.forEach { ref -> append("|server:${ref.connection.id}:${ref.library.id}") }
        }
        val loaders: List<suspend () -> HomeCatalogSection> =
            requests.map { request -> suspend { request.toSection(forceRefresh = forceRefresh) } } +
                serverLibraries.map { ref ->
                    suspend {
                        ServerCatalog.searchSection(ref, normalizedQuery).also { section ->
                            require(section.items.isNotEmpty()) {
                                getString(Res.string.search_error_no_results_for_catalog, section.title)
                            }
                        }
                    }
                }
        if (canReuseRequestState(forceRefresh, requestKey, lastRequestKey)) return
        lastRequestKey = requestKey

        activeJob?.cancel()
        _uiState.value = SearchUiState(isLoading = true)

        activeJob = scope.launch {
            val resultChannel = Channel<IndexedSearchResult>(Channel.UNLIMITED)
            val jobs = loaders.mapIndexed { index, load ->
                launch {
                    runCatching { load() }
                        .fold(
                            onSuccess = { section ->
                                resultChannel.trySend(
                                    IndexedSearchResult(
                                        index = index,
                                        section = section,
                                    ),
                                )
                            },
                            onFailure = { error ->
                                if (error is CancellationException) throw error
                                resultChannel.trySend(
                                    IndexedSearchResult(
                                        index = index,
                                        error = error,
                                    ),
                                )
                            },
                        )
                }
            }
            val closeChannelJob = launch {
                jobs.joinAll()
                resultChannel.close()
            }
            val results = arrayOfNulls<IndexedSearchResult>(loaders.size)

            try {
                for (result in resultChannel) {
                    results[result.index] = result
                    val sections = results.orderedSections()
                    if (sections.isNotEmpty()) {
                        _uiState.value = SearchUiState(
                            isLoading = true,
                            sections = sections,
                        )
                    }
                }
            } finally {
                closeChannelJob.cancel()
                resultChannel.close()
            }

            val completedResults = results.filterNotNull()
            val sections = results.orderedSections()
            val firstFailure = completedResults.firstNotNullOfOrNull { it.error?.message }
            val allFailed = completedResults.isNotEmpty() && completedResults.all { it.error != null }

            _uiState.value = SearchUiState(
                isLoading = sections.isEmpty() && hasPendingAddonManifests,
                sections = sections,
                emptyStateReason = when {
                    sections.isNotEmpty() -> null
                    hasPendingAddonManifests -> null
                    allFailed -> SearchEmptyStateReason.RequestFailed
                    else -> SearchEmptyStateReason.NoResults
                },
                errorMessage = if (allFailed) firstFailure else null,
            )
        }
    }

    fun clear() {
        activeJob?.cancel()
        lastRequestKey = null
        _uiState.value = SearchUiState()
    }

    fun reset() {
        activeJob?.cancel()
        activeDiscoverJob?.cancel()
        lastRequestKey = null
        discoverSources = emptyList()
        lastDiscoverRequestKey = null
        _uiState.value = SearchUiState()
        _discoverUiState.value = DiscoverUiState()
    }

    fun refreshDiscover(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean = false,
    ) {
        val enabledAddons = addons.enabledAddons()
        val hasPendingAddonManifests = enabledAddons.hasPendingEnabledManifests()
        val addonManifestErrorMessage = enabledAddons.firstEnabledManifestError()
        val activeAddons = enabledAddons.filter { it.manifest != null }
        val serverLibraries = ServerCatalog.titleLibraries()
        if (activeAddons.isEmpty() && serverLibraries.isEmpty()) {
            activeDiscoverJob?.cancel()
            discoverSources = emptyList()
            lastDiscoverRequestKey = null
            log.d { "Discover refresh aborted: no active addons" }
            _discoverUiState.value = DiscoverUiState(
                isLoading = hasPendingAddonManifests,
                emptyStateReason = when {
                    hasPendingAddonManifests -> null
                    addonManifestErrorMessage != null -> DiscoverEmptyStateReason.RequestFailed
                    else -> DiscoverEmptyStateReason.NoActiveAddons
                },
                errorMessage = addonManifestErrorMessage,
            )
            return
        }

        val sources = buildDiscoverSources(activeAddons) + serverLibraries.map { ref ->
            DiscoverCatalogOption(
                key = ref.target.let { "server:${it.connectionId}:${it.libraryId}" },
                addonName = ServerRepository.sourceLabel(ref.connection),
                manifestUrl = "",
                type = ref.library.kind.contentType,
                catalogId = ref.library.id,
                catalogName = ref.title,
                supportsPagination = true,
                server = ref.target,
            )
        }
        val current = _discoverUiState.value
        val hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent
        val requestKey = DiscoverRequestKey(
            sources = sources,
            hideUnreleasedContent = hideUnreleasedContent,
            hasPendingAddonManifests = hasPendingAddonManifests,
        )
        if (canReuseRequestState(forceRefresh, requestKey, lastDiscoverRequestKey)) {
            log.d {
                "Reusing discover state type=${current.selectedType} catalog=${current.selectedCatalogKey} " +
                    "genre=${current.selectedGenre ?: "<all>"} items=${current.items.size} nextSkip=${current.nextSkip}"
            }
            return
        }

        discoverSources = sources
        lastDiscoverRequestKey = requestKey
        if (sources.isEmpty()) {
            activeDiscoverJob?.cancel()
            log.d { "Discover refresh found no compatible discover catalogs" }
            _discoverUiState.value = DiscoverUiState(
                isLoading = hasPendingAddonManifests,
                emptyStateReason = if (hasPendingAddonManifests) null else DiscoverEmptyStateReason.NoDiscoverCatalogs,
            )
            return
        }

        val preferredCatalogKey = DiscoverSelectionStorage.loadCatalogKey()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val selectedCatalog = requireNotNull(
            resolveDiscoverCatalog(
                sources = sources,
                preferredCatalogKey = preferredCatalogKey,
                currentCatalogKey = current.selectedCatalogKey,
            ),
        )
        val typeOptions = sources.map { it.type }.distinct()
        val selectedType = selectedCatalog.type
        val catalogOptions = sources.filter { it.type == selectedType }
        val selectedGenre = selectedCatalog.resolveGenreSelection(current.selectedGenre)

        _discoverUiState.value = DiscoverUiState(
            typeOptions = typeOptions,
            selectedType = selectedType,
            catalogOptions = catalogOptions,
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedGenre,
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )

        log.d {
            "Discover refresh prepared type=$selectedType catalog=${selectedCatalog.key} " +
                "genre=${selectedGenre ?: "<all>"} sources=${sources.size}"
        }

        loadDiscoverFeed(
            reset = true,
            forceRefresh = forceRefresh,
        )
    }

    fun selectDiscoverType(type: String) {
        val current = _discoverUiState.value
        if (current.selectedType == type) return

        val catalogOptions = discoverSources.filter { it.type == type }
        val selectedCatalog = catalogOptions.firstOrNull() ?: run {
            _discoverUiState.value = current.copy(
                selectedType = type,
                catalogOptions = emptyList(),
                selectedCatalogKey = null,
                selectedGenre = null,
                items = emptyList(),
                isLoading = false,
                nextSkip = null,
                emptyStateReason = DiscoverEmptyStateReason.NoDiscoverCatalogs,
                errorMessage = null,
            )
            return
        }

        _discoverUiState.value = current.copy(
            selectedType = type,
            catalogOptions = catalogOptions,
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedCatalog.resolveGenreSelection(null),
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        DiscoverSelectionStorage.saveCatalogKey(selectedCatalog.key)
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun selectDiscoverCatalog(catalogKey: String) {
        val current = _discoverUiState.value
        if (current.selectedCatalogKey == catalogKey) return

        val selectedCatalog = current.catalogOptions.firstOrNull { it.key == catalogKey } ?: return
        _discoverUiState.value = current.copy(
            selectedCatalogKey = selectedCatalog.key,
            selectedGenre = selectedCatalog.resolveGenreSelection(null),
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        DiscoverSelectionStorage.saveCatalogKey(selectedCatalog.key)
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun selectDiscoverGenre(genre: String?) {
        val current = _discoverUiState.value
        val selectedCatalog = current.selectedCatalog ?: return
        val normalizedGenre = selectedCatalog.resolveGenreSelection(genre)
        if (current.selectedGenre == normalizedGenre) return

        _discoverUiState.value = current.copy(
            selectedGenre = normalizedGenre,
            items = emptyList(),
            isLoading = false,
            nextSkip = null,
            emptyStateReason = null,
            errorMessage = null,
        )
        loadDiscoverFeed(
            reset = true,
            forceRefresh = false,
        )
    }

    fun loadMoreDiscover() {
        val current = _discoverUiState.value
        if (current.isLoading || current.nextSkip == null) return
        loadDiscoverFeed(
            reset = false,
            forceRefresh = false,
        )
    }

    private fun buildSearchRequests(
        addons: List<ManagedAddon>,
        query: String,
    ): List<SearchCatalogRequest> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.supportsSearch() }
                .map { catalog ->
                    SearchCatalogRequest(
                        addon = addon,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.type,
                        query = query,
                        supportsPagination = catalog.supportsPagination(),
                    )
                }
        }

    private fun buildDiscoverSources(addons: List<ManagedAddon>): List<DiscoverCatalogOption> =
        addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            addon to manifest
        }.flatMap { (addon, manifest) ->
            manifest.catalogs
                .filter { catalog -> catalog.supportsDiscover() }
                .map { catalog ->
                    val genreExtra = catalog.genreExtra()
                    DiscoverCatalogOption(
                        key = "${manifest.id}:${catalog.type}:${catalog.id}",
                        addonName = addon.displayTitle,
                        manifestUrl = addon.manifestUrl,
                        type = catalog.type,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        genreOptions = genreExtra?.options.orEmpty(),
                        genreRequired = genreExtra?.isRequired == true,
                        supportsPagination = catalog.supportsPagination(),
                    )
                }
        }

    private suspend fun SearchCatalogRequest.toSection(forceRefresh: Boolean): HomeCatalogSection {
        val manifest = requireNotNull(addon.manifest)
        val page = fetchCatalogPage(
            manifestUrl = manifest.transportUrl,
            type = type,
            catalogId = catalogId,
            search = query,
            forceRefresh = forceRefresh,
        ).withUnreleasedFilter()
        val posterPattern = com.nuvio.app.core.poster.CustomPosterUrlRepository.let {
            it.ensureLoaded()
            it.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.SEARCH)
        }
        val items = page.items.withCustomPosterUrls(posterPattern)
        require(items.isNotEmpty()) {
            getString(Res.string.search_error_no_results_for_catalog, catalogName)
        }

        return HomeCatalogSection(
            key = "${manifest.id}:search:$type:$catalogId:${query.lowercase()}",
            title = getString(Res.string.discover_catalog_context, catalogName, type.displayLabel()),
            subtitle = addon.displayTitle,
            addonName = addon.displayTitle,
            target = CatalogTarget.Addon(
                manifestUrl = manifest.transportUrl,
                contentType = type,
                catalogId = catalogId,
                supportsPagination = supportsPagination,
            ),
            items = items,
            availableItemCount = page.rawItemCount,
            hasMore = supportsPagination && page.nextSkip != null,
        )
    }

    private fun loadDiscoverFeed(
        reset: Boolean,
        forceRefresh: Boolean,
    ) {
        activeDiscoverJob?.cancel()
        val current = _discoverUiState.value
        val selectedCatalog = current.selectedCatalog ?: return
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return
        val requestUrl = if (selectedCatalog.server != null) {
            selectedCatalog.key
        } else {
            buildCatalogUrl(
                manifestUrl = selectedCatalog.manifestUrl,
                type = selectedCatalog.type,
                catalogId = selectedCatalog.catalogId,
                genre = current.selectedGenre,
                search = null,
                skip = requestedSkip.takeIf { it > 0 },
            )
        }

        log.d {
            "Discover request reset=$reset addon=${selectedCatalog.addonName} type=${selectedCatalog.type} " +
                "catalogId=${selectedCatalog.catalogId} catalogKey=${selectedCatalog.key} " +
                "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip url=$requestUrl"
        }

        _discoverUiState.value = current.copy(
            isLoading = true,
            items = if (reset) emptyList() else current.items,
            nextSkip = if (reset) null else current.nextSkip,
            consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
            emptyStateReason = null,
            errorMessage = null,
        )

        activeDiscoverJob = scope.launch {
            runCatching {
                val server = selectedCatalog.server
                if (server != null) {
                    ServerCatalog.page(server, skip = requestedSkip)
                } else {
                    fetchCatalogPage(
                        manifestUrl = selectedCatalog.manifestUrl,
                        type = selectedCatalog.type,
                        catalogId = selectedCatalog.catalogId,
                        genre = current.selectedGenre,
                        skip = requestedSkip.takeIf { it > 0 },
                        forceRefresh = forceRefresh,
                    )
                }.withUnreleasedFilter()
            }.fold(
                onSuccess = { page ->
                    val latest = _discoverUiState.value
                    if (latest.selectedCatalogKey != selectedCatalog.key || latest.selectedGenre != current.selectedGenre) {
                        return@fold
                    }
                    val mergedItems = if (reset) {
                        page.items
                    } else {
                        mergeCatalogItems(latest.items, page.items)
                    }.let { items ->
                        val pattern = com.nuvio.app.core.poster.CustomPosterUrlRepository.let { repo ->
                            repo.ensureLoaded()
                            repo.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.SEARCH)
                        }
                        items.withCustomPosterUrls(pattern)
                    }
                    val supportsPagination = selectedCatalog.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > latest.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else latest.consecutiveDuplicatePages,
                    )
                    log.d {
                        "Discover response catalogKey=${selectedCatalog.key} returned=${page.items.size} " +
                            "merged=${mergedItems.size} rawItemCount=${page.rawItemCount} nextSkip=${page.nextSkip} " +
                            "sample=${page.items.previewNames()}"
                    }
                    _discoverUiState.value = latest.copy(
                        items = mergedItems,
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        emptyStateReason = if (mergedItems.isEmpty()) DiscoverEmptyStateReason.NoResults else null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        log.d {
                            "Discover request cancelled catalogKey=${selectedCatalog.key} addon=${selectedCatalog.addonName} " +
                                "type=${selectedCatalog.type} catalogId=${selectedCatalog.catalogId} " +
                                "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip"
                        }
                        return@fold
                    }

                    val latest = _discoverUiState.value
                    if (latest.selectedCatalogKey != selectedCatalog.key || latest.selectedGenre != current.selectedGenre) {
                        return@fold
                    }
                    log.e(error) {
                        "Discover request failed catalogKey=${selectedCatalog.key} addon=${selectedCatalog.addonName} " +
                            "type=${selectedCatalog.type} catalogId=${selectedCatalog.catalogId} " +
                            "genre=${current.selectedGenre ?: "<all>"} skip=$requestedSkip url=$requestUrl"
                    }
                    _discoverUiState.value = latest.copy(
                        items = if (reset) emptyList() else latest.items,
                        isLoading = false,
                        nextSkip = null,
                        emptyStateReason = DiscoverEmptyStateReason.RequestFailed,
                        errorMessage = error.message ?: getString(Res.string.discover_empty_load_failed_message),
                    )
                },
            )
        }
    }
}

private data class IndexedSearchResult(
    val index: Int,
    val section: HomeCatalogSection? = null,
    val error: Throwable? = null,
)

private fun Array<IndexedSearchResult?>.orderedSections(): List<HomeCatalogSection> =
    mapNotNull { result -> result?.section }

private fun CatalogPage.withUnreleasedFilter(): CatalogPage {
    if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private data class SearchCatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val query: String,
    val supportsPagination: Boolean,
)

private fun AddonCatalog.supportsSearch(): Boolean =
    extra.any { property -> property.name == "search" } &&
        extra.none { property -> property.isRequired && property.name != "search" }

private fun AddonCatalog.supportsDiscover(): Boolean {
    if (extra.any { property -> property.name == "search" && property.isRequired }) {
        return false
    }

    return extra.none { property ->
        when (property.name) {
            "genre" -> property.isRequired && property.options.isEmpty()
            "skip" -> false
            "search" -> false
            else -> property.isRequired
        }
    }
}

private fun AddonCatalog.genreExtra(): AddonExtraProperty? =
    extra.firstOrNull { property -> property.name == "genre" }

private fun DiscoverCatalogOption.resolveGenreSelection(requestedGenre: String?): String? =
    when {
        genreOptions.isEmpty() -> null
        requestedGenre != null && genreOptions.contains(requestedGenre) -> requestedGenre
        genreRequired -> genreOptions.firstOrNull()
        else -> null
    }

private fun List<MetaPreview>.previewNames(limit: Int = 5): String {
    if (isEmpty()) return "[]"
    return take(limit).joinToString(prefix = "[", postfix = if (size > limit) ", ...]" else "]") { item ->
        item.name
    }
}

private fun String.displayLabel(): String =
    localizedMediaTypeLabel(this)

private fun String.typeSortKey(): String =
    when (lowercase()) {
        "movie" -> "0_movie"
        "series" -> "1_series"
        "anime" -> "2_anime"
        else -> "9_$this"
    }
