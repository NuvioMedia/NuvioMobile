package com.nuvio.app.core.poster

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CustomPosterUrlRepository {
    private val _pattern = MutableStateFlow("")
    val pattern: StateFlow<String> = _pattern.asStateFlow()

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

    private fun loadFromDisk() {
        hasLoaded = true
        _pattern.value = CustomPosterUrlStorage.loadPattern().orEmpty().trim()
    }
}
