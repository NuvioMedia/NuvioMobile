package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsenetUiState(
    val profileId: Int = 0,
    val sources: UsenetSourceConfiguration = UsenetSourceConfiguration(),
    val engine: UsenetConfiguration = UsenetConfiguration(),
    val loaded: Boolean = false,
    /** The stored sources could not be read, for example after the device keystore was reset. */
    val error: Boolean = false,
)

/**
 * The active profile's Usenet sources and the device's engine settings. Sources hold provider
 * passwords and indexer keys: they stay on the device, encrypted, and are never synced.
 */
object UsenetRepository {
    private val log = Logger.withTag("UsenetRepository")
    private val _uiState = MutableStateFlow(UsenetUiState())
    val uiState: StateFlow<UsenetUiState> = _uiState.asStateFlow()

    /** Whether this build and platform can play Usenet at all. */
    val isSupported: Boolean
        get() = AppFeaturePolicy.usenetEnabled && UsenetPlatform.isSupported

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded || !isSupported) return
        load()
    }

    fun onProfileChanged() {
        if (!isSupported) return
        load()
    }

    /** After an account reset: every profile's sources and the search state are gone. */
    fun clearLocalState() {
        hasLoaded = false
        if (isSupported) runCatching { UsenetPlatform.clearAll() }
        _uiState.value = UsenetUiState()
    }

    /** A deleted profile's sources and credentials are removed from this device. */
    fun removeProfile(profileId: Int) {
        if (!isSupported) return
        runCatching { UsenetPlatform.removeProfile(profileId) }
        if (_uiState.value.profileId == profileId) load()
    }

    fun onAppForegrounded() {
        if (isSupported) UsenetPlatform.onAppForegrounded()
    }

    fun onAppBackgrounded() {
        if (isSupported) UsenetPlatform.onAppBackgrounded()
    }

    /** The active profile's sources, loaded if needed; empty where Usenet is unsupported. */
    fun sources(): UsenetSourceConfiguration {
        ensureLoaded()
        val state = _uiState.value
        return if (state.profileId == ProfileRepository.activeProfileId) state.sources else UsenetSourceConfiguration()
    }

    fun engineSettings(): UsenetConfiguration {
        ensureLoaded()
        return _uiState.value.engine
    }

    /**
     * Applies [change] to the active profile's sources and stores the result. Failure carries the
     * validation message, which names no credential.
     */
    fun updateSources(change: (UsenetSourceConfiguration) -> UsenetSourceConfiguration): Result<UsenetSourceConfiguration> {
        ensureLoaded()
        val current = _uiState.value
        if (!current.loaded || current.profileId != ProfileRepository.activeProfileId) {
            return Result.failure(IllegalStateException("Usenet sources are not loaded"))
        }
        val updated = change(current.sources).normalized()
        return runCatching {
            UsenetPlatform.saveSources(current.profileId, updated)
            _uiState.value = current.copy(sources = updated, error = false)
            updated
        }
    }

    fun updateEngineSettings(change: (UsenetConfiguration) -> UsenetConfiguration) {
        ensureLoaded()
        val updated = change(_uiState.value.engine).sanitized()
        runCatching { UsenetPlatform.saveEngineSettings(updated) }
            .onSuccess { _uiState.value = _uiState.value.copy(engine = updated) }
            .onFailure { log.w { "Could not save Usenet engine settings: ${it::class.simpleName}" } }
    }

    private fun load() {
        hasLoaded = true
        val profileId = ProfileRepository.activeProfileId
        val engine = runCatching { UsenetPlatform.loadEngineSettings() }.getOrDefault(UsenetConfiguration())
        _uiState.value = runCatching { UsenetPlatform.loadSources(profileId) }.fold(
            onSuccess = { UsenetUiState(profileId, it, engine, loaded = true) },
            onFailure = {
                // Never overwrite stored credentials that could not be read.
                log.w { "Could not read Usenet sources: ${it::class.simpleName}" }
                UsenetUiState(profileId, engine = engine, error = true)
            },
        )
    }
}
