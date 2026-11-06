package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.ChannelGroup
import com.nuvio.app.features.livetv.model.ChannelSort
import com.nuvio.app.features.livetv.model.CustomGroup
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgSource
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.model.PlaylistAccount
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.model.Reminder
import com.nuvio.app.features.livetv.model.ZapMode
import com.nuvio.app.features.livetv.model.matchFrameRate
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * One stored preference value. The same five kinds the TV app's DataStore holds, so a setup
 * saved on a TV (and synced through Google Drive) reads back with the same types here.
 */
sealed class PrefValue {
    data class Bool(val value: Boolean) : PrefValue()
    data class Int(val value: kotlin.Int) : PrefValue()
    data class Long(val value: kotlin.Long) : PrefValue()
    data class StringSet(val value: Set<String>) : PrefValue()
    data class Str(val value: String) : PrefValue()
}

/** Every key the TV app stores, by the exact DataStore name, so stored data is interchangeable. */
internal object LiveTvKeys {
    const val playlists = "playlists"
    const val epgs = "epg_sources"

    const val showInSidebar = "show_in_sidebar"
    const val showNames = "show_channel_names"
    const val showNumbers = "show_channel_numbers"
    const val markPlaying = "mark_playing_channel"
    const val showLogos = "show_channel_logos"
    const val showPreview = "show_preview"
    const val showDetails = "show_program_details"
    const val showFavGroup = "show_favorites_group"
    const val showRecentGroup = "show_recent_group"
    const val compactRows = "compact_rows"
    const val showProgramTimes = "show_program_times"
    const val showEpisodeNumbers = "show_episode_numbers"
    const val guideSize = "guide_size"
    const val epgOffset = "epg_offset_minutes"
    const val reverseZap = "reverse_zap"
    const val zapMode = "zap_mode"
    const val autoPlayLast = "auto_play_last"
    const val rememberGroup = "remember_group"
    const val fullscreenOnSelect = "fullscreen_on_select"
    const val bannerSeconds = "banner_seconds"
    const val playlistRefresh = "playlist_refresh_hours"
    const val epgRefresh = "epg_refresh_hours"
    const val epgPast = "epg_past_hours"
    const val epgFuture = "epg_future_days"
    const val clock24 = "clock_24h"
    const val autoReconnect = "auto_reconnect"
    const val autoBackupChannel = "auto_backup_channel"
    const val catchupCache = "catchup_cache"
    const val channelSort = "channel_sort"
    const val solidHighlight = "solid_highlight"
    const val showInSearch = "show_in_search"
    const val guideSearchLive = "guide_search_live"
    const val guideSearchCatalog = "guide_search_catalog"
    const val guideSearchOnDemand = "guide_search_ondemand"
    const val vodSearchLive = "vod_search_live"
    const val vodSearchCatalog = "vod_search_catalog"
    const val vodSearchOnDemand = "vod_search_ondemand"
    const val showAllGroup = "show_all_group"
    const val resumeInPreview = "resume_in_preview"
    const val showGroupCounts = "show_group_counts"
    const val smallHeader = "small_header"
    const val aspectMode = "aspect_mode"
    const val sequentialNumbers = "sequential_numbers"
    const val overlayMode = "overlay_mode"
    const val startOnLiveTv = "start_on_live_tv"
    const val startPage = "start_page"
    const val openOn = "open_on"
    const val launcherCardShape = "launcher_card_shape"
    const val logoSourcesOff = "logo_sources_off"
    const val catchupPreferHls = "catchup_prefer_hls"
    const val phoneLogos = "phone_channel_logos"
    const val phoneNames = "phone_channel_names"
    const val phoneNumbers = "phone_channel_numbers"
    const val catchupShift = "catchup_shift_minutes"
    const val parentalPin = "parental_pin"
    const val lockAdultContent = "lock_adult_content"
    const val showQualityBadges = "show_quality_badges"
    const val showFrameRate = "show_frame_rate"
    const val matchFrameRate = "match_frame_rate"
    const val frameRateMode = "frame_rate_mode"
    const val onDemandInSidebar = "on_demand_in_sidebar"
    const val onDemandEnabled = "on_demand_enabled"
    const val recordingsInSidebar = "recordings_in_sidebar"
    const val sideMenuOrder = "side_menu_order"
    const val sideMenuHidden = "side_menu_hidden"
    const val calendarInMenu = "calendar_in_menu"
    const val calendarUpcomingRow = "calendar_upcoming_row"
    const val calendarNewEpisodeCards = "calendar_new_episode_cards"
    const val calendarHideSpoilers = "calendar_hide_spoilers"
    const val calendarCountry = "calendar_country"
    const val calendarSkipNewsTalk = "calendar_skip_news_talk"
    const val calendarView = "calendar_view"
    const val calendarOkAction = "calendar_ok_action"
    const val calendarHideWatched = "calendar_hide_watched"
    const val calendarSports = "calendar_sports"
    const val calendarFollows = "calendar_follows"
    const val calendarHiddenShows = "calendar_hidden_shows"
    const val calendarChannelPicks = "calendar_channel_picks"
    const val calendarPlans = "calendar_plans"
    const val onDemandInStreams = "on_demand_in_streams"
    const val onDemandStreamsPosition = "on_demand_streams_position"
    const val onDemandAutoPlay = "on_demand_auto_play"
    const val searchOrder = "search_order"
    const val searchPreview = "search_preview"
    const val onDemandAddonPosters = "on_demand_addon_posters"
    const val groupPlaylistHeadings = "group_playlist_headings"
    /** This device has had Group by Playlist turned on once (per device, never synced). */
    const val groupPlaylistTurnedOn = "group_playlist_turned_on_once"
    /** This device only: ids of playlists and guides removed here, not yet passed on by sync. */
    const val removedSources = "removed_sources"
    const val nameRemovals = "name_removals"
    const val developerTools = "developer_tools"
    const val browseByChannelName = "browse_by_channel_name"
    const val showPlaylistInInfo = "show_playlist_in_info"
    const val showNowLine = "show_now_line"
    const val audioPassthrough = "audio_passthrough"
    const val showPosters = "show_posters"
    const val vodMergeDuplicates = "vod_merge_duplicates"
    const val vodDefaultSort = "vod_default_sort"
    const val recordingStorage = "recording_storage"
    const val recordingSpaceRule = "recording_space_rule"
    const val recordPre = "record_pre_minutes"
    const val recordPost = "record_post_minutes"
    const val recordSeriesKeep = "record_series_keep"
    const val vodSorts = "vod_sorts"
    const val homeRowEnabled = "home_row_enabled"
    const val homeRowHiddenProfiles = "home_row_hidden_profiles"
    const val homeRowAboveContinueWatching = "home_row_above_cw"
    const val homeRowSource = "home_row_source"
    const val homeRowTitle = "home_row_title"
    const val homeRowSort = "home_row_sort"
    const val homeRowLimit = "home_row_limit"
    const val homeRowHidePlaceholders = "home_row_hide_placeholders"
    const val homeRowOpensGuide = "home_row_opens_guide"
    const val homeRowShowNext = "home_row_show_next"
    const val homeRowCompact = "home_row_compact"
    const val bufferSize = "buffer_size"
    const val groupSort = "group_sort"
    const val timeshiftEnabled = "timeshift_enabled"
    const val timeshiftMinutes = "timeshift_minutes"
    const val rewindAlways = "rewind_always"
    const val captionsByDefault = "captions_by_default"
    const val clockMode = "clock_mode"
    const val playlistAccounts = "playlist_accounts"
    const val clockPosition = "clock_position"
    const val clockSize = "clock_size"
    const val clockOpacity = "clock_opacity"
    const val clockBackground = "clock_background"
    const val panelBackground = "panel_background"
    const val guideInfoBackground = "guide_info_background"
    const val guideBackdrop = "guide_backdrop"
    const val recentInPanel = "recent_in_panel"
    const val panelRow = "panel_row"
    const val channelSurf = "channel_surf"
    const val barHidden = "bar_hidden"
    const val guideInfoHidden = "guide_info_hidden"
    const val launcherRows = "launcher_rows"
    const val lockedGroups = "locked_groups"
    const val reminders = "reminders"
    /** Phone only (not a TV key): minutes before a show its reminder rings. */
    const val reminderLead = "reminder_lead_minutes"
    /** Phone only (not a TV key): whether live channels show in Home's Continue Watching. */
    const val continueWatchingLive = "continue_watching_live"
    const val channelQuality = "channel_quality"
    const val channelAudio = "channel_audio"
    const val vodHiddenCategories = "vod_hidden_categories"
    const val vodGroupOrder = "vod_group_order"
    const val vodGroupNames = "vod_group_names"
    const val onDemandImports = "on_demand_imports"
    const val channelOrder = "channel_order"
    const val channelCopies = "channel_copies"

    const val hiddenChannels = "hidden_channels"
    const val hiddenGroups = "hidden_groups"
    const val favorites = "favorites"
    const val recent = "recent"
    const val lastChannel = "last_channel"
    const val previousChannel = "previous_channel"
    const val lastGroup = "last_group"
    const val channelNames = "channel_names"
    const val channelLogos = "channel_logos"
    const val channelNumbers = "channel_numbers"
    const val groupNames = "group_names"
    const val groupOrder = "group_order"
    const val customGroups = "custom_groups"
    const val epgOverrides = "epg_overrides"
    /** Channel key → PictureShape name, for channels set away from Auto. */
    const val pictureShapes = "picture_shapes"
    /** Sports: settings and channel fixes (shared by every profile), as JSON. */
    const val sports = "sports"
    /** Sports: the teams, leagues and names you follow, and game reminders (per profile), as JSON. */
    const val sportsMine = "sports_mine"

    /** Separates the EPG source id from the channel id in a stored EPG assignment. */
    const val EPG_SEP = "\u0001"

