package com.nuvio.app.features.tmdb

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TmdbSettingsRepository {
    private val _uiState = MutableStateFlow(TmdbSettings())
    val uiState: StateFlow<TmdbSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var apiKey = ""
    private var language = "en"
    private var useTrailers = true
    private var useArtwork = true
    private var useBasicInfo = true
    private var useDetails = true
    private var useCredits = true
    private var useProductions = true
    private var useNetworks = true
    private var useEpisodes = true
    private var useSeasonPosters = true
    private var useMoreLikeThis = true
    private var useCollections = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): TmdbSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun effectiveApiKey(): String = snapshot().apiKey.ifBlank { TmdbConfig.API_KEY }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (enabled == value) return
        enabled = value
        publish()
        TmdbSettingsStorage.saveEnabled(value)
    }

    fun setApiKey(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (apiKey == normalized) return
        apiKey = normalized
        publish()
        TmdbSettingsStorage.saveApiKey(normalized)
        invalidateMetadata()
    }

    fun setLanguage(value: String) {
        ensureLoaded()
        val normalized = normalizeLanguage(value)
        if (language == normalized) return
        language = normalized
        publish()
        TmdbSettingsStorage.saveLanguage(normalized)
    }

    fun setUseTrailers(value: Boolean) = setBoolean(
        current = useTrailers,
        next = value,
        update = { useTrailers = it },
        persist = TmdbSettingsStorage::saveUseTrailers,
    )

    fun setUseArtwork(value: Boolean) = setBoolean(
        current = useArtwork,
        next = value,
        update = { useArtwork = it },
        persist = TmdbSettingsStorage::saveUseArtwork,
    )

    fun setUseBasicInfo(value: Boolean) = setBoolean(
        current = useBasicInfo,
        next = value,
        update = { useBasicInfo = it },
        persist = TmdbSettingsStorage::saveUseBasicInfo,
    )

    fun setUseDetails(value: Boolean) = setBoolean(
        current = useDetails,
        next = value,
        update = { useDetails = it },
        persist = TmdbSettingsStorage::saveUseDetails,
    )

    fun setUseCredits(value: Boolean) = setBoolean(
        current = useCredits,
        next = value,
        update = { useCredits = it },
        persist = TmdbSettingsStorage::saveUseCredits,
    )

    fun setUseProductions(value: Boolean) = setBoolean(
        current = useProductions,
        next = value,
        update = { useProductions = it },
        persist = TmdbSettingsStorage::saveUseProductions,
    )

    fun setUseNetworks(value: Boolean) = setBoolean(
        current = useNetworks,
        next = value,
        update = { useNetworks = it },
        persist = TmdbSettingsStorage::saveUseNetworks,
    )

    fun setUseEpisodes(value: Boolean) = setBoolean(
        current = useEpisodes,
        next = value,
        update = { useEpisodes = it },
        persist = TmdbSettingsStorage::saveUseEpisodes,
    )

    fun setUseSeasonPosters(value: Boolean) = setBoolean(
        current = useSeasonPosters,
        next = value,
        update = { useSeasonPosters = it },
        persist = TmdbSettingsStorage::saveUseSeasonPosters,
    )

    fun setUseMoreLikeThis(value: Boolean) = setBoolean(
        current = useMoreLikeThis,
        next = value,
        update = { useMoreLikeThis = it },
        persist = TmdbSettingsStorage::saveUseMoreLikeThis,
    )

    fun setUseCollections(value: Boolean) = setBoolean(
        current = useCollections,
        next = value,
        update = { useCollections = it },
        persist = TmdbSettingsStorage::saveUseCollections,
    )

    private fun setBoolean(
        current: Boolean,
        next: Boolean,
        update: (Boolean) -> Unit,
        persist: (Boolean) -> Unit,
    ) {
        ensureLoaded()
        if (current == next) return
        update(next)
        publish()
        persist(next)
    }

    private fun loadFromDisk() {
        val wasLoaded = hasLoaded
        val previousApiKey = apiKey
        hasLoaded = true
        enabled = TmdbSettingsStorage.loadEnabled() ?: false
        apiKey = TmdbSettingsStorage.loadApiKey()?.trim().orEmpty()
        val storedLanguage = TmdbSettingsStorage.loadLanguage()
        language = if (storedLanguage == null) "en" else normalizeLanguage(storedLanguage)
        useTrailers = TmdbSettingsStorage.loadUseTrailers() ?: true
        useArtwork = TmdbSettingsStorage.loadUseArtwork() ?: true
        useBasicInfo = TmdbSettingsStorage.loadUseBasicInfo() ?: true
        useDetails = TmdbSettingsStorage.loadUseDetails() ?: true
        useCredits = TmdbSettingsStorage.loadUseCredits() ?: true
        useProductions = TmdbSettingsStorage.loadUseProductions() ?: true
        useNetworks = TmdbSettingsStorage.loadUseNetworks() ?: true
        useEpisodes = TmdbSettingsStorage.loadUseEpisodes() ?: true
        useSeasonPosters = TmdbSettingsStorage.loadUseSeasonPosters() ?: true
        useMoreLikeThis = TmdbSettingsStorage.loadUseMoreLikeThis() ?: true
        useCollections = TmdbSettingsStorage.loadUseCollections() ?: true
        publish()
        if (wasLoaded && previousApiKey != apiKey) {
            invalidateMetadata()
        }
    }

    private fun publish() {
        _uiState.value = TmdbSettings(
            enabled = enabled,
            apiKey = apiKey,
            language = language,
            useTrailers = useTrailers,
            useArtwork = useArtwork,
            useBasicInfo = useBasicInfo,
            useDetails = useDetails,
            useCredits = useCredits,
            useProductions = useProductions,
            useNetworks = useNetworks,
            useEpisodes = useEpisodes,
            useSeasonPosters = useSeasonPosters,
            useMoreLikeThis = useMoreLikeThis,
            useCollections = useCollections,
        )
    }

    private fun invalidateMetadata() {
        MetaDetailsRepository.clear()
        ContinueWatchingEnrichmentCache.clearAll(ProfileRepository.activeProfileId)
    }
}

internal fun normalizeLanguage(value: String?): String {
    val trimmed = value?.trim()?.replace('_', '-') ?: return ""
    return trimmed.takeIf { it.isNotBlank() } ?: ""
}
