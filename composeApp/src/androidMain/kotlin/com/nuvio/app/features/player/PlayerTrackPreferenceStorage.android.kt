package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object PlayerTrackPreferenceStorage {
    private const val preferencesName = "nuvio_player_track_preferences"
    private const val subtitleTypeKey = "subtitle_type"
    private const val subtitleLanguageKey = "subtitle_language"
    private const val subtitleNameKey = "subtitle_name"
    private const val subtitleTrackIdKey = "subtitle_track_id"
    private const val addonSubtitleIdKey = "addon_subtitle_id"
    private const val addonSubtitleUrlKey = "addon_subtitle_url"
    private const val addonSubtitleAddonNameKey = "addon_subtitle_addon_name"
    private const val audioLanguageKey = "audio_language"
    private const val audioNameKey = "audio_name"
    private const val audioTrackIdKey = "audio_track_id"
    private const val subtitleIsForcedKey = "subtitle_is_forced"
    private const val subtitleDelayMsKey = "subtitle_delay_ms"
    private const val audioDelayMsKey = "audio_delay_ms"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(contentId: String): PersistedPlayerTrackPreference? {
        val id = contentId.normalizedStorageId() ?: return null
        val preference = PersistedPlayerTrackPreference(
            subtitleType = loadString(subtitleTypeKey, id),
            subtitleLanguage = loadString(subtitleLanguageKey, id),
            subtitleName = loadString(subtitleNameKey, id),
            subtitleTrackId = loadString(subtitleTrackIdKey, id),
            addonSubtitleId = loadString(addonSubtitleIdKey, id),
            addonSubtitleUrl = loadString(addonSubtitleUrlKey, id),
            addonSubtitleAddonName = loadString(addonSubtitleAddonNameKey, id),
            audioLanguage = loadString(audioLanguageKey, id),
            audioName = loadString(audioNameKey, id),
            audioTrackId = loadString(audioTrackIdKey, id),
            subtitleIsForced = loadBoolean(subtitleIsForcedKey, id),
        )
        return preference.takeIf {
            listOf(
                it.subtitleType,
                it.subtitleLanguage,
                it.subtitleName,
                it.subtitleTrackId,
                it.addonSubtitleId,
                it.addonSubtitleUrl,
                it.addonSubtitleAddonName,
                it.audioLanguage,
                it.audioName,
                it.audioTrackId,
            ).any { value -> !value.isNullOrBlank() } || it.subtitleIsForced != null
        }
    }

    actual fun save(contentId: String, preference: PersistedPlayerTrackPreference) {
        val id = contentId.normalizedStorageId() ?: return
        preferences?.edit()?.apply {
            putOptionalString(subtitleTypeKey, id, preference.subtitleType)
            putOptionalString(subtitleLanguageKey, id, preference.subtitleLanguage)
            putOptionalString(subtitleNameKey, id, preference.subtitleName)
            putOptionalString(subtitleTrackIdKey, id, preference.subtitleTrackId)
            putOptionalString(addonSubtitleIdKey, id, preference.addonSubtitleId)
            putOptionalString(addonSubtitleUrlKey, id, preference.addonSubtitleUrl)
            putOptionalString(addonSubtitleAddonNameKey, id, preference.addonSubtitleAddonName)
            putOptionalString(audioLanguageKey, id, preference.audioLanguage)
            putOptionalString(audioNameKey, id, preference.audioName)
            putOptionalString(audioTrackIdKey, id, preference.audioTrackId)
            putOptionalBoolean(subtitleIsForcedKey, id, preference.subtitleIsForced)
        }?.apply()
    }

    actual fun loadSubtitleDelayMs(videoId: String): Int? {
        val id = videoId.normalizedStorageId() ?: return null
        val key = scopedKey(subtitleDelayMsKey, id)
        return preferences?.let { prefs ->
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        }
    }

    actual fun saveSubtitleDelayMs(videoId: String, delayMs: Int) {
        val id = videoId.normalizedStorageId() ?: return
        preferences
            ?.edit()
            ?.putInt(scopedKey(subtitleDelayMsKey, id), delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS))
            ?.apply()
    }

    actual fun loadAudioDelayMs(audioOutputKey: String): Int? {
        val id = audioOutputKey.normalizedStorageId() ?: return null
        val key = scopedKey(audioDelayMsKey, id)
        return preferences?.let { prefs ->
            if (prefs.contains(key)) prefs.getInt(key, 0) else null
        }
    }

    actual fun saveAudioDelayMs(audioOutputKey: String, delayMs: Int) {
        val id = audioOutputKey.normalizedStorageId() ?: return
        val key = scopedKey(audioDelayMsKey, id)
        preferences?.edit()?.apply {
            if (delayMs == 0) {
                remove(key)
            } else {
                putInt(key, delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS))
            }
        }?.apply()
    }

    private fun loadString(field: String, contentId: String): String? =
        preferences
            ?.getString(scopedKey(field, contentId), null)
            ?.takeIf { it.isNotBlank() }

    private fun loadBoolean(field: String, contentId: String): Boolean? {
        val key = scopedKey(field, contentId)
        val prefs = preferences ?: return null
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    private fun SharedPreferences.Editor.putOptionalBoolean(field: String, contentId: String, value: Boolean?) {
        val key = scopedKey(field, contentId)
        if (value == null) {
            remove(key)
        } else {
            putBoolean(key, value)
        }
    }

    private fun SharedPreferences.Editor.putOptionalString(field: String, contentId: String, value: String?) {
        val key = scopedKey(field, contentId)
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            putString(key, value)
        }
    }

    private fun scopedKey(field: String, contentId: String): String =
        ProfileScopedKey.of("$field|$contentId")

    private fun String.normalizedStorageId(): String? =
        trim().takeIf { it.isNotBlank() }
}
