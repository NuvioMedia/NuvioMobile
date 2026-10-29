package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.JsonObject

internal expect object MdbListSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadApiKey(): String?
    fun saveApiKey(apiKey: String)
    fun loadUseImdb(): Boolean?
    fun saveUseImdb(enabled: Boolean)
    fun loadUseTmdb(): Boolean?
    fun saveUseTmdb(enabled: Boolean)
    fun loadUseTomatoes(): Boolean?
    fun saveUseTomatoes(enabled: Boolean)
    fun loadUseMetacritic(): Boolean?
    fun saveUseMetacritic(enabled: Boolean)
    fun loadUseTrakt(): Boolean?
    fun saveUseTrakt(enabled: Boolean)
    fun loadUseLetterboxd(): Boolean?
    fun saveUseLetterboxd(enabled: Boolean)
    fun loadUseAudience(): Boolean?
    fun saveUseAudience(enabled: Boolean)
    fun loadUseMal(): Boolean?
    fun saveUseMal(enabled: Boolean)
    fun loadShowOnHero(): Boolean?
    fun saveShowOnHero(enabled: Boolean)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