    /** Per-device keys that sync never copies. */
    private val SYNC_EXCLUDED_BASE = setOf(
        "last_channel", "previous_channel", "last_group", "recent", "on_demand_imports",
        "channel_quality", "developer_tools", "recording_storage",
        "phone_channel_logos", "phone_channel_names", "phone_channel_numbers"
    )
    private val PROFILE_SUFFIX = Regex("""_p\d+$""")

    /** Also matches each profile's own copy ("recent_p2"). */
    val SYNC_EXCLUDED: Set<String> = object : AbstractSet<String>() {
        override val size get() = SYNC_EXCLUDED_BASE.size
        override fun iterator() = SYNC_EXCLUDED_BASE.iterator()
        override fun contains(element: String) = element.replace(PROFILE_SUFFIX, "") in SYNC_EXCLUDED_BASE
    }

    fun seededKey(id: Int) = "profile_seeded_p$id"
    val SEEDED = Regex("""^profile_seeded_p(\d+)$""")
}

// ---------- typed reads of a stored map ----------

internal fun Map<String, PrefValue>.bool(key: String): Boolean? = (this[key] as? PrefValue.Bool)?.value
internal fun Map<String, PrefValue>.int(key: String): Int? = (this[key] as? PrefValue.Int)?.value
internal fun Map<String, PrefValue>.long(key: String): Long? = (this[key] as? PrefValue.Long)?.value
internal fun Map<String, PrefValue>.str(key: String): String? = (this[key] as? PrefValue.Str)?.value
internal fun Map<String, PrefValue>.strSet(key: String): Set<String>? = (this[key] as? PrefValue.StringSet)?.value

internal operator fun MutableMap<String, PrefValue>.set(key: String, value: Boolean) { this[key] = PrefValue.Bool(value) }
internal operator fun MutableMap<String, PrefValue>.set(key: String, value: Int) { this[key] = PrefValue.Int(value) }
internal operator fun MutableMap<String, PrefValue>.set(key: String, value: Long) { this[key] = PrefValue.Long(value) }
internal operator fun MutableMap<String, PrefValue>.set(key: String, value: String) { this[key] = PrefValue.Str(value) }
internal operator fun MutableMap<String, PrefValue>.set(key: String, value: Set<String>) { this[key] = PrefValue.StringSet(value) }

// ---------- org.json-style lenient reads of kotlinx JSON ----------

internal val liveTvJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.optString(key: String, fallback: String = ""): String {
    val e = this[key] ?: return fallback
    return when (e) {
        is JsonNull -> fallback
        is JsonPrimitive -> e.content
        else -> e.toString()
    }
}

