package com.nuvio.app.features.plugins

import android.content.Context
import android.content.SharedPreferences

internal object PluginStorage {
    private const val preferencesName = "nuvio_plugins"
    private const val pluginsStateKey = "plugins_state"
    private const val scraperCodeDirectoryName = "nuvio_plugin_scrapers"

    private var preferences: SharedPreferences? = null
    private var scraperCodeStore: PluginScraperCodeFileStore? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        scraperCodeStore = PluginScraperCodeFileStore(context.filesDir.resolve(scraperCodeDirectoryName))
    }

    fun loadState(profileId: Int): String? =
        preferences?.getString("${pluginsStateKey}_$profileId", null)

    fun saveState(profileId: Int, payload: String) {
        preferences
            ?.edit()
            ?.putString("${pluginsStateKey}_$profileId", payload)
            ?.apply()
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        scraperCodeStore?.contains(profileId, scraperId) == true

    fun loadScraperCode(profileId: Int, scraperId: String): String? =
        scraperCodeStore?.load(profileId, scraperId)

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean = scraperCodeStore?.save(profileId, scraperId, code, overwrite) == true

    fun loadScraperSettings(scraperId: String): String? =
        preferences?.getString("settings_${scraperId}", null)

    fun saveScraperSettings(scraperId: String, payload: String) {
        preferences
            ?.edit()
            ?.putString("settings_${scraperId}", payload)
            ?.apply()
    }

    fun loadCfSession(host: String): String? =
        preferences?.getString("cf_session_${host}", null)

    fun saveCfSession(host: String, payload: String) {
        preferences
            ?.edit()
            ?.putString("cf_session_${host}", payload)
            ?.apply()
    }

    fun removeCfSession(host: String) {
        preferences
            ?.edit()
            ?.remove("cf_session_${host}")
            ?.apply()
    }
}

internal fun currentPluginPlatform(): String = "android"

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
