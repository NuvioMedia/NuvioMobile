package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import com.nuvio.app.features.simkl.SimklRewatchMode
import com.nuvio.app.features.simkl.SimklRewatchNextUpMode
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.TraktSettingsUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Provider-neutral entry point for tracking source preferences.
 *
 * The serialized profile payload intentionally stays in the existing Trakt settings store so
 * current installations keep their choices. New application code should depend on this facade;
 * the persistence implementation can then be migrated without leaking a provider name again.
 */
typealias TrackingSettingsUiState = TraktSettingsUiState

object TrackingSettingsRepository {
    val uiState: StateFlow<TrackingSettingsUiState>
        get() = TraktSettingsRepository.uiState

    fun ensureLoaded() = TraktSettingsRepository.ensureLoaded()

    fun onProfileChanged() = TraktSettingsRepository.onProfileChanged()

    fun clearLocalState() = TraktSettingsRepository.clearLocalState()

    fun setLibrarySourceMode(source: LibrarySourceMode) =
        TraktSettingsRepository.setLibrarySourceMode(source)

    fun setWatchProgressSource(source: WatchProgressSource, profileId: Int) =
        TraktSettingsRepository.setWatchProgressSource(source, profileId)

    fun setContinueWatchingDaysCap(days: Int) =
        TraktSettingsRepository.setContinueWatchingDaysCap(days)

    fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference) =
        TraktSettingsRepository.setMoreLikeThisSource(source)

    fun setSimklAnimeIdPreference(preference: SimklAnimeIdPreference) =
        TraktSettingsRepository.setSimklAnimeIdPreference(preference)

    fun setSimklRewatchMode(mode: SimklRewatchMode) =
        TraktSettingsRepository.setSimklRewatchMode(mode)

    /**
     * How much of a rewatch the app offers as next up. The runs read from the account are re-derived
     * right away, so Continue Watching follows the choice without waiting for the next sync.
     */
    suspend fun setSimklRewatchNextUpMode(mode: SimklRewatchNextUpMode) {
        TraktSettingsRepository.setSimklRewatchNextUpMode(mode)
        SimklSyncRepository.refreshRewatchRuns()
    }

    /** Where a Simkl playback counts as finished, in percent. */
    fun setSimklWatchedThresholdPercent(percent: Int) =
        TraktSettingsRepository.setSimklWatchedThresholdPercent(percent)
}
