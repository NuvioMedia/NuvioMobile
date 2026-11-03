package com.nuvio.app.features.dismissed

import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DismissedRepository : SynchronizedObject() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(DismissedUiState())
    val uiState: StateFlow<DismissedUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var loadedProfileId: Int? = null

    fun ensureLoaded() {
        val currentProfileId = ProfileRepository.activeProfileId
        if (hasLoaded && loadedProfileId == currentProfileId) return
        loadFromDisk(currentProfileId)
    }

    fun onProfileChanged() {
        val currentProfileId = ProfileRepository.activeProfileId
        loadFromDisk(currentProfileId)
    }

    fun clearLocalState() {
        synchronized(this) {
            hasLoaded = false
            loadedProfileId = null
            _uiState.value = DismissedUiState()
        }
    }

    private fun loadFromDisk(profileId: Int) {
        synchronized(this) {
            hasLoaded = true
            loadedProfileId = profileId
            val raw = DismissedStorage.loadPayload(profileId)
            if (raw.isNullOrBlank()) {
                _uiState.value = DismissedUiState(isLoaded = true)
                return
            }
            val items: List<DismissedItem> = runCatching {
                json.decodeFromString<List<DismissedItem>>(raw)
            }.getOrElse { emptyList() }

            val keys = buildSet {
                items.forEach { item ->
                    addAll(dismissedItemKeys(type = item.type, id = item.id))
                }
            }
            _uiState.value = DismissedUiState(
                items = items,
                dismissedKeys = keys,
                isLoaded = true,
            )
        }
    }

    private fun persist() {
        val profileId = loadedProfileId ?: ProfileRepository.activeProfileId
        val payload = runCatching {
            json.encodeToString(_uiState.value.items)
        }.getOrDefault("[]")
        DismissedStorage.savePayload(profileId, payload)
    }

    fun isDismissed(type: String, id: String): Boolean {
        ensureLoaded()
        val keys = dismissedItemKeys(type, id)
        val currentKeys = _uiState.value.dismissedKeys
        return keys.any(currentKeys::contains)
    }

    fun dismiss(preview: MetaPreview) {
        dismiss(preview.toDismissedItem())
    }

    fun dismiss(item: DismissedItem) {
        ensureLoaded()
        synchronized(this) {
            val currentItems = _uiState.value.items
            val existingIndex = currentItems.indexOfFirst {
                it.id.equals(item.id, ignoreCase = true) &&
                    dismissedItemTypeAliases(it.type).contains(item.type.trim().lowercase())
            }
            val updatedItems = if (existingIndex >= 0) {
                currentItems.toMutableList().apply { this[existingIndex] = item }
            } else {
                currentItems + item
            }
            val keys = buildSet {
                updatedItems.forEach { itm ->
                    addAll(dismissedItemKeys(type = itm.type, id = itm.id))
                }
            }
            _uiState.value = _uiState.value.copy(
                items = updatedItems,
                dismissedKeys = keys,
            )
            persist()
        }
        HomeRepository.applyCurrentSettings()
    }

    fun undismiss(type: String, id: String) {
        ensureLoaded()
        synchronized(this) {
            val aliases = dismissedItemTypeAliases(type)
            val updatedItems = _uiState.value.items.filterNot {
                it.id.equals(id.trim(), ignoreCase = true) && aliases.contains(it.type.trim().lowercase())
            }
            val keys = buildSet {
                updatedItems.forEach { itm ->
                    addAll(dismissedItemKeys(type = itm.type, id = itm.id))
                }
            }
            _uiState.value = _uiState.value.copy(
                items = updatedItems,
                dismissedKeys = keys,
            )
            persist()
        }
        HomeRepository.applyCurrentSettings()
    }

    fun toggleDismissed(preview: MetaPreview) {
        if (isDismissed(preview.type, preview.id)) {
            undismiss(preview.type, preview.id)
        } else {
            dismiss(preview)
        }
    }

    fun undismissAll() {
        ensureLoaded()
        synchronized(this) {
            _uiState.value = _uiState.value.copy(
                items = emptyList(),
                dismissedKeys = emptySet(),
            )
            persist()
        }
        HomeRepository.applyCurrentSettings()
    }
}
