package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val PUSH_DEBOUNCE_MS = 1500L

object ProfileSettingsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileSettingsSync")
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var isApplyingRemoteBlob: Boolean = false

    @Volatile
    private var isServerSyncInFlight: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null

    private var observeJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        ensureRepositoriesLoaded()
        ProviderCredentialSync.startObserving()
        MediaServerSync.startObserving()
        observeLocalChangesAndPush()
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
        skipNextPushSignature = null
        ProviderCredentialSync.clearAccountState()
        MediaServerSync.clearAccountState()
    }

    fun onProfileChanged() {
        if (observeJob?.isActive != true) return
        skipNextPushSignature = currentObservedStateSignature()
        ProviderCredentialSync.onProfileChanged()
    }

    suspend fun pull(profileId: Int): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            if (ProfileRepository.activeProfileId != profileId) {
                log.d { "pull(profileId=$profileId) — skipped because profile is no longer active" }
                return@withLock false
            }
            isServerSyncInFlight = true
            try {
                val localBlob = exportSettingsBlob()
                if (ProfileRepository.activeProfileId != profileId) return@withLock false
                val localSignature = buildSignature(localBlob)

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_platform", MOBILE_SYNC_PLATFORM)
                }
                val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
                if (ProfileRepository.activeProfileId != profileId) return@withLock false
                val response = result.decodeList<SettingsBlobResponse>().firstOrNull()
                val remoteJson = response?.settingsJson

                if (remoteJson == null) {
                    log.i { "pull(profileId=$profileId) — no remote settings blob found" }
                    return@withLock false
                }

                isApplyingRemoteBlob = true
                try {
                    val remoteBlob = runCatching {
                        json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), remoteJson)
                    }.getOrElse { error ->
                        log.e(error) { "pull(profileId=$profileId) — failed to decode remote settings blob" }
                        return@withLock false
                    }
                    val remoteSignature = buildSignature(remoteBlob)
                    if (remoteSignature == localSignature) {
                        log.d { "pull(profileId=$profileId) — remote matches local" }
                        return@withLock false
                    }

                    if (ProfileRepository.activeProfileId != profileId) return@withLock false
                    applyRemoteBlob(remoteBlob)
                    skipNextPushSignature = currentObservedStateSignature()
                } finally {
                    isApplyingRemoteBlob = false
                }

                log.i { "pull(profileId=$profileId) — applied remote settings blob" }
                true
            } catch (error: Exception) {
                log.e(error) { "pull(profileId=$profileId) — FAILED" }
                false
            } finally {
                isServerSyncInFlight = false
            }
        }
    }

    suspend fun pushCurrentProfileToRemote(): Boolean {
        ensureRepositoriesLoaded()
        return syncMutex.withLock {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                val blob = exportSettingsBlob()
                if (ProfileRepository.activeProfileId != profileId) return@runCatching false
                pushToRemoteLocked(profileId, blob)
                true
            }.onFailure { error ->
                log.e(error) { "pushCurrentProfileToRemote() — FAILED" }
            }.getOrDefault(false)
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        val signatureFlows = listOf(
            ThemeSettingsRepository.selectedThemePreference.map { "theme" },
            ThemeSettingsRepository.customThemePreference.map { "custom_theme_colors" },
            ThemeSettingsRepository.amoledEnabled.map { "amoled" },
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.map { "liquid_glass_tab_bar" },
            ThemeSettingsRepository.navBarGlowEnabled.map { "nav_bar_glow_enabled" },
            ThemeSettingsRepository.navBarStyle.map { "nav_bar_style" },
            PosterCardStyleRepository.uiState.map { "poster_card_style" },
            CustomPosterUrlRepository.pattern.map { "custom_poster_url" },
            CustomPosterUrlRepository.enabledScreens.map { "custom_poster_screens" },
            CardDepthStyleRepository.uiState.map { "card_depth_style" },
            PlayerSettingsRepository.uiState.map { "player" },
            StreamBadgeSettingsRepository.uiState.map { "stream_badges" },
            DebridSettingsRepository.uiState.map { "debrid" },
            TmdbSettingsRepository.uiState.map { "tmdb" },
            MdbListSettingsRepository.uiState.map { "mdblist" },
            MetaScreenSettingsRepository.uiState.map { "meta" },
            CollectionMobileSettingsRepository.uiState.map { "collection_mobile_settings" },
            ContinueWatchingPreferencesRepository.uiState.map { "continue_watching" },
            TrackingSettingsRepository.uiState.map { "trakt_settings" },
            TraktCommentsSettings.enabled.map { "trakt_comments" },
            EpisodeReleaseNotificationsRepository.uiState.map { "episode_release_alerts" },
        )

        observeJob = scope.launch {
            combine(signatureFlows) { currentObservedStateSignature() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    if (isApplyingRemoteBlob || isServerSyncInFlight) return@collect
                    if (signature != currentObservedStateSignature()) return@collect
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun pushToRemoteLocked(profileId: Int, blob: MobileProfileSettingsBlob) {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", MOBILE_SYNC_PLATFORM)
            put("p_settings_json", json.encodeToJsonElement(MobileProfileSettingsBlob.serializer(), blob))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
        log.d { "pushToRemoteLocked(profileId=$profileId) — success" }
    }

    private fun exportSettingsBlob(): MobileProfileSettingsBlob {
        ensureRepositoriesLoaded()
        return MobileProfileSettingsBlob(
            features = MobileProfileSettingsFeatures(
                themeSettings = ThemeSettingsStorage.exportToSyncPayload(),
                posterCardStyleSettingsPayload = PosterCardStyleStorage.loadPayload().orEmpty().trim(),
                customPosterUrlPattern = CustomPosterUrlStorage.loadPattern().orEmpty().trim(),
                customPosterEnabledScreens = CustomPosterUrlStorage.loadEnabledScreens()
                    ?.joinToString(",").orEmpty(),
                cardDepthStyleSettingsPayload = CardDepthStyleStorage.loadPayload().orEmpty().trim(),
                playerSettings = withoutProfileCredentials(
                    PROFILE_PLAYER_SETTINGS_FEATURE,
                    PlayerSettingsStorage.exportToSyncPayload(),
                ),
                streamBadgeSettings = StreamBadgeSettingsStorage.exportToSyncPayload(),
                debridSettings = withoutProfileCredentials(
                    PROFILE_DEBRID_SETTINGS_FEATURE,
                    DebridSettingsStorage.exportToSyncPayload(),
                ),
                tmdbSettings = withoutProfileCredentials(
                    PROFILE_TMDB_SETTINGS_FEATURE,
                    TmdbSettingsStorage.exportToSyncPayload(),
                ),
                mdbListSettings = withoutProfileCredentials(
                    PROFILE_MDBLIST_SETTINGS_FEATURE,
                    MdbListSettingsStorage.exportToSyncPayload(),
                ),
                metaScreenSettingsPayload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim(),
                collectionMobileSettingsPayload = CollectionMobileSettingsStorage.loadPayload().orEmpty().trim(),
                continueWatchingSettingsPayload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim(),
                traktSettingsPayload = TraktSettingsStorage.loadPayload().orEmpty().trim(),
                traktCommentsSettings = TraktCommentsStorage.exportToSyncPayload(),
                notificationsSettings = NotificationsSettingsPayload(
                    episodeReleaseAlertsEnabled = EpisodeReleaseNotificationsRepository.uiState.value.isEnabled,
                ),
            ),
        )
    }

    private fun applyRemoteBlob(blob: MobileProfileSettingsBlob) {
        ThemeSettingsStorage.replaceFromSyncPayload(blob.features.themeSettings)
        ThemeSettingsRepository.onProfileChanged()

        PosterCardStyleStorage.savePayload(blob.features.posterCardStyleSettingsPayload)
        PosterCardStyleRepository.onProfileChanged()

        CustomPosterUrlStorage.savePattern(blob.features.customPosterUrlPattern.ifBlank { null })
        val remoteScreenKeys = blob.features.customPosterEnabledScreens
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.toSet()
        CustomPosterUrlStorage.saveEnabledScreens(remoteScreenKeys)
        CustomPosterUrlRepository.onProfileChanged()
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()

        CardDepthStyleStorage.savePayload(blob.features.cardDepthStyleSettingsPayload)
        CardDepthStyleRepository.onProfileChanged()

        val localPlayerSettings = PlayerSettingsStorage.exportToSyncPayload()
        val localIntroDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey()
        PlayerSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_PLAYER_SETTINGS_FEATURE,
                blob.features.playerSettings,
                localPlayerSettings,
            ),
        )
        localIntroDbApiKey?.let(PlayerSettingsStorage::saveIntroDbApiKey)
        PlayerSettingsRepository.onProfileChanged()

        StreamBadgeSettingsStorage.replaceFromSyncPayload(blob.features.streamBadgeSettings)
        StreamBadgeSettingsRepository.onProfileChanged()

        DebridSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_DEBRID_SETTINGS_FEATURE,
                blob.features.debridSettings,
                DebridSettingsStorage.exportToSyncPayload(),
            ),
        )
        DebridSettingsRepository.onProfileChanged()

        TmdbSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_TMDB_SETTINGS_FEATURE,
                blob.features.tmdbSettings,
                TmdbSettingsStorage.exportToSyncPayload(),
            ),
        )
        TmdbSettingsRepository.onProfileChanged()

        MdbListSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_MDBLIST_SETTINGS_FEATURE,
                blob.features.mdbListSettings,
                MdbListSettingsStorage.exportToSyncPayload(),
            ),
        )
        MdbListMetadataService.clearCache()
        MdbListSettingsRepository.onProfileChanged()

        MetaScreenSettingsStorage.savePayload(blob.features.metaScreenSettingsPayload)
        MetaScreenSettingsRepository.onProfileChanged()

        CollectionMobileSettingsStorage.savePayload(blob.features.collectionMobileSettingsPayload)
        CollectionMobileSettingsRepository.onProfileChanged()

        ContinueWatchingPreferencesStorage.savePayload(blob.features.continueWatchingSettingsPayload)
        ContinueWatchingPreferencesRepository.onProfileChanged()

        TraktSettingsStorage.savePayload(blob.features.traktSettingsPayload)
        TrackingSettingsRepository.onProfileChanged()

        TraktCommentsStorage.replaceFromSyncPayload(blob.features.traktCommentsSettings)
        TraktCommentsSettings.onProfileChanged()

        EpisodeReleaseNotificationsRepository.applyFromSyncEnabled(blob.features.notificationsSettings.episodeReleaseAlertsEnabled)
    }

    private fun ensureRepositoriesLoaded() {
        ThemeSettingsRepository.ensureLoaded()
        PosterCardStyleRepository.ensureLoaded()
        CustomPosterUrlRepository.ensureLoaded()
        CardDepthStyleRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.ensureLoaded()
        DebridSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.ensureLoaded()
        CollectionMobileSettingsRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TraktCommentsSettings.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
    }

    private fun buildSignature(blob: MobileProfileSettingsBlob): String =
        json.encodeToString(MobileProfileSettingsBlob.serializer(), blob)

    private fun currentObservedStateSignature(): String = buildSignature(exportSettingsBlob())

}

