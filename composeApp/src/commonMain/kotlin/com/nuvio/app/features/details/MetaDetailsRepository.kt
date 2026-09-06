package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktMoreLikeThis
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object MetaDetailsRepository {
    private data class CachedMetaEntry(
        val baseMeta: MetaDetails,
        val metaScreenMeta: MetaDetails? = null,
        val metaScreenSettingsFingerprint: String? = null,
    )

    private val log = Logger.withTag("MetaDetailsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    private val cachedMetaByRequestKey = mutableMapOf<String, CachedMetaEntry>()
    private val inFlightBaseMeta = mutableMapOf<String, Deferred<Pair<MetaDetails, String>?>>()
    private val inFlightMutex = Mutex()

    fun load(type: String, id: String) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"
        val currentState = _uiState.value
        val mdbListSettings = MdbListSettingsRepository.snapshot()
        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(mdbListSettings)

        cachedMetaByRequestKey[requestKey]?.let { cachedEntry ->
            cachedEntry.metaScreenMeta
                ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
                ?.let { cachedMeta ->
                    _uiState.value = MetaDetailsUiState(meta = cachedMeta.withUnreleasedFilter())
                    activeRequestKey = requestKey
                    return
                }

            val cachedBaseMeta = cachedEntry.baseMeta
            if (!shouldEnrichForMetaScreen(cachedBaseMeta, id, mdbListSettings)) {
                _uiState.value = MetaDetailsUiState(meta = cachedBaseMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
                return
            }

            if (currentState.isLoading && activeRequestKey == requestKey) {
                log.d { "Meta screen enrichment already in flight — type=$type id=$id" }
                return
            }

            activeRequestKey = requestKey
            _uiState.value = MetaDetailsUiState(
                isLoading = true,
                meta = cachedBaseMeta,
            )

            scope.launch {
                val enrichedMeta = withContext(Dispatchers.Default) {
                    enrichForMetaScreen(
                        requestKey = requestKey,
                        meta = cachedBaseMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        settings = mdbListSettings,
                        settingsFingerprint = metaScreenSettingsFingerprint,
                    )
                }
                if (activeRequestKey == requestKey) {
                    _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
                }
            }
            return
        }

        if (currentState.meta?.type == type && currentState.meta.id == id && !currentState.isLoading) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            activeRequestKey = requestKey
            return
        }

        if (currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            return
        }

        activeRequestKey = requestKey
        _uiState.value = MetaDetailsUiState(isLoading = true)

        scope.launch {
            val baseResult = fetchBaseMeta(type, id)
            if (baseResult == null) {
                log.w { "Failed to load meta for type=$type id=$id" }
                if (activeRequestKey == requestKey) {
                    _uiState.value = MetaDetailsUiState(
                        errorMessage = getString(Res.string.details_load_failed_all_addons),
                    )
                    activeRequestKey = null
                }
                return@launch
            }

            val (baseMeta, candidateType) = baseResult
            publishLoadedMeta(
                requestKey = requestKey,
                meta = baseMeta,
                fallbackItemId = id,
                fallbackItemType = candidateType,
                mdbListSettings = mdbListSettings,
                metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
            )
        }
    }

    fun peek(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        val currentMeta = _uiState.value.meta?.takeIf { it.type == type && it.id == id }
        if (currentMeta != null) return currentMeta

        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
        val cachedEntry = cachedMetaByRequestKey[requestKey] ?: return null
        return cachedEntry.metaScreenMeta
            ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
            ?: cachedEntry.baseMeta
    }

    fun clear() {
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        inFlightBaseMeta.values.forEach { it.cancel() }
        inFlightBaseMeta.clear()
        _uiState.value = MetaDetailsUiState()
    }

    suspend fun fetch(type: String, id: String, cacheResult: Boolean = true): MetaDetails? {
        val requestKey = "$type:$id"
        cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }

        val baseResult = fetchBaseMeta(type, id) ?: return null
        val (baseMeta, _) = baseResult
        if (cacheResult) {
            cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = baseMeta)
        }
        return baseMeta
    }

    private suspend fun fetchBaseMeta(
        type: String,
        id: String,
    ): Pair<MetaDetails, String>? {
        val requestKey = "$type:$id"
        val deferred = inFlightMutex.withLock {
            inFlightBaseMeta.getOrPut(requestKey) {
                scope.async(Dispatchers.Default) {
                    try {
                        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
                        val candidates = findReadyMetaCandidates(type = type, id = metaLookupId)

                        for (candidate in candidates) {
                            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                                tryFetchMeta(
                                    manifest = candidate.manifest,
                                    type = candidate.candidateType,
                                    id = metaLookupId,
                                    includeMdbList = false,
                                )
                            }
                            if (result != null) {
                                return@async result to candidate.candidateType
                            }
                        }

                        val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
                        if (tmdbMeta != null) {
                            return@async tmdbMeta to type
                        }

                        null
                    } finally {
                        inFlightMutex.withLock {
                            inFlightBaseMeta.remove(requestKey)
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    private const val FETCH_TIMEOUT_MS = 5_000L
    private const val METADATA_PROVIDER_READY_TIMEOUT_MS = 1_500L
    private const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
    private const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L

    internal data class MetaCandidate(
        val manifest: AddonManifest,
        val candidateType: String,
    )

    private suspend fun findReadyMetaCandidates(type: String, id: String): List<MetaCandidate> {
        AddonRepository.initialize()

        findMetaCandidates(AddonRepository.uiState.value, type, id).takeIf { it.isNotEmpty() }?.let { return it }

        if (!AddonRepository.uiState.value.hasPendingEnabledAddonManifests()) {
            return emptyList()
        }

        val readyState = withTimeoutOrNull(METADATA_PROVIDER_READY_TIMEOUT_MS) {
            AddonRepository.uiState.first { state ->
                findMetaCandidates(state, type, id).isNotEmpty() ||
                    !state.hasPendingEnabledAddonManifests()
            }
        } ?: AddonRepository.uiState.value

        return findMetaCandidates(readyState, type, id)
    }

    internal fun findMetaCandidates(
        state: com.nuvio.app.features.addons.AddonsUiState,
        type: String,
        id: String,
    ): List<MetaCandidate> {
        val manifests = state.addons.enabledAddons().mapNotNull { it.manifest }
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val metaResourceManifests = manifests.filter { manifest ->
            manifest.resources.any { it.name == "meta" }
        }

        // Priority order (matching NuvioTV MetaRepositoryImpl):
        // 1) Addons that explicitly support requested type AND explicitly match the ID prefix
        // 2) Addons that support inferred canonical type AND explicitly match the ID prefix
        // 3) Top addon in installed order that exposes meta resource and explicitly matches ID prefix
        // 4) Fallback: Addons that support requested type but have no idPrefixes (accept all IDs)
        // 5) Fallback: Addons that support inferred canonical type with no idPrefixes
        // 6) Fallback: Top addon in installed order that exposes meta resource with no idPrefixes
        val prioritizedCandidates = linkedSetOf<MetaCandidate>()

        manifests.forEach { manifest ->
            if (manifest.supportsMetaType(requestedType) && manifest.hasExplicitMetaIdPrefix(id)) {
                prioritizedCandidates.add(MetaCandidate(manifest, requestedType))
            }
        }

        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            manifests.forEach { manifest ->
                if (manifest.supportsMetaType(inferredType) && manifest.hasExplicitMetaIdPrefix(id)) {
                    prioritizedCandidates.add(MetaCandidate(manifest, inferredType))
                }
            }
        }

        metaResourceManifests.firstOrNull { it.hasExplicitMetaIdPrefix(id) }?.let { topMetaManifest ->
            topMetaManifest.supportedCandidateType(requestedType, inferredType)?.let { fallbackType ->
                prioritizedCandidates.add(MetaCandidate(topMetaManifest, fallbackType))
            }
        }

        if (prioritizedCandidates.isEmpty()) {
            manifests.forEach { manifest ->
                if (manifest.supportsMetaType(requestedType) && manifest.hasNoMetaIdPrefixes()) {
                    prioritizedCandidates.add(MetaCandidate(manifest, requestedType))
                }
            }
            if (!inferredType.equals(requestedType, ignoreCase = true)) {
                manifests.forEach { manifest ->
                    if (manifest.supportsMetaType(inferredType) && manifest.hasNoMetaIdPrefixes()) {
                        prioritizedCandidates.add(MetaCandidate(manifest, inferredType))
                    }
                }
            }
            metaResourceManifests.firstOrNull { it.hasNoMetaIdPrefixes() }?.let { topMetaManifest ->
                topMetaManifest.supportedCandidateType(requestedType, inferredType)?.let { fallbackType ->
                    prioritizedCandidates.add(MetaCandidate(topMetaManifest, fallbackType))
                }
            }
        }

        return prioritizedCandidates.toList()
    }

    private fun AddonManifest.supportsMetaType(type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && resource.supportsType(target)
        }
    }

    private fun com.nuvio.app.features.addons.AddonResource.supportsType(type: String): Boolean {
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun AddonManifest.hasExplicitMetaIdPrefix(id: String): Boolean {
        val metaResource = resources.firstOrNull { it.name == "meta" }
        if (metaResource?.idPrefixes != null && metaResource.idPrefixes.isNotEmpty()) {
            return metaResource.idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        if (idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix, ignoreCase = true) }
        }
        return false
    }

    private fun AddonManifest.hasNoMetaIdPrefixes(): Boolean {
        val metaResource = resources.firstOrNull { it.name == "meta" }
        val resourcePrefixes = metaResource?.idPrefixes
        if (resourcePrefixes != null) {
            return resourcePrefixes.isEmpty()
        }
        return idPrefixes.isEmpty()
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = type.trim()
        if (normalizedType.equals("tv", ignoreCase = true)) return "series"
        val known = setOf("movie", "series", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "series"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    private fun AddonManifest.supportedCandidateType(requestedType: String, inferredType: String): String? = when {
        supportsMetaType(requestedType) -> requestedType
        supportsMetaType(inferredType) -> inferredType
        else -> null
    }

    private fun findMetaManifests(state: com.nuvio.app.features.addons.AddonsUiState, type: String, id: String): List<AddonManifest> =
        findMetaCandidates(state, type, id).map { it.manifest }.distinct()

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            TmdbSettingsRepository.ensureLoaded()
            log.d { "Fetching meta from: $url" }
            val payload = fetchAddonResponseText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            val tmdbEnriched = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                TmdbMetadataService.enrichMeta(
                    meta = result,
                    fallbackItemId = id,
                    settings = TmdbSettingsRepository.snapshot(),
                )
            } ?: result
            val enriched = if (includeMdbList) {
                MdbListSettingsRepository.ensureLoaded()
                withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
                    MdbListMetadataService.enrichMeta(
                        meta = tmdbEnriched,
                        fallbackItemId = id,
                        settings = MdbListSettingsRepository.snapshot(),
                    )
                } ?: tmdbEnriched
            } else {
                tmdbEnriched
            }
            log.d { "Parsed meta: type=${enriched.type}, name=${enriched.name}, videos=${enriched.videos.size}" }
            if (enriched.videos.isNotEmpty()) {
                val first = enriched.videos.first()
                log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            }
            enriched
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.e(e) { "Failed to fetch/parse meta from $url (manifest=${manifest.transportUrl})" }
            null
        }
    }

    private fun com.nuvio.app.features.addons.AddonsUiState.hasPendingEnabledAddonManifests(): Boolean =
        addons.enabledAddons().any { addon -> addon.manifest == null && addon.isRefreshing }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return itemId

        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: itemId
    }

    private suspend fun tryFetchTmdbFallbackMeta(type: String, id: String): MetaDetails? =
        withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = id,
                settings = TmdbSettingsRepository.snapshot(),
            )
        }

    private suspend fun publishLoadedMeta(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
    ) {
        val cachedEntry = CachedMetaEntry(baseMeta = meta)
        cachedMetaByRequestKey[requestKey] = cachedEntry

        if (!shouldEnrichForMetaScreen(meta, fallbackItemId, mdbListSettings)) {
            if (activeRequestKey == requestKey) {
                _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
            }
            return
        }

        // Emit immediately with TMDB-enriched meta (hero/logo/poster/backdrop are already final
        // from tryFetchMeta, so no layout shift occurs here — matching Desktop behaviour)
        if (activeRequestKey == requestKey) {
            _uiState.value = MetaDetailsUiState(
                isLoading = true,
                meta = meta.withUnreleasedFilter(),
            )
        }

        val enrichedMeta = withContext(Dispatchers.Default) {
            enrichForMetaScreen(
                requestKey = requestKey,
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                settings = mdbListSettings,
                settingsFingerprint = metaScreenSettingsFingerprint,
            )
        }
        cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
            metaScreenMeta = enrichedMeta,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
        if (activeRequestKey == requestKey) {
            _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
        }
    }

    private suspend fun enrichForMetaScreen(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
        settingsFingerprint: String,
    ): MetaDetails {
        val mdbListEnrichedMeta = withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
            MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                settings = settings,
            )
        } ?: meta

        val enrichedMeta = applyMoreLikeThisSource(
            meta = mdbListEnrichedMeta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
        )

        cachedMetaByRequestKey[requestKey] = cachedMetaByRequestKey[requestKey]
            ?.copy(
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )
            ?: CachedMetaEntry(
                baseMeta = meta,
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )

        return enrichedMeta
    }

    private suspend fun applyMoreLikeThisSource(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
    ): MetaDetails {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val shouldUseTrakt = shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) && supportsMoreLikeThis(meta, fallbackItemType)

        if (shouldUseTrakt) {
            val items = runCatching {
                TraktRelatedRepository.getRelated(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Trakt related titles for ${meta.id}: ${error.message}" }
            }.getOrDefault(emptyList())

            return meta.copy(
                moreLikeThis = items,
                moreLikeThisSource = MoreLikeThisSource.TRAKT.takeIf { items.isNotEmpty() },
            )
        }

        val tmdbSettings = TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis) {
            return meta.copy(moreLikeThis = emptyList(), moreLikeThisSource = null)
        }

        return meta.copy(
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { meta.moreLikeThis.isNotEmpty() },
        )
    }

    private fun shouldFetchMdbListOnMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean = MdbListMetadataService.shouldFetchForMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
    )

    private fun shouldEnrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean {
        if (shouldFetchMdbListOnMetaScreen(meta, fallbackItemId, settings)) return true
        return shouldApplyMoreLikeThisSource(meta)
    }

    private fun shouldApplyMoreLikeThisSource(meta: MetaDetails): Boolean {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val trackingSettings = TrackingSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = trackingSettings.moreLikeThisSource,
        ) || !tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis || meta.moreLikeThisSource == null && meta.moreLikeThis.isNotEmpty()
    }

    private fun buildMetaScreenSettingsFingerprint(
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): String {
        TrackingSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        val providers = settings.enabledProvidersInPriorityOrder().joinToString(",")
        val trackingSettings = TrackingSettingsRepository.uiState.value
        val traktAuthMode = TraktAuthRepository.uiState.value.mode
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return buildString {
            append("${settings.enabled}:${settings.apiKey.trim()}:$providers")
            append("|more_like=${trackingSettings.moreLikeThisSource}:$traktAuthMode")
            append("|tmdb=${tmdbSettings.enabled}:${tmdbSettings.useMoreLikeThis}:${tmdbSettings.hasApiKey}:${tmdbSettings.language}")
        }
    }

    private fun supportsMoreLikeThis(meta: MetaDetails, fallbackItemType: String): Boolean =
        normalizeMoreLikeThisType(meta.type) != null || normalizeMoreLikeThisType(fallbackItemType) != null

    private fun normalizeMoreLikeThisType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> null
        }

    private fun MetaDetails.withUnreleasedFilter(): MetaDetails {
        if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMoreLikeThis = moreLikeThis.filterReleasedItems(todayIsoDate)
        return copy(
            moreLikeThis = releasedMoreLikeThis,
            moreLikeThisSource = moreLikeThisSource.takeIf { releasedMoreLikeThis.isNotEmpty() },
            collectionItems = collectionItems.filterReleasedItems(todayIsoDate),
        )
    }

   
    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }
}
