package com.nuvio.app.features.autosync

import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey

internal object AutoSyncPreferencesAndroid {
    private const val preferencesName = "nuvio_autosync_settings"
    private const val preferredSubtitleAutoSyncOnStartKey =
        "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        AutoSyncPreferencesRepository.installPersistence(
            load = {
                val key = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, false)
                } else {
                    null
                }
            },
            save = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey), enabled)
                    .apply()
            },
            loadAggressiveMode = {
                val key = ProfileScopedKey.of(aggressiveModeKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, true)
                } else {
                    null
                }
            },
            saveAggressiveMode = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(aggressiveModeKey), enabled)
                    .apply()
            },
            loadSyncToleranceMs = {
                val key = ProfileScopedKey.of(syncToleranceMsKey)
                if (preferences.contains(key)) preferences.getInt(key, 0) else null
            },
            saveSyncToleranceMs = { toleranceMs ->
                preferences
                    .edit()
                    .putInt(ProfileScopedKey.of(syncToleranceMsKey), toleranceMs)
                    .apply()
            },
        )
    }
}
