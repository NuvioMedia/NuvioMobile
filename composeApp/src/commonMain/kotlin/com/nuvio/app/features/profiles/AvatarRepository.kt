package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.membership.CosmeticEntitlement
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.MemberAssetStorage
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val MemberAvatarBucket = "membership-profile-avatars"
private val AvatarCatalogRefreshInterval = 15.minutes

@Serializable
private data class StoredAvatarCatalogPayload(
    val items: List<AvatarCatalogItem> = emptyList(),
    val memberItems: List<MemberAvatarCatalogItem> = emptyList(),
)

@Serializable
private data class MemberAvatarCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    val category: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("bg_color") val bgColor: String? = null,
    @SerialName("asset_version") val assetVersion: Int,
)

internal fun availableAvatarCatalog(
    standardCatalog: List<AvatarCatalogItem>,
    memberCatalog: List<AvatarCatalogItem>,
    hasMemberAccess: Boolean,
): List<AvatarCatalogItem> = standardCatalog + if (hasMemberAccess) memberCatalog else emptyList()

object AvatarRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AvatarRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _avatars = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatars: StateFlow<List<AvatarCatalogItem>> = _avatars.asStateFlow()

    private var standardCatalog = emptyList<AvatarCatalogItem>()
    private var memberCatalog = emptyList<AvatarCatalogItem>()
    private var memberCatalogMetadata = emptyList<MemberAvatarCatalogItem>()
    private var standardLoaded = false
    private var cacheHydrated = false
    private var accessObserverStarted = false
    private var standardFetchInFlight = false
    private var memberFetchInFlight = false
    private var hasMemberAccess = false
    private var lastStandardRefresh: TimeMark? = null
    private var lastMemberRefresh: TimeMark? = null

    suspend fun shareableImageUrl(profile: NuvioProfile): String? {
        val storage = "${ServerConfigurationRepository.active.value.backendUrl}/storage/v1/object/"
        normalizedAvatarUrl(profile.avatarUrl)?.let { url -> return url.takeIf { it.startsWith(storage) } }
        val avatar = profile.avatarId?.let { id -> avatars.value.firstOrNull { it.id == id } } ?: return null
        if (!avatar.memberOnly) return avatar.storagePath.takeIf { it.isNotBlank() }?.let(::avatarStorageUrl)
        return try {
            SupabaseProvider.client.storage[MemberAvatarBucket].createSignedUrl(avatar.storagePath, 1.days)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to share supporter avatar ${avatar.id}" }
            null
        }
    }

    suspend fun fetchAvatars() {
        hydrateFromCacheIfNeeded()
        ensureMemberAccessObserver()
        if (standardLoaded && standardCatalog.isNotEmpty()) {
            publishCatalog()
            return
        }
        fetchStandardCatalog()
    }

    suspend fun refreshAvatars(force: Boolean = false) {
        hydrateFromCacheIfNeeded()
        ensureMemberAccessObserver()
        if (force || isRefreshDue(lastStandardRefresh)) {
            fetchStandardCatalog()
        }
        if (hasMemberAccess && (force || isRefreshDue(lastMemberRefresh))) {
            fetchMemberCatalog()
        }
    }

    private fun isRefreshDue(lastRefresh: TimeMark?): Boolean =
        lastRefresh == null || lastRefresh.elapsedNow() >= AvatarCatalogRefreshInterval

    private fun hydrateFromCacheIfNeeded() {
        if (cacheHydrated) return
        cacheHydrated = true

        val payload = AvatarStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val stored = runCatching {
            json.decodeFromString<StoredAvatarCatalogPayload>(payload)
        }.getOrNull() ?: return

        standardCatalog = stored.items
            .filter { it.isActive }
            .sortedWith(compareBy({ it.category }, { it.sortOrder }))
        memberCatalogMetadata = stored.memberItems
        memberCatalog = memberCatalogMetadata
            .mapNotNull(::loadCachedMemberAvatar)
            .sortedWith(compareBy({ it.category }, { it.sortOrder }))
        standardLoaded = standardCatalog.isNotEmpty()
        publishCatalog()
    }

    private fun ensureMemberAccessObserver() {
        if (accessObserverStarted) return
        accessObserverStarted = true
        MemberAccessRepository.ensureStarted()
        hasMemberAccess = MemberAccessRepository.access.value.entitlements
            .includes(CosmeticEntitlement.PROFILE_AVATARS)
        publishCatalog()
        if (hasMemberAccess) scope.launch { fetchMemberCatalog() }
        scope.launch {
            MemberAccessRepository.access.collectLatest { access ->
                val nextAccess = access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS)
                if (nextAccess == hasMemberAccess) return@collectLatest
                hasMemberAccess = nextAccess
                if (nextAccess) {
                    fetchMemberCatalog()
                } else {
                    publishCatalog()
                }
            }
        }
    }

    private suspend fun fetchStandardCatalog() {
        if (standardFetchInFlight) return
        standardFetchInFlight = true
        try {
            val result = SupabaseProvider.client.postgrest.rpc("get_avatar_catalog")
            val items = result.decodeList<AvatarCatalogItem>()
            standardCatalog = items.filter { it.isActive }.sortedWith(
                compareBy({ it.category }, { it.sortOrder }),
            )
            standardLoaded = true
            lastStandardRefresh = TimeSource.Monotonic.markNow()
            publishCatalog()
            saveCachedCatalog()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "Failed to fetch avatar catalog" }
        } finally {
            standardFetchInFlight = false
        }
    }

    private suspend fun fetchMemberCatalog() {
        if (memberFetchInFlight) return
        memberFetchInFlight = true
        try {
            val remote = SupabaseProvider.client.postgrest
                .rpc("get_member_profile_avatar_catalog")
                .decodeList<MemberAvatarCatalogItem>()
            lastMemberRefresh = TimeSource.Monotonic.markNow()
            memberCatalogMetadata = remote
            saveCachedCatalog()
            memberCatalog = coroutineScope {
                remote.map { item ->
                    async { loadMemberAvatar(item) }
                }.awaitAll().filterNotNull()
            }.sortedWith(compareBy({ it.category }, { it.sortOrder }))
            publishCatalog()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter avatar catalog" }
        } finally {
            memberFetchInFlight = false
        }
    }

    private suspend fun loadMemberAvatar(item: MemberAvatarCatalogItem): AvatarCatalogItem? {
        return try {
            val cacheKey = memberAvatarCacheKey(item)
            val localImageUrl = MemberAssetStorage.loadProfileAvatar(cacheKey)
                ?: SupabaseProvider.client.storage[MemberAvatarBucket]
                    .downloadAuthenticated(item.storagePath)
                    .let { bytes -> MemberAssetStorage.saveProfileAvatar(cacheKey, bytes) }
                ?: return null
            item.toAvatarCatalogItem(localImageUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Unable to load supporter avatar ${item.id}" }
            null
        }
    }

    private fun loadCachedMemberAvatar(item: MemberAvatarCatalogItem): AvatarCatalogItem? {
        val localImageUrl = MemberAssetStorage.loadProfileAvatar(memberAvatarCacheKey(item)) ?: return null
        return item.toAvatarCatalogItem(localImageUrl)
    }

    private fun memberAvatarCacheKey(item: MemberAvatarCatalogItem): String {
        val extension = item.storagePath.substringAfterLast('.', "img")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        return "${item.id}-v${item.assetVersion}.$extension"
    }

    private fun MemberAvatarCatalogItem.toAvatarCatalogItem(localImageUrl: String): AvatarCatalogItem =
        AvatarCatalogItem(
            id = id,
            displayName = displayName,
            storagePath = storagePath,
            category = category,
            sortOrder = sortOrder,
            bgColor = bgColor,
            localImageUrl = localImageUrl,
            memberOnly = true,
        )

    private fun saveCachedCatalog() {
        AvatarStorage.savePayload(
            json.encodeToString(
                StoredAvatarCatalogPayload(
                    items = standardCatalog,
                    memberItems = memberCatalogMetadata,
                ),
            ),
        )
    }

    private fun publishCatalog() {
        _avatars.value = availableAvatarCatalog(
            standardCatalog = standardCatalog,
            memberCatalog = memberCatalog,
            hasMemberAccess = hasMemberAccess,
        )
    }
}
