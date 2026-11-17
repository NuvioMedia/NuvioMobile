@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.storage

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import com.nuvio.app.features.profiles.MAX_PROFILES

internal actual object PlatformLocalAccountDataCleaner {
    private val plainKeys = listOf(
        "profile_payload",
        "avatar_catalog_payload",
        "anonymous_user_id",
        "member_access_payload",
    )
    private val profilePinCachePrefixes = listOf("profile_pin_cache_")
    private val profileIndexedPrefixes = listOf(
        "installed_manifest_urls_",
        "plugins_state_",
        "library_payload_",
        "watched_payload_",
        "watch_progress_payload_",
    )
    private val profileScopedBaseKeys = listOf(
        "episode_shuffle",
        "catalog_settings_payload",
        "discover_catalog_key",
        "continue_watching_preferences_payload",
        "poster_card_style_payload",
        "episode_release_notifications_payload",
        "episode_release_notification_scheduled_ids",
        "selected_theme",
        "amoled_enabled",
        "show_loading_overlay",
        "preferred_audio_language",
        "secondary_preferred_audio_language",
        "preferred_subtitle_language",
        "secondary_preferred_subtitle_language",
        "subtitle_text_color",
        "subtitle_outline_enabled",
        "subtitle_font_size_sp",
        "subtitle_bottom_offset",
        "stream_reuse_last_link_enabled",
        "stream_reuse_last_link_cache_hours",
        "stream_badge_rules",
        "show_file_size_badges",
        "stream_badge_placement",
        "debrid_stream_badge_rules",
        "p2p_enabled",
        "enable_upload",
        "hide_torrent_stats",
        "mdblist_enabled",
        "mdblist_api_key",
        "mdblist_use_imdb",
        "mdblist_use_tmdb",
        "mdblist_use_tomatoes",
        "mdblist_use_metacritic",
        "mdblist_use_trakt",
        "mdblist_use_letterboxd",
        "mdblist_use_audience",
        "mdblist_use_mal",
        "trakt_auth_payload",
        "simkl_auth_metadata",
        "simkl_sync_snapshot",
        "trakt_library_payload",
        "trakt_settings_payload",
        "library_display_settings_payload",
        "pending_watch_progress_source",
        "collection_mobile_settings_payload",
        "collections_payload",
    )

    actual fun wipe() {
        val defaults = NSUserDefaults.standardUserDefaults

        plainKeys.forEach(defaults::removeObjectForKey)

        (1..MAX_PROFILES).forEach { profileId ->
            profileIndexedPrefixes.forEach { prefix ->
                defaults.removeObjectForKey("$prefix$profileId")
            }
            profilePinCachePrefixes.forEach { prefix ->
                defaults.removeObjectForKey("$prefix$profileId")
            }
            profileScopedBaseKeys.forEach { baseKey ->
                defaults.removeObjectForKey("${baseKey}_$profileId")
            }
        }

        for (key in defaults.dictionaryRepresentation().keys) {
            val keyString = key as? String ?: continue
            if (
                keyString.startsWith("stream_link_") ||
                keyString.startsWith("cw_enrichment_cache_")
            ) {
                defaults.removeObjectForKey(keyString)
            }
        }

        val scraperCodePath = "${NSHomeDirectory()}/Library/Application Support/nuvio_plugin_scrapers"
        if (NSFileManager.defaultManager.fileExistsAtPath(scraperCodePath)) {
            NSFileManager.defaultManager.removeItemAtPath(scraperCodePath, null)
        }
        val membershipPath = "${NSHomeDirectory()}/Library/Application Support/NuvioMembership"
        if (NSFileManager.defaultManager.fileExistsAtPath(membershipPath)) {
            NSFileManager.defaultManager.removeItemAtPath(membershipPath, null)
        }
    }
}
