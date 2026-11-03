package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

actual object StreamBadgeSettingsStorage {
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val showAddonLogoKey = "show_addon_logo"
    private const val showInPlayerKey = "show_badges_in_player"
    private const val playerBadgeSourceKey = "player_badge_source"
    private const val streamBackgroundModeKey = "stream_background_mode"
    private const val streamBadgePlacementKey = "stream_badge_placement"
    private const val streamFormattingEnabledKey = "stream_formatting_enabled"
    private const val streamFormatPresetKey = "stream_format_preset"
    private const val streamFormattingScopeKey = "stream_formatting_scope"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"
    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, showInPlayerKey, playerBadgeSourceKey, streamBadgePlacementKey, streamBackgroundModeKey, streamFormattingEnabledKey, streamFormatPresetKey, streamFormattingScopeKey)

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? = loadBoolean(showFileSizeBadgesKey)

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        saveBoolean(showFileSizeBadgesKey, enabled)
    }

    actual fun loadShowAddonLogo(): Boolean? = loadBoolean(showAddonLogoKey)

    actual fun saveShowAddonLogo(enabled: Boolean) {
        saveBoolean(showAddonLogoKey, enabled)
    }

    actual fun loadShowInPlayer(): Boolean? = loadBoolean(showInPlayerKey)

    actual fun saveShowInPlayer(enabled: Boolean) {
        saveBoolean(showInPlayerKey, enabled)
    }

    actual fun loadPlayerBadgeSource(): String? = loadString(playerBadgeSourceKey)

    actual fun savePlayerBadgeSource(source: String) {
        saveString(playerBadgeSourceKey, source)
    }

    actual fun loadStreamBadgePlacement(): String? = loadString(streamBadgePlacementKey)

    actual fun loadStreamBackgroundMode(): String? = loadString(streamBackgroundModeKey)

    actual fun saveStreamBackgroundMode(mode: String) {
        saveString(streamBackgroundModeKey, mode)
    }

    actual fun saveStreamBadgePlacement(placement: String) {
        saveString(streamBadgePlacementKey, placement)
    }

    actual fun loadStreamFormattingEnabled(): Boolean? = loadBoolean(streamFormattingEnabledKey)

    actual fun saveStreamFormattingEnabled(enabled: Boolean) {
        saveBoolean(streamFormattingEnabledKey, enabled)
    }

    actual fun loadStreamFormatPreset(): String? = loadString(streamFormatPresetKey)

    actual fun saveStreamFormatPreset(preset: String) {
        saveString(streamFormatPresetKey, preset)
    }

    actual fun loadStreamFormattingScope(): String? = loadString(streamFormattingScopeKey)

    actual fun saveStreamFormattingScope(scope: String) {
        saveString(streamFormattingScopeKey, scope)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        loadString(legacyDebridStreamBadgeRulesKey)

    actual fun clearLegacyDebridStreamBadgeRules() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    private fun loadString(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(key))
    }

    private fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val scopedKey = ProfileScopedKey.of(key)
        return if (defaults.objectForKey(scopedKey) != null) {
            defaults.boolForKey(scopedKey)
        } else {
            null
        }
    }

    private fun saveBoolean(key: String, enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = ProfileScopedKey.of(key))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadShowInPlayer()?.let { put(showInPlayerKey, encodeSyncBoolean(it)) }
        loadPlayerBadgeSource()?.let { put(playerBadgeSourceKey, encodeSyncString(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
        loadStreamBackgroundMode()?.let { put(streamBackgroundModeKey, encodeSyncString(it)) }
        loadStreamFormattingEnabled()?.let { put(streamFormattingEnabledKey, encodeSyncBoolean(it)) }
        loadStreamFormatPreset()?.let { put(streamFormatPresetKey, encodeSyncString(it)) }
        loadStreamFormattingScope()?.let { put(streamFormattingScopeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncBoolean(showInPlayerKey)?.let(::saveShowInPlayer)
        payload.decodeSyncString(playerBadgeSourceKey)?.let(::savePlayerBadgeSource)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
        payload.decodeSyncString(streamBackgroundModeKey)?.let(::saveStreamBackgroundMode)
        payload.decodeSyncBoolean(streamFormattingEnabledKey)?.let(::saveStreamFormattingEnabled)
        payload.decodeSyncString(streamFormatPresetKey)?.let(::saveStreamFormatPreset)
        payload.decodeSyncString(streamFormattingScopeKey)?.let(::saveStreamFormattingScope)
    }
}