internal fun JsonObject.optBoolean(key: String, fallback: Boolean = false): Boolean {
    val p = this[key] as? JsonPrimitive ?: return fallback
    if (p is JsonNull) return fallback
    return when (p.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}

internal fun JsonObject.optLong(key: String, fallback: Long = 0L): Long {
    val p = this[key] as? JsonPrimitive ?: return fallback
    if (p is JsonNull) return fallback
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: fallback
}

internal fun JsonObject.optInt(key: String, fallback: Int = 0): Int {
    val p = this[key] as? JsonPrimitive ?: return fallback
    if (p is JsonNull) return fallback
    return p.content.toIntOrNull() ?: p.content.toDoubleOrNull()?.toInt() ?: fallback
}

internal fun JsonObject.optJSONObject(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.optJSONArray(key: String): JsonArray? = this[key] as? JsonArray

/** A JSON array element as a string, the way JSONArray.getString reads it. */
internal fun JsonElement.asString(): String = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

internal fun JsonArray.strings(): List<String> = map { it.asString() }

internal fun parseObject(raw: String): JsonObject = liveTvJson.parseToJsonElement(raw) as JsonObject
internal fun parseArray(raw: String): JsonArray = liveTvJson.parseToJsonElement(raw) as JsonArray


/**
 * The JSON shapes the TV app stores its lists and maps in, kept identical so a synced setup
 * decodes the same on both. org.json's lenient reads (a number where a string was expected, a
 * missing field) are matched by the opt* helpers below.
 */
internal object LiveTvCodec {
    // ---- playlists / guides

    fun decodePlaylists(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            parseArray(raw).map { e ->
                val o = e as JsonObject
                PlaylistSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    userAgent = o.optString("userAgent"),
                    useEmbeddedEpg = o.optBoolean("useEmbeddedEpg", true),
                    xtreamServer = o.optString("xtreamServer"),
                    xtreamUsername = o.optString("xtreamUsername"),
                    xtreamPassword = o.optString("xtreamPassword"),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    channelCount = o.optInt("channelCount"),
                    // Logins from before On Demand existed stay off until you switch them on.
                    importVod = o.optBoolean("importVod", false),
                    importLive = o.optBoolean("importLive", true),
                    streamFormat = o.optString("streamFormat").ifBlank { "auto" },
                    serverTimezone = o.optString("serverTimezone"),
                    catchupNoHlsSince = o.optLong("catchupNoHlsSince", 0L),
                    logoSource = o.optString("logoSource").ifBlank { "playlist" }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encodePlaylists(list: List<PlaylistSource>): String = buildJsonArray {
        list.forEach { s ->
            add(buildJsonObject {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("enabled", s.enabled); put("userAgent", s.userAgent)
                put("useEmbeddedEpg", s.useEmbeddedEpg)
                put("xtreamServer", s.xtreamServer); put("xtreamUsername", s.xtreamUsername)
                put("xtreamPassword", s.xtreamPassword)
                put("lastUpdatedMs", s.lastUpdatedMs); put("lastError", s.lastError ?: "")
                put("channelCount", s.channelCount)
                put("importVod", s.importVod)
                put("importLive", s.importLive)
                put("streamFormat", s.streamFormat)
                put("serverTimezone", s.serverTimezone)
                put("catchupNoHlsSince", s.catchupNoHlsSince)
                put("logoSource", s.logoSource)
            })
        }
    }.toString()

    fun decodeEpgs(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            parseArray(raw).map { e ->
                val o = e as JsonObject
                EpgSource(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    enabled = o.optBoolean("enabled", true),
                    lastUpdatedMs = o.optLong("lastUpdatedMs"),
                    lastError = o.optString("lastError").ifBlank { null },
                    programCount = o.optInt("programCount")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encodeEpgs(list: List<EpgSource>): String = buildJsonArray {
        list.forEach { s ->
            add(buildJsonObject {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("enabled", s.enabled); put("lastUpdatedMs", s.lastUpdatedMs)
                put("lastError", s.lastError ?: ""); put("programCount", s.programCount)
            })
        }
    }.toString()

    // ---- lists and maps

    fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parseArray(raw).strings() }.getOrDefault(emptyList())
    }

    fun encodeStringList(list: List<String>): String = buildJsonArray { list.forEach { add(it) } }.toString()

    fun decodeListMap(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = parseObject(raw)
            o.keys.associateWith { k -> (o[k] as? JsonArray)?.strings() ?: emptyList() }
        }.getOrDefault(emptyMap())
    }

    fun encodeListMap(map: Map<String, List<String>>): String = buildJsonObject {
        map.forEach { (k, v) -> put(k, buildJsonArray { v.forEach { add(it) } }) }
    }.toString()

    fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = parseObject(raw)
            o.keys.associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun encodeStringMap(map: Map<String, String>): String = buildJsonObject {
        map.forEach { (k, v) -> put(k, v) }
    }.toString()

    fun decodeCustomGroups(raw: String?): List<CustomGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            parseArray(raw).map { e ->
                val o = e as JsonObject
                CustomGroup(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    channelKeys = o.optJSONArray("keys")?.strings() ?: emptyList()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encodeCustomGroups(list: List<CustomGroup>): String = buildJsonArray {
        list.forEach { g ->
            add(buildJsonObject {
                put("id", g.id); put("name", g.name)
                put("keys", buildJsonArray { g.channelKeys.forEach { add(it) } })
            })
        }
    }.toString()

    fun decodeReminders(raw: String?): List<Reminder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            parseArray(raw).map { e ->
                val o = e as JsonObject
                Reminder(o.optString("k"), o.optString("c"), o.optString("t"), o.optLong("s"))
            }
        }.getOrDefault(emptyList())
    }

    fun encodeReminders(list: List<Reminder>): String = buildJsonArray {
        list.forEach {
            add(buildJsonObject { put("k", it.channelKey); put("c", it.channelName); put("t", it.title); put("s", it.startMs) })
        }
    }.toString()

    fun decodeAccounts(raw: String?): Map<String, PlaylistAccount> {
        val out = HashMap<String, PlaylistAccount>()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val o = parseObject(raw)
            o.keys.forEach { id ->
                val a = o.optJSONObject(id) ?: return@forEach
                out[id] = PlaylistAccount(
                    expiresAtMs = a.optLong("exp"),
                    status = a.optString("status"),
                    maxConnections = a.optInt("max"),
                    activeConnections = a.optInt("active"),
                    checkedAtMs = a.optLong("checked")
                )
            }
        }
        return out
    }

    fun encodeAccounts(map: Map<String, PlaylistAccount>): String = buildJsonObject {
        map.forEach { (id, a) ->
            put(id, buildJsonObject {
                put("exp", a.expiresAtMs); put("status", a.status)
                put("max", a.maxConnections); put("active", a.activeConnections); put("checked", a.checkedAtMs)
            })
        }
    }.toString()
}

/**
 * Live TV's stored data: playlists, guides, settings and what you've done with channels, held
 * in memory and persisted through lambdas the platform installs (see [installPersistence]), the
 * way [com.nuvio.app.features.autosync.AutoSyncPreferencesRepository] does.
 *
 * Two maps, like the TV app's two DataStores: the main one (synced between devices) and a
 * per-device one for small, often-written, this-device-only data (quality badges, account
 * details, On Demand import times, sources removed here).
 *
 * Open so a test can work on a store of its own; the app uses [LiveTvPreferences].
 */
internal open class LiveTvPreferencesStore {
    internal open val usesPlatformVpnBackup: Boolean get() = false

    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _data = MutableStateFlow<Map<String, PrefValue>>(emptyMap())
    private val _device = MutableStateFlow<Map<String, PrefValue>>(emptyMap())
    private val _profile = MutableStateFlow(1)

    /** Everything in the main store, by key (for sync and tests). */
    val rawData: StateFlow<Map<String, PrefValue>> = _data.asStateFlow()

    /** The per-device store. */
    val rawDevice: StateFlow<Map<String, PrefValue>> = _device.asStateFlow()

    private var savePersisted: ((Map<String, PrefValue>) -> Unit)? = null
    private var saveDevicePersisted: ((Map<String, PrefValue>) -> Unit)? = null

    /**
     * Installs how the two maps are loaded and saved. Loading happens right away (the stored
     * data isn't profile-dependent); every edit afterwards hands the whole map to [save].
     */
    fun installPersistence(
        load: () -> Map<String, PrefValue>?,
        save: (Map<String, PrefValue>) -> Unit,
        loadDevice: () -> Map<String, PrefValue>? = { null },
        saveDevice: (Map<String, PrefValue>) -> Unit = {},
    ) {
        synchronized(lock) {
            savePersisted = save
            saveDevicePersisted = saveDevice
            _data.value = load()?.toMap() ?: emptyMap()
            _device.value = loadDevice()?.toMap() ?: emptyMap()
        }
        runCatching { turnOnPlaylistGroupsOnce() }
    }

    /** Which Nuvio profile's favorites, recents and so on are in use (see [onProfileChanged]). */
    val activeProfile: StateFlow<Int> = _profile.asStateFlow()

    // ---------- Nuvio profiles ----------
    // Favorites, recents, hidden and arranged groups/channels, reminders and On Demand's hidden
    // categories and order are kept per Nuvio profile. Playlists, guides, settings, renames,
    // channel numbers, EPG choices and parental locks stay shared by every profile.
    // The first profile uses the original keys (so nothing moves for it); the others use the same
    // names with "_p<id>" on the end, the same way the TV app does.

    private val currentProfile: Int get() = _profile.value

    private val personalStringKeys: List<String> = listOf(
        LiveTvKeys.favorites, LiveTvKeys.recent, LiveTvKeys.lastChannel, LiveTvKeys.previousChannel, LiveTvKeys.lastGroup,
        LiveTvKeys.groupOrder, LiveTvKeys.customGroups, LiveTvKeys.channelOrder, LiveTvKeys.channelCopies, LiveTvKeys.reminders,
        LiveTvKeys.vodGroupOrder, LiveTvKeys.vodSorts, LiveTvKeys.sportsMine
    )
    private val personalSetKeys: List<String> = listOf(
        LiveTvKeys.hiddenChannels, LiveTvKeys.hiddenGroups, LiveTvKeys.vodHiddenCategories
    )

    private fun my(key: String, id: Int = currentProfile): String = if (id <= 1) key else "${key}_p$id"

    /**
     * The stored data as profile [id] sees it: its own copies of the personal keys put in place
     * of the first profile's. A profile that hasn't been set up yet sees the first profile's.
     */
    private fun profileView(p: Map<String, PrefValue>, id: Int): Map<String, PrefValue> {
        if (id <= 1) return p
        val seeded = p.bool(LiveTvKeys.seededKey(id)) == true
        val m = p.toMutableMap()
        (personalStringKeys + personalSetKeys).forEach { k ->
            val own = p[my(k, id)]
            if (own != null) m[k] = own else if (seeded) m.remove(k)
        }
        return m
    }

    /**
     * The first time a profile uses Live TV, it starts with a copy of the first profile's
     * favorites, hidden groups and so on; from then on each profile changes only its own.
     */
    private fun seedProfile(id: Int) {
        if (id <= 1) return
        edit { p ->
            if (p.bool(LiveTvKeys.seededKey(id)) == true) return@edit
            (personalStringKeys + personalSetKeys).forEach { k -> if (p[my(k, id)] == null) p[k]?.let { p[my(k, id)] = it } }
            p[LiveTvKeys.seededKey(id)] = true
        }
    }

    /** Tells the store which Nuvio profile is active now (the first profile is 1). */
    fun onProfileChanged(id: Int) {
        if (_profile.value == id) return
        runCatching { seedProfile(id) }
        _profile.value = id
    }

    /**
     * Group by Playlist, switched on once for everyone (people with several playlists kept asking
     * where it was): the first time this version starts on a device. Turned off after that, it
     * stays off: this device remembers it's been done (kept per device, not synced).
     */
    private fun turnOnPlaylistGroupsOnce() {
        if (_device.value.bool(LiveTvKeys.groupPlaylistTurnedOn) == true) return
        edit { it[LiveTvKeys.groupPlaylistHeadings] = true }
        editDevice { it[LiveTvKeys.groupPlaylistTurnedOn] = true }
    }

    // ---------- editing ----------

    /** Changes the main store atomically and saves it. */
    fun edit(block: (MutableMap<String, PrefValue>) -> Unit) {
        val saved: Map<String, PrefValue>? = synchronized(lock) {
            val m = _data.value.toMutableMap()
            block(m)
            if (m == _data.value) null else { _data.value = m; m }
        }
        if (saved != null) savePersisted?.invoke(saved)
    }

    /** Changes the per-device store atomically and saves it. */
    fun editDevice(block: (MutableMap<String, PrefValue>) -> Unit) {
        val saved: Map<String, PrefValue>? = synchronized(lock) {
            val m = _device.value.toMutableMap()
            block(m)
            if (m == _device.value) null else { _device.value = m; m }
        }
        if (saved != null) saveDevicePersisted?.invoke(saved)
    }

    // ---------- reading ----------

    val playlists: StateFlow<List<PlaylistSource>> = _data
        .map { LiveTvCodec.decodePlaylists(it.str(LiveTvKeys.playlists)) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val epgSources: StateFlow<List<EpgSource>> = _data
        .map { LiveTvCodec.decodeEpgs(it.str(LiveTvKeys.epgs)) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun currentPlaylists(): List<PlaylistSource> = LiveTvCodec.decodePlaylists(_data.value.str(LiveTvKeys.playlists))
    fun currentEpgSources(): List<EpgSource> = LiveTvCodec.decodeEpgs(_data.value.str(LiveTvKeys.epgs))
    fun currentSettings(): LiveTvSettings = readSettings(_data.value)

    val settings: StateFlow<LiveTvSettings> = _data.map { readSettings(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, LiveTvSettings())

    private fun readSettings(p: Map<String, PrefValue>): LiveTvSettings {
        val d = LiveTvSettings()
        return LiveTvSettings(
            showInSidebar = p.bool(LiveTvKeys.showInSidebar) ?: d.showInSidebar,
            showChannelNames = p.bool(LiveTvKeys.showNames) ?: d.showChannelNames,
            showChannelNumbers = p.bool(LiveTvKeys.showNumbers) ?: d.showChannelNumbers,
            markPlayingChannel = p.bool(LiveTvKeys.markPlaying) ?: d.markPlayingChannel,
            showChannelLogos = p.bool(LiveTvKeys.showLogos) ?: d.showChannelLogos,
            showPreview = p.bool(LiveTvKeys.showPreview) ?: d.showPreview,
            showProgramDetails = p.bool(LiveTvKeys.showDetails) ?: d.showProgramDetails,
            showFavoritesGroup = p.bool(LiveTvKeys.showFavGroup) ?: d.showFavoritesGroup,
            showRecentGroup = p.bool(LiveTvKeys.showRecentGroup) ?: d.showRecentGroup,
            compactRows = p.bool(LiveTvKeys.compactRows) ?: d.compactRows,
            showProgramTimes = p.bool(LiveTvKeys.showProgramTimes) ?: d.showProgramTimes,
            showEpisodeNumbers = p.bool(LiveTvKeys.showEpisodeNumbers) ?: d.showEpisodeNumbers,
            guideSize = p.str(LiveTvKeys.guideSize) ?: d.guideSize,
            epgOffsetMinutes = p.int(LiveTvKeys.epgOffset) ?: d.epgOffsetMinutes,
            reverseZap = p.bool(LiveTvKeys.reverseZap) ?: d.reverseZap,
            zapMode = p.str(LiveTvKeys.zapMode)?.let { v -> ZapMode.entries.firstOrNull { it.name == v } } ?: d.zapMode,
            autoPlayLastChannel = p.bool(LiveTvKeys.autoPlayLast) ?: d.autoPlayLastChannel,
            rememberLastGroup = p.bool(LiveTvKeys.rememberGroup) ?: d.rememberLastGroup,
            openFullscreenOnSelect = p.bool(LiveTvKeys.fullscreenOnSelect) ?: d.openFullscreenOnSelect,
            infoBannerSeconds = p.int(LiveTvKeys.bannerSeconds) ?: d.infoBannerSeconds,
            playlistRefreshHours = p.int(LiveTvKeys.playlistRefresh) ?: d.playlistRefreshHours,
            epgRefreshHours = p.int(LiveTvKeys.epgRefresh) ?: d.epgRefreshHours,
            epgPastHours = p.int(LiveTvKeys.epgPast) ?: d.epgPastHours,
            epgFutureDays = p.int(LiveTvKeys.epgFuture) ?: d.epgFutureDays,
            use24HourClock = p.bool(LiveTvKeys.clock24) ?: d.use24HourClock,
            autoReconnect = p.bool(LiveTvKeys.autoReconnect) ?: d.autoReconnect,
            autoBackupChannel = p.bool(LiveTvKeys.autoBackupChannel) ?: d.autoBackupChannel,
            catchupCache = p.bool(LiveTvKeys.catchupCache) ?: d.catchupCache,
            channelSort = p.str(LiveTvKeys.channelSort)?.let { v -> ChannelSort.entries.firstOrNull { it.name == v } } ?: d.channelSort,
            solidHighlight = p.bool(LiveTvKeys.solidHighlight) ?: d.solidHighlight,
            showInSearch = p.bool(LiveTvKeys.showInSearch) ?: d.showInSearch,
            guideSearchLive = p.bool(LiveTvKeys.guideSearchLive) ?: d.guideSearchLive,
            guideSearchCatalog = p.bool(LiveTvKeys.guideSearchCatalog) ?: d.guideSearchCatalog,
            guideSearchOnDemand = p.bool(LiveTvKeys.guideSearchOnDemand) ?: d.guideSearchOnDemand,
            onDemandSearchLive = p.bool(LiveTvKeys.vodSearchLive) ?: d.onDemandSearchLive,
            onDemandSearchCatalog = p.bool(LiveTvKeys.vodSearchCatalog) ?: d.onDemandSearchCatalog,
            onDemandSearchOnDemand = p.bool(LiveTvKeys.vodSearchOnDemand) ?: d.onDemandSearchOnDemand,
            showAllChannelsGroup = p.bool(LiveTvKeys.showAllGroup) ?: d.showAllChannelsGroup,
            resumeLastInPreview = p.bool(LiveTvKeys.resumeInPreview) ?: d.resumeLastInPreview,
            showGroupCounts = p.bool(LiveTvKeys.showGroupCounts) ?: d.showGroupCounts,
            smallHeader = p.bool(LiveTvKeys.smallHeader) ?: d.smallHeader,
            aspectMode = p.str(LiveTvKeys.aspectMode) ?: d.aspectMode,
            sequentialNumbers = p.bool(LiveTvKeys.sequentialNumbers) ?: d.sequentialNumbers,
            overlayMode = p.bool(LiveTvKeys.overlayMode) ?: d.overlayMode,
            startOnLiveTv = p.bool(LiveTvKeys.startOnLiveTv) ?: d.startOnLiveTv,
            startPage = p.str(LiveTvKeys.startPage) ?: d.startPage,
            openOn = p.str(LiveTvKeys.openOn) ?: d.openOn,
            launcherCardShape = p.str(LiveTvKeys.launcherCardShape) ?: d.launcherCardShape,
            logoSourcesOff = p.str(LiveTvKeys.logoSourcesOff) ?: d.logoSourcesOff,
            catchupPreferHls = p.bool(LiveTvKeys.catchupPreferHls) ?: d.catchupPreferHls,
            phoneChannelLogos = p.bool(LiveTvKeys.phoneLogos) ?: d.phoneChannelLogos,
            phoneChannelNames = p.bool(LiveTvKeys.phoneNames) ?: d.phoneChannelNames,
            phoneChannelNumbers = p.bool(LiveTvKeys.phoneNumbers) ?: d.phoneChannelNumbers,
            catchupShiftMinutes = p.int(LiveTvKeys.catchupShift) ?: d.catchupShiftMinutes,
            parentalPin = p.str(LiveTvKeys.parentalPin) ?: d.parentalPin,
            lockAdultContent = p.bool(LiveTvKeys.lockAdultContent) ?: d.lockAdultContent,
            showQualityBadges = p.bool(LiveTvKeys.showQualityBadges) ?: d.showQualityBadges,
            showFrameRate = p.bool(LiveTvKeys.showFrameRate) ?: d.showFrameRate,
            // Before the three choices it was on or off; on worked like "On start/stop".
            frameRateMode = p.str(LiveTvKeys.frameRateMode) ?: if (p.bool(LiveTvKeys.matchFrameRate) == true) "START_STOP" else d.frameRateMode,
            onDemandInSidebar = p.bool(LiveTvKeys.onDemandInSidebar) ?: d.onDemandInSidebar,
            onDemandEnabled = p.bool(LiveTvKeys.onDemandEnabled) ?: d.onDemandEnabled,
            recordingsInSidebar = p.bool(LiveTvKeys.recordingsInSidebar) ?: d.recordingsInSidebar,
            sideMenuOrder = p.str(LiveTvKeys.sideMenuOrder) ?: d.sideMenuOrder,
            sideMenuHidden = p.str(LiveTvKeys.sideMenuHidden) ?: d.sideMenuHidden,
            calendarInMenu = p.bool(LiveTvKeys.calendarInMenu) ?: d.calendarInMenu,
            calendarUpcomingRow = p.bool(LiveTvKeys.calendarUpcomingRow) ?: d.calendarUpcomingRow,
            calendarNewEpisodeCards = p.bool(LiveTvKeys.calendarNewEpisodeCards) ?: d.calendarNewEpisodeCards,
            calendarHideSpoilers = p.bool(LiveTvKeys.calendarHideSpoilers) ?: d.calendarHideSpoilers,
            calendarCountry = p.str(LiveTvKeys.calendarCountry) ?: d.calendarCountry,
            calendarSkipNewsTalk = p.bool(LiveTvKeys.calendarSkipNewsTalk) ?: d.calendarSkipNewsTalk,
            calendarView = p.str(LiveTvKeys.calendarView) ?: d.calendarView,
            calendarOkAction = p.str(LiveTvKeys.calendarOkAction) ?: d.calendarOkAction,
            calendarHideWatched = p.bool(LiveTvKeys.calendarHideWatched) ?: d.calendarHideWatched,
            calendarSports = p.bool(LiveTvKeys.calendarSports) ?: d.calendarSports,
            onDemandInStreams = p.bool(LiveTvKeys.onDemandInStreams) ?: d.onDemandInStreams,
            onDemandStreamsPosition = p.str(LiveTvKeys.onDemandStreamsPosition) ?: d.onDemandStreamsPosition,
            onDemandAutoPlay = p.bool(LiveTvKeys.onDemandAutoPlay) ?: d.onDemandAutoPlay,
            searchOrder = p.str(LiveTvKeys.searchOrder) ?: d.searchOrder,
            searchPreview = p.bool(LiveTvKeys.searchPreview) ?: d.searchPreview,
            onDemandAddonPosters = p.bool(LiveTvKeys.onDemandAddonPosters) ?: d.onDemandAddonPosters,
            groupPlaylistHeadings = p.bool(LiveTvKeys.groupPlaylistHeadings) ?: d.groupPlaylistHeadings,
            nameRemovals = p.str(LiveTvKeys.nameRemovals) ?: d.nameRemovals,
            developerTools = p.bool(LiveTvKeys.developerTools) ?: d.developerTools,
            browseByChannelName = p.bool(LiveTvKeys.browseByChannelName) ?: d.browseByChannelName,
            showPlaylistInInfo = p.bool(LiveTvKeys.showPlaylistInInfo) ?: d.showPlaylistInInfo,
            showNowLine = p.bool(LiveTvKeys.showNowLine) ?: d.showNowLine,
            audioPassthrough = p.bool(LiveTvKeys.audioPassthrough) ?: d.audioPassthrough,
            showPosters = p.bool(LiveTvKeys.showPosters) ?: d.showPosters,
            vodMergeDuplicates = p.bool(LiveTvKeys.vodMergeDuplicates) ?: d.vodMergeDuplicates,
            vodDefaultSort = p.str(LiveTvKeys.vodDefaultSort) ?: d.vodDefaultSort,
            recordingStorage = p.str(LiveTvKeys.recordingStorage) ?: d.recordingStorage,
            recordingSpaceRule = p.str(LiveTvKeys.recordingSpaceRule) ?: d.recordingSpaceRule,
            recordPreMinutes = p.int(LiveTvKeys.recordPre) ?: d.recordPreMinutes,
            recordPostMinutes = p.int(LiveTvKeys.recordPost) ?: d.recordPostMinutes,
            recordSeriesKeep = p.int(LiveTvKeys.recordSeriesKeep) ?: d.recordSeriesKeep,
            // The row used to be on/off for the whole app. Turned off before (with no per-profile
            // choice saved yet): that was done from the main profile, so it stays off there only.
            homeRowEnabled = true,
            homeRowHiddenProfiles = p.strSet(LiveTvKeys.homeRowHiddenProfiles)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: if (p.bool(LiveTvKeys.homeRowEnabled) == false) setOf(1) else emptySet(),
            homeRowAboveContinueWatching = p.bool(LiveTvKeys.homeRowAboveContinueWatching) ?: d.homeRowAboveContinueWatching,
            homeRowSource = p.str(LiveTvKeys.homeRowSource) ?: d.homeRowSource,
            homeRowTitle = p.str(LiveTvKeys.homeRowTitle) ?: d.homeRowTitle,
            homeRowSort = p.str(LiveTvKeys.homeRowSort) ?: d.homeRowSort,
            homeRowLimit = p.int(LiveTvKeys.homeRowLimit) ?: d.homeRowLimit,
            homeRowHidePlaceholders = p.bool(LiveTvKeys.homeRowHidePlaceholders) ?: d.homeRowHidePlaceholders,
            homeRowOpensGuide = p.bool(LiveTvKeys.homeRowOpensGuide) ?: d.homeRowOpensGuide,
            homeRowShowNext = p.bool(LiveTvKeys.homeRowShowNext) ?: d.homeRowShowNext,
            homeRowCompact = p.bool(LiveTvKeys.homeRowCompact) ?: d.homeRowCompact,
            bufferSize = p.str(LiveTvKeys.bufferSize) ?: d.bufferSize,
            groupSort = p.str(LiveTvKeys.groupSort) ?: d.groupSort,
            timeshiftEnabled = p.bool(LiveTvKeys.timeshiftEnabled) ?: d.timeshiftEnabled,
            timeshiftMinutes = p.int(LiveTvKeys.timeshiftMinutes) ?: d.timeshiftMinutes,
            rewindAlways = p.bool(LiveTvKeys.rewindAlways) ?: d.rewindAlways,
            captionsByDefault = p.bool(LiveTvKeys.captionsByDefault) ?: d.captionsByDefault,
            clockMode = p.str(LiveTvKeys.clockMode) ?: d.clockMode,
            clockPosition = p.str(LiveTvKeys.clockPosition) ?: d.clockPosition,
            clockSize = p.str(LiveTvKeys.clockSize) ?: d.clockSize,
            clockOpacity = p.int(LiveTvKeys.clockOpacity) ?: d.clockOpacity,
            clockBackground = p.bool(LiveTvKeys.clockBackground) ?: d.clockBackground,
            panelBackground = p.str(LiveTvKeys.panelBackground) ?: d.panelBackground,
            guideInfoBackground = p.str(LiveTvKeys.guideInfoBackground) ?: d.guideInfoBackground,
            guideBackdrop = p.str(LiveTvKeys.guideBackdrop) ?: d.guideBackdrop,
            recentInPanel = p.bool(LiveTvKeys.recentInPanel) ?: d.recentInPanel,
            // Before the choice existed it was an on / off switch for Recent.
            panelRow = p.str(LiveTvKeys.panelRow) ?: if (p.bool(LiveTvKeys.recentInPanel) == false) "OFF" else d.panelRow,
            channelSurf = p.bool(LiveTvKeys.channelSurf) ?: d.channelSurf,
            barHidden = p.str(LiveTvKeys.barHidden) ?: d.barHidden,
            guideInfoHidden = p.str(LiveTvKeys.guideInfoHidden) ?: d.guideInfoHidden,
            launcherRows = p.str(LiveTvKeys.launcherRows) ?: d.launcherRows
        )
    }

    /**
     * Channels and groups hidden in every profile that uses Live TV. No one sees them, so the
     * guide doesn't need their listings.
     */
    val hiddenEverywhere: StateFlow<HiddenEverywhere> = _data.map { p ->
        val ids = listOf(1) + p.keys.mapNotNull { k ->
            LiveTvKeys.SEEDED.find(k)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { p.bool(k) == true }
        }
        HiddenEverywhere(ids.distinct().map { id ->
            val v = profileView(p, id)
            val hiddenGroups = v.strSet(LiveTvKeys.hiddenGroups) ?: emptySet()
            // Channels this profile still shows somewhere else: in Favorites, in a group of its
            // own, or copied into another group. Their own group being hidden doesn't hide them.
            val kept = HashSet<String>()
            kept += LiveTvCodec.decodeStringList(v.str(LiveTvKeys.favorites))
            LiveTvCodec.decodeCustomGroups(v.str(LiveTvKeys.customGroups)).forEach { g -> if (g.id !in hiddenGroups) kept += g.channelKeys }
            LiveTvCodec.decodeListMap(v.str(LiveTvKeys.channelCopies)).forEach { (groupId, keys) -> if (groupId !in hiddenGroups) kept += keys }
            HiddenView(v.strSet(LiveTvKeys.hiddenChannels) ?: emptySet(), hiddenGroups, kept)
        })
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, HiddenEverywhere(emptyList()))

    fun currentHiddenEverywhere(): HiddenEverywhere = hiddenEverywhere.value

    val userState: StateFlow<LiveUserState> = combine(_data, _device, _profile) { p0, d, id ->
        readUserState(p0, d, id)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, LiveUserState())

    fun currentUserState(): LiveUserState = readUserState(_data.value, _device.value, currentProfile)

    private fun readUserState(p0: Map<String, PrefValue>, d: Map<String, PrefValue>, id: Int): LiveUserState {
        val p = profileView(p0, id)
        return LiveUserState(
            hiddenChannels = p.strSet(LiveTvKeys.hiddenChannels) ?: emptySet(),
            hiddenGroups = p.strSet(LiveTvKeys.hiddenGroups) ?: emptySet(),
            favorites = LiveTvCodec.decodeStringList(p.str(LiveTvKeys.favorites)),
            recent = LiveTvCodec.decodeStringList(p.str(LiveTvKeys.recent)),
            lastChannelKey = p.str(LiveTvKeys.lastChannel),
            previousChannelKey = p.str(LiveTvKeys.previousChannel),
            lastGroupId = p.str(LiveTvKeys.lastGroup),
            channelNames = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.channelNames)),
            channelLogos = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.channelLogos)),
            channelNumbers = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.channelNumbers))
                .mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }.toMap(),
            groupNames = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.groupNames)),
            groupOrder = LiveTvCodec.decodeStringList(p.str(LiveTvKeys.groupOrder)),
            customGroups = LiveTvCodec.decodeCustomGroups(p.str(LiveTvKeys.customGroups)),
            epgOverrides = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.epgOverrides)).mapNotNull { (k, v) ->
                val parts = v.split(LiveTvKeys.EPG_SEP, limit = 2)
                if (parts.size == 2) k to EpgAssignment(parts[0], parts[1]) else null
            }.toMap(),
            channelOrder = LiveTvCodec.decodeListMap(p.str(LiveTvKeys.channelOrder)),
            channelCopies = LiveTvCodec.decodeListMap(p.str(LiveTvKeys.channelCopies)),
            lockedGroups = p.strSet(LiveTvKeys.lockedGroups) ?: emptySet(),
            reminders = LiveTvCodec.decodeReminders(p.str(LiveTvKeys.reminders)),
            channelQuality = LiveTvCodec.decodeStringMap(d.str(LiveTvKeys.channelQuality) ?: p.str(LiveTvKeys.channelQuality)),
            channelAudio = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.channelAudio)),
            vodHiddenCategories = p.strSet(LiveTvKeys.vodHiddenCategories) ?: emptySet(),
            vodGroupOrder = LiveTvCodec.decodeStringList(p.str(LiveTvKeys.vodGroupOrder)),
            vodGroupNames = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.vodGroupNames)),
            vodSorts = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.vodSorts))
        )
    }

    // ---------- sources ----------

    fun updatePlaylists(transform: (List<PlaylistSource>) -> List<PlaylistSource>) {
        var removed = emptySet<String>()
        edit { p ->
            val before = LiveTvCodec.decodePlaylists(p.str(LiveTvKeys.playlists))
            val after = transform(before)
            removed = before.map { it.id }.toSet() - after.map { it.id }.toSet()
            p[LiveTvKeys.playlists] = LiveTvCodec.encodePlaylists(after)
        }
        noteRemovedSources(removed)
    }

    fun updateEpgSources(transform: (List<EpgSource>) -> List<EpgSource>) {
        var removed = emptySet<String>()
        edit { p ->
            val before = LiveTvCodec.decodeEpgs(p.str(LiveTvKeys.epgs))
            val after = transform(before)
            removed = before.map { it.id }.toSet() - after.map { it.id }.toSet()
            p[LiveTvKeys.epgs] = LiveTvCodec.encodeEpgs(after)
        }
        noteRemovedSources(removed)
    }

    // Playlists and guides removed on this device (by you), until sync has passed that on. Sync
    // only takes a source out of the saved copy when it's listed here, so a device that simply
    // never had a playlist (or lost it) can't delete it from your other devices.
    private fun noteRemovedSources(ids: Set<String>) {
        val clean = ids.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        editDevice { it[LiveTvKeys.removedSources] = (it.strSet(LiveTvKeys.removedSources) ?: emptySet()) + clean }
    }

    fun removedSourceIds(): Set<String> = _device.value.strSet(LiveTvKeys.removedSources) ?: emptySet()

    fun forgetRemovedSources(ids: Set<String>) {
        if (ids.isEmpty()) return
        editDevice { it[LiveTvKeys.removedSources] = (it.strSet(LiveTvKeys.removedSources) ?: emptySet()) - ids }
    }

    // ---------- calendar (synced with the rest of Live TV's settings) ----------

    // Each Nuvio profile has its own followed and hidden shows, the same as on the TV: the first profile
    // keeps the original keys; another profile sees the first profile's list until it changes its own.
    private fun mineOrShared(p: Map<String, PrefValue>, key: String, id: Int): String =
        (if (id <= 1) p.str(key) else (p.str(my(key, id)) ?: p.str(key))) ?: ""

    val calendarFollows: Flow<String> = combine(_data, _profile) { p, id -> mineOrShared(p, LiveTvKeys.calendarFollows, id) }.distinctUntilChanged()
    fun calendarFollowsNow(): String = mineOrShared(_data.value, LiveTvKeys.calendarFollows, currentProfile)
    fun editCalendarFollows(transform: (String) -> String) = edit {
        it[my(LiveTvKeys.calendarFollows)] = transform(mineOrShared(it, LiveTvKeys.calendarFollows, currentProfile))
    }

    val calendarHiddenShows: Flow<String> = combine(_data, _profile) { p, id -> mineOrShared(p, LiveTvKeys.calendarHiddenShows, id) }.distinctUntilChanged()
    fun calendarHiddenShowsNow(): String = mineOrShared(_data.value, LiveTvKeys.calendarHiddenShows, currentProfile)
    fun editCalendarHiddenShows(transform: (String) -> String) = edit {
        it[my(LiveTvKeys.calendarHiddenShows)] = transform(mineOrShared(it, LiveTvKeys.calendarHiddenShows, currentProfile))
    }

    val calendarChannelPicks: Flow<String> = _data.map { it.str(LiveTvKeys.calendarChannelPicks) ?: "" }.distinctUntilChanged()
    fun editCalendarChannelPicks(transform: (String) -> String) = edit { it[LiveTvKeys.calendarChannelPicks] = transform(it.str(LiveTvKeys.calendarChannelPicks) ?: "") }

    val calendarPlans: Flow<String> = _data.map { it.str(LiveTvKeys.calendarPlans) ?: "" }.distinctUntilChanged()
    fun editCalendarPlans(transform: (String) -> String) = edit { it[LiveTvKeys.calendarPlans] = transform(it.str(LiveTvKeys.calendarPlans) ?: "") }

    // ---------- settings ----------

    fun updateSettings(transform: (LiveTvSettings) -> LiveTvSettings) {
        edit { p -> writeSettings(p, transform(readSettings(p))) }
    }

    private fun writeSettings(p: MutableMap<String, PrefValue>, s: LiveTvSettings) {
        p[LiveTvKeys.showInSidebar] = s.showInSidebar
        p[LiveTvKeys.showNames] = s.showChannelNames
        p[LiveTvKeys.showNumbers] = s.showChannelNumbers
        p[LiveTvKeys.markPlaying] = s.markPlayingChannel
        p[LiveTvKeys.showLogos] = s.showChannelLogos
        p[LiveTvKeys.showPreview] = s.showPreview
        p[LiveTvKeys.showDetails] = s.showProgramDetails
        p[LiveTvKeys.showFavGroup] = s.showFavoritesGroup
        p[LiveTvKeys.showRecentGroup] = s.showRecentGroup
        p[LiveTvKeys.compactRows] = s.compactRows
        p[LiveTvKeys.showProgramTimes] = s.showProgramTimes
        p[LiveTvKeys.showEpisodeNumbers] = s.showEpisodeNumbers
        p[LiveTvKeys.guideSize] = s.guideSize
        p[LiveTvKeys.epgOffset] = s.epgOffsetMinutes
        p[LiveTvKeys.reverseZap] = s.reverseZap
        p[LiveTvKeys.zapMode] = s.zapMode.name
        p[LiveTvKeys.autoPlayLast] = s.autoPlayLastChannel
        p[LiveTvKeys.rememberGroup] = s.rememberLastGroup
        p[LiveTvKeys.fullscreenOnSelect] = s.openFullscreenOnSelect
        p[LiveTvKeys.bannerSeconds] = s.infoBannerSeconds
        p[LiveTvKeys.playlistRefresh] = s.playlistRefreshHours
        p[LiveTvKeys.epgRefresh] = s.epgRefreshHours
        p[LiveTvKeys.epgPast] = s.epgPastHours
        p[LiveTvKeys.epgFuture] = s.epgFutureDays
        p[LiveTvKeys.clock24] = s.use24HourClock
        p[LiveTvKeys.autoReconnect] = s.autoReconnect
        p[LiveTvKeys.autoBackupChannel] = s.autoBackupChannel
        p[LiveTvKeys.catchupCache] = s.catchupCache
        p[LiveTvKeys.channelSort] = s.channelSort.name
        p[LiveTvKeys.solidHighlight] = s.solidHighlight
        p[LiveTvKeys.showInSearch] = s.showInSearch
        p[LiveTvKeys.guideSearchLive] = s.guideSearchLive
        p[LiveTvKeys.guideSearchCatalog] = s.guideSearchCatalog
        p[LiveTvKeys.guideSearchOnDemand] = s.guideSearchOnDemand
        p[LiveTvKeys.vodSearchLive] = s.onDemandSearchLive
        p[LiveTvKeys.vodSearchCatalog] = s.onDemandSearchCatalog
        p[LiveTvKeys.vodSearchOnDemand] = s.onDemandSearchOnDemand
        p[LiveTvKeys.showAllGroup] = s.showAllChannelsGroup
        p[LiveTvKeys.resumeInPreview] = s.resumeLastInPreview
        p[LiveTvKeys.showGroupCounts] = s.showGroupCounts
        p[LiveTvKeys.smallHeader] = s.smallHeader
        p[LiveTvKeys.aspectMode] = s.aspectMode
        p[LiveTvKeys.sequentialNumbers] = s.sequentialNumbers
        p[LiveTvKeys.overlayMode] = s.overlayMode
        p[LiveTvKeys.startOnLiveTv] = s.startOnLiveTv
        p[LiveTvKeys.startPage] = s.startPage
        p[LiveTvKeys.openOn] = s.openOn
        p[LiveTvKeys.launcherCardShape] = s.launcherCardShape
        p[LiveTvKeys.logoSourcesOff] = s.logoSourcesOff
        p[LiveTvKeys.catchupPreferHls] = s.catchupPreferHls
        p[LiveTvKeys.phoneLogos] = s.phoneChannelLogos
        p[LiveTvKeys.phoneNames] = s.phoneChannelNames
        p[LiveTvKeys.phoneNumbers] = s.phoneChannelNumbers
        p[LiveTvKeys.catchupShift] = s.catchupShiftMinutes
        p[LiveTvKeys.parentalPin] = s.parentalPin
        p[LiveTvKeys.lockAdultContent] = s.lockAdultContent
        p[LiveTvKeys.showQualityBadges] = s.showQualityBadges
        p[LiveTvKeys.showFrameRate] = s.showFrameRate
        p[LiveTvKeys.frameRateMode] = s.frameRateMode
        p[LiveTvKeys.matchFrameRate] = s.matchFrameRate
        p[LiveTvKeys.onDemandInSidebar] = s.onDemandInSidebar
        p[LiveTvKeys.onDemandEnabled] = s.onDemandEnabled
        p[LiveTvKeys.recordingsInSidebar] = s.recordingsInSidebar
        p[LiveTvKeys.sideMenuOrder] = s.sideMenuOrder
        p[LiveTvKeys.sideMenuHidden] = s.sideMenuHidden
        p[LiveTvKeys.calendarInMenu] = s.calendarInMenu
        p[LiveTvKeys.calendarUpcomingRow] = s.calendarUpcomingRow
        p[LiveTvKeys.calendarNewEpisodeCards] = s.calendarNewEpisodeCards
        p[LiveTvKeys.calendarHideSpoilers] = s.calendarHideSpoilers
        p[LiveTvKeys.calendarCountry] = s.calendarCountry
        p[LiveTvKeys.calendarSkipNewsTalk] = s.calendarSkipNewsTalk
        p[LiveTvKeys.calendarView] = s.calendarView
        p[LiveTvKeys.calendarOkAction] = s.calendarOkAction
        p[LiveTvKeys.calendarHideWatched] = s.calendarHideWatched
        p[LiveTvKeys.calendarSports] = s.calendarSports
        p[LiveTvKeys.onDemandInStreams] = s.onDemandInStreams
        p[LiveTvKeys.onDemandStreamsPosition] = s.onDemandStreamsPosition
        p[LiveTvKeys.onDemandAutoPlay] = s.onDemandAutoPlay
        p[LiveTvKeys.searchOrder] = s.searchOrder
        p[LiveTvKeys.searchPreview] = s.searchPreview
        p[LiveTvKeys.onDemandAddonPosters] = s.onDemandAddonPosters
        p[LiveTvKeys.groupPlaylistHeadings] = s.groupPlaylistHeadings
        p[LiveTvKeys.nameRemovals] = s.nameRemovals
        p[LiveTvKeys.developerTools] = s.developerTools
        p[LiveTvKeys.browseByChannelName] = s.browseByChannelName
        p[LiveTvKeys.showPlaylistInInfo] = s.showPlaylistInInfo
        p[LiveTvKeys.showNowLine] = s.showNowLine
        p[LiveTvKeys.audioPassthrough] = s.audioPassthrough
        p[LiveTvKeys.showPosters] = s.showPosters
        p[LiveTvKeys.vodMergeDuplicates] = s.vodMergeDuplicates
        p[LiveTvKeys.vodDefaultSort] = s.vodDefaultSort
        p[LiveTvKeys.recordingStorage] = s.recordingStorage
        p[LiveTvKeys.recordingSpaceRule] = s.recordingSpaceRule
        p[LiveTvKeys.recordPre] = s.recordPreMinutes
        p[LiveTvKeys.recordPost] = s.recordPostMinutes
        p[LiveTvKeys.recordSeriesKeep] = s.recordSeriesKeep
        p[LiveTvKeys.homeRowEnabled] = s.homeRowEnabled
        p[LiveTvKeys.homeRowHiddenProfiles] = s.homeRowHiddenProfiles.map { it.toString() }.toSet()
        p[LiveTvKeys.homeRowAboveContinueWatching] = s.homeRowAboveContinueWatching
        p[LiveTvKeys.homeRowSource] = s.homeRowSource
        p[LiveTvKeys.homeRowTitle] = s.homeRowTitle
        p[LiveTvKeys.homeRowSort] = s.homeRowSort
        p[LiveTvKeys.homeRowLimit] = s.homeRowLimit
        p[LiveTvKeys.homeRowHidePlaceholders] = s.homeRowHidePlaceholders
        p[LiveTvKeys.homeRowOpensGuide] = s.homeRowOpensGuide
        p[LiveTvKeys.homeRowShowNext] = s.homeRowShowNext
        p[LiveTvKeys.homeRowCompact] = s.homeRowCompact
        p[LiveTvKeys.bufferSize] = s.bufferSize
        p[LiveTvKeys.groupSort] = s.groupSort
        p[LiveTvKeys.timeshiftEnabled] = s.timeshiftEnabled
        p[LiveTvKeys.timeshiftMinutes] = s.timeshiftMinutes
        p[LiveTvKeys.rewindAlways] = s.rewindAlways
        p[LiveTvKeys.captionsByDefault] = s.captionsByDefault
        p[LiveTvKeys.clockMode] = s.clockMode
        p[LiveTvKeys.clockPosition] = s.clockPosition
        p[LiveTvKeys.clockSize] = s.clockSize
        p[LiveTvKeys.clockOpacity] = s.clockOpacity
        p[LiveTvKeys.clockBackground] = s.clockBackground
        p[LiveTvKeys.panelBackground] = s.panelBackground
        p[LiveTvKeys.guideInfoBackground] = s.guideInfoBackground
        p[LiveTvKeys.guideBackdrop] = s.guideBackdrop
        p[LiveTvKeys.recentInPanel] = s.recentInPanel
        p[LiveTvKeys.panelRow] = s.panelRow
        p[LiveTvKeys.channelSurf] = s.channelSurf
        p[LiveTvKeys.barHidden] = s.barHidden
        p[LiveTvKeys.guideInfoHidden] = s.guideInfoHidden
        p[LiveTvKeys.launcherRows] = s.launcherRows
    }

    // ---------- channel management ----------

    private fun editStringMap(key: String, block: (MutableMap<String, String>) -> Unit) = edit { p ->
        val m = LiveTvCodec.decodeStringMap(p.str(key)).toMutableMap()
        block(m)
        p[key] = LiveTvCodec.encodeStringMap(m)
    }

    fun setChannelName(key: String, name: String?) = editStringMap(LiveTvKeys.channelNames) { m ->
        if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim()
    }

    /** The logo picked for a channel ("Change Logo"); null puts its own back. */
    fun setChannelLogo(key: String, url: String?) = editStringMap(LiveTvKeys.channelLogos) { m ->
        if (url.isNullOrBlank()) m.remove(key) else m[key] = url.trim()
    }

    fun setChannelNumber(key: String, number: Int?) = editStringMap(LiveTvKeys.channelNumbers) { m ->
        if (number == null) m.remove(key) else m[key] = number.toString()
    }

    fun setGroupName(groupId: String, name: String?) {
        if (groupId.startsWith(ChannelGroup.CUSTOM_PREFIX)) {
            updateCustomGroups { list -> list.map { if (it.id == groupId && !name.isNullOrBlank()) it.copy(name = name.trim()) else it } }
            return
        }
        editStringMap(LiveTvKeys.groupNames) { m -> if (name.isNullOrBlank()) m.remove(groupId) else m[groupId] = name.trim() }
    }

    /** Moves [groupId] within [currentOrder] (the order currently shown) and saves the result. */
    fun moveGroup(groupId: String, delta: Int, currentOrder: List<String>) {
        val order = currentOrder.toMutableList()
        val i = order.indexOf(groupId)
        if (i < 0) return
        order.removeAt(i)
        order.add((i + delta).coerceIn(0, order.size), groupId)
        edit { it[my(LiveTvKeys.groupOrder)] = LiveTvCodec.encodeStringList(order) }
    }

    /** Saves the whole group order at once (drag to reorder): "my order" from then on. */
    fun setGroupOrder(ids: List<String>) = edit { p ->
        p[my(LiveTvKeys.groupOrder)] = LiveTvCodec.encodeStringList(ids)
        if (p.str(LiveTvKeys.groupSort) != "custom") p[LiveTvKeys.groupSort] = "custom"
    }

    fun createCustomGroup(name: String): String {
        val id = ChannelGroup.CUSTOM_PREFIX + randomId(8)
        updateCustomGroups { it + CustomGroup(id, name.trim().ifBlank { "My group" }) }
        return id
    }

    fun deleteCustomGroup(groupId: String) = updateCustomGroups { list -> list.filterNot { it.id == groupId } }

    fun addToCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId && key !in it.channelKeys) it.copy(channelKeys = it.channelKeys + key) else it }
    }

    fun removeFromCustomGroup(groupId: String, key: String) = updateCustomGroups { list ->
        list.map { if (it.id == groupId) it.copy(channelKeys = it.channelKeys - key) else it }
    }

    fun moveInCustomGroup(groupId: String, key: String, delta: Int) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) return@map g
            val keys = g.channelKeys.toMutableList()
            val i = keys.indexOf(key)
            if (i < 0) return@map g
            keys.removeAt(i)
            keys.add((i + delta).coerceIn(0, keys.size), key)
            g.copy(channelKeys = keys)
        }
    }

    fun setEpgOverride(key: String, assignment: EpgAssignment?) = editStringMap(LiveTvKeys.epgOverrides) { m ->
        if (assignment == null) m.remove(key) else m[key] = assignment.sourceId + LiveTvKeys.EPG_SEP + assignment.xmltvId
    }

    fun clearEpgOverrides() = edit { it[LiveTvKeys.epgOverrides] = LiveTvCodec.encodeStringMap(emptyMap()) }

    /** Picture shape chosen per channel (channels left on Auto aren't listed). */
    val pictureShapes: Flow<Map<String, String>> = _data.map { LiveTvCodec.decodeStringMap(it.str(LiveTvKeys.pictureShapes)) }

    /** [shapeName] "AUTO" forgets the channel's pick. */
    fun setPictureShape(key: String, shapeName: String) = editStringMap(LiveTvKeys.pictureShapes) { m ->
        if (shapeName == "AUTO") m.remove(key) else m[key] = shapeName
    }

    // ---------- Sports ----------

    val sportsSettingsJson: Flow<String?> = _data.map { it.str(LiveTvKeys.sports) }.distinctUntilChanged()

    val sportsMineJson: Flow<String?> = combine(_data, _profile) { p, id -> profileView(p, id).str(LiveTvKeys.sportsMine) }
        .distinctUntilChanged()

    fun editSportsSettings(transform: (String?) -> String) = edit { p -> p[LiveTvKeys.sports] = transform(p.str(LiveTvKeys.sports)) }

    fun editSportsMine(transform: (String?) -> String) = edit { p ->
        val id = currentProfile
        p[my(LiveTvKeys.sportsMine, id)] = transform(profileView(p, id).str(LiveTvKeys.sportsMine))
    }

    // ---------- parental controls, reminders, quality, On Demand ----------

    fun setGroupLocked(groupId: String, locked: Boolean) = edit { p ->
        val cur = p.strSet(LiveTvKeys.lockedGroups) ?: emptySet()
        p[LiveTvKeys.lockedGroups] = if (locked) cur + groupId else cur - groupId
    }

    fun addReminder(r: Reminder) = edit { p ->
        val cur = LiveTvCodec.decodeReminders(p.str(my(LiveTvKeys.reminders))).filterNot { it.channelKey == r.channelKey && it.startMs == r.startMs }
        p[my(LiveTvKeys.reminders)] = LiveTvCodec.encodeReminders((cur + r).sortedBy { it.startMs })
    }

    fun removeReminder(channelKey: String, startMs: Long) = edit { p ->
        p[my(LiveTvKeys.reminders)] = LiveTvCodec.encodeReminders(
            LiveTvCodec.decodeReminders(p.str(my(LiveTvKeys.reminders))).filterNot { it.channelKey == channelKey && it.startMs == startMs }
        )
    }

    /** Minutes before a show its reminder rings (0 = at the start). */
    val reminderLeadMinutes: StateFlow<Int> = _data.map { it.int(LiveTvKeys.reminderLead) ?: DEFAULT_REMINDER_LEAD }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, DEFAULT_REMINDER_LEAD)

    fun setReminderLeadMinutes(minutes: Int) = edit { it[LiveTvKeys.reminderLead] = minutes.coerceIn(0, 120) }

    /** Whether live channels and catch-up replays show in Home's Continue Watching (off by default). */
    val showInContinueWatching: StateFlow<Boolean> = _data.map { it.bool(LiveTvKeys.continueWatchingLive) ?: false }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    fun setShowInContinueWatching(on: Boolean) = edit { it[LiveTvKeys.continueWatchingLive] = on }

    /** Drops reminders for shows that started more than [graceMs] ago. */
    fun pruneReminders(now: Long, graceMs: Long = 30 * 60_000L) = edit { p ->
        val cur = LiveTvCodec.decodeReminders(p.str(my(LiveTvKeys.reminders)))
        val kept = cur.filter { it.startMs + graceMs > now }
        if (kept.size != cur.size) p[my(LiveTvKeys.reminders)] = LiveTvCodec.encodeReminders(kept)
    }

    /** The audio track picked on a channel, used every time it plays (null forgets it). */
    fun setChannelAudio(key: String, audio: String?) = editStringMap(LiveTvKeys.channelAudio) { m ->
        if (audio == null) m.remove(key) else m[key] = audio
    }

    fun setChannelQuality(key: String, quality: String) {
        // Older versions kept these in the main settings: move them over once.
        val legacy = _data.value.str(LiveTvKeys.channelQuality)
        if (legacy != null) edit { it.remove(LiveTvKeys.channelQuality) }
        editDevice { p ->
            val m = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.channelQuality) ?: legacy).toMutableMap()
            if (m[key] == quality && p[LiveTvKeys.channelQuality] != null) return@editDevice
            m[key] = quality
            p[LiveTvKeys.channelQuality] = LiveTvCodec.encodeStringMap(m)
        }
    }

    /** On Demand: one category's sort (null = back to the default sort). */
    fun setVodSort(uid: String, sort: String?) = edit { p ->
        val m = LiveTvCodec.decodeStringMap(profileView(p, currentProfile).str(LiveTvKeys.vodSorts)).toMutableMap()
        if (sort == null) m.remove(uid) else m[uid] = sort
        p[my(LiveTvKeys.vodSorts)] = LiveTvCodec.encodeStringMap(m)
    }

    /** On Demand: every category's sort at once (Manage VOD Groups → Done). */
    fun setVodSorts(sorts: Map<String, String>) = edit { p -> p[my(LiveTvKeys.vodSorts)] = LiveTvCodec.encodeStringMap(sorts) }

    /** Manage VOD Groups: which groups show, their order and names, saved together (Done). */
    fun saveVodGroups(hidden: Set<String>, order: List<String>, names: Map<String, String>) = edit { p ->
        p[my(LiveTvKeys.vodHiddenCategories)] = hidden
        p[my(LiveTvKeys.vodGroupOrder)] = LiveTvCodec.encodeStringList(order)
        p[LiveTvKeys.vodGroupNames] = LiveTvCodec.encodeStringMap(names.filterValues { it.isNotBlank() })
    }

    fun updateVodHiddenCategories(show: Set<String>, hide: Set<String>) = edit { p ->
        val cur = p.strSet(my(LiveTvKeys.vodHiddenCategories)) ?: emptySet()
        p[my(LiveTvKeys.vodHiddenCategories)] = (cur - show) + hide
    }

    fun clearVodHiddenCategories() = edit { it[my(LiveTvKeys.vodHiddenCategories)] = emptySet<String>() }

    /** Account details per playlist (expiry, connections), as the provider last reported them. */
    val playlistAccounts: StateFlow<Map<String, PlaylistAccount>> = _device
        .map { LiveTvCodec.decodeAccounts(it.str(LiveTvKeys.playlistAccounts)) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun setPlaylistAccount(playlistId: String, a: PlaylistAccount?) = editDevice { p ->
        val m = LiveTvCodec.decodeAccounts(p.str(LiveTvKeys.playlistAccounts)).toMutableMap()
        if (a == null) m.remove(playlistId) else m[playlistId] = a
        p[LiveTvKeys.playlistAccounts] = LiveTvCodec.encodeAccounts(m)
    }

    /** When each provider's On Demand catalog was last imported (per device, not synced). */
    fun onDemandImportTimes(): Map<String, Long> =
        LiveTvCodec.decodeStringMap(_device.value.str(LiveTvKeys.onDemandImports) ?: _data.value.str(LiveTvKeys.onDemandImports))
            .mapValues { it.value.toLongOrNull() ?: 0L }

    fun setOnDemandImportTime(playlistId: String, time: Long) {
        val legacy = _data.value.str(LiveTvKeys.onDemandImports)
        if (legacy != null) edit { it.remove(LiveTvKeys.onDemandImports) }
        editDevice { p ->
            val m = LiveTvCodec.decodeStringMap(p.str(LiveTvKeys.onDemandImports) ?: legacy).toMutableMap()
            m[playlistId] = time.toString()
            p[LiveTvKeys.onDemandImports] = LiveTvCodec.encodeStringMap(m)
        }
    }

    // ---------- ordering and copies ----------

    /** Saves the order of a playlist group or All channels (Reorder channels). */
    fun setChannelOrder(groupId: String, keys: List<String>) = edit { p ->
        val m = LiveTvCodec.decodeListMap(p.str(my(LiveTvKeys.channelOrder))).toMutableMap()
        m[groupId] = keys
        p[my(LiveTvKeys.channelOrder)] = LiveTvCodec.encodeListMap(m)
    }

    fun setFavoritesOrder(keys: List<String>) = edit { p ->
        val cur = LiveTvCodec.decodeStringList(p.str(my(LiveTvKeys.favorites)))
        // Keep any favorites that weren't in the list (e.g. hidden ones) at the end.
        p[my(LiveTvKeys.favorites)] = LiveTvCodec.encodeStringList(keys.filter { it in cur } + cur.filter { it !in keys })
    }

    fun setCustomGroupOrder(groupId: String, keys: List<String>) = updateCustomGroups { list ->
        list.map { g ->
            if (g.id != groupId) g
            else g.copy(channelKeys = keys.filter { it in g.channelKeys } + g.channelKeys.filter { it !in keys })
        }
    }

    /** Copies a channel into another playlist group (it stays in its own group too). */
    fun copyChannel(groupId: String, key: String) = edit { p ->
        val m = LiveTvCodec.decodeListMap(p.str(my(LiveTvKeys.channelCopies))).toMutableMap()
        val cur = m[groupId].orEmpty()
        if (key !in cur) m[groupId] = cur + key
        p[my(LiveTvKeys.channelCopies)] = LiveTvCodec.encodeListMap(m)
    }

    fun removeChannelCopy(groupId: String, key: String) = edit { p ->
        val m = LiveTvCodec.decodeListMap(p.str(my(LiveTvKeys.channelCopies))).toMutableMap()
        m[groupId] = m[groupId].orEmpty() - key
        if (m[groupId].isNullOrEmpty()) m.remove(groupId)
        p[my(LiveTvKeys.channelCopies)] = LiveTvCodec.encodeListMap(m)
    }

    fun resetChannelOrder() = edit { it[my(LiveTvKeys.channelOrder)] = LiveTvCodec.encodeListMap(emptyMap()) }

    fun clearChannelCopies() = edit { it[my(LiveTvKeys.channelCopies)] = LiveTvCodec.encodeListMap(emptyMap()) }

    fun resetChannelEdits() = edit {
        it[LiveTvKeys.channelNames] = LiveTvCodec.encodeStringMap(emptyMap())
        it[LiveTvKeys.channelNumbers] = LiveTvCodec.encodeStringMap(emptyMap())
    }

    fun resetGroupEdits() = edit {
        it[LiveTvKeys.groupNames] = LiveTvCodec.encodeStringMap(emptyMap())
        it[my(LiveTvKeys.groupOrder)] = LiveTvCodec.encodeStringList(emptyList())
    }

    private fun updateCustomGroups(transform: (List<CustomGroup>) -> List<CustomGroup>) = edit { p ->
        p[my(LiveTvKeys.customGroups)] = LiveTvCodec.encodeCustomGroups(transform(LiveTvCodec.decodeCustomGroups(p.str(my(LiveTvKeys.customGroups)))))
    }

    // ---------- per-channel state ----------

    fun setChannelHidden(key: String, hidden: Boolean) = edit { p ->
        val cur = p.strSet(my(LiveTvKeys.hiddenChannels)) ?: emptySet()
        p[my(LiveTvKeys.hiddenChannels)] = if (hidden) cur + key else cur - key
    }

    /** Applies a batch of group show/hide changes at once (Manage visibility for groups). */
    fun updateHiddenGroups(show: Set<String>, hide: Set<String>) = edit { p ->
        val cur = p.strSet(my(LiveTvKeys.hiddenGroups)) ?: emptySet()
        p[my(LiveTvKeys.hiddenGroups)] = (cur - show) + hide
    }

    /** Applies a batch of show/hide changes at once (Manage visibility). */
    fun updateHiddenChannels(show: Set<String>, hide: Set<String>) = edit { p ->
        val cur = p.strSet(my(LiveTvKeys.hiddenChannels)) ?: emptySet()
        p[my(LiveTvKeys.hiddenChannels)] = (cur - show) + hide
    }

    fun clearHiddenChannels() = edit { it[my(LiveTvKeys.hiddenChannels)] = emptySet<String>() }

    fun setGroupHidden(groupId: String, hidden: Boolean) = edit { p ->
        val cur = p.strSet(my(LiveTvKeys.hiddenGroups)) ?: emptySet()
        p[my(LiveTvKeys.hiddenGroups)] = if (hidden) cur + groupId else cur - groupId
    }

    fun clearHiddenGroups() = edit { it[my(LiveTvKeys.hiddenGroups)] = emptySet<String>() }

    fun toggleFavorite(key: String) = edit { p ->
        val cur = LiveTvCodec.decodeStringList(p.str(my(LiveTvKeys.favorites)))
        p[my(LiveTvKeys.favorites)] = LiveTvCodec.encodeStringList(if (key in cur) cur - key else cur + key)
    }

    /**
     * Pick favorites (several at once): of [inList], exactly [chosen] are favorites afterwards.
     * One save; favorites outside [inList] keep their place, new ones go at the end in list order.
     */
    fun setFavoritesIn(inList: List<String>, chosen: Set<String>) = edit { p ->
        val cur = LiveTvCodec.decodeStringList(p.str(my(LiveTvKeys.favorites)))
        val inSet = inList.toHashSet()
        val kept = cur.filter { it !in inSet || it in chosen }
        val keptSet = kept.toHashSet()
        p[my(LiveTvKeys.favorites)] = LiveTvCodec.encodeStringList(kept + inList.filter { it in chosen && it !in keptSet }.distinct())
    }

    fun moveFavorite(key: String, delta: Int) = edit { p ->
        val cur = LiveTvCodec.decodeStringList(p.str(my(LiveTvKeys.favorites))).toMutableList()
        val idx = cur.indexOf(key)
        if (idx < 0) return@edit
        val target = (idx + delta).coerceIn(0, cur.lastIndex)
        cur.removeAt(idx)
        cur.add(target, key)
        p[my(LiveTvKeys.favorites)] = LiveTvCodec.encodeStringList(cur)
    }

    fun clearFavorites() = edit { it[my(LiveTvKeys.favorites)] = LiveTvCodec.encodeStringList(emptyList()) }

    fun clearRecent() = edit { it[my(LiveTvKeys.recent)] = LiveTvCodec.encodeStringList(emptyList()) }

    /** [addToRecent] false: adult channels, which never go in Recently watched. */
    fun recordWatched(key: String, addToRecent: Boolean = true) = edit { p ->
        val last = p.str(my(LiveTvKeys.lastChannel))
        if (last != null && last != key) p[my(LiveTvKeys.previousChannel)] = last
        p[my(LiveTvKeys.lastChannel)] = key
        if (addToRecent) {
            val recent = LiveTvCodec.decodeStringList(p.str(my(LiveTvKeys.recent))).filter { it != key }
            p[my(LiveTvKeys.recent)] = LiveTvCodec.encodeStringList((listOf(key) + recent).take(30))
        }
    }

    fun setLastGroup(groupId: String) = edit { it[my(LiveTvKeys.lastGroup)] = groupId }

    // ---------- sync ----------

    /** Fires whenever anything in Live TV's stored data changes. */
    val changes: Flow<Unit> = _data.map { }

    private fun randomId(length: Int): String {
        val alphabet = "0123456789abcdef"
        return buildString(length) { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }
}

/** The app's Live TV preferences. */
internal object LiveTvPreferences : LiveTvPreferencesStore() {
    override val usesPlatformVpnBackup: Boolean get() = true
}

internal const val DEFAULT_REMINDER_LEAD = 1

/** One profile's hidden channels and groups, and the channels it shows elsewhere anyway. */
data class HiddenView(val channels: Set<String>, val groups: Set<String>, val kept: Set<String> = emptySet())

/** Each profile's hidden channels and hidden groups (see [LiveTvPreferencesStore.hiddenEverywhere]). */
data class HiddenEverywhere(private val views: List<HiddenView>) {
    /**
     * True when no profile shows this channel (hidden itself, or its group is). A channel in
     * Favorites, in a group you made, or copied into another group is shown: its listings are
     * kept even when its own group is hidden.
     */
    fun hides(channelKey: String, groupId: String): Boolean =
        views.isNotEmpty() && views.all { v ->
            channelKey !in v.kept && (channelKey in v.channels || groupId in v.groups)
        }
}
