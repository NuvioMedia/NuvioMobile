package com.nuvio.app.features.livetv

import platform.Foundation.NSUserDefaults

actual object LiveTvStorage {
    private const val sourceTypeKey = "live_tv_source_type"
    private const val sourceUrlKey = "live_tv_m3u_source_url"
    private const val localPlaylistDataKey = "live_tv_m3u_local_playlist_data"
    private const val stalkerPortalUrlKey = "live_tv_stalker_portal_url"
    private const val stalkerMacAddressKey = "live_tv_stalker_mac_address"
    private const val stalkerUsernameKey = "live_tv_stalker_username"
    private const val stalkerPasswordKey = "live_tv_stalker_password"
    private const val xtreamServerUrlKey = "live_tv_xtream_server_url"
    private const val xtreamUsernameKey = "live_tv_xtream_username"
    private const val xtreamPasswordKey = "live_tv_xtream_password"
    private const val favoriteUrlsKey = "live_tv_favorite_channel_urls"
    private const val recentChannelUrlKey = "live_tv_recent_channel_url"
    private const val recentChannelNameKey = "live_tv_recent_channel_name"
    private const val recentChannelLogoKey = "live_tv_recent_channel_logo"
    private const val recentChannelGroupKey = "live_tv_recent_channel_group"
    private const val recentChannelTvgIdKey = "live_tv_recent_channel_tvg_id"

    private fun resolvedProfileId(): Int = resolveLiveTvStorageProfileId()

    private fun scopedKey(baseKey: String, profileId: Int = resolvedProfileId()): String = "${baseKey}_$profileId"

    private fun loadScopedString(baseKey: String): String? {
        val defaults = NSUserDefaults.standardUserDefaults
        val profileId = resolvedProfileId()
        return defaults.stringForKey(scopedKey(baseKey, profileId))
            ?: if (profileId == 1) defaults.stringForKey(baseKey) else null
    }

    private fun saveScopedString(baseKey: String, value: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        val profileId = resolvedProfileId()
        val profileKey = scopedKey(baseKey, profileId)
        if (value.isNullOrBlank()) {
            defaults.removeObjectForKey(profileKey)
            if (profileId == 1) defaults.removeObjectForKey(baseKey)
        } else {
            defaults.setObject(value, forKey = profileKey)
            if (profileId == 1) defaults.setObject(value, forKey = baseKey)
        }
    }

    actual fun loadSourceType(): LiveTvSourceType =
        when (loadScopedString(sourceTypeKey)) {
            LiveTvSourceType.Stalker.name -> LiveTvSourceType.Stalker
            LiveTvSourceType.Xtream.name -> LiveTvSourceType.Xtream
            else -> LiveTvSourceType.M3u
        }

    actual fun saveSourceType(type: LiveTvSourceType) {
        saveScopedString(sourceTypeKey, type.name)
    }

    actual fun loadSourceUrl(): String? =
        loadScopedString(sourceUrlKey)

    actual fun saveSourceUrl(url: String) {
        saveScopedString(sourceUrlKey, url)
    }

    actual fun loadLocalPlaylistData(): String? =
        loadScopedString(localPlaylistDataKey)

    actual fun saveLocalPlaylistData(data: String) {
        saveScopedString(localPlaylistDataKey, data)
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings {
        return LiveTvStalkerSettings(
            portalUrl = loadScopedString(stalkerPortalUrlKey).orEmpty(),
            macAddress = loadScopedString(stalkerMacAddressKey).orEmpty(),
            username = loadScopedString(stalkerUsernameKey).orEmpty(),
            password = loadScopedString(stalkerPasswordKey).orEmpty(),
        )
    }

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        saveScopedString(stalkerPortalUrlKey, settings.portalUrl)
        saveScopedString(stalkerMacAddressKey, settings.macAddress)
        saveScopedString(stalkerUsernameKey, settings.username)
        saveScopedString(stalkerPasswordKey, settings.password)
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings {
        return LiveTvXtreamSettings(
            serverUrl = loadScopedString(xtreamServerUrlKey).orEmpty(),
            username = loadScopedString(xtreamUsernameKey).orEmpty(),
            password = loadScopedString(xtreamPasswordKey).orEmpty(),
        )
    }

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        saveScopedString(xtreamServerUrlKey, settings.serverUrl)
        saveScopedString(xtreamUsernameKey, settings.username)
        saveScopedString(xtreamPasswordKey, settings.password)
    }

    actual fun loadFavoriteUrls(): Set<String> =
        loadScopedString(favoriteUrlsKey)
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    actual fun saveFavoriteUrls(urls: Set<String>) {
        saveScopedString(favoriteUrlsKey, urls.sorted().joinToString("\n"))
    }

    actual fun loadRecentChannel(): LiveTvRecentChannel? {
        val streamUrl = loadScopedString(recentChannelUrlKey).orEmpty().trim()
        val name = loadScopedString(recentChannelNameKey).orEmpty().trim()
        if (streamUrl.isBlank() || name.isBlank()) return null
        return LiveTvRecentChannel(
            streamUrl = streamUrl,
            name = name,
            logoUrl = loadScopedString(recentChannelLogoKey)?.takeIf(String::isNotBlank),
            group = loadScopedString(recentChannelGroupKey).orEmpty(),
            tvgId = loadScopedString(recentChannelTvgIdKey)?.takeIf(String::isNotBlank),
        )
    }

    actual fun saveRecentChannel(channel: LiveTvRecentChannel?) {
        saveScopedString(recentChannelUrlKey, channel?.streamUrl)
        saveScopedString(recentChannelNameKey, channel?.name)
        saveScopedString(recentChannelLogoKey, channel?.logoUrl)
        saveScopedString(recentChannelGroupKey, channel?.group)
        saveScopedString(recentChannelTvgIdKey, channel?.tvgId)
    }
}
