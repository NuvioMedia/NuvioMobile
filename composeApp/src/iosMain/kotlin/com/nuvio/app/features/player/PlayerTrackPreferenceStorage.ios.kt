package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object PlayerTrackPreferenceStorage {
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
        saveOptionalString(subtitleTypeKey, id, preference.subtitleType)
        saveOptionalString(subtitleLanguageKey, id, preference.subtitleLanguage)
        saveOptionalString(subtitleNameKey, id, preference.subtitleName)
        saveOptionalString(subtitleTrackIdKey, id, preference.subtitleTrackId)
        saveOptionalString(addonSubtitleIdKey, id, preference.addonSubtitleId)
        saveOptionalString(addonSubtitleUrlKey, id, preference.addonSubtitleUrl)
        saveOptionalString(addonSubtitleAddonNameKey, id, preference.addonSubtitleAddonName)
        saveOptionalString(audioLanguageKey, id, preference.audioLanguage)
        saveOptionalString(audioNameKey, id, preference.audioName)
        saveOptionalString(audioTrackIdKey, id, preference.audioTrackId)
        saveOptionalBoolean(subtitleIsForcedKey, id, preference.subtitleIsForced)
    }

    actual fun loadSubtitleDelayMs(videoId: String): Int? {
        val id = videoId.normalizedStorageId() ?: return null
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(subtitleDelayMsKey, id)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveSubtitleDelayMs(videoId: String, delayMs: Int) {
        val id = videoId.normalizedStorageId() ?: return
        NSUserDefaults.standardUserDefaults.setInteger(
            delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS).toLong(),
            forKey = scopedKey(subtitleDelayMsKey, id),
        )
    }

    actual fun loadAudioDelayMs(audioOutputKey: String): Int? {
        val id = audioOutputKey.normalizedStorageId() ?: return null
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(audioDelayMsKey, id)
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            null
        }
    }

    actual fun saveAudioDelayMs(audioOutputKey: String, delayMs: Int) {
        val id = audioOutputKey.normalizedStorageId() ?: return
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(audioDelayMsKey, id)
        if (delayMs == 0) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setInteger(delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS).toLong(), forKey = key)
        }
    }

    private fun loadString(field: String, contentId: String): String? =
        NSUserDefaults.standardUserDefaults
            .stringForKey(scopedKey(field, contentId))
            ?.takeIf { it.isNotBlank() }

    private fun loadBoolean(field: String, contentId: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(field, contentId)
        if (defaults.objectForKey(key) == null) return null
        return defaults.boolForKey(key)
    }

    private fun saveOptionalBoolean(field: String, contentId: String, value: Boolean?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(field, contentId)
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setBool(value, forKey = key)
        }
    }

    private fun saveOptionalString(field: String, contentId: String, value: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = scopedKey(field, contentId)
        if (value.isNullOrBlank()) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
    }

    private fun scopedKey(field: String, contentId: String): String =
        ProfileScopedKey.of("$field|$contentId")

    private fun String.normalizedStorageId(): String? =
        trim().takeIf { it.isNotBlank() }
}