@Serializable
private data class MobileProfileSettingsBlob(
    val version: Int = 4,
    val features: MobileProfileSettingsFeatures = MobileProfileSettingsFeatures(),
)

@Serializable
private data class MobileProfileSettingsFeatures(
    @SerialName("theme_settings") val themeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("poster_card_style_settings_payload") val posterCardStyleSettingsPayload: String = "",
    @SerialName("custom_poster_url_pattern") val customPosterUrlPattern: String = "",
    @SerialName("custom_poster_enabled_screens") val customPosterEnabledScreens: String = "",
    @SerialName("card_depth_style_settings_payload") val cardDepthStyleSettingsPayload: String = "",
    @SerialName("player_settings") val playerSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("stream_badge_settings") val streamBadgeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("debrid_settings") val debridSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("tmdb_settings") val tmdbSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("mdblist_settings") val mdbListSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("meta_screen_settings_payload") val metaScreenSettingsPayload: String = "",
    @SerialName("collection_mobile_settings_payload") val collectionMobileSettingsPayload: String = "",
    @SerialName("continue_watching_settings_payload") val continueWatchingSettingsPayload: String = "",
    @SerialName("trakt_settings_payload") val traktSettingsPayload: String = "",
    @SerialName("trakt_comments_settings") val traktCommentsSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("notifications_settings") val notificationsSettings: NotificationsSettingsPayload = NotificationsSettingsPayload(),
)

@Serializable
private data class NotificationsSettingsPayload(
    @SerialName("episode_release_alerts_enabled") val episodeReleaseAlertsEnabled: Boolean = false,
)

@Serializable
private data class SettingsBlobResponse(
    @SerialName("profile_id") val profileId: Int = 0,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
