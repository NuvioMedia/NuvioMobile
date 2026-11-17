package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.trakt.tv"
private const val WATCHLIST_KEY = "trakt:watchlist"
private const val PERSONAL_LIST_PREFIX = "trakt:list:"
private const val LIST_FETCH_CONCURRENCY = 3
private const val TRAKT_PAGE_LIMIT = 1_000
private const val SNAPSHOT_CACHE_TTL_MS = 60_000L
private const val LIST_TABS_CACHE_TTL_MS = 60_000L
private const val FORCE_REFRESH_DEDUP_MS = 10_000L
private const val MAX_VISIBLE_ERROR_MESSAGE_LENGTH = 240

data class TraktLibraryUiState(
    val listTabs: List<TraktListTab> = emptyList(),
    val entriesByList: Map<String, List<LibraryItem>> = emptyMap(),
    val allItems: List<LibraryItem> = emptyList(),
    val membershipByContent: Map<String, Set<String>> = emptyMap(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

object TraktLibraryRepository {
    private val log = Logger.withTag("TraktLibrary")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktLibraryUiState())
    val uiState: StateFlow<TraktLibraryUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private val refreshJobMutex = Mutex()
    private val refreshMutex = Mutex()
    private var refreshJob: Job? = null
    private var lastRefreshAtMs: Long = 0L
    private var lastListTabsRefreshAtMs: Long = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        loadSnapshotFromDisk()
    }

    fun preloadListTabsAsync() {
        if (!TraktAuthRepository.isAuthenticated.value) return
        if (_uiState.value.listTabs.isNotEmpty()) return
        scope.launch {
            runCatching { preloadListTabs() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Failed to preload Trakt list tabs: ${error.message}" }
                }
        }
    }

    fun onProfileChanged() {
        hasLoaded = false
        lastRefreshAtMs = 0L
        lastListTabsRefreshAtMs = 0L
        _uiState.value = TraktLibraryUiState()
        ensureLoaded()
    }

    fun clearLocalState() {
        hasLoaded = false
        lastRefreshAtMs = 0L
        lastListTabsRefreshAtMs = 0L
        _uiState.value = TraktLibraryUiState()
        TraktLibraryStorage.savePayload("")
    }

    fun currentListTabs(): List<TraktListTab> = _uiState.value.listTabs

    fun isInAnyList(itemId: String, itemType: String): Boolean {
        val key = contentKey(itemId, itemType)
        return _uiState.value.membershipByContent[key].orEmpty().isNotEmpty()
    }

    suspend fun refreshNow() {
        refresh(force = true)
    }

    suspend fun ensureFresh() {
        refresh(force = false)
    }

    private suspend fun preloadListTabs() {
        ensureLoaded()
        refreshMutex.withLock {
            if (_uiState.value.listTabs.isNotEmpty()) return

            val headers = TraktAuthRepository.authorizedHeaders() ?: return
            val tabs = fetchListTabs(headers)
            _uiState.value = _uiState.value.copy(
                listTabs = tabs,
                errorMessage = null,
            )
            lastListTabsRefreshAtMs = TraktPlatformClock.nowEpochMs()
        }
    }

    private suspend fun refresh(force: Boolean) {
        ensureLoaded()
        val job = refreshJobMutex.withLock {
            val runningRefresh = refreshJob?.takeIf(Job::isActive)
            if (runningRefresh != null) {
                // Automatic callers must not queue behind an already running request: while the
                // network is unavailable the request can stay suspended and every queued waiter
                // would be retained, eventually exhausting the heap.
                if (!force) return
                runningRefresh
            } else {
                scope.launch { refreshOnce(force) }.also { refreshJob = it }
            }
        }
        job.join()
    }

    private suspend fun refreshOnce(force: Boolean) {
        refreshMutex.withLock {
            val now = TraktPlatformClock.nowEpochMs()
            val current = _uiState.value
            val cacheWindowMs = if (force) FORCE_REFRESH_DEDUP_MS else SNAPSHOT_CACHE_TTL_MS
            if (
                current.hasLoaded &&
                current.errorMessage == null &&
                now - lastRefreshAtMs <= cacheWindowMs
            ) {
                return
            }

            val headers = TraktAuthRepository.authorizedHeaders()
            if (headers == null) {
                _uiState.value = TraktLibraryUiState()
                lastRefreshAtMs = 0L
                lastListTabsRefreshAtMs = 0L
                return
            }

            _uiState.value = current.copy(isLoading = true, errorMessage = null)

            val result = runCatching {
                fetchSnapshot(headers) { partialState ->
                    _uiState.value = partialState.copy(
                        isLoading = true,
                        hasLoaded = true,
                        errorMessage = null,
                    )
                }
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Failed to refresh Trakt library" }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = traktLibraryLoadErrorMessage(error),
                )
                return
            }

            val snapshot = result.getOrThrow()
            _uiState.value = snapshot.copy(
                isLoading = false,
                hasLoaded = true,
                errorMessage = null,
            )
            persistSnapshot(_uiState.value)
            lastRefreshAtMs = now
        }
    }

    suspend fun getMembershipSnapshot(item: LibraryItem): TraktMembershipSnapshot {
        ensureLoaded()
        if (TraktAuthRepository.isAuthenticated.value) {
            ensureFresh()
        }
        val itemMembership = _uiState.value.membershipByContent[contentKey(item.id, item.type)].orEmpty()
        val map = _uiState.value.listTabs.associate { tab ->
            tab.key to itemMembership.contains(tab.key)
        }
        return TraktMembershipSnapshot(listMembership = map)
    }

    suspend fun applyMembershipChanges(item: LibraryItem, changes: TraktMembershipChanges) {
        ensureLoaded()
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        ensureFresh()

        val current = getMembershipSnapshot(item).listMembership
        val desired = changes.desiredMembership
        val keys = (current.keys + desired.keys).distinct()
        val previousState = _uiState.value

        _uiState.value = applyOptimisticMembershipChanges(
            state = previousState,
            item = item,
            desiredMembership = desired,
        )

        try {
            for (key in keys) {
                val before = current[key] == true
                val after = desired[key] == true
                if (before == after) continue

                if (key == WATCHLIST_KEY) {
                    if (after) {
                        addToWatchlist(headers, item)
                    } else {
                        removeFromWatchlist(headers, item)
                    }
                } else {
                    val listId = key.removePrefix(PERSONAL_LIST_PREFIX)
                    if (listId == key || listId.isBlank()) continue
                    if (after) {
                        addToPersonalList(headers, listId, item)
                    } else {
                        removeFromPersonalList(headers, listId, item)
                    }
                }
            }
        } catch (error: Throwable) {
            _uiState.value = previousState
            throw error
        }
    }

    private fun applyOptimisticMembershipChanges(
        state: TraktLibraryUiState,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
    ): TraktLibraryUiState {
        if (state.listTabs.isEmpty()) return state

        val contentKey = contentKey(item.id, item.type)
        val currentMembership = state.membershipByContent[contentKey].orEmpty()
        val updatedEntriesByList = state.entriesByList.toMutableMap()
        val keys = (currentMembership + desiredMembership.keys).distinct()

        keys.forEach { listKey ->
            val before = currentMembership.contains(listKey)
            val after = desiredMembership[listKey] == true
            if (before == after) return@forEach

            if (after) {
                val resolvedItem = resolveOptimisticItem(state, item)
                updatedEntriesByList[listKey] = listOf(resolvedItem) +
                    updatedEntriesByList[listKey].orEmpty().filterNot {
                        allContentKeys(it).contains(contentKey)
                    }
            } else {
                updatedEntriesByList[listKey] = updatedEntriesByList[listKey].orEmpty().filterNot {
                    allContentKeys(it).contains(contentKey)
                }
            }
        }

        return rebuildUiState(
            listTabs = state.listTabs,
            entriesByList = updatedEntriesByList,
        )
    }

    private fun resolveOptimisticItem(
        state: TraktLibraryUiState,
        item: LibraryItem,
    ): LibraryItem {
        val itemKey = contentKey(item.id, item.type)
        val existing = state.allItems.firstOrNull {
            allContentKeys(it).contains(itemKey)
        }
        val base = existing ?: item
        val savedAt = base.savedAtEpochMs.takeIf { it > 0L }
            ?: item.savedAtEpochMs.takeIf { it > 0L }
            ?: TraktPlatformClock.nowEpochMs()

        return base.copy(savedAtEpochMs = savedAt)
    }

    private fun rebuildUiState(
        listTabs: List<TraktListTab>,
        entriesByList: Map<String, List<LibraryItem>>,
    ): TraktLibraryUiState {
        val normalizedEntriesByList = linkedMapOf<String, List<LibraryItem>>()
        listTabs.forEach { tab ->
            normalizedEntriesByList[tab.key] = entriesByList[tab.key].orEmpty()
        }

        val membershipByContent = mutableMapOf<String, MutableSet<String>>()
        normalizedEntriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                allContentKeys(entry).forEach { key ->
                    membershipByContent
                        .getOrPut(key) { mutableSetOf() }
                        .add(listKey)
                }
            }
        }

        val entriesWithMembership = normalizedEntriesByList.mapValues { (_, entries) ->
            entries.map { entry ->
                entry.copy(listKeys = membershipByContent[contentKey(entry.id, entry.type)].orEmpty())
            }
        }

        val allItemsByContent = linkedMapOf<String, LibraryItem>()
        entriesWithMembership.values
            .flatten()
            .sortedByDescending { it.savedAtEpochMs }
            .forEach { entry ->
                val key = contentKey(entry.id, entry.type)
                allItemsByContent[key] = entry.copy(listKeys = membershipByContent[key].orEmpty())
            }

        return TraktLibraryUiState(
            listTabs = listTabs,
            entriesByList = entriesWithMembership,
            allItems = allItemsByContent.values.toList().sortedByDescending { it.savedAtEpochMs },
            membershipByContent = membershipByContent.mapValues { it.value.toSet() },
            isLoading = false,
            hasLoaded = true,
            errorMessage = null,
        )
    }

    private suspend fun fetchSnapshot(
        headers: Map<String, String>,
        onPartialState: ((TraktLibraryUiState) -> Unit)? = null,
    ): TraktLibraryUiState = withContext(Dispatchers.Default) {
        val now = TraktPlatformClock.nowEpochMs()
        val cachedTabs = _uiState.value.listTabs
        val allTabs = if (
            cachedTabs.isNotEmpty() &&
            now - lastListTabsRefreshAtMs <= LIST_TABS_CACHE_TTL_MS
        ) {
            cachedTabs
        } else {
            fetchListTabs(headers).also {
                lastListTabsRefreshAtMs = now
            }
        }

        val entriesByList = fetchEntriesByList(
            headers = headers,
            allTabs = allTabs,
            onProgress = onPartialState?.let { emitPartial ->
                { partialEntriesByList ->
                    emitPartial(
                        rebuildUiState(
                            listTabs = allTabs,
                            entriesByList = partialEntriesByList,
                        ),
                    )
                }
            },
        )

        rebuildUiState(
            listTabs = allTabs,
            entriesByList = entriesByList,
        )
    }

    private fun loadSnapshotFromDisk() {
        val payload = TraktLibraryStorage.loadPayload().orEmpty().trim()
        if (payload.isBlank()) return

        val cached = runCatching {
            json.decodeFromString<StoredTraktLibraryPayload>(payload)
        }.onFailure {
            log.w { "Failed to parse cached Trakt library payload: ${it.message}" }
        }.getOrNull() ?: return

        val state = rebuildUiState(
            listTabs = cached.listTabs,
            entriesByList = cached.entriesByList,
        )
        _uiState.value = state.copy(isLoading = false, errorMessage = null, hasLoaded = true)
    }

    private fun persistSnapshot(state: TraktLibraryUiState) {
        val payload = StoredTraktLibraryPayload(
            listTabs = state.listTabs,
            entriesByList = state.entriesByList,
        )
        TraktLibraryStorage.savePayload(json.encodeToString(payload))
    }

    private suspend fun traktLibraryLoadErrorMessage(error: Throwable): String {
        val fallback = getString(Res.string.trakt_library_load_failed)
        val detail = error.userVisibleMessage()
        return when {
            detail.isBlank() -> fallback
            detail.equals(fallback, ignoreCase = true) -> fallback
            else -> detail
        }
    }

    private fun Throwable.userVisibleMessage(): String {
        val raw = message?.trim()?.takeIf { it.isNotBlank() }
            ?: toString().trim()
        val firstLine = raw.lines().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= MAX_VISIBLE_ERROR_MESSAGE_LENGTH) {
            firstLine
        } else {
            firstLine.take(MAX_VISIBLE_ERROR_MESSAGE_LENGTH).trimEnd() + "..."
        }
    }

    private suspend fun fetchListTabs(headers: Map<String, String>): List<TraktListTab> {
        val watchlistTabs = listOf(
            TraktListTab(
                key = WATCHLIST_KEY,
                title = getString(Res.string.trakt_watchlist),
                type = TraktListType.WATCHLIST,
                sortBy = "rank",
                sortHow = "asc",
            ),
        )
        return watchlistTabs + fetchPersonalLists(headers)
    }

    private suspend fun fetchEntriesByList(
        headers: Map<String, String>,
        allTabs: List<TraktListTab>,
        onProgress: ((Map<String, List<LibraryItem>>) -> Unit)? = null,
    ): Map<String, List<LibraryItem>> = coroutineScope {
        val entriesByList = linkedMapOf<String, List<LibraryItem>>()
        allTabs.forEach { tab ->
            entriesByList[tab.key] = emptyList()
        }
        val listSemaphore = Semaphore(LIST_FETCH_CONCURRENCY)
        val personalTabs = allTabs.filter { it.type == TraktListType.PERSONAL }

        val watchlistDeferred = async {
            listSemaphore.withPermit {
                fetchWatchlistItems(headers)
            }
        }
        val personalEntries = personalTabs.associate { tab ->
            tab.key to async {
                val listId = tab.traktListId?.toString() ?: tab.slug.orEmpty()
                if (listId.isBlank()) {
                    emptyList()
                } else {
                    listSemaphore.withPermit {
                        fetchPersonalListItems(headers, tab)
                    }
                }
            }
        }

        entriesByList[WATCHLIST_KEY] = watchlistDeferred.await()
        onProgress?.invoke(entriesByList.toMap())

        val pendingEntries = personalEntries.toMutableMap()
        while (pendingEntries.isNotEmpty()) {
            val (listKey, listItems) = select<Pair<String, List<LibraryItem>>> {
                pendingEntries.forEach { (key, deferred) ->
                    deferred.onAwait { key to it }
                }
            }
            entriesByList[listKey] = listItems
            pendingEntries.remove(listKey)
            onProgress?.invoke(entriesByList.toMap())
        }

        entriesByList.toMap()
    }

    private suspend fun fetchPersonalLists(headers: Map<String, String>): List<TraktListTab> {
        val payload = getJson(headers = headers, url = "$BASE_URL/users/me/lists")
        val lists = json.decodeFromString<List<TraktListSummaryDto>>(payload)
        return lists
            .filter { it.type.equals("personal", ignoreCase = true) }
            .mapNotNull { list ->
                val traktId = list.ids?.trakt
                val listIdPath = traktId?.toString() ?: list.ids?.slug ?: return@mapNotNull null
                val fallbackTitle = traktId?.let { getString(Res.string.trakt_list_fallback_title, it) }
                    ?: "List $listIdPath"
                TraktListTab(
                    key = "$PERSONAL_LIST_PREFIX$listIdPath",
                    title = list.name?.ifBlank { null } ?: fallbackTitle,
                    type = TraktListType.PERSONAL,
                    traktListId = traktId,
                    slug = list.ids?.slug,
                    description = list.description,
                    privacy = TraktListPrivacy.fromApi(list.privacy),
                    sortBy = list.sortBy,
                    sortHow = list.sortHow,
                )
            }
    }

    private suspend fun fetchWatchlistItems(headers: Map<String, String>): List<LibraryItem> {
        val (movieItems, showItems) = coroutineScope {
            val moviesDeferred = async {
                fetchPagedListItems(
                    headers = headers,
                    urlForPage = { page ->
                        "$BASE_URL/users/me/watchlist/movies/rank?extended=full,images&page=$page&limit=$TRAKT_PAGE_LIMIT"
                    },
                )
            }
            val showsDeferred = async {
                fetchPagedListItems(
                    headers = headers,
                    urlForPage = { page ->
                        "$BASE_URL/users/me/watchlist/shows/rank?extended=full,images&page=$page&limit=$TRAKT_PAGE_LIMIT"
                    },
                )
            }
            moviesDeferred.await() to showsDeferred.await()
        }
        return (movieItems + showItems)
            .mapNotNull(::mapToLibraryItem)
            .sortedByDescending { it.savedAtEpochMs }
    }

    private suspend fun fetchPersonalListItems(
        headers: Map<String, String>,
        tab: TraktListTab,
    ): List<LibraryItem> {
        val listId = tab.traktListId?.toString() ?: tab.slug.orEmpty()
        if (listId.isBlank()) return emptyList()

        val sortQuery = buildSortQuery(tab.sortBy, tab.sortHow)
        val (movieItems, showItems) = coroutineScope {
            val moviesDeferred = async {
                fetchPagedListItems(
                    headers = headers,
                    urlForPage = { page ->
                        "$BASE_URL/users/me/lists/$listId/items/movie?extended=full,images&page=$page&limit=$TRAKT_PAGE_LIMIT$sortQuery"
                    },
                )
            }
            val showsDeferred = async {
                fetchPagedListItems(
                    headers = headers,
                    urlForPage = { page ->
                        "$BASE_URL/users/me/lists/$listId/items/show?extended=full,images&page=$page&limit=$TRAKT_PAGE_LIMIT$sortQuery"
                    },
                )
            }
            moviesDeferred.await() to showsDeferred.await()
        }

        return (movieItems + showItems)
            .mapNotNull(::mapToLibraryItem)
            .sortedWith(
                compareBy<LibraryItem> { it.traktRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.savedAtEpochMs },
            )
    }

    private suspend fun fetchPagedListItems(
        headers: Map<String, String>,
        urlForPage: (Int) -> String,
    ): List<TraktListItemDto> {
        val items = mutableListOf<TraktListItemDto>()
        var page = 1

        while (true) {
            val response = getJsonResponse(headers = headers, url = urlForPage(page))
            val pageItems = json.decodeFromString<List<TraktListItemDto>>(response.body)
            items.addAll(pageItems)

            val pageCount = response.headerInt("x-pagination-page-count") ?: page
            if (page >= pageCount || pageItems.size < TRAKT_PAGE_LIMIT) break
            page += 1
        }

        return items
    }

    private suspend fun getJson(headers: Map<String, String>, url: String): String =
        getJsonResponse(headers, url).body

    private suspend fun getJsonResponse(
        headers: Map<String, String>,
        url: String,
    ): RawHttpResponse {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json") + headers,
            body = "",
            followRedirects = true,
        )
        if (response.status !in 200..299) {
            throw IllegalStateException(errorMessageForStatus(response.status, localizedString(Res.string.trakt_error_request_failed)))
        }
        if (response.body.isBlank()) {
            throw IllegalStateException(localizedString(Res.string.trakt_error_empty_response))
        }
        return response
    }

    private suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): RawHttpResponse {
        val response = httpRequestRaw(
            method = "POST",
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
            ) + headers,
            body = body,
            followRedirects = true,
        )
        if (response.status !in 200..299) {
            throw IllegalStateException(errorMessageForStatus(response.status, localizedString(Res.string.trakt_error_request_failed)))
        }
        return response
    }

    private fun RawHttpResponse.headerInt(name: String): Int? =
        headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.substringBefore(',')
            ?.trim()
            ?.toIntOrNull()

    private fun buildSortQuery(sortBy: String?, sortHow: String?): String = buildString {
        sortBy?.takeIf { it.isNotBlank() }?.let { append("&sort_by=").append(it) }
        sortHow?.takeIf { it.isNotBlank() }?.let { append("&sort_how=").append(it) }
    }

    private suspend fun addToWatchlist(headers: Map<String, String>, item: LibraryItem) {
        val body = buildMutationBody(item)
        val response = postJson(
            url = "$BASE_URL/sync/watchlist",
            body = body,
            headers = headers,
        )
        if (!isSuccessfulAddResponse(response.body)) {
            throw IllegalStateException(errorMessageForStatus(response.status, localizedString(Res.string.trakt_error_add_watchlist_failed)))
        }
    }

    private suspend fun removeFromWatchlist(headers: Map<String, String>, item: LibraryItem) {
        val body = buildMutationBody(item)
        postJson(
            url = "$BASE_URL/sync/watchlist/remove",
            body = body,
            headers = headers,
        )
    }

    private suspend fun addToPersonalList(headers: Map<String, String>, listId: String, item: LibraryItem) {
        val body = buildMutationBody(item)
        val response = postJson(
            url = "$BASE_URL/users/me/lists/$listId/items",
            body = body,
            headers = headers,
        )
        if (!isSuccessfulAddResponse(response.body)) {
            throw IllegalStateException(errorMessageForStatus(response.status, localizedString(Res.string.trakt_error_add_list_failed)))
        }
    }

    private suspend fun removeFromPersonalList(headers: Map<String, String>, listId: String, item: LibraryItem) {
        val body = buildMutationBody(item)
        postJson(
            url = "$BASE_URL/users/me/lists/$listId/items/remove",
            body = body,
            headers = headers,
        )
    }

    private suspend fun buildMutationBody(item: LibraryItem): String {
        val type = normalizeType(item.type)
        val ids = resolveIds(item)
        if (!ids.hasAnyId()) {
            throw IllegalStateException(localizedString(Res.string.trakt_error_missing_ids))
        }

        val request = if (type == "movie") {
            TraktListItemsMutationRequestDto(
                movies = listOf(
                    TraktListMovieRequestItemDto(
                        title = item.name,
                        year = extractYear(item.releaseInfo),
                        ids = ids,
                    ),
                ),
            )
        } else {
            TraktListItemsMutationRequestDto(
                shows = listOf(
                    TraktListShowRequestItemDto(
                        title = item.name,
                        year = extractYear(item.releaseInfo),
                        ids = ids,
                    ),
                ),
            )
        }
        return json.encodeToString(request)
    }

    private fun resolveIds(item: LibraryItem): TraktIdsDto {
        val parsed = parseTraktContentIds(item.id)

        return TraktIdsDto(
            imdb = item.imdbId ?: parsed.imdb,
            tmdb = item.tmdbId ?: parsed.tmdb,
            trakt = item.traktId ?: parsed.trakt,
        )
    }

    private fun mapToLibraryItem(item: TraktListItemDto): LibraryItem? {
        val movie = item.movie
        val show = item.show
        val type = when (item.type?.lowercase()) {
            "movie" -> "movie"
            "show" -> "series"
            else -> return null
        }
        val media = when (type) {
            "movie" -> movie
            else -> show
        } ?: return null
        val ids = media.ids

        val fallbackId = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            item.id != null -> "trakt-item:${item.id}"
            !media.title.isNullOrBlank() -> "${type}:${media.title.lowercase()}:${media.year ?: 0}"
            else -> null
        } ?: return null

        val id = normalizeTraktContentId(
            ids?.toExternalIds(),
            fallback = fallbackId,
        ).takeIf { it.isNotBlank() }
            ?: return null

        val poster = media.images.traktBestPosterUrl()
        val banner = media.images.traktBestBackdropUrl()
        val logo = media.images.traktBestLogoUrl()

        val savedAt = item.listedAt
            ?.takeIf { it.isNotBlank() }
            ?.let(TraktPlatformClock::parseIsoDateTimeToEpochMs)
            ?: TraktPlatformClock.nowEpochMs()

        return LibraryItem(
            id = id,
            type = type,
            name = media.title?.ifBlank { id } ?: id,
            poster = poster,
            banner = banner,
            logo = logo,
            description = media.overview,
            releaseInfo = media.year?.toString(),
            imdbRating = media.rating?.toString(),
            genres = media.genres.orEmpty(),
            traktRank = item.rank,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            tmdbId = ids?.tmdb,
            traktId = ids?.trakt,
            savedAtEpochMs = savedAt,
        )
    }

    private fun contentKey(itemId: String, itemType: String): String {
        val parsed = parseTraktContentIds(itemId)
        val normalizedId = normalizeTraktContentId(parsed, fallback = itemId.trim())
        val stableId = normalizedId.ifBlank { itemId.trim() }
        return "${normalizeType(itemType)}:$stableId"
    }

    private fun allContentKeys(entry: LibraryItem): Set<String> {
        val type = normalizeType(entry.type)
        val keys = mutableSetOf(contentKey(entry.id, entry.type))
        entry.imdbId?.takeIf { it.isNotBlank() }?.let { keys.add("$type:$it") }
        entry.tmdbId?.let { keys.add("$type:tmdb:$it") }
        entry.traktId?.let { keys.add("$type:trakt:$it") }
        return keys
    }

    private fun isSuccessfulAddResponse(body: String): Boolean {
        if (body.isBlank()) return false
        val parsed = runCatching {
            json.decodeFromString<TraktListItemsMutationResponseDto>(body)
        }.getOrNull() ?: return false
        val added = parsed.added
        val existing = parsed.existing
        val addCount = (added?.movies ?: 0) + (added?.shows ?: 0) + (added?.seasons ?: 0) + (added?.episodes ?: 0)
        val existingCount = (existing?.movies ?: 0) + (existing?.shows ?: 0) + (existing?.seasons ?: 0) + (existing?.episodes ?: 0)
        return addCount > 0 || existingCount > 0
    }

    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    private fun errorMessageForStatus(status: Int, defaultMessage: String): String =
        when (status) {
            401, 403 -> localizedString(Res.string.trakt_error_authorization_expired)
            404 -> localizedString(Res.string.trakt_error_list_not_found)
            420 -> localizedString(Res.string.trakt_error_list_limit_reached)
            429 -> localizedString(Res.string.trakt_error_rate_limit_reached)
            else -> "$defaultMessage ($status)"
        }

    private fun normalizeType(type: String): String {
        val normalized = type.trim().lowercase()
        return when (normalized) {
            "movie", "film" -> "movie"
            "tv", "show", "series", "tvshow" -> "series"
            else -> normalized
        }
    }

    private fun extractYear(releaseInfo: String?): Int? {
        if (releaseInfo.isNullOrBlank()) return null
        val yearText = Regex("(19|20)\\d{2}").find(releaseInfo)?.value ?: return null
        return yearText.toIntOrNull()
    }

    private fun TraktIdsDto.hasAnyId(): Boolean =
        trakt != null || !imdb.isNullOrBlank() || tmdb != null

    private fun TraktIdsDto.toExternalIds(): TraktExternalIds =
        TraktExternalIds(
            trakt = trakt,
            imdb = imdb,
            tmdb = tmdb,
        )
}

