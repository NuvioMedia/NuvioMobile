package com.nuvio.app.core.poster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CustomPosterSourceMode(val storageValue: String) {
    OFF("off"),
    TAGS_ONLY("tags_only"),
    POSTERS_AND_TAGS("posters_and_tags");

    companion object {
        fun fromStorage(value: String?, hasPattern: Boolean): CustomPosterSourceMode =
            when (value) {
                OFF.storageValue -> OFF
                TAGS_ONLY.storageValue -> TAGS_ONLY
                POSTERS_AND_TAGS.storageValue -> if (hasPattern) POSTERS_AND_TAGS else OFF
                else -> if (hasPattern) POSTERS_AND_TAGS else OFF
            }
    }
}

object CustomPosterUrlRepository {
    private val _pattern = MutableStateFlow("")
    val pattern: StateFlow<String> = _pattern.asStateFlow()

    private val _enabledScreens = MutableStateFlow(CustomPosterScreen.ALL)
    val enabledScreens: StateFlow<Set<CustomPosterScreen>> = _enabledScreens.asStateFlow()

    private val _mode = MutableStateFlow(CustomPosterSourceMode.OFF)
    val mode: StateFlow<CustomPosterSourceMode> = _mode.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _pattern.value = ""
        _enabledScreens.value = CustomPosterScreen.ALL
        _mode.value = CustomPosterSourceMode.OFF
    }

    fun setPattern(pattern: String) {
        ensureLoaded()
        val trimmed = pattern.trim()
        if (_pattern.value == trimmed) return
        _pattern.value = trimmed
        CustomPosterUrlStorage.savePattern(trimmed.ifBlank { null })
        if (trimmed.isBlank()) setMode(CustomPosterSourceMode.OFF)
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun clearPattern() {
        ensureLoaded()
        if (_pattern.value.isBlank()) return
        _pattern.value = ""
        CustomPosterUrlStorage.savePattern(null)
        setMode(CustomPosterSourceMode.OFF)
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun setMode(mode: CustomPosterSourceMode) {
        ensureLoaded()
        if (mode == CustomPosterSourceMode.POSTERS_AND_TAGS && _pattern.value.isBlank()) return
        if (_mode.value == mode) return
        _mode.value = mode
        CustomPosterUrlStorage.saveMode(mode.storageValue)
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun patternForScreen(screen: CustomPosterScreen): String {
        ensureLoaded()
        return if (
            _mode.value == CustomPosterSourceMode.POSTERS_AND_TAGS &&
            screen in _enabledScreens.value
        ) _pattern.value else ""
    }

    fun setScreenEnabled(screen: CustomPosterScreen, enabled: Boolean) {
        ensureLoaded()
        val current = _enabledScreens.value
        val updated = if (enabled) current + screen else current - screen
        if (updated == current) return
        _enabledScreens.value = updated
        CustomPosterUrlStorage.saveEnabledScreens(CustomPosterScreen.toKeys(updated))
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun isScreenEnabled(screen: CustomPosterScreen): Boolean {
        ensureLoaded()
        return screen in _enabledScreens.value
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _pattern.value = CustomPosterUrlStorage.loadPattern().orEmpty().trim()
        _enabledScreens.value = CustomPosterScreen.fromKeys(CustomPosterUrlStorage.loadEnabledScreens())
        _mode.value = CustomPosterSourceMode.fromStorage(
            CustomPosterUrlStorage.loadMode(),
            hasPattern = _pattern.value.isNotBlank(),
        )
    }
}
