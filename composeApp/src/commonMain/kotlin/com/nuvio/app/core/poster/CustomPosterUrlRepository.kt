package com.nuvio.app.core.poster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CustomPosterUrlRepository {
    private val _pattern = MutableStateFlow("")
    val pattern: StateFlow<String> = _pattern.asStateFlow()

    private val _enabledScreens = MutableStateFlow(CustomPosterScreen.ALL)
    val enabledScreens: StateFlow<Set<CustomPosterScreen>> = _enabledScreens.asStateFlow()

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
    }

    fun setPattern(pattern: String) {
        ensureLoaded()
        val trimmed = pattern.trim()
        if (_pattern.value == trimmed) return
        _pattern.value = trimmed
        CustomPosterUrlStorage.savePattern(trimmed.ifBlank { null })
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun clearPattern() {
        ensureLoaded()
        if (_pattern.value.isBlank()) return
        _pattern.value = ""
        CustomPosterUrlStorage.savePattern(null)
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()
    }

    fun patternForScreen(screen: CustomPosterScreen): String {
        ensureLoaded()
        return if (screen in _enabledScreens.value) _pattern.value else ""
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
    }
}
