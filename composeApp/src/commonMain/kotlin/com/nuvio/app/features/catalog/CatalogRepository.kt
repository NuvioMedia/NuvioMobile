package com.nuvio.app.features.catalog

import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.sortLibraryItems
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            fetchInternalLibrary(request)
            return
        }
        fetchPage(
            request = request,
            reset = true,
            forceRefresh = force,
        )
    }

    fun loadMore() {
        val request = activeRequest ?: return
        val current = _uiState.value
        if (current.isLoading || current.nextSkip == null) return
        fetchPage(
            request = request,
            reset = false,
            forceRefresh = false,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeRequest = null
        scrollPositions.clear()
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

    private fun fetchInternalLibrary(request: CatalogRequest) {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                val target = request.target as CatalogTarget.Library
                LibraryRepository.ensureLoaded()
                val libraryState = LibraryRepository.uiState.value
                val items = libraryState.sections
                    .firstOrNull { it.type == target.sectionType }
                    ?.items
                    .orEmpty()
                sortLibraryItems(
                    items = items,
                    selected = target.sortOption,
                    sourceMode = libraryState.sourceMode,
                    listKey = target.sectionType,
                )
                    .map { it.toMetaPreview() }
                    .let(::dedupeCatalogItems)
            }.fold(
                onSuccess = { items ->
                    if (activeRequest != request) return@fold
                    _uiState.value = CatalogUiState(
                        items = items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold
                    _uiState.value = CatalogUiState(
                        items = emptyList(),
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
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

                    is CatalogTarget.CollectionSource -> fetchCollectionSourcePage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

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
                    _uiState.value = CatalogUiState(
                        items = mergedItems,
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
                        errorMessage = error.message ?: getString(Res.string.catalog_load_failed),
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
