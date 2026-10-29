package com.nuvio.app.core.sync

import kotlinx.serialization.json.JsonObject

internal const val PROFILE_PLAYER_SETTINGS_FEATURE = "player_settings"
internal const val PROFILE_DEBRID_SETTINGS_FEATURE = "debrid_settings"
internal const val PROFILE_TMDB_SETTINGS_FEATURE = "tmdb_settings"
internal const val PROFILE_MDBLIST_SETTINGS_FEATURE = "mdblist_settings"

private val profileCredentialKeys = mapOf(
    PROFILE_PLAYER_SETTINGS_FEATURE to setOf(
        "animeskip_client_id",
        "introdb_api_key",
    ),
    PROFILE_DEBRID_SETTINGS_FEATURE to setOf(
        "debrid_torbox_api_key",
        "debrid_premiumize_api_key",
        "debrid_real_debrid_api_key",
    ),
    PROFILE_TMDB_SETTINGS_FEATURE to setOf("tmdb_api_key"),
    PROFILE_MDBLIST_SETTINGS_FEATURE to setOf("mdblist_api_key"),
)

internal fun withoutProfileCredentials(feature: String, payload: JsonObject): JsonObject {
    val keys = profileCredentialKeys[feature].orEmpty()
    if (keys.isEmpty() || payload.keys.none(keys::contains)) return payload
    return JsonObject(payload.filterKeys { it !in keys })
}

internal fun preservingLocalProfileCredentials(
    feature: String,
    remotePayload: JsonObject,
    localPayload: JsonObject,
): JsonObject {
    if (feature == PROFILE_TMDB_SETTINGS_FEATURE) return withoutProfileCredentials(feature, remotePayload)
    val keys = profileCredentialKeys[feature].orEmpty()
    if (keys.isEmpty()) return remotePayload
    val merged = remotePayload.toMutableMap()
    keys.forEach { key ->
        val localValue = localPayload[key]
        if (localValue == null) {
            merged.remove(key)
        } else {
            merged[key] = localValue
        }
    }
    return JsonObject(merged)
}
