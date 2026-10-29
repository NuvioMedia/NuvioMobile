package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.WatchProgressSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val released: String? = null,
    val hasAired: Boolean = true,
    val lastWatched: Long,
    val sortTimestamp: Long,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
)

@Serializable
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float? = null,
    val progressKey: String? = null,
)

internal fun CachedInProgressItem.resolvedProgressKey(): String =
    progressKey?.takeIf(String::isNotBlank)
        ?: buildWatchProgressKey(
            contentId = contentId,
            seasonNumber = season,
            episodeNumber = episode,
        )

@Serializable
private data class CachedEnrichmentPayload(
    val nextUp: List<CachedNextUpItem> = emptyList(),
    val inProgress: List<CachedInProgressItem> = emptyList(),
)

internal object ContinueWatchingEnrichmentCache {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val storageKey = "cw_enrichment_cache"
    private val cacheLock = SynchronizedObject()
    private val cachedPayloads = mutableMapOf<CacheScope, CachedEnrichmentPayload?>()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun getNextUpSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedNextUpItem> =
        loadPayload(profileId = profileId, source = source)?.nextUp ?: emptyList()

    fun getInProgressSnapshot(
        profileId: Int,
        source: WatchProgressSource,
    ): List<CachedInProgressItem> =
        loadPayload(profileId = profileId, source = source)?.inProgress ?: emptyList()

    fun getSnapshots(
        profileId: Int,
        source: WatchProgressSource,
    ): Pair<List<CachedNextUpItem>, List<CachedInProgressItem>> {
        val payload = loadPayload(profileId = profileId, source = source)
        val nextUp = payload?.nextUp ?: emptyList()
        val inProgress = payload?.inProgress ?: emptyList()
        return nextUp to inProgress
    }

    fun saveSnapshots(
        profileId: Int,
        source: WatchProgressSource,
        generation: Int,
        nextUp: List<CachedNextUpItem>,
        inProgress: List<CachedInProgressItem>,
        force: Boolean = false,
    ): Boolean = synchronized(cacheLock) {
        if (generation != _generation.value) return@synchronized false

        val payload = CachedEnrichmentPayload(nextUp = nextUp.toList(), inProgress = inProgress.toList())
        val scope = CacheScope(profileId = profileId, source = source)
        if (!force && cachedPayloads[scope] == payload) {
            return@synchronized true
        }

        removeLegacyPayload(profileId)
        val encoded = runCatching {
            json.encodeToString(payload)
        }.getOrNull() ?: return@synchronized false
        ContinueWatchingEnrichmentStorage.savePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            encoded,
        )
        cachedPayloads[scope] = payload
        true
    }

    fun invalidate(
        profileId: Int,
        source: WatchProgressSource,
    ) = synchronized(cacheLock) {
        ContinueWatchingEnrichmentStorage.removePayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        )
        removeLegacyPayload(profileId)
        cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        advanceGeneration()
    }

    fun clearAll(profileId: Int) = synchronized(cacheLock) {
        WatchProgressSource.entries.forEach { source ->
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            cachedPayloads.remove(CacheScope(profileId = profileId, source = source))
        }
        removeLegacyPayload(profileId)
        advanceGeneration()
    }

    fun clearLocalState() = synchronized(cacheLock) {
        cachedPayloads.clear()
        advanceGeneration()
    }

    fun onProfileChanged() = synchronized(cacheLock) {
        cachedPayloads.clear()
        advanceGeneration()
    }

    private fun loadPayload(
        profileId: Int,
        source: WatchProgressSource,
    ): CachedEnrichmentPayload? = synchronized(cacheLock) {
        val scope = CacheScope(profileId = profileId, source = source)
        if (cachedPayloads.containsKey(scope)) return@synchronized cachedPayloads[scope]
        removeLegacyPayload(profileId)
        val raw = ContinueWatchingEnrichmentStorage.loadPayload(
            continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
        ) ?: run {
            cachedPayloads[scope] = null
            return@synchronized null
        }
        runCatching {
            json.decodeFromString<CachedEnrichmentPayload>(raw)
        }.getOrNull()?.also { payload ->
            cachedPayloads[scope] = payload
        } ?: run {
            cachedPayloads[scope] = null
            ContinueWatchingEnrichmentStorage.removePayload(
                continueWatchingEnrichmentStorageKey(profileId = profileId, source = source),
            )
            null
        }
    }

    private fun removeLegacyPayload(profileId: Int) {
        ContinueWatchingEnrichmentStorage.removePayload(legacyStorageKey(profileId))
    }

    private fun advanceGeneration() {
        _generation.value += 1
    }

    private data class CacheScope(
        val profileId: Int,
        val source: WatchProgressSource,
    )

    internal fun continueWatchingEnrichmentStorageKey(
        profileId: Int,
        source: WatchProgressSource,
    ): String = ProfileScopedKey.of(
        baseKey = "${storageKey}_${source.name.lowercase()}",
        profileId = profileId,
    )

    internal fun legacyStorageKey(profileId: Int): String =
        ProfileScopedKey.of(storageKey, profileId)
}
