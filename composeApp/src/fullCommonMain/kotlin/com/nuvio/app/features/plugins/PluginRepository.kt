package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.addons_and_plugins_refreshed
import nuvio.composeapp.generated.resources.plugins_error_enter_repo_url
import nuvio.composeapp.generated.resources.plugins_error_enter_valid_url
import nuvio.composeapp.generated.resources.plugins_error_provider_not_found
import nuvio.composeapp.generated.resources.plugins_repository_already_installed
import nuvio.composeapp.generated.resources.plugins_repository_install_failed
import nuvio.composeapp.generated.resources.plugins_repository_refresh_failed
import org.jetbrains.compose.resources.getString

@Serializable
private data class PluginRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class PluginPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private data class PluginPersistenceSnapshot(
    val profileId: Int,
    val generation: Long,
    val revision: Long,
    val state: PluginsUiState,
)

private data class LoadedPluginState(
    val state: PluginsUiState,
    val requiresMigration: Boolean,
)

private data class PluginRefreshSnapshot(
    val profileId: Int,
    val generation: Long,
    val previousScrapers: Map<String, PluginScraper>,
)

actual object PluginRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("PluginRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(PluginsUiState())
    actual val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId = 1
    private val profileLock = SynchronizedObject()
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val repositoryRefreshSemaphore = Semaphore(2)
    private val activeRefreshJobsLock = SynchronizedObject()
    private val serverPushLock = SynchronizedObject()
    private val latestServerPushRevisionByProfile = mutableMapOf<Int, Long>()
    private val serverPushMutex = Mutex()
    private val serverPushRevision = atomic(0L)
    private val persistenceGeneration = atomic(0L)
    private val persistenceRevision = atomic(0L)
    private val persistenceLock = SynchronizedObject()
    private val persistedRevisionByProfile = mutableMapOf<Int, Long>()

    actual fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        val shouldRefreshStoredRepos = !initialized || currentProfileId != effectiveProfileId
        ensureStateLoadedForProfile(effectiveProfileId)
        if (!shouldRefreshStoredRepos) return

        val state = _uiState.value
        val nowEpochMs = currentEpochMillis()
        state.repositories.filter { repo ->
            shouldRefreshRepository(repo, state.scrapers, nowEpochMs)
        }.forEach { repo ->
            refreshRepositoryInternal(repo.manifestUrl, pushAfterRefresh = false, ensureInitialized = false)
        }
    }

    actual fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        val changed = synchronized(profileLock) {
            if (effectiveProfileId == currentProfileId && initialized) {
                false
            } else {
                persistenceGeneration.incrementAndGet()
                currentProfileId = effectiveProfileId
                initialized = false
                _uiState.value = PluginsUiState()
                true
            }
        }
        if (changed) cancelActiveRefreshes()
    }

    actual fun clearLocalState() {
        synchronized(profileLock) {
            persistenceGeneration.incrementAndGet()
            currentProfileId = 1
            initialized = false
            _uiState.value = PluginsUiState()
        }
        cancelActiveRefreshes()
    }

    actual suspend fun pullFromServer(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        ensureStateLoadedForProfile(effectiveProfileId)
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }

        try {
            val rows = SupabaseProvider.client.postgrest
                .from("plugins")
                .select {
                    filter { eq("profile_id", expectedProfile.first) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<PluginRow>()

            val urls = dedupeManifestUrls(rows.map { it.url })
            var repositoriesToRefresh: List<PluginRepositoryItem> = emptyList()
            var persistenceSnapshot: PluginPersistenceSnapshot? = null
            val jobsToCancel = mutableListOf<Job>()
            val applied = synchronized(activeRefreshJobsLock) {
                synchronized(profileLock) {
                    if (
                        currentProfileId != expectedProfile.first ||
                        persistenceGeneration.value != expectedProfile.second
                    ) {
                        false
                    } else {
                        val existingState = _uiState.value
                        val existingReposByUrl = existingState.repositories.associateBy { it.manifestUrl }
                        val retainedUrls = urls.toSet()
                        (existingReposByUrl.keys - retainedUrls).forEach { removedUrl ->
                            activeRefreshJobs.remove(removedUrl)?.let(jobsToCancel::add)
                        }
                        val nowEpochMs = currentEpochMillis()
                        val nextRepos = urls.map { url ->
                            val existing = existingReposByUrl[url]
                            if (existing == null) {
                                PluginRepositoryItem(
                                    manifestUrl = url,
                                    name = url.substringBefore("?").substringAfterLast('/'),
                                    isRefreshing = true,
                                )
                            } else {
                                val shouldRefresh = shouldRefreshRepository(
                                    repository = existing,
                                    scrapers = existingState.scrapers,
                                    nowEpochMs = nowEpochMs,
                                )
                                existing.copy(
                                    isRefreshing = shouldRefresh,
                                    errorMessage = if (shouldRefresh) null else existing.errorMessage,
                                )
                            }
                        }
                        val nextScrapers = existingState.scrapers.filter { scraper ->
                            retainedUrls.contains(scraper.repositoryUrl)
                        }

                        _uiState.value = PluginsUiState(
                            pluginsEnabled = existingState.pluginsEnabled,
                            groupStreamsByRepository = existingState.groupStreamsByRepository,
                            repositories = nextRepos,
                            scrapers = nextScrapers,
                        )
                        repositoriesToRefresh = nextRepos.filter(PluginRepositoryItem::isRefreshing)
                        persistenceSnapshot = persistenceSnapshotLocked()
                        true
                    }
                }
            }
            jobsToCancel.forEach(Job::cancel)
            if (!applied) return
            persistenceSnapshot?.let(::persist)

            repositoriesToRefresh.forEach { repository ->
                refreshRepositoryInternal(
                    manifestUrl = repository.manifestUrl,
                    pushAfterRefresh = false,
                    ensureInitialized = false,
                    expectedProfileId = expectedProfile.first,
                    expectedGeneration = expectedProfile.second,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.e(error) { "pullFromServer failed" }
        }
    }

    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult {
        initialize()
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_error_enter_valid_url))
        }

        val previousById = synchronized(profileLock) {
            if (
                currentProfileId != expectedProfile.first ||
                persistenceGeneration.value != expectedProfile.second
            ) {
                null
            } else {
                _uiState.value.scrapers.associateBy { it.id }
            }
        } ?: return AddPluginRepositoryResult.Error("Active profile changed while adding the repository")

        return try {
            val (repo, scrapers) = fetchRepositoryData(
                manifestUrl = manifestUrl,
                profileId = expectedProfile.first,
                previousScrapers = previousById,
            )
            var duplicate = false
            var persistenceSnapshot: PluginPersistenceSnapshot? = null
            var serverPushSnapshot: Pair<Int, List<PluginPushItem>>? = null
            val applied = synchronized(profileLock) {
                if (
                    currentProfileId != expectedProfile.first ||
                    persistenceGeneration.value != expectedProfile.second
                ) {
                    false
                } else {
                    duplicate = _uiState.value.repositories.any { it.manifestUrl == manifestUrl }
                    if (!duplicate) {
                        _uiState.update { state ->
                            state.copy(
                                repositories = state.repositories + repo,
                                scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers,
                            )
                        }
                        persistenceSnapshot = persistenceSnapshotLocked()
                        serverPushSnapshot = serverPushSnapshotLocked()
                    }
                    true
                }
            }
            when {
                !applied -> AddPluginRepositoryResult.Error("Active profile changed before the repository could be added")
                duplicate -> AddPluginRepositoryResult.Error(getString(Res.string.plugins_repository_already_installed))
                else -> {
                    persistenceSnapshot?.let(::persist)
                    serverPushSnapshot?.let(::pushToServer)
                    AddPluginRepositoryResult.Success(repo)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_repository_install_failed))
        }
    }

    actual fun removeRepository(manifestUrl: String) {
        initialize()
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }
        val (snapshots, refreshJob) = synchronized(activeRefreshJobsLock) {
            val committed = synchronized(profileLock) {
                if (
                    currentProfileId != expectedProfile.first ||
                    persistenceGeneration.value != expectedProfile.second
                ) {
                    null
                } else {
                    _uiState.update { state ->
                        state.copy(
                            repositories = state.repositories.filterNot { it.manifestUrl == manifestUrl },
                            scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl },
                        )
                    }
                    persistenceSnapshotLocked() to serverPushSnapshotLocked()
                }
            }
            val job = if (committed != null) activeRefreshJobs.remove(manifestUrl) else null
            committed to job
        }
        if (snapshots == null) return
        refreshJob?.cancel()
        persist(snapshots.first)
        pushToServer(snapshots.second)
    }

    actual fun refreshAll() {
        initialize()
        _uiState.value.repositories.forEach { repo ->
            refreshRepositoryInternal(repo.manifestUrl, pushAfterRefresh = false, ensureInitialized = false)
        }
    }

    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) {
        refreshRepositoryInternal(manifestUrl, pushAfterRefresh, ensureInitialized = true)
    }

    private fun refreshRepositoryInternal(
        manifestUrl: String,
        pushAfterRefresh: Boolean,
        ensureInitialized: Boolean,
        expectedProfileId: Int? = null,
        expectedGeneration: Long? = null,
    ) {
        if (ensureInitialized) initialize()

        val snapshot = synchronized(profileLock) {
            if (
                expectedProfileId != null &&
                (currentProfileId != expectedProfileId || persistenceGeneration.value != expectedGeneration)
            ) {
                null
            } else {
                PluginRefreshSnapshot(
                    profileId = currentProfileId,
                    generation = persistenceGeneration.value,
                    previousScrapers = _uiState.value.scrapers.associateBy { it.id },
                )
            }
        } ?: return
        var jobToStart: Job? = null

        synchronized(activeRefreshJobsLock) {
            val existingJob = activeRefreshJobs[manifestUrl]
            // A LAZY job is not active yet, but it already owns this repository's slot.
            if (existingJob == null || existingJob.isCompleted) {
                val canRefresh = synchronized(profileLock) {
                    if (
                        currentProfileId != snapshot.profileId ||
                        persistenceGeneration.value != snapshot.generation ||
                        _uiState.value.repositories.none { it.manifestUrl == manifestUrl }
                    ) {
                        false
                    } else {
                        markRefreshing(manifestUrl)
                        true
                    }
                }

                if (canRefresh) {
                    lateinit var refreshJob: Job
                    refreshJob = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val result = try {
                                Result.success(
                                    repositoryRefreshSemaphore.withPermit {
                                        fetchRepositoryData(manifestUrl, snapshot.profileId, snapshot.previousScrapers)
                                    },
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }

                            // Resource lookup is suspending; resolve it before entering
                            // the profile lock so no suspension occurs in the critical section.
                            val refreshFailureMessage = if (result.isFailure) {
                                getString(Res.string.plugins_repository_refresh_failed)
                            } else {
                                null
                            }
                            var persistenceSnapshot: PluginPersistenceSnapshot? = null
                            var serverPushSnapshot: Pair<Int, List<PluginPushItem>>? = null
                            val applied = synchronized(profileLock) {
                                if (
                                    currentProfileId != snapshot.profileId ||
                                    persistenceGeneration.value != snapshot.generation ||
                                    _uiState.value.repositories.none { it.manifestUrl == manifestUrl }
                                ) {
                                    false
                                } else {
                                    _uiState.update { state ->
                                        result.fold(
                                            onSuccess = { (repo, scrapers) ->
                                                val updatedRepos = state.repositories.map { existing ->
                                                    if (existing.manifestUrl == manifestUrl) {
                                                        repo.copy(isRefreshing = false, errorMessage = repo.errorMessage)
                                                    } else {
                                                        existing
                                                    }
                                                }
                                                state.copy(
                                                    repositories = updatedRepos,
                                                    scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers,
                                                )
                                            },
                                            onFailure = { error ->
                                                state.copy(
                                                    repositories = state.repositories.map { existing ->
                                                        if (existing.manifestUrl == manifestUrl) {
                                                            existing.copy(
                                                                isRefreshing = false,
                                                                errorMessage = error.message ?: refreshFailureMessage,
                                                            )
                                                        } else {
                                                            existing
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                    persistenceSnapshot = persistenceSnapshotLocked()
                                    if (pushAfterRefresh) serverPushSnapshot = serverPushSnapshotLocked()
                                    true
                                }
                            }
                            if (!applied) return@launch

                            persistenceSnapshot?.let(::persist)
                            if (pushAfterRefresh) serverPushSnapshot?.let(::pushToServer)
                            if (pushAfterRefresh && result.isSuccess) {
                                NuvioToastController.show(getString(Res.string.addons_and_plugins_refreshed))
                            }
                        } finally {
                            synchronized(activeRefreshJobsLock) {
                                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                                    activeRefreshJobs.remove(manifestUrl)
                                }
                            }
                        }
                    }
                    activeRefreshJobs[manifestUrl] = refreshJob
                    jobToStart = refreshJob
                }
            }
        }
        jobToStart?.start()
    }

    actual fun toggleScraper(scraperId: String, enabled: Boolean) {
        initialize()
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }
        val snapshot = synchronized(profileLock) {
            if (
                currentProfileId != expectedProfile.first ||
                persistenceGeneration.value != expectedProfile.second
            ) {
                null
            } else {
                _uiState.update { state ->
                    state.copy(
                        scrapers = state.scrapers.map { scraper ->
                            if (scraper.id == scraperId) {
                                scraper.copy(enabled = if (scraper.manifestEnabled) enabled else false)
                            } else {
                                scraper
                            }
                        },
                    )
                }
                persistenceSnapshotLocked()
            }
        } ?: return
        persist(snapshot)
    }

    actual fun setPluginsEnabled(enabled: Boolean) {
        initialize()
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }
        val snapshot = synchronized(profileLock) {
            if (
                currentProfileId != expectedProfile.first ||
                persistenceGeneration.value != expectedProfile.second
            ) {
                null
            } else {
                _uiState.update { it.copy(pluginsEnabled = enabled) }
                persistenceSnapshotLocked()
            }
        } ?: return
        persist(snapshot)
    }

    actual fun setGroupStreamsByRepository(enabled: Boolean) {
        initialize()
        val expectedProfile = synchronized(profileLock) {
            currentProfileId to persistenceGeneration.value
        }
        val snapshot = synchronized(profileLock) {
            if (
                currentProfileId != expectedProfile.first ||
                persistenceGeneration.value != expectedProfile.second
            ) {
                null
            } else {
                _uiState.update { it.copy(groupStreamsByRepository = enabled) }
                persistenceSnapshotLocked()
            }
        } ?: return
        persist(snapshot)
    }

    actual fun setLocalPluginSearchPaused(paused: Boolean) {
        PluginRuntime.setSearchPaused(paused)
    }

    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> {
        initialize()
        if (!_uiState.value.pluginsEnabled) return emptyList()
        return _uiState.value.scrapers.filter { scraper ->
            scraper.enabled && scraper.supportsType(type)
        }
    }

    actual suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>> {
        initialize()
        val scraper = _uiState.value.scrapers.find { it.id == scraperId }
            ?: return Result.failure(IllegalArgumentException(getString(Res.string.plugins_error_provider_not_found)))

        val mediaType = if (scraper.supportsType("movie")) "movie" else "tv"
        val season = if (mediaType == "tv") 1 else null
        val episode = if (mediaType == "tv") 1 else null
        return executeScraperInternal(
            scraper = scraper,
            tmdbId = "603",
            mediaType = mediaType,
            season = season,
            episode = episode,
            respectSearchPause = false,
        )
    }

    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>> = executeScraperInternal(
        scraper = scraper,
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        respectSearchPause = true,
    )

    private suspend fun executeScraperInternal(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        respectSearchPause: Boolean,
    ): Result<List<PluginRuntimeResult>> {
        return try {
            val resolvedTmdbId = resolvePluginTmdbId(
                tmdbId = tmdbId,
                mediaType = mediaType,
            )
            val results = PluginRuntime.executePlugin(
                code = scraper.code,
                tmdbId = resolvedTmdbId,
                mediaType = normalizePluginType(mediaType),
                season = season,
                episode = episode,
                scraperId = scraper.id,
                respectSearchPause = respectSearchPause,
            )
            log.d { "Plugin provider ${scraper.name} completed with ${results.size} stream(s)" }
            Result.success(results)
        } catch (cancelled: CancellationException) {
            // Cancellation is control flow (search stopped/profile changed), not
            // a provider failure. Never turn it into an ordinary empty result.
            throw cancelled
        } catch (error: Exception) {
            log.w(error) { "Plugin provider ${scraper.name} failed for $mediaType:$tmdbId" }
            Result.failure(error)
        }
    }

    private suspend fun resolvePluginTmdbId(
        tmdbId: String,
        mediaType: String,
    ): String {
        val trimmed = tmdbId.trim()
        if (trimmed.isBlank()) return tmdbId

        return TmdbService.ensureTmdbId(
            videoId = trimmed,
            mediaType = mediaType,
        ) ?: trimmed
    }

    private suspend fun fetchRepositoryData(
        manifestUrl: String,
        profileId: Int,
        previousScrapers: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>> {
        return withContext(Dispatchers.Default) {
            val payload = httpGetText(manifestUrl)
            val manifest = PluginManifestParser.parse(payload)
            val baseUrl = manifestUrl.substringBefore("?").removeSuffix("/manifest.json")

            val supportedScrapers = manifest.scrapers
                .filter { scraper -> scraper.isSupportedOnCurrentPlatform() }
                .distinctBy { scraper -> scraper.id }
            val scrapers = supportedScrapers
                .mapNotNull { info ->
                    val codeUrl = if (info.filename.startsWith("http://") || info.filename.startsWith("https://")) {
                        info.filename
                    } else {
                        "$baseUrl/${info.filename.trimStart('/')}"
                    }
                    try {
                        val code = httpGetText(codeUrl)
                        val scraperId = "${manifestUrl.lowercase()}:${info.id}"
                        val cached = PluginStorage.saveScraperCode(
                            profileId = profileId,
                            scraperId = scraperId,
                            code = code,
                            overwrite = true,
                        )
                        if (!cached) {
                            log.w { "Failed to cache plugin scraper $scraperId" }
                        }
                        val previous = previousScrapers[scraperId]
                        val enabled = when {
                            !info.enabled -> false
                            previous != null -> previous.enabled
                            else -> info.enabled
                        }

                        PluginScraper(
                            id = scraperId,
                            repositoryUrl = manifestUrl,
                            name = info.name,
                            description = info.description.orEmpty(),
                            version = info.version,
                            filename = info.filename,
                            supportedTypes = info.supportedTypes,
                            enabled = enabled,
                            manifestEnabled = info.enabled,
                            hasSettings = info.hasSettings,
                            logo = info.logo,
                            contentLanguage = info.contentLanguage ?: emptyList(),
                            formats = info.formats ?: info.supportedFormats,
                            code = code,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        log.w(error) { "Failed to download plugin scraper ${info.id} from $manifestUrl" }
                        null
                    }
                }

            val repo = PluginRepositoryItem(
                manifestUrl = manifestUrl,
                name = manifest.name,
                description = manifest.description,
                version = manifest.version,
                scraperCount = supportedScrapers.size,
                lastUpdated = currentEpochMillis(),
                isRefreshing = false,
                errorMessage = if (scrapers.size < supportedScrapers.size) {
                    getString(Res.string.plugins_repository_refresh_failed)
                } else {
                    null
                },
            )
            repo to scrapers
        }
    }

    private fun PluginManifestScraper.isSupportedOnCurrentPlatform(): Boolean {
        val platform = currentPluginPlatform().lowercase()
        val supported = supportedPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        val disabled = disabledPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        if (supported.isNotEmpty() && platform !in supported) return false
        if (platform in disabled) return false
        return true
    }

    private fun shouldRefreshRepository(
        repository: PluginRepositoryItem,
        scrapers: List<PluginScraper>,
        nowEpochMs: Long,
    ): Boolean = isPluginRepositoryRefreshDue(repository.lastUpdated, nowEpochMs) ||
        scrapers.count { scraper -> scraper.repositoryUrl == repository.manifestUrl } < repository.scraperCount

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { state ->
            state.copy(
                repositories = state.repositories.map { repo ->
                    if (repo.manifestUrl == manifestUrl) {
                        repo.copy(isRefreshing = true, errorMessage = null)
                    } else {
                        repo
                    }
                },
            )
        }
    }

    private fun serverPushSnapshotLocked(): Pair<Int, List<PluginPushItem>> =
        currentProfileId to _uiState.value.repositories.mapIndexed { index, repo ->
            PluginPushItem(
                url = repo.manifestUrl,
                name = repo.name,
                enabled = true,
                sortOrder = index,
            )
        }

    private fun pushToServer() {
        pushToServer(synchronized(profileLock) { serverPushSnapshotLocked() })
    }

    private fun pushToServer(snapshot: Pair<Int, List<PluginPushItem>>) {
        val revision = serverPushRevision.incrementAndGet()
        synchronized(serverPushLock) {
            latestServerPushRevisionByProfile[snapshot.first] = revision
        }
        scope.launch {
            serverPushMutex.withLock {
                val isLatest = synchronized(serverPushLock) {
                    latestServerPushRevisionByProfile[snapshot.first] == revision
                }
                if (!isLatest) return@withLock
                runCatching {
                    val params = buildJsonObject {
                        put("p_profile_id", snapshot.first)
                        put("p_plugins", json.encodeToJsonElement(snapshot.second))
                    }
                    SupabaseProvider.client.postgrest.rpc("sync_push_plugins", params)
                }.onFailure { error ->
                    log.e(error) { "pushToServer failed profile=${snapshot.first}" }
                }
            }
        }
    }

    private fun persistenceSnapshotLocked(): PluginPersistenceSnapshot =
        PluginPersistenceSnapshot(
            profileId = currentProfileId,
            generation = persistenceGeneration.value,
            revision = persistenceRevision.incrementAndGet(),
            state = _uiState.value,
        )

    private fun persist() {
        val snapshot = synchronized(profileLock) { persistenceSnapshotLocked() }
        val requiresCodeWrite = snapshot.state.scrapers.any { scraper ->
            !PluginStorage.hasScraperCode(snapshot.profileId, scraper.id)
        }
        if (requiresCodeWrite) {
            scope.launch { persist(snapshot) }
        } else {
            persist(snapshot)
        }
    }

    private fun persist(snapshot: PluginPersistenceSnapshot) {
        if (snapshot.generation != persistenceGeneration.value) return
        var cached = true
        snapshot.state.scrapers.forEach { scraper ->
            val scraperCached = PluginStorage.saveScraperCode(
                profileId = snapshot.profileId,
                scraperId = scraper.id,
                code = scraper.code,
                overwrite = false,
            )
            cached = scraperCached && cached
        }
        if (!cached || snapshot.generation != persistenceGeneration.value) {
            if (!cached) {
                log.w { "Failed to persist plugin scraper cache for profile ${snapshot.profileId}" }
            }
            return
        }

        synchronized(persistenceLock) {
            if (snapshot.generation != persistenceGeneration.value) return@synchronized
            val persistedRevision = persistedRevisionByProfile[snapshot.profileId] ?: Long.MIN_VALUE
            if (snapshot.revision < persistedRevision) return@synchronized
            val payload = snapshot.state.toStoredPluginsState()
            PluginStorage.saveState(snapshot.profileId, json.encodeToString(payload))
            persistedRevisionByProfile[snapshot.profileId] = snapshot.revision
        }
    }

    private fun loadStoredState(profileId: Int): StoredPluginsState? {
        val raw = PluginStorage.loadState(profileId)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            json.decodeFromString<StoredPluginsState>(raw)
        }.getOrNull()
    }

    private fun cancelActiveRefreshes() {
        val jobs = synchronized(activeRefreshJobsLock) {
            activeRefreshJobs.values.toList().also { activeRefreshJobs.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    private fun ensureStateLoadedForProfile(profileId: Int) {
        var profileChanged = false
        var requiresMigration = false
        val loaded = synchronized(profileLock) {
            if (initialized && currentProfileId == profileId) {
                false
            } else {
                if (currentProfileId != profileId) {
                    persistenceGeneration.incrementAndGet()
                    profileChanged = true
                }

                currentProfileId = profileId
                val loadedState = loadStateAsUiState(profileId)
                _uiState.value = loadedState.state
                initialized = true
                requiresMigration = loadedState.requiresMigration
                true
            }
        }
        if (!loaded) return
        if (profileChanged) cancelActiveRefreshes()
        if (requiresMigration) persist()
    }

    private fun loadStateAsUiState(profileId: Int): LoadedPluginState {
        val stored = loadStoredState(profileId)
        var requiresMigration = false
        val scrapers = stored?.scrapers
            ?.mapNotNull { storedScraper ->
                storedScraper.restorePluginScraper { scraperId ->
                    PluginStorage.loadScraperCode(profileId, scraperId)
                }?.also { restored ->
                    requiresMigration = requiresMigration || restored.requiresMigration
                }?.scraper
            }
            ?: emptyList()
        return LoadedPluginState(
            state = PluginsUiState(
                pluginsEnabled = stored?.pluginsEnabled ?: true,
                groupStreamsByRepository = stored?.groupStreamsByRepository ?: false,
                repositories = stored?.repositories
                    ?.map {
                        PluginRepositoryItem(
                            manifestUrl = it.manifestUrl,
                            name = it.name,
                            description = it.description,
                            version = it.version,
                            scraperCount = it.scraperCount,
                            lastUpdated = it.lastUpdated,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                    ?: emptyList(),
                scrapers = scrapers,
            ),
            requiresMigration = requiresMigration,
        )
    }

    private fun dedupeManifestUrls(urls: List<String>): List<String> =
        urls.map(::ensureManifestSuffix).distinct()

    private fun ensureManifestSuffix(url: String): String {
        val path = url.substringBefore("?").trimEnd('/')
        val query = url.substringAfter("?", "")
        val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
        return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
    }

    private suspend fun normalizeManifestUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { getString(Res.string.plugins_error_enter_repo_url) }

        val normalizedScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }

        val withoutFragment = normalizedScheme.substringBefore("#")
        val query = withoutFragment.substringAfter("?", "")
        val path = withoutFragment.substringBefore("?").trimEnd('/')
        val manifestPath = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
        return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && !active.id.isBlank() && active.usesPrimaryPlugins) 1 else profileId
    }
}
