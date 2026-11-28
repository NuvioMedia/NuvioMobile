package com.nuvio.app.features.membership

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ProfileBackgroundBucket = "membership-profile-backgrounds"

data class ProfileBackgroundCatalogItem(
    val id: String,
    val displayName: String,
    val landscapeImageBytes: ByteArray? = null,
    val portraitImageBytes: ByteArray? = null,
    val assetVersion: Int,
)

object ProfileBackgroundRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileBackgroundRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _catalog = MutableStateFlow<List<ProfileBackgroundCatalogItem>>(emptyList())
    val catalog: StateFlow<List<ProfileBackgroundCatalogItem>> = _catalog.asStateFlow()
    private var loadJob: Job? = null
    private var assetLoadJob: Job? = null
    private var remoteCatalog = emptyList<SupabaseProfileBackgroundCatalogItem>()
    private var cacheHydrated = false
    private var remoteLoaded = false

    fun ensureLoaded() {
        hydrateFromCacheIfNeeded()
        if (remoteLoaded || loadJob?.isActive == true) return
        loadJob = scope.launch {
            try {
                val items = SupabaseProvider.client.postgrest
                    .rpc("get_member_profile_background_catalog")
                    .decodeList<SupabaseProfileBackgroundCatalogItem>()
                remoteCatalog = items
                publishMetadata(items)
                saveCachedCatalog(items)
                remoteLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Unable to load supporter profile backgrounds" }
            }
        }
    }

    fun loadSelectedAndPreload(id: String, portrait: Boolean) {
        ensureLoaded()
        remoteCatalog.firstOrNull { it.id == id }?.let { item ->
            loadCachedAndPublish(item, portrait)
        }
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            val initialSelected = remoteCatalog.firstOrNull { it.id == id }
            initialSelected?.let { loadAndPublish(it, portrait) }
            loadJob?.join()
            val selected = remoteCatalog.firstOrNull { it.id == id } ?: return@launch
            if (selected.assetVersion != initialSelected?.assetVersion) {
                loadAndPublish(selected, portrait)
            }
            coroutineScope {
                remoteCatalog
                    .filterNot { it.id == id }
                    .map { item -> launch { loadAndPublish(item, portrait) } }
                    .joinAll()
            }
        }
    }

    fun preloadLandscapeImages() {
        ensureLoaded()
        assetLoadJob?.cancel()
        assetLoadJob = scope.launch {
            val initialVersions = remoteCatalog.associate { it.id to it.assetVersion }
            preload(remoteCatalog, portrait = false)
            loadJob?.join()
            preload(
                items = remoteCatalog.filter { initialVersions[it.id] != it.assetVersion },
                portrait = false,
            )
        }
    }

    fun invalidate() {
        cacheHydrated = false
        remoteLoaded = false
        loadJob?.cancel()
        loadJob = null
        assetLoadJob?.cancel()
        assetLoadJob = null
        remoteCatalog = emptyList()
        _catalog.value = emptyList()
    }

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true
        val payload = MemberAssetStorage.loadProfileBackgroundCatalogPayload().orEmpty().trim()
        if (payload.isEmpty()) return
        val stored = runCatching {
            json.decodeFromString<StoredProfileBackgroundCatalogPayload>(payload)
        }.getOrNull() ?: return
        remoteCatalog = stored.items
        publishMetadata(stored.items)
    }

    private fun publishMetadata(items: List<SupabaseProfileBackgroundCatalogItem>) {
        val current = _catalog.value.associateBy(ProfileBackgroundCatalogItem::id)
        _catalog.value = items.map { item ->
            val cached = current[item.id]?.takeIf { it.assetVersion == item.assetVersion }
            ProfileBackgroundCatalogItem(
                id = item.id,
                displayName = item.displayName,
                landscapeImageBytes = cached?.landscapeImageBytes,
                portraitImageBytes = cached?.portraitImageBytes,
                assetVersion = item.assetVersion,
            )
        }
    }

    private fun saveCachedCatalog(items: List<SupabaseProfileBackgroundCatalogItem>) {
        MemberAssetStorage.saveProfileBackgroundCatalogPayload(
            json.encodeToString(StoredProfileBackgroundCatalogPayload(items)),
        )
    }

    private fun loadCachedAndPublish(item: SupabaseProfileBackgroundCatalogItem, portrait: Boolean) {
        val landscape = MemberAssetStorage.loadProfileBackground(
            "${item.id}-v${item.assetVersion}",
        )
        val bytes = if (portrait) {
            item.portraitStoragePath?.let {
                MemberAssetStorage.loadProfileBackground("${item.id}-portrait-v${item.assetVersion}")
            } ?: landscape
        } else {
            landscape
        } ?: return
        publishImage(item, portrait, bytes)
    }

    private suspend fun preload(items: List<SupabaseProfileBackgroundCatalogItem>, portrait: Boolean) {
        coroutineScope {
            items.map { item -> launch { loadAndPublish(item, portrait) } }.joinAll()
        }
    }

    private suspend fun loadAndPublish(item: SupabaseProfileBackgroundCatalogItem, portrait: Boolean) {
        try {
            val bytes = if (portrait) loadPortraitImage(item) else loadLandscapeImage(item)
            publishImage(item, portrait, bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter profile background ${item.id}" }
        }
    }

    private fun publishImage(
        item: SupabaseProfileBackgroundCatalogItem,
        portrait: Boolean,
        bytes: ByteArray,
    ) {
        _catalog.update { catalog ->
            catalog.map { background ->
                val current = if (portrait) background.portraitImageBytes else background.landscapeImageBytes
                when {
                    background.id != item.id || background.assetVersion != item.assetVersion -> background
                    current?.contentEquals(bytes) == true -> background
                    portrait -> background.copy(portraitImageBytes = bytes)
                    else -> background.copy(landscapeImageBytes = bytes)
                }
            }
        }
    }

    private suspend fun loadPortraitImage(item: SupabaseProfileBackgroundCatalogItem): ByteArray {
        val portraitPath = item.portraitStoragePath ?: return loadLandscapeImage(item)
        return try {
            loadImage(
                cacheKey = "${item.id}-portrait-v${item.assetVersion}",
                storagePath = portraitPath,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load portrait background ${item.id}" }
            loadLandscapeImage(item)
        }
    }

    private suspend fun loadLandscapeImage(item: SupabaseProfileBackgroundCatalogItem): ByteArray =
        loadImage(
            cacheKey = "${item.id}-v${item.assetVersion}",
            storagePath = item.storagePath,
        )

    private suspend fun loadImage(cacheKey: String, storagePath: String): ByteArray =
        MemberAssetStorage.loadProfileBackground(cacheKey)
            ?: SupabaseProvider.client.storage[ProfileBackgroundBucket]
                .downloadAuthenticated(storagePath)
                .also { MemberAssetStorage.saveProfileBackground(cacheKey, it) }
}

@Serializable
private data class StoredProfileBackgroundCatalogPayload(
    val items: List<SupabaseProfileBackgroundCatalogItem> = emptyList(),
)

@Serializable
private data class SupabaseProfileBackgroundCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("portrait_storage_path") val portraitStoragePath: String? = null,
    @SerialName("asset_version") val assetVersion: Int,
)
