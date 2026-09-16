package com.nuvio.app.features.tmdb

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

actual object TmdbSettingsStorage {
    private const val preferencesName = "nuvio_tmdb_settings"
    private const val enabledKey = "tmdb_enabled"
    private const val apiKeyKey = "tmdb_api_key"
    private const val languageKey = "tmdb_language"
    private const val useTrailersKey = "tmdb_use_trailers"
    private const val useArtworkKey = "tmdb_use_artwork"
    private const val useBasicInfoKey = "tmdb_use_basic_info"
    private const val useDetailsKey = "tmdb_use_details"
    private const val useReleaseDatesKey = "tmdb_use_release_dates"
    private const val useCreditsKey = "tmdb_use_credits"
    private const val useProductionsKey = "tmdb_use_productions"
    private const val useNetworksKey = "tmdb_use_networks"
    private const val useEpisodesKey = "tmdb_use_episodes"
    private const val useSeasonPostersKey = "tmdb_use_season_posters"
    private const val useMoreLikeThisKey = "tmdb_use_more_like_this"
    private const val useCollectionsKey = "tmdb_use_collections"
    private val syncKeys = listOf(
        enabledKey,
        languageKey,
        useTrailersKey,
        useArtworkKey,
        useBasicInfoKey,
        useDetailsKey,
        useReleaseDatesKey,
        useCreditsKey,
        useProductionsKey,
        useNetworksKey,
        useEpisodesKey,
        useSeasonPostersKey,
        useMoreLikeThisKey,
        useCollectionsKey,
    )

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        saveBoolean(enabledKey, enabled)
    }

    actual fun loadApiKey(): String? =
        preferences?.getString(ProfileScopedKey.of(apiKeyKey), null)

    actual fun saveApiKey(apiKey: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(apiKeyKey), apiKey)
            ?.apply()
    }

    actual fun loadLanguage(): String? =
        preferences?.getString(ProfileScopedKey.of(languageKey), null)

    actual fun saveLanguage(language: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(languageKey), language)
            ?.apply()
    }

    actual fun loadUseTrailers(): Boolean? = loadBoolean(useTrailersKey)

    actual fun saveUseTrailers(enabled: Boolean) {
        saveBoolean(useTrailersKey, enabled)
    }

    actual fun loadUseArtwork(): Boolean? = loadBoolean(useArtworkKey)

    actual fun saveUseArtwork(enabled: Boolean) {
        saveBoolean(useArtworkKey, enabled)
    }

    actual fun loadUseBasicInfo(): Boolean? = loadBoolean(useBasicInfoKey)

    actual fun saveUseBasicInfo(enabled: Boolean) {
        saveBoolean(useBasicInfoKey, enabled)
    }

    actual fun loadUseDetails(): Boolean? = loadBoolean(useDetailsKey)

    actual fun saveUseDetails(enabled: Boolean) {
        saveBoolean(useDetailsKey, enabled)
    }

    actual fun loadUseReleaseDates(): Boolean? = loadBoolean(useReleaseDatesKey)

    actual fun saveUseReleaseDates(enabled: Boolean) {
        saveBoolean(useReleaseDatesKey, enabled)
    }

    actual fun loadUseCredits(): Boolean? = loadBoolean(useCreditsKey)

    actual fun saveUseCredits(enabled: Boolean) {
        saveBoolean(useCreditsKey, enabled)
    }

    actual fun loadUseProductions(): Boolean? = loadBoolean(useProductionsKey)

    actual fun saveUseProductions(enabled: Boolean) {
        saveBoolean(useProductionsKey, enabled)
    }

    actual fun loadUseNetworks(): Boolean? = loadBoolean(useNetworksKey)

    actual fun saveUseNetworks(enabled: Boolean) {
        saveBoolean(useNetworksKey, enabled)
    }

    actual fun loadUseEpisodes(): Boolean? = loadBoolean(useEpisodesKey)

    actual fun saveUseEpisodes(enabled: Boolean) {
        saveBoolean(useEpisodesKey, enabled)
    }

    actual fun loadUseSeasonPosters(): Boolean? = loadBoolean(useSeasonPostersKey)

    actual fun saveUseSeasonPosters(enabled: Boolean) {
        saveBoolean(useSeasonPostersKey, enabled)
    }

    actual fun loadUseMoreLikeThis(): Boolean? = loadBoolean(useMoreLikeThisKey)

    actual fun saveUseMoreLikeThis(enabled: Boolean) {
        saveBoolean(useMoreLikeThisKey, enabled)
    }

    actual fun loadUseCollections(): Boolean? = loadBoolean(useCollectionsKey)

    actual fun saveUseCollections(enabled: Boolean) {
        saveBoolean(useCollectionsKey, enabled)
    }

    private fun loadBoolean(key: String): Boolean? =
        preferences?.let { sharedPreferences ->
            val scopedKey = ProfileScopedKey.of(key)
            if (sharedPreferences.contains(scopedKey)) {
                sharedPreferences.getBoolean(scopedKey, false)
            } else {
                null
            }
        }

    private fun saveBoolean(key: String, enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(key), enabled)
            ?.apply()
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadLanguage()?.let { put(languageKey, encodeSyncString(it)) }
        loadUseTrailers()?.let { put(useTrailersKey, encodeSyncBoolean(it)) }
        loadUseArtwork()?.let { put(useArtworkKey, encodeSyncBoolean(it)) }
        loadUseBasicInfo()?.let { put(useBasicInfoKey, encodeSyncBoolean(it)) }
        loadUseDetails()?.let { put(useDetailsKey, encodeSyncBoolean(it)) }
        loadUseReleaseDates()?.let { put(useReleaseDatesKey, encodeSyncBoolean(it)) }
        loadUseCredits()?.let { put(useCreditsKey, encodeSyncBoolean(it)) }
        loadUseProductions()?.let { put(useProductionsKey, encodeSyncBoolean(it)) }
        loadUseNetworks()?.let { put(useNetworksKey, encodeSyncBoolean(it)) }
        loadUseEpisodes()?.let { put(useEpisodesKey, encodeSyncBoolean(it)) }
        loadUseSeasonPosters()?.let { put(useSeasonPostersKey, encodeSyncBoolean(it)) }
        loadUseMoreLikeThis()?.let { put(useMoreLikeThisKey, encodeSyncBoolean(it)) }
        loadUseCollections()?.let { put(useCollectionsKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        preferences?.edit()?.apply {
            syncKeys.forEach { remove(ProfileScopedKey.of(it)) }
        }?.apply()

        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(languageKey)?.let(::saveLanguage)
        payload.decodeSyncBoolean(useTrailersKey)?.let(::saveUseTrailers)
        payload.decodeSyncBoolean(useArtworkKey)?.let(::saveUseArtwork)
        payload.decodeSyncBoolean(useBasicInfoKey)?.let(::saveUseBasicInfo)
        payload.decodeSyncBoolean(useDetailsKey)?.let(::saveUseDetails)
        payload.decodeSyncBoolean(useReleaseDatesKey)?.let(::saveUseReleaseDates)
        payload.decodeSyncBoolean(useCreditsKey)?.let(::saveUseCredits)
        payload.decodeSyncBoolean(useProductionsKey)?.let(::saveUseProductions)
        payload.decodeSyncBoolean(useNetworksKey)?.let(::saveUseNetworks)
        payload.decodeSyncBoolean(useEpisodesKey)?.let(::saveUseEpisodes)
        payload.decodeSyncBoolean(useSeasonPostersKey)?.let(::saveUseSeasonPosters)
        payload.decodeSyncBoolean(useMoreLikeThisKey)?.let(::saveUseMoreLikeThis)
        payload.decodeSyncBoolean(useCollectionsKey)?.let(::saveUseCollections)
    }
}
