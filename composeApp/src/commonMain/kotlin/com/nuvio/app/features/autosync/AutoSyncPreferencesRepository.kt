package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoSync-owned preference state.
 *
 * Persistence is injected by the platform so this feature does not add fields or methods to
 * Nuvio's general PlayerSettingsRepository/PlayerSettingsStorage architecture.
 */
internal object AutoSyncPreferencesRepository {
    private val _preferredSubtitleAutoSyncOnStart = MutableStateFlow(false)
    val preferredSubtitleAutoSyncOnStart: StateFlow<Boolean> =
        _preferredSubtitleAutoSyncOnStart.asStateFlow()

    /** Thorough search: stop only on a stronger match; the applied match is gated the same. */
    private val _aggressiveMode = MutableStateFlow(true)
    val aggressiveMode: StateFlow<Boolean> = _aggressiveMode.asStateFlow()

    /** Keep the original timing when the needed correction is at most this; 0 disables it. */
    val syncToleranceOptionsMs = listOf(0, 100, 200, 300, 400, 500)
    private val _syncToleranceMs = MutableStateFlow(0)
    val syncToleranceMs: StateFlow<Int> = _syncToleranceMs.asStateFlow()

    private var loadedProfileId: Int? = null
    private var loadPersistedValue: (() -> Boolean?)? = null
    private var savePersistedValue: ((Boolean) -> Unit)? = null
    private var loadAggressiveModePersistedValue: (() -> Boolean?)? = null
    private var saveAggressiveModePersistedValue: ((Boolean) -> Unit)? = null
    private var loadSyncTolerancePersistedValue: (() -> Int?)? = null
    private var saveSyncTolerancePersistedValue: ((Int) -> Unit)? = null
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    fun installPersistence(
        load: () -> Boolean?,
        save: (Boolean) -> Unit,
        loadAggressiveMode: () -> Boolean? = { null },
        saveAggressiveMode: (Boolean) -> Unit = {},
        loadSyncToleranceMs: () -> Int? = { null },
        saveSyncToleranceMs: (Int) -> Unit = {},
    ) {
        loadPersistedValue = load
        savePersistedValue = save
        loadAggressiveModePersistedValue = loadAggressiveMode
        saveAggressiveModePersistedValue = saveAggressiveMode
        loadSyncTolerancePersistedValue = loadSyncToleranceMs
        saveSyncTolerancePersistedValue = saveSyncToleranceMs
        loadedProfileId = null
    }

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return

        _preferredSubtitleAutoSyncOnStart.value = loadPersistedValue?.invoke() ?: false
        _aggressiveMode.value = loadAggressiveModePersistedValue?.invoke() ?: true
        _syncToleranceMs.value = loadSyncTolerancePersistedValue?.invoke()
            ?.takeIf { it in syncToleranceOptionsMs } ?: 0
        loadedProfileId = profileId
        lastStartupSessionKey = null
        lastStartupPlaybackKey = null
    }

    fun setPreferredSubtitleAutoSyncOnStart(enabled: Boolean) {
        ensureLoaded()
        if (_preferredSubtitleAutoSyncOnStart.value == enabled) return
        _preferredSubtitleAutoSyncOnStart.value = enabled
        savePersistedValue?.invoke(enabled)
    }

    fun setAggressiveMode(enabled: Boolean) {
        ensureLoaded()
        if (_aggressiveMode.value == enabled) return
        _aggressiveMode.value = enabled
        saveAggressiveModePersistedValue?.invoke(enabled)
    }

    fun setSyncToleranceMs(toleranceMs: Int) {
        ensureLoaded()
        if (toleranceMs !in syncToleranceOptionsMs || _syncToleranceMs.value == toleranceMs) return
        _syncToleranceMs.value = toleranceMs
        saveSyncTolerancePersistedValue?.invoke(toleranceMs)
    }

    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean {
        ensureLoaded()
        if (!_preferredSubtitleAutoSyncOnStart.value) return false
        if (lastStartupSessionKey == sessionKey && lastStartupPlaybackKey == playbackKey) return false

        lastStartupSessionKey = sessionKey
        lastStartupPlaybackKey = playbackKey
        return true
    }
}
