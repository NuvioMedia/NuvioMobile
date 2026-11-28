package com.nuvio.app.features.audiosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much of the film is read ahead of playback for dialogue samples, on any network. */
internal enum class AudioSamplingMode {
    /** Listen during playback only. */
    OFF,

    /** Fewer spots over one connection, with a small data cap. The most a metered network gets. */
    LOW,

    /** More spots over several connections, for the fastest sync. */
    HIGH,
}

/**
 * Settings of the audio subtitle sync fallback (see AutoSync). Persistence is injected by the
 * platform, so Nuvio's own player settings are left untouched.
 */
internal object AudioSyncSettings {
    // Declared first: object properties initialize in order.
    private val DEFAULT_SAMPLING_MODE = AudioSamplingMode.LOW

    // Off until the user turns it on: it reads extra data from the stream and runs on-device analysis.
    private val _fallbackEnabled = MutableStateFlow(false)

    /** Sync to the audio when AutoSync keeps a subtitle's original timing. Off by default. */
    val fallbackEnabled: StateFlow<Boolean> = _fallbackEnabled.asStateFlow()

    // Low by default: one extra connection, since some hosts limit concurrent connections per stream.
    private val _samplingMode = MutableStateFlow(DEFAULT_SAMPLING_MODE)

    /** Reading short parts of the film ahead of playback; see [AudioSamplingMode]. */
    val samplingMode: StateFlow<AudioSamplingMode> = _samplingMode.asStateFlow()

    private var save: (key: String, value: Boolean) -> Unit = { _, _ -> }
    private var saveString: (key: String, value: String) -> Unit = { _, _ -> }

    fun installPersistence(
        load: (key: String) -> Boolean?,
        save: (key: String, value: Boolean) -> Unit,
        loadString: (key: String) -> String?,
        saveString: (key: String, value: String) -> Unit,
    ) {
        this.save = save
        this.saveString = saveString
        _fallbackEnabled.value = load(FALLBACK_ENABLED) ?: false
        _samplingMode.value = loadString(SAMPLING_MODE)
            ?.let { stored -> AudioSamplingMode.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_SAMPLING_MODE
    }

    fun setFallbackEnabled(enabled: Boolean) {
        _fallbackEnabled.value = enabled
        save(FALLBACK_ENABLED, enabled)
    }

    fun setSamplingMode(mode: AudioSamplingMode) {
        _samplingMode.value = mode
        saveString(SAMPLING_MODE, mode.name)
    }

    private const val FALLBACK_ENABLED = "audio_sync_fallback_enabled"
    private const val SAMPLING_MODE = "audio_sync_sampling_mode"
}
