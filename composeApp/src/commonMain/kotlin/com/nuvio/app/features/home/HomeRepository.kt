package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.cloudstream.CloudStreamRepository
import com.nuvio.app.features.cloudstream.CloudStreamPluginItem
import com.nuvio.app.features.cloudstream.toMetaPreview
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.reapplyCustomPosterUrls
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.collection.findCollectionCatalog
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.random.Random

object HomeRepository {
    private val log = Logger.withTag("HomeRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var refreshPreparationJob: Job? = null
    private var refreshRequestGeneration = 0L
    private var activeFetchGeneration = 0L
    private var lifecycleGeneration = 0L
    private var applySettingsGeneration = 0L
    private var activeRequestKey: String? = null
    private var currentRequestKey: String? = null
    private var currentDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var cachedSections: Map<String, HomeCatalogSection> = emptyMap()
    private var cachedCloudStreamSections: List<HomeCatalogSection> = emptyList()
    private var cloudStreamHomeJob: Job? = null
    private var cloudStreamHomeRequestKey: String? = null
    private var cloudStreamHomeCompletedKey: String? = null
    private var cachedCollectionHeroItems: List<MetaPreview> = emptyList()
    private var collectionHeroJob: Job? = null
    private var collectionHeroRequestKey: String? = null
    private var lastPublishedCatalogHeroEmpty: Boolean = true
    private var lastErrorMessage: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        applySettingsGeneration++
        val activeAddons = addons.enabledAddons()
        val lifecycle = lifecycleGeneration
        val requestGeneration = ++refreshRequestGeneration
        refreshPreparationJob?.cancel()
        lateinit var preparationJob: Job
        preparationJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Descriptor expansion and localized title lookup can block; run them
                // on the repository dispatcher instead of the Compose caller.
                val requests = buildHomeCatalogDefinitions(activeAddons)
                if (lifecycle != lifecycleGeneration || requestGeneration != refreshRequestGeneration) return@launch
                val requestCacheKeys = requests.mapTo(mutableSetOf(), HomeCatalogDefinition::cacheKey)
                val requestKey = requests.joinToString(separator = "|", transform = HomeCatalogDefinition::cacheKey)
                currentDefinitions = requests
                cachedSections = cachedSections.filterKeys(requestCacheKeys::contains)
                currentRequestKey = requestKey
                refreshCloudStreamHomeSections()
                if (!force && activeRequestKey == requestKey && activeJob?.isActive == true) return@launch
                val fetchGeneration = ++activeFetchGeneration
                activeJob?.cancel()
                activeJob = null
                activeRequestKey = requestKey
                if (requests.isEmpty()) {
                    activeRequestKey = null
                    cachedSections = emptyMap()
                    lastErrorMessage = null
                    publishCurrentState(isLoading = false, requestKey = requestKey)
                    ensureCollectionHeroFallback(
                        addons = activeAddons,
                        forceRefresh = force,
                        refreshSources = true,
                        requestKey = requestKey,
                    )
                    return@launch
                }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                lateinit var fetchJob: Job
                fetchJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val prioritizedRequests = prioritizeDefinitions(
                            definitions = requests,
                            snapshot = HomeCatalogSettingsRepository.snapshot(),
                        )
                        val loadedSections = linkedMapOf<String, HomeCatalogSection>().apply { putAll(cachedSections) }
                        var firstErrorMessage: String? = null
                        var batchIndex = 0
                        prioritizedRequests.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
                            if (
                                lifecycle != lifecycleGeneration ||
                                fetchGeneration != activeFetchGeneration ||
                                activeRequestKey != requestKey
                            ) return@launch
                            val results = batch.map { request ->
                                async {
                                    val result: Result<HomeCatalogSection> = try {
                                        Result.success(request.toSection(forceRefresh = force))
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Throwable) {
                                        Result.failure(error)
                                    }
                                    request to result
                                }
                            }.awaitAll()
                            if (
                                lifecycle != lifecycleGeneration ||
                                fetchGeneration != activeFetchGeneration ||
                                activeRequestKey != requestKey
                            ) return@launch
                            results.mapNotNull { (request, result) ->
                                result.getOrNull()?.let { section -> request.cacheKey to section }
                            }.forEach { (cacheKey, section) -> loadedSections[cacheKey] = section }
                            if (firstErrorMessage == null) {
                                firstErrorMessage = results.firstNotNullOfOrNull { (_, result) ->
                                    result.exceptionOrNull()?.message
                                }
                            }
                            cachedSections = loadedSections.toMap()
                            lastErrorMessage = firstErrorMessage
                            if (batchIndex == 0 || (batchIndex + 1) % HOME_CATALOG_PUBLISH_INTERVAL == 0) {
                                publishCurrentState(isLoading = true, requestKey = requestKey)
                            }
                            batchIndex++
                        }
                        if (
                            lifecycle != lifecycleGeneration ||
                            fetchGeneration != activeFetchGeneration ||
                            activeRequestKey != requestKey
                        ) return@launch
                        cachedSections = loadedSections.toMap()
                        lastErrorMessage = firstErrorMessage
                        activeRequestKey = null
                        publishCurrentState(isLoading = false, requestKey = requestKey)
                        ensureCollectionHeroFallback(
                            addons = activeAddons,
                            forceRefresh = force,
                            refreshSources = true,
                            requestKey = requestKey,
                        )
                    } finally {
                        if (activeJob === fetchJob) activeJob = null
                    }
                }
                activeJob = fetchJob
                fetchJob.start()
            } finally {
                if (refreshPreparationJob === preparationJob) refreshPreparationJob = null
            }
        }
        refreshPreparationJob = preparationJob
        preparationJob.start()
    }

    private fun refreshCloudStreamHomeSections() {
        val lifecycle = lifecycleGeneration
        CloudStreamRepository.initialize()
        val state = CloudStreamRepository.uiState.value
        val hiddenProviderIds = state.hiddenHomeCatalogProviderIds
        val plugins = if (state.hideAllHomeCatalogs) emptyList() else state.plugins
            .filter(CloudStreamPluginItem::isRunnable)
            .filterNot { it.metadata.id.value in hiddenProviderIds }
        val runnableProviderIds = plugins.mapTo(mutableSetOf()) { it.metadata.id.value }
        val requestKey = buildString {
            append(state.hideAllHomeCatalogs).append('|')
            append(hiddenProviderIds.sorted().joinToString(",")).append('|')
            plugins.sortedBy { it.metadata.id.value }.forEach { plugin ->
                append(plugin.metadata.id.value).append(':')
                append(plugin.metadata.version).append(':')
                append(plugin.installedVersion).append(':')
                append(plugin.enabled).append(':')
                append(plugin.verified).append('|')
            }
        }
        cachedCloudStreamSections = if (state.hideAllHomeCatalogs) emptyList() else
            cachedCloudStreamSections.filter { section ->
                val target = section.target as? CatalogTarget.CloudStream
                target == null || (target.providerId in runnableProviderIds && target.providerId !in hiddenProviderIds)
            }
        if (
            cloudStreamHomeRequestKey == requestKey &&
            (cloudStreamHomeJob?.let { !it.isCompleted } == true || cloudStreamHomeCompletedKey == requestKey)
        ) return
        cloudStreamHomeJob?.cancel()
        cloudStreamHomeRequestKey = requestKey
        if (plugins.isEmpty()) {
            HomeCatalogSettingsRepository.syncCloudStreamCatalogs(emptyList())
            cloudStreamHomeCompletedKey = requestKey
            cloudStreamHomeJob = null
            publishCurrentState(isLoading = _uiState.value.isLoading, requestKey = currentRequestKey)
            return
        }
        publishCurrentState(isLoading = _uiState.value.isLoading, requestKey = currentRequestKey)
        lateinit var loadJob: Job
        loadJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val providerResults: List<Result<List<HomeCatalogSection>>> = coroutineScope {
                    val pluginSemaphore = Semaphore(3)
                    plugins.map { plugin ->
                        async {
                            pluginSemaphore.withPermit {
                                try {
                                    Result.success(
                                        CloudStreamRepository.getMainPage(plugin.metadata.id.value, 1).getOrThrow()
                                            .mapNotNull { (sectionName, items) ->
                                                if (items.isEmpty()) return@mapNotNull null
                                                val contentType = items.firstOrNull()?.type?.nuvioType ?: "other"
                                                HomeCatalogSection(
                                                    key = "cloudstream:" + plugin.metadata.id.value + ":" + sectionName,
                                                    title = sectionName,
                                                    subtitle = plugin.metadata.name,
                                                    addonName = plugin.metadata.name,
                                                    target = CatalogTarget.CloudStream(
                                                        plugin.metadata.id.value,
                                                        sectionName,
                                                        contentType,
                                                    ),
                                                    items = items.map { it.toMetaPreview() },
                                                    availableItemCount = items.size,
                                                    hasMore = true,
                                                    supportsViewAll = true,
                                                )
                                            },
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    log.w(error) {
                                        "CloudStream home catalog failed for " + plugin.metadata.name +
                                            " id=" + plugin.metadata.id.value
                                    }
                                    Result.failure(error)
                                }
                            }
                        }
                    }.awaitAll()
                }
                val allProvidersSucceeded = providerResults.all { it.isSuccess }
                val sections = providerResults.mapNotNull { it.getOrNull() }.flatten()

                if (lifecycle != lifecycleGeneration || cloudStreamHomeRequestKey != requestKey) return@launch
                val latestState = CloudStreamRepository.uiState.value
                val latestRunnableIds = if (latestState.hideAllHomeCatalogs) emptySet() else
                    latestState.plugins.filter(CloudStreamPluginItem::isRunnable).mapTo(mutableSetOf()) { it.metadata.id.value }
                val visibleSections = if (latestState.hideAllHomeCatalogs) emptyList() else
                    sections.filter { section ->
                        val target = section.target as? CatalogTarget.CloudStream
                        target == null || (
                            target.providerId in latestRunnableIds &&
                                target.providerId !in latestState.hiddenHomeCatalogProviderIds
                        )
                    }
                HomeCatalogSettingsRepository.syncCloudStreamCatalogs(visibleSections)
                cachedCloudStreamSections = visibleSections
                cloudStreamHomeCompletedKey = requestKey.takeIf { allProvidersSucceeded }
                publishCurrentState(isLoading = _uiState.value.isLoading, requestKey = currentRequestKey)
            } finally {
                if (cloudStreamHomeJob === loadJob) cloudStreamHomeJob = null
            }
        }
        cloudStreamHomeJob = loadJob
        loadJob.start()
    }

    fun applyCurrentSettings() {
        val lifecycle = lifecycleGeneration
        val generation = ++applySettingsGeneration
        scope.launch {
            // CloudStream repository refreshes can complete close together. Debounce
            // their home-catalog invalidations so we do not cancel/re-fetch provider
            // catalogs repeatedly while a batch of repositories is being applied.
            delay(250)
            if (lifecycle != lifecycleGeneration || generation != applySettingsGeneration) return@launch
            refreshCloudStreamHomeSections()
            publishCurrentState(isLoading = _uiState.value.isLoading, requestKey = currentRequestKey)
            ensureCollectionHeroFallback(
                addons = AddonRepository.uiState.value.addons.enabledAddons(),
                forceRefresh = false,
                refreshSources = false,
                requestKey = currentRequestKey,
            )
        }
    }

    fun clear() {
        lifecycleGeneration++
        applySettingsGeneration++
        refreshRequestGeneration++
        activeFetchGeneration++
        refreshPreparationJob?.cancel()
        refreshPreparationJob = null
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        currentRequestKey = null
        currentDefinitions = emptyList()
        cachedSections = emptyMap()
        cloudStreamHomeJob?.cancel()
        cloudStreamHomeJob = null
        cloudStreamHomeRequestKey = null
        cloudStreamHomeCompletedKey = null
        cachedCloudStreamSections = emptyList()
        cachedCollectionHeroItems = emptyList()
        collectionHeroJob?.cancel()
        collectionHeroJob = null
        collectionHeroRequestKey = null
        lastPublishedCatalogHeroEmpty = true
        lastErrorMessage = null
        _uiState.value = HomeUiState()
    }

    private fun publishCurrentState(
        isLoading: Boolean,
        requestKey: String?,
    ) {
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        val preferences = snapshot.preferences
        val todayIsoDate = if (snapshot.hideUnreleasedContent) CurrentDateProvider.todayIsoDate() else null
        CustomPosterUrlRepository.ensureLoaded()
        val posterPattern = CustomPosterUrlRepository.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.HOME)
        fun HomeCatalogSection.withReleaseFilter(): HomeCatalogSection =
            if (todayIsoDate == null) this else filterReleasedItems(todayIsoDate)
        fun HomeCatalogSection.withPosterOverlay(): HomeCatalogSection =
            copy(items = items.reapplyCustomPosterUrls(posterPattern))
        fun HomeCatalogSection.withGlobalPosterFilter(): HomeCatalogSection =
            if (!snapshot.hideMergedTitlesWithoutPosters) this
            else copy(items = items.filterNot { it.poster.isNullOrBlank() })

        val hiddenIncludedSourceKeys = snapshot.mergedRows
            .asSequence()
            .filter { row ->
                row.hideIncludedRows &&
                    preferences[mergedHomeRowKey(row.id)]?.enabled != false
            }
            .flatMap { it.sourceKeys.asSequence() }
            .toSet()
        val catalogSections = orderHomeCatalogDefinitions(currentDefinitions, snapshot)
            .mapNotNull { definition ->
                val preference = preferences[definition.key]
                if (preference?.enabled == false || definition.key in hiddenIncludedSourceKeys) {
                    return@mapNotNull null
                }

                val section = cachedSections[definition.cacheKey]
                    ?.withPosterOverlay()
                    ?.withReleaseFilter()
                    ?.withGlobalPosterFilter()
                    ?: return@mapNotNull null
                if (section.items.isEmpty()) return@mapNotNull null
                val customTitle = preference?.customTitle.orEmpty()
                section.copy(
                    title = customTitle.ifBlank { definition.titleFor(snapshot.showCatalogType) },
                )
            }

        // Reuse the already-fetched source sections. A catalog hidden as its own home row can
        // still be included in a merged row; the two layout preferences are deliberately separate.
        val visibleCatalogSectionsByKey = catalogSections.associateBy(HomeCatalogSection::key)
        val sourceSectionsByKey = currentDefinitions.mapNotNull { definition ->
            // Reuse the already-normalized section when its own row is visible. Only transform the
            // cached raw section again for a source whose standalone home row is hidden.
            val section = visibleCatalogSectionsByKey[definition.key]
                ?: cachedSections[definition.cacheKey]
                    ?.withPosterOverlay()
                    ?.withReleaseFilter()
                    ?.withGlobalPosterFilter()
                ?: return@mapNotNull null
            if (section.items.isEmpty() && !section.hasMore) return@mapNotNull null
            val customTitle = preferences[definition.key]?.customTitle.orEmpty()
            definition.key to section.copy(
                title = customTitle.ifBlank { definition.titleFor(snapshot.showCatalogType) },
            )
        }.toMap()

        val mergedSections = snapshot.mergedRows
            .sortedBy { it.order }
            .mapNotNull { mergedRow ->
                val rowKey = mergedHomeRowKey(mergedRow.id)
                if (preferences[rowKey]?.enabled == false) return@mapNotNull null
                val sourceSections = mergedRow.sourceKeys.mapNotNull(sourceSectionsByKey::get)
                if (sourceSections.isEmpty()) return@mapNotNull null
                val displayMode = mergedRow.resolvedDisplayMode()
                val contentType = mergedRow.contentTypeForDisplay()
                val mergedItems = mergeHomeRowItems(
                    sourceItems = sourceSections.map(HomeCatalogSection::items),
                    deduplicate = mergedRow.deduplicate,
                    maxItems = HOME_MERGED_ROW_ITEM_LIMIT,
                    blendMode = mergedRow.resolvedBlendMode(),
                    sortMode = MergedHomeRowSortMode.OFF,
                    contentType = contentType,
                    hideTitlesWithoutPosters = false,
                )
                val hasMore = sourceSections.any(HomeCatalogSection::hasMore)
                // Keep the home entry reachable when the preview page has no items matching
                // the row's filter but one or more source catalogs can still provide more pages.
                if (mergedItems.isEmpty() && !hasMore) return@mapNotNull null

                val sourceTabs = when (displayMode) {
                    MergedHomeRowDisplayMode.SOURCE_TABS -> buildList {
                        add(
                            HomeCatalogTab(
                                key = "all",
                                title = "All",
                                items = mergedItems,
                                availableItemCount = mergedItems.size,
                                hasMore = hasMore,
                            ),
                        )
                        sourceSections.forEach { source ->
                            val sourceItems = filterMergedHomeItems(
                                items = source.items,
                                contentType = contentType,
                                hideTitlesWithoutPosters = false,
                            )
                            add(
                                HomeCatalogTab(
                                    key = source.key,
                                    title = cleanMergedSourceTabTitle(source.title),
                                    items = sourceItems,
                                    availableItemCount = sourceItems.size,
                                    hasMore = source.hasMore,
                                ),
                            )
                        }
                    }
                    MergedHomeRowDisplayMode.TYPE_TABS -> buildMergedHomeTypeTabs(
                        sourceItems = sourceSections.map(HomeCatalogSection::items),
                        allItems = mergedItems,
                        deduplicate = mergedRow.deduplicate,
                        blendMode = mergedRow.resolvedBlendMode(),
                        sortMode = MergedHomeRowSortMode.OFF,
                        hideTitlesWithoutPosters = snapshot.hideMergedTitlesWithoutPosters,
                        hasMore = sourceSections.any(HomeCatalogSection::hasMore),
                        maxItems = HOME_MERGED_ROW_ITEM_LIMIT,
                    )
                    MergedHomeRowDisplayMode.UNIFIED_CAROUSEL -> emptyList()
                }
                val customTitle = preferences[rowKey]?.customTitle.orEmpty()
                HomeCatalogSection(
                    key = rowKey,
                    title = customTitle.ifBlank { mergedRow.title },
                    subtitle = "Merged row",
                    addonName = "Merged row",
                    target = CatalogTarget.MergedRow(rowId = mergedRow.id),
                    items = mergedItems,
                    availableItemCount = mergedItems.size,
                    hasMore = sourceSections.any(HomeCatalogSection::hasMore),
                    isMergedRow = true,
                    displayMode = mergedRow.displayMode,
                    sourceTabs = sourceTabs,
                    supportsViewAll = true,
                )
            }
        val cloudStreamSections = cachedCloudStreamSections.mapNotNull { section ->
            val preference = snapshot.preferences[section.key]
            if (preference?.enabled == false) return@mapNotNull null
            section.withPosterOverlay().withReleaseFilter().copy(
                title = preference?.customTitle?.takeIf { it.isNotBlank() } ?: section.title,
            )
        }
        val sectionByKey = (catalogSections + mergedSections + cloudStreamSections).associateBy(HomeCatalogSection::key)
        val configuredHomeOrder = HomeCatalogSettingsRepository.uiState.value.items
            .mapIndexed { index, item -> item.key to index }
            .toMap()
        val sections = sectionByKey.values.sortedBy { section ->
            configuredHomeOrder[section.key] ?: Int.MAX_VALUE
        }

        val catalogHeroItems = if (snapshot.heroEnabled) {
            val heroRandom = Random((requestKey?.hashCode() ?: 0).absoluteValue + 1)
            orderHomeCatalogDefinitions(currentDefinitions, snapshot)
                .filter { definition -> preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> cachedSections[definition.cacheKey] }
                .map { section -> section.withPosterOverlay().withReleaseFilter() }
                .flatMap { section -> section.items }
                .distinctBy { item -> "${item.type}:${item.id}" }
                .shuffled(heroRandom)
                .take(HOME_HERO_ITEM_LIMIT)
        } else {
            emptyList()
        }
        lastPublishedCatalogHeroEmpty = snapshot.heroEnabled && catalogHeroItems.isEmpty()
        val heroItems = if (snapshot.heroEnabled) {
            catalogHeroItems.ifEmpty { cachedCollectionHeroItems }
        } else {
            emptyList()
        }

        _uiState.value = HomeUiState(
            isLoading = isLoading,
            heroItems = heroItems,
            sections = sections,
            errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
        )
    }

    private suspend fun HomeCatalogDefinition.toSection(forceRefresh: Boolean): HomeCatalogSection {
        serverConnectionId?.let { connectionId -> return toServerSection(connectionId) }
        CustomPosterUrlRepository.ensureLoaded()
        val pattern = CustomPosterUrlRepository.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.HOME)
        val page = fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
            maxItems = HOME_CATALOG_PREVIEW_FETCH_LIMIT,
            forceRefresh = forceRefresh,
        )
        val items = if (pattern.isNotBlank()) {
            page.items.withCustomPosterUrls(pattern)
        } else {
            page.items
        }
        if (items.isEmpty()) {
            return HomeCatalogSection(
                key = key,
                title = defaultTitle,
                subtitle = addonName,
                addonName = addonName,
                target = CatalogTarget.Addon(
                    manifestUrl = manifestUrl,
                    contentType = type,
                    catalogId = catalogId,
                    supportsPagination = supportsPagination,
                ),
                items = emptyList(),
                availableItemCount = 0,
                hasMore = false,
            )
        }

        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            target = CatalogTarget.Addon(
                manifestUrl = manifestUrl,
                contentType = type,
                catalogId = catalogId,
                supportsPagination = supportsPagination,
            ),
            items = items,
            availableItemCount = page.rawItemCount,
            hasMore = supportsPagination && page.nextSkip != null,
        )
    }

    private suspend fun HomeCatalogDefinition.toServerSection(connectionId: String): HomeCatalogSection {
        val target = CatalogTarget.Server(connectionId, catalogId, type)
        val page = ServerCatalog.page(target, skip = 0, limit = HOME_CATALOG_PREVIEW_FETCH_LIMIT)
        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            target = target,
            items = page.items,
            availableItemCount = page.rawItemCount,
            hasMore = page.nextSkip != null,
        )
    }

    private fun ensureCollectionHeroFallback(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean,
        refreshSources: Boolean,
        requestKey: String?,
    ) {
        if (!lastPublishedCatalogHeroEmpty) return
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        if (!snapshot.heroEnabled) return
        val collections = enabledCollectionsForHero(snapshot)
        if (collections.isEmpty()) {
            cachedCollectionHeroItems = emptyList()
            collectionHeroRequestKey = null
            return
        }

        val nextRequestKey = collectionHeroRequestKey(
            collections = collections,
            addons = addons,
            snapshot = snapshot,
            requestKey = requestKey,
        )
        if (!refreshSources && collectionHeroRequestKey == nextRequestKey) return

        collectionHeroJob?.cancel()
        collectionHeroRequestKey = nextRequestKey
        cachedCollectionHeroItems = emptyList()
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = requestKey,
        )

        collectionHeroJob = scope.launch {
            val sources = collectionHeroSources(collections)
            val sourceResults = sources.map { source ->
                async {
                    runCatching {
                        source.resolveCollectionHeroItems(
                            addons = addons,
                            forceRefresh = forceRefresh,
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            val random = Random((nextRequestKey.hashCode()).absoluteValue + 7)
            cachedCollectionHeroItems = roundRobinCollectionHeroItems(sourceResults)
                .distinctBy { item -> item.stableKey() }
                .shuffled(random)
                .take(HOME_HERO_ITEM_LIMIT)
            publishCurrentState(
                isLoading = _uiState.value.isLoading,
                requestKey = requestKey,
            )
        }
    }

    private fun enabledCollectionsForHero(snapshot: HomeCatalogSettingsSnapshot): List<Collection> {
        val preferences = snapshot.preferences
        return CollectionRepository.collections.value
            .filter { collection ->
                collection.folders.isNotEmpty() &&
                    preferences["collection_${collection.id}"]?.enabled != false
            }
            .sortedBy { collection ->
                preferences["collection_${collection.id}"]?.order ?: Int.MAX_VALUE
            }
    }

    private fun collectionHeroSources(collections: List<Collection>): List<CollectionSource> =
        collections
            .flatMap { collection -> collection.folders }
            .flatMap { folder -> folder.resolvedSources }
            .take(HOME_COLLECTION_HERO_SOURCE_LIMIT)

    private suspend fun CollectionSource.resolveCollectionHeroItems(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean,
    ): List<MetaPreview> {
        val page = when {
            isTmdb -> TmdbCollectionSourceResolver.resolve(source = this, page = 1)
            isTrakt -> TraktPublicListSourceResolver.resolve(source = this, page = 1)
            else -> {
                val catalogSource = addonCatalogSource() ?: return emptyList()
                val resolvedCatalog = addons.findCollectionCatalog(catalogSource) ?: return emptyList()
                fetchCatalogPage(
                    manifestUrl = resolvedCatalog.addon.manifestUrl,
                    type = catalogSource.type,
                    catalogId = catalogSource.catalogId,
                    genre = catalogSource.genre,
                    maxItems = HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT,
                    forceRefresh = forceRefresh,
                )
            }
        }
        val items = page.items
        return if (HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) {
            items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
        } else {
            items
        }
    }

    private fun roundRobinCollectionHeroItems(sourceResults: List<List<MetaPreview>>): List<MetaPreview> {
        val iterators = sourceResults.filter { it.isNotEmpty() }.map { it.iterator() }
        if (iterators.isEmpty()) return emptyList()
        val merged = mutableListOf<MetaPreview>()
        var hasMore = true
        while (hasMore && merged.size < HOME_COLLECTION_HERO_SOURCE_LIMIT * HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT) {
            hasMore = false
            iterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    merged.add(iterator.next())
                    hasMore = true
                }
            }
        }
        return merged
    }

    private fun collectionHeroRequestKey(
        collections: List<Collection>,
        addons: List<ManagedAddon>,
        snapshot: HomeCatalogSettingsSnapshot,
        requestKey: String?,
    ): String = buildString {
        append(requestKey.orEmpty())
        append("|hideUnreleased=")
        append(snapshot.hideUnreleasedContent)
        append("|collections=")
        collections.forEach { collection ->
            val preference = snapshot.preferences["collection_${collection.id}"]
            append(collection.id)
            append(":")
            append(preference?.order ?: Int.MAX_VALUE)
            append(":")
            collection.folders.forEach { folder ->
                append(folder.id)
                append("[")
                folder.resolvedSources.forEach { source ->
                    append(collectionSourceKey(source))
                    append(",")
                }
                append("]")
            }
            append(";")
        }
        append("|addons=")
        addons.forEach { addon ->
            append(addon.manifest?.id.orEmpty())
            append(":")
            append(addon.manifestUrl)
            append(":")
            append(addon.manifest?.catalogs?.size ?: 0)
            append(";")
        }
    }

    private fun collectionSourceKey(source: CollectionSource): String =
        source.catalogRouteKey()
}

private const val HOME_HERO_ITEM_LIMIT = 8
private const val HOME_COLLECTION_HERO_SOURCE_LIMIT = 6
private const val HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT = 8
private const val HOME_CATALOG_FETCH_BATCH_SIZE = 4
private const val HOME_CATALOG_PREVIEW_FETCH_LIMIT = 18
private const val HOME_CATALOG_PUBLISH_INTERVAL = 2

private fun prioritizeDefinitions(
    definitions: List<HomeCatalogDefinition>,
    snapshot: HomeCatalogSettingsSnapshot,
): List<HomeCatalogDefinition> {
    val orderedDefinitions = definitions.sortedBy { definition ->
        snapshot.preferences[definition.key]?.order ?: Int.MAX_VALUE
    }
    val (priority, remainder) = orderedDefinitions.partition { definition ->
        val preference = snapshot.preferences[definition.key]
        if (preference == null) {
            true
        } else {
            preference.enabled || (snapshot.heroEnabled && preference.heroSourceEnabled)
        }
    }
    return priority + remainder
}
