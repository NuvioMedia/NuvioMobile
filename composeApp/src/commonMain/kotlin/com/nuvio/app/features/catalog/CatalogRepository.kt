package com.nuvio.app.features.catalog

import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.toMetaPreview
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.serverMessage
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogTab
import com.nuvio.app.features.home.MergedHomeRowDisplayMode
import com.nuvio.app.features.home.MergedHomeRowContentType
import com.nuvio.app.features.home.contentTypeForDisplay
import com.nuvio.app.features.home.buildMergedHomeTypeTabs
import com.nuvio.app.features.home.ResolvedMergedHomeRowSource
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.home.mergeHomeRowItems
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequest: CatalogRequest? = null
    private val scrollPositions = linkedMapOf<CatalogRequest, CatalogScrollPosition>()
    private val mergedSessions = linkedMapOf<CatalogRequest, MergedCatalogSession>()

    fun load(
        target: CatalogTarget,
        force: Boolean = false,
    ) {
        val request = catalogRequest(target)
        if (!force && activeRequest == request && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) {
            return
        }
        activeRequest = request
        if (target is CatalogTarget.Library) {
            observeInternalLibrary(request)
            return
        }
        if (target is CatalogTarget.MergedRow) {
            fetchMergedPage(request, force)
            return
        }
        fetchPage(
            request = request,
            reset = true,
            forceRefresh = force,
        )
    }

    fun loadMore(sourceKey: String? = null) {
        val request = activeRequest ?: return
        val current = _uiState.value
        if (current.isLoading) return
        if (request.target is CatalogTarget.MergedRow) {
            fetchMergedSources(request = request, sourceKey = sourceKey, retryFailedOnly = false)
            return
        }
        if (current.nextSkip == null) return
        fetchPage(
            request = request,
            reset = false,
            forceRefresh = false,
        )
    }

    fun retryFailedMergedSources(sourceKey: String? = null) {
        val request = activeRequest ?: return
        if (request.target !is CatalogTarget.MergedRow || _uiState.value.isLoading) return
        fetchMergedSources(request = request, sourceKey = sourceKey, retryFailedOnly = true)
    }

    fun clear() {
        activeJob?.cancel()
        activeRequest = null
        scrollPositions.clear()
        mergedSessions.clear()
        _uiState.value = CatalogUiState()
    }

    fun scrollPosition(
        target: CatalogTarget,
    ): CatalogScrollPosition =
        scrollPositions[catalogRequest(target)]
            ?: CatalogScrollPosition()

    fun saveScrollPosition(
        target: CatalogTarget,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        val request = catalogRequest(target)
        scrollPositions[request] = CatalogScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }

    private fun fetchMergedPage(
        request: CatalogRequest,
        forceRefresh: Boolean,
    ) {
        activeJob?.cancel()
        mergedSessions.remove(request)
        _uiState.value = CatalogUiState(isLoading = true, isMergedCatalog = true)

        activeJob = scope.launch {
            try {
                val target = request.target as CatalogTarget.MergedRow
                val resolvedRow = HomeCatalogSettingsRepository.resolveMergedHomeRow(target.rowId)
                    ?: error("This merged row is no longer available.")
                if (resolvedRow.sources.isEmpty()) {
                    error("None of the catalogs configured in this merged row are currently available.")
                }

                val initialResults = coroutineScope {
                    resolvedRow.sources.map { source ->
                        async {
                            source to fetchMergedSourcePage(
                                source = source,
                                skip = 0,
                                forceRefresh = forceRefresh,
                                hideUnreleasedContent = request.hideUnreleasedContent,
                            )
                        }
                    }.awaitAll()
                }

                val sourceStates = initialResults.map { (source, result) ->
                    result.fold(
                        onSuccess = { page ->
                            val pagination = nextCatalogPaginationState(
                                supportsPagination = source.definition.supportsPagination ||
                                    page.rawItemCount >= CATALOG_PAGE_SIZE,
                                requestedSkip = 0,
                                page = page,
                                loadedNewItems = true,
                                consecutiveDuplicatePages = 0,
                            )
                            MergedCatalogSourceSession(
                                source = source,
                                items = dedupeCatalogItems(page.items),
                                nextSkip = pagination.nextSkip,
                                consecutiveDuplicatePages = pagination.consecutiveDuplicatePages,
                            )
                        },
                        onFailure = { error ->
                            MergedCatalogSourceSession(
                                source = source,
                                retrySkip = 0,
                                errorMessage = error.serverMessage() ?: error.message ?: "Unable to load this source.",
                            )
                        },
                    )
                }
                val session = MergedCatalogSession(
                    definition = resolvedRow.definition,
                    sources = sourceStates,
                    unavailableSourceCount = resolvedRow.unavailableSourceCount,
                )
                mergedSessions[request] = session
                if (activeRequest == request) publishMergedSession(session, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeRequest != request) return@launch
                _uiState.value = CatalogUiState(
                    isMergedCatalog = true,
                    errorMessage = error.serverMessage() ?: error.message
                        ?: getString(Res.string.catalog_load_failed),
                )
            }
        }
    }

    private suspend fun fetchMergedSourcePage(
        source: ResolvedMergedHomeRowSource,
        skip: Int,
        forceRefresh: Boolean,
        hideUnreleasedContent: Boolean,
    ): Result<CatalogPage> = try {
        val definition = source.definition
        val page = fetchCatalogPage(
            manifestUrl = definition.manifestUrl,
            type = definition.type,
            catalogId = definition.catalogId,
            skip = skip.takeIf { it > 0 },
            maxItems = CATALOG_PAGE_SIZE,
            forceRefresh = forceRefresh,
        ).withUnreleasedFilter(hideUnreleasedContent)
        CustomPosterUrlRepository.ensureLoaded()
        val posterPattern = CustomPosterUrlRepository.patternForScreen(
            com.nuvio.app.core.poster.CustomPosterScreen.HOME,
        )
        Result.success(
            if (posterPattern.isBlank()) page else page.copy(
                items = page.items.withCustomPosterUrls(posterPattern),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun fetchMergedSources(
        request: CatalogRequest,
        sourceKey: String?,
        retryFailedOnly: Boolean,
    ) {
        if (activeRequest != request || _uiState.value.isLoading) return
        val session = mergedSessions[request] ?: return
        val candidates = session.sources.filter { source ->
            (sourceKey == null || source.source.definition.key == sourceKey) &&
                if (retryFailedOnly) source.retrySkip != null else source.nextSkip != null
        }
        if (candidates.isEmpty()) return

        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        activeJob = scope.launch {
            try {
                val outcomes = coroutineScope {
                    candidates.map { sourceState ->
                        async {
                            val requestedSkip = if (retryFailedOnly) sourceState.retrySkip ?: 0 else sourceState.nextSkip ?: 0
                            Triple(
                                sourceState,
                                requestedSkip,
                                fetchMergedSourcePage(
                                    source = sourceState.source,
                                    skip = requestedSkip,
                                    forceRefresh = false,
                                    hideUnreleasedContent = request.hideUnreleasedContent,
                                ),
                            )
                        }
                    }.awaitAll()
                }
                if (activeRequest != request) return@launch

                val outcomesByKey = outcomes.associateBy { it.first.source.definition.key }
                val updatedSources = session.sources.map { previous ->
                    val outcome = outcomesByKey[previous.source.definition.key] ?: return@map previous
                    val requestedSkip = outcome.second
                    outcome.third.fold(
                        onSuccess = { page ->
                            val updatedItems = mergeCatalogItems(previous.items, page.items)
                            val pagination = nextCatalogPaginationState(
                                supportsPagination = previous.source.definition.supportsPagination ||
                                    page.rawItemCount >= CATALOG_PAGE_SIZE,
                                requestedSkip = requestedSkip,
                                page = page,
                                loadedNewItems = updatedItems.size > previous.items.size,
                                consecutiveDuplicatePages = previous.consecutiveDuplicatePages,
                            )
                            previous.copy(
                                items = updatedItems,
                                nextSkip = pagination.nextSkip,
                                retrySkip = null,
                                consecutiveDuplicatePages = pagination.consecutiveDuplicatePages,
                                errorMessage = null,
                            )
                        },
                        onFailure = { error ->
                            previous.copy(
                                nextSkip = null,
                                retrySkip = requestedSkip,
                                errorMessage = error.serverMessage() ?: error.message ?: "Unable to load this source.",
                            )
                        },
                    )
                }
                val updatedSession = session.copy(sources = updatedSources)
                mergedSessions[request] = updatedSession
                publishMergedSession(updatedSession, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeRequest != request) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                )
            }
        }
    }

    private fun publishMergedSession(session: MergedCatalogSession, isLoading: Boolean) {
        val contentType = session.definition.contentTypeForDisplay()
        val mergedItems = mergeHomeRowItems(
            sourceItems = session.sources.map { it.items },
            deduplicate = session.definition.deduplicate,
            maxItems = Int.MAX_VALUE,
            blendMode = session.definition.resolvedBlendMode(),
            sortMode = session.definition.resolvedSortMode(),
            contentType = contentType,
            hideTitlesWithoutPosters = session.definition.hideTitlesWithoutPosters,
        )
        val hasMore = session.sources.any { it.nextSkip != null }
        val failedSourceKeys = session.sources
            .filter { it.retrySkip != null }
            .mapTo(linkedSetOf()) { it.source.definition.key }
        val sourceTabs = when (session.definition.resolvedDisplayMode()) {
            MergedHomeRowDisplayMode.SOURCE_TABS -> buildList {
                add(HomeCatalogTab("all", "All", mergedItems, mergedItems.size, hasMore))
                session.sources.forEach { source ->
                    val items = com.nuvio.app.features.home.filterMergedHomeItems(
                        items = source.items,
                        contentType = contentType,
                        hideTitlesWithoutPosters = session.definition.hideTitlesWithoutPosters,
                    )
                    add(
                        HomeCatalogTab(
                            key = source.source.definition.key,
                            title = com.nuvio.app.features.home.cleanMergedSourceTabTitle(source.source.title),
                            items = items,
                            availableItemCount = items.size,
                            hasMore = source.nextSkip != null,
                        ),
                    )
                }
            }
            MergedHomeRowDisplayMode.TYPE_TABS -> buildMergedHomeTypeTabs(
                sourceItems = session.sources.map { it.items },
                allItems = mergedItems,
                deduplicate = session.definition.deduplicate,
                blendMode = session.definition.resolvedBlendMode(),
                sortMode = session.definition.resolvedSortMode(),
                hideTitlesWithoutPosters = session.definition.hideTitlesWithoutPosters,
                hasMore = hasMore,
                maxItems = Int.MAX_VALUE,
            )
            MergedHomeRowDisplayMode.UNIFIED_CAROUSEL -> emptyList()
        }
        val errorMessage = when {
            failedSourceKeys.isNotEmpty() -> "Some source catalogs could not be loaded. You can retry them."
            session.unavailableSourceCount > 0 -> "Some configured source catalogs are currently unavailable."
            else -> null
        }
        _uiState.value = CatalogUiState(
            items = mergedItems,
            isLoading = isLoading,
            errorMessage = errorMessage,
            isMergedCatalog = true,
            mergedDisplayMode = session.definition.displayMode,
            mergedSourceTabs = sourceTabs,
            mergedHasMore = hasMore,
            failedMergedSourceKeys = failedSourceKeys,
            unavailableMergedSourceCount = session.unavailableSourceCount,
        )
    }

    private fun observeInternalLibrary(request: CatalogRequest) {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        activeJob = scope.launch {
            try {
                val target = request.target as CatalogTarget.Library
                LibraryRepository.ensureLoaded()
                LibraryRepository.uiState.libraryCatalogStates(
                    target, LibraryRepository.uiState.libraryCatalogOrders(target),
                ).collect { state ->
                    if (activeRequest != request) return@collect
                    CustomPosterUrlRepository.ensureLoaded()
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(
                        com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                    )
                    _uiState.value = state.copy(
                        items = state.items.withCustomPosterUrls(posterPattern)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeRequest != request) return@launch
                _uiState.value = CatalogUiState(
                    errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                )
            }
        }
    }

    private fun fetchPage(
        request: CatalogRequest,
        reset: Boolean,
        forceRefresh: Boolean,
    ) {
        activeJob?.cancel()
        val current = _uiState.value
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return

        _uiState.value = current.copy(
            items = if (reset) emptyList() else current.items,
            isLoading = true,
            nextSkip = if (reset) null else current.nextSkip,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                when (val target = request.target) {
                    is CatalogTarget.Addon -> fetchCatalogPage(
                        manifestUrl = target.manifestUrl,
                        type = target.contentType,
                        catalogId = target.catalogId,
                        genre = target.genre,
                        skip = requestedSkip.takeIf { it > 0 },
                        forceRefresh = forceRefresh,
                    )

                    is CatalogTarget.CloudStream -> {
                        val sections = CloudStreamRepository.getMainPage(
                            target.providerId,
                            cloudStreamPageNumber(requestedSkip),
                        ).getOrThrow()
                        val items = sections.firstOrNull { it.first == target.sectionName }?.second.orEmpty()
                        CatalogPage(
                            items = items.map { it.toMetaPreview() },
                            rawItemCount = items.size,
                            nextSkip = cloudStreamNextPageSkip(requestedSkip, items.size),
                        )
                    }

                    is CatalogTarget.CollectionSource -> fetchCollectionSourcePage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

                    is CatalogTarget.Server -> ServerCatalog.page(target, skip = requestedSkip)

                    is CatalogTarget.MergedRow -> error("Merged catalogs use independent source pagination.")

                    is CatalogTarget.Library -> error(getString(Res.string.catalog_load_failed))
                }.withUnreleasedFilter(request.hideUnreleasedContent)
            }.fold(
                onSuccess = { page ->
                    if (activeRequest != request) return@fold

                    val mergedItems = if (reset) {
                        dedupeCatalogItems(page.items)
                    } else {
                        mergeCatalogItems(_uiState.value.items, page.items)
                    }
                    val supportsPagination = request.target.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > current.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
                    )
                    CustomPosterUrlRepository.ensureLoaded()
                    val posterScreen = when (request.target) {
                        is CatalogTarget.Library -> com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                        is CatalogTarget.CollectionSource -> com.nuvio.app.core.poster.CustomPosterScreen.COLLECTIONS
                        else -> com.nuvio.app.core.poster.CustomPosterScreen.HOME
                    }
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(posterScreen)
                    _uiState.value = CatalogUiState(
                        items = mergedItems.withCustomPosterUrls(posterPattern),
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold

                    _uiState.value = current.copy(
                        items = if (reset) emptyList() else current.items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
        }
    }

    private fun catalogRequest(target: CatalogTarget): CatalogRequest =
        CatalogRequest(
            target = target,
            hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent,
        )
}

private fun CatalogPage.withUnreleasedFilter(hideUnreleasedContent: Boolean): CatalogPage {
    if (!hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private suspend fun fetchCollectionSourcePage(
    target: CatalogTarget.CollectionSource,
    page: Int,
): CatalogPage {
    CollectionRepository.initialize()
    val source = CollectionRepository.getCollection(target.collectionId)
        ?.folders
        ?.firstOrNull { it.id == target.folderId }
        ?.resolvedSources
        ?.firstOrNull { it.catalogRouteKey() == target.sourceKey }
        ?: error(getString(Res.string.catalog_load_failed))

    return when {
        source.isTmdb -> TmdbCollectionSourceResolver.resolve(source = source, page = page)
        source.isTrakt -> TraktPublicListSourceResolver.resolve(source = source, page = page)
        else -> error(getString(Res.string.catalog_load_failed))
    }
}

private data class CatalogRequest(
    val target: CatalogTarget,
    val hideUnreleasedContent: Boolean,
)

private data class MergedCatalogSourceSession(
    val source: ResolvedMergedHomeRowSource,
    val items: List<com.nuvio.app.features.home.MetaPreview> = emptyList(),
    val nextSkip: Int? = null,
    val retrySkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val errorMessage: String? = null,
)

private data class MergedCatalogSession(
    val definition: com.nuvio.app.features.home.MergedHomeRowDefinition,
    val sources: List<MergedCatalogSourceSession>,
    val unavailableSourceCount: Int,
)