@Serializable
private data class StoredTraktLibraryPayload(
    val listTabs: List<TraktListTab> = emptyList(),
    val entriesByList: Map<String, List<LibraryItem>> = emptyMap(),
)

@Serializable
private data class TraktListSummaryDto(
    val name: String? = null,
    val description: String? = null,
    val privacy: String? = null,
    val type: String? = null,
    @SerialName("sort_by") val sortBy: String? = null,
    @SerialName("sort_how") val sortHow: String? = null,
    val ids: TraktListIdsDto? = null,
)

@Serializable
private data class TraktListIdsDto(
    val trakt: Long? = null,
    val slug: String? = null,
)

@Serializable
private data class TraktListItemDto(
    val rank: Int? = null,
    val id: Long? = null,
    @SerialName("listed_at") val listedAt: String? = null,
    val type: String? = null,
    val movie: TraktMediaDto? = null,
    val show: TraktMediaDto? = null,
)

@Serializable
private data class TraktMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
    val images: TraktImagesDto? = null,
)

@Serializable
private data class TraktIdsDto(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
private data class TraktListItemsMutationRequestDto(
    val movies: List<TraktListMovieRequestItemDto>? = null,
    val shows: List<TraktListShowRequestItemDto>? = null,
)

@Serializable
private data class TraktListItemsMutationResponseDto(
    val added: TraktListMutationCountDto? = null,
    val existing: TraktListMutationCountDto? = null,
    val deleted: TraktListMutationCountDto? = null,
)

@Serializable
private data class TraktListMutationCountDto(
    val movies: Int? = null,
    val shows: Int? = null,
    val seasons: Int? = null,
    val episodes: Int? = null,
)

@Serializable
private data class TraktListMovieRequestItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
)

@Serializable
private data class TraktListShowRequestItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
)
