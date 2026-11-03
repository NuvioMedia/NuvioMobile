package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

actual object LiveTvStorage {
    private const val preferencesName = "nuvio_live_tv"
    private const val sourceTypeKey = "source_type"
    private const val sourceUrlKey = "m3u_source_url"
    private const val localPlaylistDataKey = "m3u_local_playlist_data"
    private const val stalkerPortalUrlKey = "stalker_portal_url"
    private const val stalkerMacAddressKey = "stalker_mac_address"
    private const val stalkerUsernameKey = "stalker_username"
    private const val stalkerPasswordKey = "stalker_password"
    private const val xtreamServerUrlKey = "xtream_server_url"
    private const val xtreamUsernameKey = "xtream_username"
    private const val xtreamPasswordKey = "xtream_password"
    private const val favoriteUrlsKey = "favorite_channel_urls"
    private const val recentChannelUrlKey = "recent_channel_url"
    private const val recentChannelNameKey = "recent_channel_name"
    private const val recentChannelLogoKey = "recent_channel_logo"
    private const val recentChannelGroupKey = "recent_channel_group"
    private const val recentChannelTvgIdKey = "recent_channel_tvg_id"
    private const val epgCacheMagic = "NUVIO_XMLTV_1"

    private var preferences: SharedPreferences? = null
    private var appContext: Context? = null

    private fun resolvedProfileId(): Int = resolveLiveTvStorageProfileId()

    private fun scopedKey(baseKey: String, profileId: Int = resolvedProfileId()): String = "${baseKey}_$profileId"

    private fun SharedPreferences.getScopedString(baseKey: String): String? {
        val profileId = resolvedProfileId()
        return getString(scopedKey(baseKey, profileId), null)
            ?: if (profileId == 1) getString(baseKey, null) else null
    }

    private fun SharedPreferences.Editor.putScopedString(baseKey: String, value: String?) {
        val profileId = resolvedProfileId()
        val profileKey = scopedKey(baseKey, profileId)
        if (value.isNullOrBlank()) {
            remove(profileKey)
            if (profileId == 1) remove(baseKey)
        } else {
            putString(profileKey, value)
            if (profileId == 1) putString(baseKey, value)
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = appContext?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        LiveTvEpgStorageBridge.readCache = ::readEpgCacheEntry
        LiveTvEpgStorageBridge.writeCache = ::writeEpgCacheEntry
    }

    actual fun loadSourceType(): LiveTvSourceType =
        when (preferences?.getScopedString(sourceTypeKey) ?: LiveTvSourceType.M3u.name) {
            LiveTvSourceType.Stalker.name -> LiveTvSourceType.Stalker
            LiveTvSourceType.Xtream.name -> LiveTvSourceType.Xtream
            else -> LiveTvSourceType.M3u
        }

    actual fun saveSourceType(type: LiveTvSourceType) {
        preferences?.edit()?.apply {
            putScopedString(sourceTypeKey, type.name)
        }?.apply()
    }

    actual fun loadSourceUrl(): String? =
        preferences?.getScopedString(sourceUrlKey)

    actual fun saveSourceUrl(url: String) {
        preferences?.edit()?.apply {
            putScopedString(sourceUrlKey, url)
        }?.apply()
    }

    actual fun loadLocalPlaylistData(): String? =
        preferences?.getScopedString(localPlaylistDataKey)

    actual fun saveLocalPlaylistData(data: String) {
        preferences?.edit()?.apply {
            putScopedString(localPlaylistDataKey, data)
        }?.apply()
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings =
        LiveTvStalkerSettings(
            portalUrl = preferences?.getScopedString(stalkerPortalUrlKey).orEmpty(),
            macAddress = preferences?.getScopedString(stalkerMacAddressKey).orEmpty(),
            username = preferences?.getScopedString(stalkerUsernameKey).orEmpty(),
            password = preferences?.getScopedString(stalkerPasswordKey).orEmpty(),
        )

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        preferences?.edit()?.apply {
            putScopedString(stalkerPortalUrlKey, settings.portalUrl)
            putScopedString(stalkerMacAddressKey, settings.macAddress)
            putScopedString(stalkerUsernameKey, settings.username)
            putScopedString(stalkerPasswordKey, settings.password)
        }?.apply()
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings =
        LiveTvXtreamSettings(
            serverUrl = preferences?.getScopedString(xtreamServerUrlKey).orEmpty(),
            username = preferences?.getScopedString(xtreamUsernameKey).orEmpty(),
            password = preferences?.getScopedString(xtreamPasswordKey).orEmpty(),
        )

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        preferences?.edit()?.apply {
            putScopedString(xtreamServerUrlKey, settings.serverUrl)
            putScopedString(xtreamUsernameKey, settings.username)
            putScopedString(xtreamPasswordKey, settings.password)
        }?.apply()
    }

    actual fun loadFavoriteUrls(): Set<String> =
        preferences?.getScopedString(favoriteUrlsKey)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()

    actual fun saveFavoriteUrls(urls: Set<String>) {
        preferences?.edit()?.apply {
            putScopedString(favoriteUrlsKey, urls.sorted().joinToString("\n"))
        }?.apply()
    }

    actual fun loadRecentChannel(): LiveTvRecentChannel? {
        val prefs = preferences ?: return null
        val streamUrl = prefs.getScopedString(recentChannelUrlKey).orEmpty().trim()
        val name = prefs.getScopedString(recentChannelNameKey).orEmpty().trim()
        if (streamUrl.isBlank() || name.isBlank()) return null
        return LiveTvRecentChannel(
            streamUrl = streamUrl,
            name = name,
            logoUrl = prefs.getScopedString(recentChannelLogoKey)?.takeIf(String::isNotBlank),
            group = prefs.getScopedString(recentChannelGroupKey).orEmpty(),
            tvgId = prefs.getScopedString(recentChannelTvgIdKey)?.takeIf(String::isNotBlank),
        )
    }

    actual fun saveRecentChannel(channel: LiveTvRecentChannel?) {
        preferences?.edit()?.apply {
            putScopedString(recentChannelUrlKey, channel?.streamUrl)
            putScopedString(recentChannelNameKey, channel?.name)
            putScopedString(recentChannelLogoKey, channel?.logoUrl)
            putScopedString(recentChannelGroupKey, channel?.group)
            putScopedString(recentChannelTvgIdKey, channel?.tvgId)
        }?.apply()
    }

    private fun readEpgCacheEntry(profileId: Int, url: String): LiveTvEpgCacheEntry? = runCatching {
        val file = epgCacheFile(profileId, url) ?: return null
        if (!file.isFile || file.length() > MAX_DECOMPRESSED_EPG_BYTES + 4096L) return null
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            if (reader.readLine() != epgCacheMagic) return null
            val savedAt = reader.readLine()?.toLongOrNull() ?: return null
            val storedUrl = reader.readLine() ?: return null
            if (storedUrl != url) return null
            val content = reader.readText()
            if (!isValidXmlTvContent(content)) return null
            LiveTvEpgCacheEntry(url = storedUrl, content = content, savedAtEpochMs = savedAt)
        }
    }.getOrNull()

    private fun writeEpgCacheEntry(profileId: Int, entry: LiveTvEpgCacheEntry) {
        runCatching {
            val file = epgCacheFile(profileId, entry.url) ?: return
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append(epgCacheMagic).append('\n')
                writer.append(entry.savedAtEpochMs.toString()).append('\n')
                writer.append(entry.url).append('\n')
                writer.append(entry.content)
            }
            if (temporary.length() > MAX_DECOMPRESSED_EPG_BYTES + 4096L) {
                temporary.delete()
                return
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun epgCacheFile(profileId: Int, url: String): File? {
        val context = appContext ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$profileId\n$url".toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(context.filesDir, "live_tv_epg/$profileId/$digest.xmltvcache")
    }
}
