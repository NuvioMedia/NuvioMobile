package com.nuvio.app.features.trakt

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.DEFAULT_SIMKL_ANIME_ID_PREFERENCE
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

typealias WatchProgressSource = com.nuvio.app.features.tracking.WatchProgressSource

val DEFAULT_WATCH_PROGRESS_SOURCE: WatchProgressSource =
    com.nuvio.app.features.tracking.DEFAULT_WATCH_PROGRESS_SOURCE
val DEFAULT_LIBRARY_SOURCE_MODE: LibrarySourceMode =
    com.nuvio.app.features.tracking.DEFAULT_LIBRARY_SOURCE_MODE

fun librarySourceModeFromStorage(value: String?): LibrarySourceMode =
    com.nuvio.app.features.tracking.librarySourceModeFromStorage(value)

const val TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL = 0
const val TRAKT_DEFAULT_CONTINUE_WATCHING_DAYS_CAP = 60
const val TRAKT_MIN_CONTINUE_WATCHING_DAYS_CAP = 7
const val TRAKT_MAX_CONTINUE_WATCHING_DAYS_CAP = 365

val TraktContinueWatchingDaysOptions: List<Int> = listOf(
    14,
    30,
    TRAKT_DEFAULT_CONTINUE_WATCHING_DAYS_CAP,
    90,
    180,
    TRAKT_MAX_CONTINUE_WATCHING_DAYS_CAP,
    TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL,
)

@Serializable
enum class MoreLikeThisSourcePreference {
    TRAKT,
    TMDB;

    companion object {
        fun fromStorage(value: String?): MoreLikeThisSourcePreference =
            entries.firstOrNull { it.name == value } ?: DEFAULT_MORE_LIKE_THIS_SOURCE
    }
}

val DEFAULT_MORE_LIKE_THIS_SOURCE: MoreLikeThisSourcePreference = MoreLikeThisSourcePreference.TRAKT

data class TraktSettingsUiState(
    val watchProgressSource: WatchProgressSource = DEFAULT_WATCH_PROGRESS_SOURCE,
    val continueWatchingDaysCap: Int = TRAKT_DEFAULT_CONTINUE_WATCHING_DAYS_CAP,
    val librarySourceMode: LibrarySourceMode = DEFAULT_LIBRARY_SOURCE_MODE,
    val moreLikeThisSource: MoreLikeThisSourcePreference = DEFAULT_MORE_LIKE_THIS_SOURCE,
    val simklAnimeIdPreference: SimklAnimeIdPreference = DEFAULT_SIMKL_ANIME_ID_PREFERENCE,
    val simklRewatchesEnabled: Boolean = false,
)

@Serializable
private data class StoredTraktSettings(
    val watchProgressSource: String? = null,
    val continueWatchingDaysCap: Int = TRAKT_DEFAULT_CONTINUE_WATCHING_DAYS_CAP,
    val librarySourceMode: String? = null,
    val moreLikeThisSource: String? = null,
    val simklAnimeIdPreference: String? = null,
    val simklRewatchesEnabled: Boolean = false,
)

object TraktSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(TraktSettingsUiState())
    val uiState: StateFlow<TraktSettingsUiState> = _uiState.asStateFlow()

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
        _uiState.value = TraktSettingsUiState()
    }

    internal fun setWatchProgressSource(
        source: WatchProgressSource,
        profileId: Int = ProfileRepository.activeProfileId,
    ) {
        ensureLoaded()
        if (_uiState.value.watchProgressSource == source) return
        val nextState = _uiState.value.copy(watchProgressSource = source)
        persist(nextState)
        val authState = AuthRepository.state.value
        if (authState is AuthState.Authenticated && !authState.isAnonymous) {
            ProfileSettingsWatchSourceOutbox.record(
                accountId = authState.userId,
                profileId = profileId,
                source = source,
            )
        }
        _uiState.value = nextState
    }

    fun setContinueWatchingDaysCap(days: Int) {
        ensureLoaded()
        val normalized = normalizeTraktContinueWatchingDaysCap(days)
        if (_uiState.value.continueWatchingDaysCap == normalized) return
        _uiState.value = _uiState.value.copy(continueWatchingDaysCap = normalized)
        persist()
    }

    fun setLibrarySourceMode(mode: LibrarySourceMode) {
        ensureLoaded()
        if (_uiState.value.librarySourceMode == mode) return
        _uiState.value = _uiState.value.copy(librarySourceMode = mode)
        persist()
    }

    fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference) {
        ensureLoaded()
        if (_uiState.value.moreLikeThisSource == source) return
        _uiState.value = _uiState.value.copy(moreLikeThisSource = source)
        persist()
    }

    fun setSimklAnimeIdPreference(preference: SimklAnimeIdPreference) {
        ensureLoaded()
        if (_uiState.value.simklAnimeIdPreference == preference) return
        _uiState.value = _uiState.value.copy(simklAnimeIdPreference = preference)
        persist()
        com.nuvio.app.features.simkl.SimklSyncRepository.invalidateProjections()
    }

    fun setSimklRewatchesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_uiState.value.simklRewatchesEnabled == enabled) return
        _uiState.value = _uiState.value.copy(simklRewatchesEnabled = enabled)
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true

        val payload = TraktSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = TraktSettingsUiState()
            return
        }

        val stored = runCatching {
            json.decodeFromString<StoredTraktSettings>(payload)
        }.getOrNull()

        _uiState.value = if (stored != null) {
            TraktSettingsUiState(
                watchProgressSource = WatchProgressSource.fromStorage(stored.watchProgressSource),
                continueWatchingDaysCap = normalizeTraktContinueWatchingDaysCap(stored.continueWatchingDaysCap),
                librarySourceMode = librarySourceModeFromStorage(stored.librarySourceMode),
                moreLikeThisSource = MoreLikeThisSourcePreference.fromStorage(stored.moreLikeThisSource),
                simklAnimeIdPreference = SimklAnimeIdPreference.fromStorage(stored.simklAnimeIdPreference),
                simklRewatchesEnabled = stored.simklRewatchesEnabled,
            )
        } else {
            TraktSettingsUiState()
        }
    }

    private fun persist(state: TraktSettingsUiState = _uiState.value) {
        TraktSettingsStorage.savePayload(
            json.encodeToString(
                StoredTraktSettings(
                    watchProgressSource = state.watchProgressSource.name,
                    continueWatchingDaysCap = state.continueWatchingDaysCap,
                    librarySourceMode = state.librarySourceMode.name,
                    moreLikeThisSource = state.moreLikeThisSource.name,
                    simklAnimeIdPreference = state.simklAnimeIdPreference.name,
                    simklRewatchesEnabled = state.simklRewatchesEnabled,
                ),
            ),
        )
    }
}

fun normalizeTraktContinueWatchingDaysCap(days: Int): Int =
    if (days == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) {
        TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
    } else {
        days.coerceIn(TRAKT_MIN_CONTINUE_WATCHING_DAYS_CAP, TRAKT_MAX_CONTINUE_WATCHING_DAYS_CAP)
    }

fun shouldUseTraktProgress(
    isAuthenticated: Boolean,
    source: WatchProgressSource,
): Boolean = isAuthenticated && source == WatchProgressSource.TRAKT

fun effectiveWatchProgressSource(
    isTraktAuthenticated: Boolean,
    requestedSource: WatchProgressSource,
): WatchProgressSource =
    if (shouldUseTraktProgress(
            isAuthenticated = isTraktAuthenticated,
            source = requestedSource,
        )
    ) {
        WatchProgressSource.TRAKT
    } else {
        WatchProgressSource.NUVIO_SYNC
    }

fun effectiveLibrarySourceMode(
    isAuthenticated: Boolean,
    source: LibrarySourceMode,
): LibrarySourceMode =
    if (isAuthenticated && source == LibrarySourceMode.TRAKT) {
        LibrarySourceMode.TRAKT
    } else {
        LibrarySourceMode.LOCAL
    }

fun shouldUseTraktLibrary(
    isAuthenticated: Boolean,
    source: LibrarySourceMode,
): Boolean = effectiveLibrarySourceMode(isAuthenticated, source) == LibrarySourceMode.TRAKT

fun shouldUseTraktMoreLikeThis(
    isAuthenticated: Boolean,
    source: MoreLikeThisSourcePreference,
): Boolean = isAuthenticated && source == MoreLikeThisSourcePreference.TRAKT
