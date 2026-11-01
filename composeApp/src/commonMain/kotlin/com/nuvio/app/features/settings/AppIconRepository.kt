package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AppIconSettingsState(
    val selected: AppIconOption = AppIconOption.ORIGINAL,
    val pending: AppIconOption? = null,
    val changeFailed: Boolean = false,
)

internal object AppIconRepository {
    private val _state = MutableStateFlow(AppIconSettingsState())
    val state: StateFlow<AppIconSettingsState> = _state.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val savedKey = AppIconPlatform.loadSelectedIconKey()
        val savedIcon = AppIconOption.entries.firstOrNull { it.key == savedKey }
        val selected = savedIcon ?: AppIconOption.fromPlatformName(AppIconPlatform.currentIconName())
        if (savedIcon == null && selected != AppIconOption.ORIGINAL) {
            AppIconPlatform.saveSelectedIconKey(selected.key)
        }
        _state.value = AppIconSettingsState(selected = selected)
    }

    suspend fun select(icon: AppIconOption) {
        ensureLoaded()
        val current = _state.value
        if (current.pending != null || current.selected == icon) return

        _state.value = current.copy(
            pending = icon,
            changeFailed = false,
        )

        val changed = runCatching {
            AppIconPlatform.activateIcon(icon.platformName)
        }.getOrDefault(false)
        _state.value = if (changed) {
            AppIconPlatform.saveSelectedIconKey(icon.key)
            AppIconSettingsState(selected = icon)
        } else {
            current.copy(changeFailed = true)
        }
    }

    fun clearFailure() {
        val current = _state.value
        if (!current.changeFailed) return
        _state.value = current.copy(changeFailed = false)
    }
}
