package com.nuvio.app.core.storage

import android.content.Context

internal actual object PlatformLocalAccountDataCleaner {
    private val preferenceNames = listOf(
        "nuvio_addons",
        "nuvio_library",
        "nuvio_library_display_settings",
        "nuvio_home_catalog_settings",
        "nuvio_player_settings",
        "torrent_settings",
        "nuvio_profile_cache",
        "nuvio_avatar_cache",
        "nuvio_profile_pin_cache",
        "nuvio_theme_settings",
        "nuvio_poster_card_style",
        "nuvio_debrid_settings",
        "nuvio_mdblist_settings",
        "nuvio_mdblist_auth",
        "nuvio_mdblist_sync",
        "nuvio_auth",
        "nuvio_trakt_auth",
        "nuvio_simkl_auth",
        "nuvio_simkl_sync",
        "nuvio_trakt_library",
        "nuvio_trakt_settings",
        "nuvio_watched",
        "nuvio_stream_link_cache",
        "nuvio_stream_badge_settings",
        "nuvio_continue_watching_preferences",
        "nuvio_cw_enrichment",
        "nuvio_episode_release_notifications",
        "nuvio_episode_release_notifications_platform",
        "nuvio_watch_progress",
        "nuvio_discover_selection",
        "nuvio_collection_mobile_settings",
        "nuvio_collections",
        "nuvio_plugins",
        "nuvio_member_access",
    )

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun wipe() {
        val context = appContext ?: return
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
        context.filesDir.resolve("nuvio_plugin_scrapers").deleteRecursively()
    }
}
