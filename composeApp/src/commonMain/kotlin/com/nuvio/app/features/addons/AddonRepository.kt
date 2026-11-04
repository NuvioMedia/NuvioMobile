package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId: Int = 1
    private var profileGeneration: Long = 0L
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val activeRefreshJobsLock = SynchronizedObject()
    private val refreshSemaphore = Semaphore(3)
    private val pushJobsByProfile = mutableMapOf<Int, Job>()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        activateProfile(effectiveProfileId)
        val expectedProfile = synchronized(activeRefreshJobsLock) {
            if (initialized) return
            currentProfileId to profileGeneration
        }
        val profileId = expectedProfile.first
        val generation = expectedProfile.second
        log.d { "initialize() — loading local addons for profile $profileId" }

        // Read from the captured profile. A profile switch during storage access must
        // not allow this older read to replace the new profile's visible state.
        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(profileId))
        val enabledByUrl = AddonStorage.loadAddonEnabledStates(profileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }
        val existingByUrl = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        }
        val loadedAddons = storedUrls.map { manifestUrl ->
            existingByUrl[manifestUrl].toPendingAddon(
                manifestUrl = manifestUrl,
                enabled = enabledByUrl[manifestUrl],
            )
        }
        val applied = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) {
                false
            } else {
                _uiState.value = AddonsUiState(addons = loadedAddons)
                initialized = true
                true
            }
        }
        if (!applied) return
        log.d { "initialize() — local addon count: ${storedUrls.size}" }

        // Build one lookup map instead of rescanning the whole list for each URL.
        val loadedByUrl = loadedAddons.associateBy(ManagedAddon::manifestUrl)
        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = loadedByUrl[manifestUrl]
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                // Respect the HTTP cache on startup; explicit user refreshes still
                // bypass it through refreshAll()/forceRefresh.
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        val alreadyLoaded = synchronized(activeRefreshJobsLock) {
            effectiveProfileId == currentProfileId && initialized
        }
        if (alreadyLoaded) return
        activateProfile(effectiveProfileId, forceReset = true)
    }

    fun clearLocalState() {
        activateProfile(1, forceReset = true)
        val jobs = synchronized(activeRefreshJobsLock) {
            pushJobsByProfile.values.toList().also { pushJobsByProfile.clear() }
        }
        jobs.forEach(Job::cancel)
    }

    suspend fun pullFromServer(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        activateProfile(effectiveProfileId)
        val expectedGeneration = synchronized(activeRefreshJobsLock) { profileGeneration }
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        try {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", effectiveProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val urls = rowsByUrl.keys.toList()
            log.i { "pullFromServer() — server returned ${rows.size} addons" }
            urls.forEachIndexed { i, u -> log.d { "  server[$i]: $u" } }

            val existingByUrl = synchronized(activeRefreshJobsLock) {
                if (!isProfileGenerationCurrentLocked(effectiveProfileId, expectedGeneration)) return
                _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            }
            val loadedAddons = urls.map { url ->
                val row = rowsByUrl[url]
                existingByUrl[url].toPendingAddon(
                    manifestUrl = url,
                    userSetName = row?.name?.takeIf { it.isNotBlank() },
                    enabled = row?.enabled,
                )
            }
            val applied = synchronized(activeRefreshJobsLock) {
                if (!isProfileGenerationCurrentLocked(effectiveProfileId, expectedGeneration)) {
                    false
                } else {
                    _uiState.value = AddonsUiState(addons = loadedAddons)
                    initialized = true
                    true
                }
            }
            if (!applied) return
            persistSnapshot(effectiveProfileId, loadedAddons)

            val loadedByUrl = loadedAddons.associateBy(ManagedAddon::manifestUrl)
            urls.forEach { url ->
                val existing = existingByUrl[url]
                val addon = loadedByUrl[url]
                if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                    refreshAddon(url)
                }
            }
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.e(error) { "pullFromServer() — FAILED" }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        val expectedProfile = synchronized(activeRefreshJobsLock) { currentProfileId to profileGeneration }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        var duplicateAtCommit = false
        val appliedAddons = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(expectedProfile.first, expectedProfile.second)) {
                null
            } else {
                duplicateAtCommit = _uiState.value.addons.any { it.manifestUrl == manifestUrl }
                if (!duplicateAtCommit) {
                    _uiState.update { current ->
                        current.copy(
                            addons = current.addons + ManagedAddon(
                                manifestUrl = manifestUrl,
                                manifest = manifest,
                                isRefreshing = false,
                                errorMessage = null,
                            ),
                        )
                    }
                }
                _uiState.value.addons
            }
        } ?: return AddAddonResult.Error("Active profile changed while adding the add-on")
        if (duplicateAtCommit) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }
        persistSnapshot(expectedProfile.first, appliedAddons)
        pushToServer(expectedProfile.first, expectedProfile.second)
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        val mutation = synchronized(activeRefreshJobsLock) {
            val profileId = currentProfileId
            val generation = profileGeneration
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            val current = _uiState.value
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            if (updatedAddons.size == current.addons.size) return
            _uiState.value = current.copy(addons = updatedAddons)
            val refreshJob = activeRefreshJobs.remove(manifestUrl)
            Triple(profileId to generation, updatedAddons, refreshJob)
        }
        mutation.third?.cancel()
        persistSnapshot(mutation.first.first, mutation.second)
        pushToServer(mutation.first.first, mutation.first.second)
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val mutation = synchronized(activeRefreshJobsLock) {
            val profileId = currentProfileId
            val generation = profileGeneration
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            val addons = _uiState.value.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return
            }
            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            _uiState.value = _uiState.value.copy(addons = reordered)
            Triple(profileId to generation, reordered, Unit)
        }
        persistSnapshot(mutation.first.first, mutation.second)
        pushToServer(mutation.first.first, mutation.first.second)
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val mutation = synchronized(activeRefreshJobsLock) {
            val profileId = currentProfileId
            val generation = profileGeneration
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            val current = _uiState.value
            val index = current.addons.indexOfFirst { it.manifestUrl == manifestUrl }
            if (index < 0 || current.addons[index].enabled == enabled) return
            val existing = current.addons[index]
            val shouldRefresh = enabled && existing.manifest == null && !existing.isRefreshing
            val updatedAddons = current.addons.toMutableList().apply {
                this[index] = existing.copy(enabled = enabled)
            }
            _uiState.value = current.copy(addons = updatedAddons)
            Triple(profileId to generation, updatedAddons, shouldRefresh)
        }
        persistSnapshot(mutation.first.first, mutation.second)
        pushToServer(mutation.first.first, mutation.first.second)
        if (mutation.third) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        val refreshPlan = synchronized(activeRefreshJobsLock) {
            val profileId = currentProfileId
            val generation = profileGeneration
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return

            val manifestUrls = _uiState.value.addons
                .filter { it.enabled }
                .distinctBy { it.manifestUrl }
                .map { it.manifestUrl }
            val jobsToCancel = manifestUrls.mapNotNull { activeRefreshJobs.remove(it) }
            Triple(profileId to generation, manifestUrls, jobsToCancel)
        }
        refreshPlan.third.forEach(Job::cancel)
        refreshPlan.second.forEach { manifestUrl ->
            refreshAddonForProfile(
                manifestUrl = manifestUrl,
                forceRefresh = true,
                notifyOnSuccess = false,
                expectedProfileId = refreshPlan.first.first,
                expectedGeneration = refreshPlan.first.second,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
        notifyOnSuccess: Boolean = false,
    ) {
        val expectedProfile = synchronized(activeRefreshJobsLock) {
            currentProfileId to profileGeneration
        }
        refreshAddonForProfile(
            manifestUrl = manifestUrl,
            forceRefresh = forceRefresh,
            notifyOnSuccess = notifyOnSuccess,
            expectedProfileId = expectedProfile.first,
            expectedGeneration = expectedProfile.second,
        )
    }

    private fun refreshAddonForProfile(
        manifestUrl: String,
        forceRefresh: Boolean,
        notifyOnSuccess: Boolean,
        expectedProfileId: Int,
        expectedGeneration: Long,
    ) {
        var jobToStart: Job? = null
        synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(expectedProfileId, expectedGeneration)) return
            val existingJob = activeRefreshJobs[manifestUrl]
            if (existingJob != null && !existingJob.isCompleted) return
            markRefreshing(manifestUrl)
            lateinit var refreshJob: Job
            refreshJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result: Result<AddonManifest> = try {
                        refreshSemaphore.withPermit {
                            val payload = fetchAddonResponseText(url = manifestUrl, forceRefresh = forceRefresh)
                            Result.success(AddonManifestParser.parse(manifestUrl = manifestUrl, payload = payload))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        log.w(error) { "refreshAddon() failed manifestUrl=" + manifestUrl }
                        Result.failure(error)
                    }
                    val refreshFailureMessage = if (result.isFailure) {
                        // Refresh jobs run on Dispatchers.Default and can outlive host-side tests.
                        // Compose resource lookup may require an Android instrumented context, so
                        // keep a safe fallback instead of leaking an exception from this background job.
                        runCatching { getString(Res.string.addon_load_manifest_failed) }
                            .getOrDefault("Failed to load add-on manifest")
                    } else {
                        null
                    }
                    val applied = synchronized(activeRefreshJobsLock) {
                        if (
                            !isProfileGenerationCurrentLocked(expectedProfileId, expectedGeneration) ||
                            activeRefreshJobs[manifestUrl] !== refreshJob
                        ) {
                            false
                        } else {
                            _uiState.update { current ->
                                current.copy(
                                    addons = current.addons.map { addon ->
                                        if (addon.manifestUrl != manifestUrl) addon
                                        else result.fold(
                                            onSuccess = { manifest ->
                                                addon.copy(manifest = manifest, isRefreshing = false, errorMessage = null)
                                            },
                                            onFailure = { error ->
                                                addon.copy(
                                                    isRefreshing = false,
                                                    errorMessage = error.message ?: refreshFailureMessage,
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                            true
                        }
                    }
                    if (applied && notifyOnSuccess && result.isSuccess) {
                        NuvioToastController.show(getString(Res.string.addons_and_plugins_refreshed))
                    }
                } finally {
                    synchronized(activeRefreshJobsLock) {
                        if (activeRefreshJobs[manifestUrl] === refreshJob) activeRefreshJobs.remove(manifestUrl)
                    }
                }
            }
            activeRefreshJobs[manifestUrl] = refreshJob
            jobToStart = refreshJob
        }
        jobToStart?.start()
    }

    private fun pushToServer(
        profileId: Int,
        generation: Long,
    ) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val addons = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) return
            _uiState.value.addons
        }
            .distinctBy { it.manifestUrl }
            .mapIndexed { index, addon ->
                AddonPushItem(
                    url = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = addon.enabled,
                    sortOrder = index,
                )
            }

        lateinit var pushJob: Job
        pushJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                    putSyncOriginClientId()
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                log.d { "pushToServer() — success" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
            } finally {
                synchronized(activeRefreshJobsLock) {
                    if (pushJobsByProfile[profileId] === pushJob) {
                        pushJobsByProfile.remove(profileId)
                    }
                }
            }
        }

        var previousJob: Job? = null
        val scheduled = synchronized(activeRefreshJobsLock) {
            if (!isProfileGenerationCurrentLocked(profileId, generation)) {
                false
            } else {
                previousJob = pushJobsByProfile.put(profileId, pushJob)
                true
            }
        }
        if (!scheduled) {
            pushJob.cancel()
            return
        }
        previousJob?.cancel()
        pushJob.start()
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persistSnapshot(profileId: Int, addons: List<ManagedAddon>) {
        AddonStorage.saveInstalledAddonUrls(
            profileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            profileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun isProfileGenerationCurrentLocked(profileId: Int, generation: Long): Boolean =
        currentProfileId == profileId &&
            profileGeneration == generation &&
            resolveEffectiveProfileId(ProfileRepository.activeProfileId) == profileId

    private fun activateProfile(profileId: Int, forceReset: Boolean = false) {
        var jobsToCancel: List<Job> = emptyList()
        synchronized(activeRefreshJobsLock) {
            if (forceReset || currentProfileId != profileId) {
                profileGeneration += 1L
                jobsToCancel = activeRefreshJobs.values.toList()
                activeRefreshJobs.clear()
                currentProfileId = profileId
                initialized = false
                _uiState.value = AddonsUiState()
            }
        }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
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
    if (trimmed.isEmpty()) {
        throw IllegalArgumentException(getString(Res.string.addons_error_enter_url))
    }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}
