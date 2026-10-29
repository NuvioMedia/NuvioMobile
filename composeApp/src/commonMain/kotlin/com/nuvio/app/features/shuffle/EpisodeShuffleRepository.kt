package com.nuvio.app.features.shuffle

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class EpisodeShuffleProfile(
    val available: Boolean = false,
    val shows: Map<String, EpisodeShuffleSettings> = emptyMap(),
) {
    fun settings(contentId: String, contentType: String): EpisodeShuffleSettings {
        val saved = shows[contentId] ?: EpisodeShuffleSettings()
        return saved.copy(enabled = saved.enabled && available &&
            contentType.lowercase() in setOf("series", "tv", "show", "tvshow"))
    }
}

internal object EpisodeShuffleRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loadedProfileId: Int? = null
    private val state = MutableStateFlow(EpisodeShuffleProfile())
    val uiState = state.asStateFlow()
    val shuffle = EpisodeShuffle()

    fun ensureLoaded() {
        if (loadedProfileId == ProfileRepository.activeProfileId) return
        onProfileChanged()
    }

    fun onProfileChanged() {
        loadedProfileId = ProfileRepository.activeProfileId
        state.value = readProfile(ProfileRepository.activeProfileId)
    }

    fun readProfile(profileId: Int): EpisodeShuffleProfile = runCatching {
        EpisodeShuffleStorage.load(profileId)?.let { json.decodeFromString<EpisodeShuffleProfile>(it) }
    }.getOrNull() ?: EpisodeShuffleProfile()

    fun setAvailable(available: Boolean): Boolean {
        ensureLoaded()
        return persist(state.value.copy(available = available), ProfileRepository.activeProfileId)
    }

    fun save(contentId: String, settings: EpisodeShuffleSettings, profileId: Int): Boolean {
        if (contentId.isBlank()) return false
        val profile = if (loadedProfileId == profileId) state.value else readProfile(profileId)
        return persist(profile.copy(shows = profile.shows + (contentId to settings)), profileId).also { saved ->
            if (saved) ShuffleSurface.entries.forEach { shuffle.clearSelection(profileId, contentId, it) }
        }
    }

    private fun persist(profile: EpisodeShuffleProfile, profileId: Int): Boolean = runCatching {
        EpisodeShuffleStorage.save(profileId, json.encodeToString(profile))
        if (profileId == ProfileRepository.activeProfileId) {
            loadedProfileId = profileId
            state.value = profile
        }
    }.isSuccess

    fun clearLocalState() {
        loadedProfileId = null
        state.value = EpisodeShuffleProfile()
        shuffle.clear()
    }
}

internal expect object EpisodeShuffleStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
}
