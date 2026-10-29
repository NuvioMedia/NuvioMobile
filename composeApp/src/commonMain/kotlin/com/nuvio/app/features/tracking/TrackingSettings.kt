package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import com.nuvio.app.features.simkl.SimklRewatchMode
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

    /**
     * How rewatches are recorded and offered. The runs on the Continue Watching row follow the mode
     * right away instead of at the next sync: the sessions the app already read are re-derived, and
     * turning rewatches off drops the row's runs with them. Turning them on also reads the account
     * once in the background, because a snapshot that holds no sessions yet has nothing to re-derive
     * and the row would otherwise wait for the next changed watermark.
     */
    suspend fun setSimklRewatchMode(mode: SimklRewatchMode) {
        TraktSettingsRepository.setSimklRewatchMode(mode)
        SimklSyncRepository.refreshRewatchRuns()
        if (mode != SimklRewatchMode.OFF) {
            // The same read the Sync now button forces, so the switch never needs one by hand.
            SimklSyncRepository.refreshAsync(TrackingRefreshIntent.USER_INITIATED)
        }
    }

    /** Where a Simkl playback counts as finished, in percent. */
    fun setSimklWatchedThresholdPercent(percent: Int) =
        TraktSettingsRepository.setSimklWatchedThresholdPercent(percent)
}
