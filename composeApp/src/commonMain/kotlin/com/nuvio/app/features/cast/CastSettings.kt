package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Universal Cast - no proxy / no transcode. Direct URL to TV.
// Kept minimal for future global Cast toggle.
data class CastSettingsUiState(
    val castEnabled: Boolean = true,
)

object CastSettingsRepository {
    private val _uiState = MutableStateFlow(CastSettingsUiState())
    val uiState: StateFlow<CastSettingsUiState> = _uiState.asStateFlow()

    fun ensureLoaded() {
        // No-op now, kept for API compat
        if (_uiState.value.castEnabled) return
    }

    fun onProfileChanged() {}
    fun clearLocalState() {
        _uiState.value = CastSettingsUiState()
    }
}

// Kept for binary compat - no longer used (proxy/transcode removed)
internal expect object CastSettingsStorage {
    fun loadProxyEnabled(): Boolean?
    fun saveProxyEnabled(enabled: Boolean)
    fun loadTranscodeMode(): String?
    fun saveTranscodeMode(mode: String)
    fun loadMaxResolution(): String?
    fun saveMaxResolution(resolution: String)
    fun loadUseHardwareAcceleration(): Boolean?
    fun saveUseHardwareAcceleration(enabled: Boolean)
    fun loadTranscodeAudioToAac(): Boolean?
    fun saveTranscodeAudioToAac(enabled: Boolean)
}
