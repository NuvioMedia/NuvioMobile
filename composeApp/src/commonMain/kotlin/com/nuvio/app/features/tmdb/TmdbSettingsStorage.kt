package com.nuvio.app.features.tmdb

import kotlinx.serialization.json.JsonObject

internal expect object TmdbSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadApiKey(): String?
    fun saveApiKey(apiKey: String)
    fun loadLanguage(): String?
    fun saveLanguage(language: String)
    fun loadUseTrailers(): Boolean?
    fun saveUseTrailers(enabled: Boolean)
    fun loadUseArtwork(): Boolean?
    fun saveUseArtwork(enabled: Boolean)
    fun loadUseBasicInfo(): Boolean?
    fun saveUseBasicInfo(enabled: Boolean)
    fun loadUseDetails(): Boolean?
    fun saveUseDetails(enabled: Boolean)
    fun loadUseCredits(): Boolean?
    fun saveUseCredits(enabled: Boolean)
    fun loadUseProductions(): Boolean?
    fun saveUseProductions(enabled: Boolean)
    fun loadUseNetworks(): Boolean?
    fun saveUseNetworks(enabled: Boolean)
    fun loadUseEpisodes(): Boolean?
    fun saveUseEpisodes(enabled: Boolean)
    fun loadUseSeasonPosters(): Boolean?
    fun saveUseSeasonPosters(enabled: Boolean)
    fun loadUseMoreLikeThis(): Boolean?
    fun saveUseMoreLikeThis(enabled: Boolean)
    fun loadUseCollections(): Boolean?
    fun saveUseCollections(enabled: Boolean)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
