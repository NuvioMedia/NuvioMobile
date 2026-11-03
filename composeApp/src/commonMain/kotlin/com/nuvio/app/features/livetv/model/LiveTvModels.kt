package com.nuvio.app.features.livetv.model

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlin.concurrent.Volatile

/**
 * A user-added M3U playlist. Xtream Codes logins are stored as a playlist too; the
 * URL is built from the server, username and password when the source is refreshed.
 */
data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val userAgent: String = "",
    /** Also load the EPG URL advertised in the playlist header (url-tvg / x-tvg-url). */
    val useEmbeddedEpg: Boolean = true,
    val xtreamServer: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val channelCount: Int = 0,
    /** Xtream: also import the provider's movies and series (On Demand). */
    val importVod: Boolean = true,
    /** Xtream: import the live TV channels (off = On Demand only). */
    val importLive: Boolean = true,
    /** Xtream live stream format: "auto" (what the provider allows), "ts" or "m3u8". */
    val streamFormat: String = "auto",
    /** Xtream: the server's time zone (from its account info), used for catch-up times. */
    val serverTimezone: String = "",
    /**
     * Xtream: when catch-up as HLS (.m3u8) last failed on this provider while TS worked (0: HLS
     * fine). For a week after, catch-up asks for TS straight away (saves a failed try on every
     * replay); then HLS is tried again, in case the provider has added it.
     */
    val catchupNoHlsSince: Long = 0L,
    /**
     * Where channel logos come from: "playlist" (the playlist's own, the guide's when the
     * playlist has none) or "epg" (the guide's, the playlist's when the guide has none).
     */
    val logoSource: String = "playlist"
) {
    val isXtream: Boolean get() = xtreamServer.isNotBlank()

    /** Whether this source's live channels (and their guide) are loaded. */
    val liveEnabled: Boolean get() = enabled && (!isXtream || importLive)

    fun resolvedUrl(): String {
        if (!isXtream) return tidyLink(url)
        return "${xtreamBase()}/get.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}&type=m3u_plus&output=ts"
    }

    fun xtreamEpgUrl(): String? {
        if (!isXtream) return null
        return "${xtreamBase()}/xmltv.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}"
    }

    fun xtreamApiUrl(): String =
        "${xtreamBase()}/player_api.php?username=${enc(xtreamUsername)}&password=${enc(xtreamPassword)}"

    /** Just scheme://host:port, even if a full link (get.php?..., player_api.php, /c/) was pasted. */
    fun xtreamBase(): String = cleanXtreamServer(xtreamServer)

    private fun enc(s: String) = s.trim().encodeURLParameter()

    companion object {
        /**
         * A typed or pasted link made safe: dashes that only look like "-" (the longer dashes a
         * phone keyboard or a chat app swaps in, the non-breaking hyphen) become a real hyphen,
         * and invisible characters and spaces that came along with a paste are dropped.
         */
        fun tidyLink(raw: String): String = raw
            .replace(Regex("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]"), "-")
            .replace(Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00A0\\s]"), "")

        fun cleanXtreamServer(raw: String): String {
            // Whatever dash-like character the keyboard typed (some show as blank on screen)
            // becomes a plain hyphen. Any other odd non-ASCII character in the server name too.
            val t = raw.trim()
            val schemeLen = Regex("^[a-zA-Z]+://").find(t)?.value?.length ?: 0
            val hostEnd = t.indexOf('/', schemeLen).let { if (it < 0) t.length else it }
            val hostPart = t.substring(schemeLen, hostEnd)
                .replace(Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00A0\\s]"), "")
                .replace(Regex("[^\\x00-\\x7F]"), "-")
            val fixed = t.substring(0, schemeLen) + hostPart + t.substring(hostEnd)
            var s = tidyLink(fixed)
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return runCatching {
                val u = Url(s)
                val host = u.host.ifBlank { return@runCatching s.trimEnd('/') }
                val port = if (u.specifiedPort > 0) ":${u.specifiedPort}" else ""
                "${u.protocol.name.lowercase()}://$host$port"
            }.getOrDefault(s.trimEnd('/'))
        }

        /** Username / password from a pasted link like ".../get.php?username=a&password=b". */
        fun credentialsFromLink(raw: String): Pair<String, String>? {
            val query = raw.substringAfter('?', "")
            if (query.isEmpty()) return null
            val params = query.split('&').associate {
                it.substringBefore('=').lowercase() to
                    runCatching { it.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true) }
                        .getOrDefault(it.substringAfter('=', ""))
            }
            val user = params["username"].orEmpty()
            val pass = params["password"].orEmpty()
            return if (user.isNotBlank() && pass.isNotBlank()) user to pass else null
        }
    }
}

/** A user-added XMLTV guide (plain or .gz). */
data class EpgSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
    val programCount: Int = 0
)

data class CatchupInfo(
    val type: String,
    val source: String?,
    val days: Int,
    /** "catchup-correction" from the playlist: hours to shift catch-up times by (TiviMate style). */
    val correctionHours: Double = 0.0
)

data class LiveChannel(
    /** Stable key used for favorites, hidden channels and last-channel memory. */
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    /** Group id (unique across playlists) and its display title. */
    val groupId: String,
    val group: String,
    val number: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val catchup: CatchupInfo? = null,
    /** Protected (DRM) stream details from the playlist, e.g. MPEG-DASH with a license. */
    val drm: DrmInfo? = null
)

/**
 * DRM for a protected stream, as playlists describe it (Kodi / TiviMate "#KODIPROP" lines):
 * [scheme] is "clearkey", "widevine" or "playready"; [license] is a license-server URL, or for
 * ClearKey the keys themselves ("kid:key" pairs or a JSON key set).
 */
data class DrmInfo(
    val scheme: String,
    val license: String,
    val licenseHeaders: Map<String, String> = emptyMap(),
    /** "mpd", "hls"… when the playlist says what kind of stream it is. */
    val manifestType: String? = null
)

/** One show in the guide (an XMLTV <programme>). */
data class EpgProgram(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val episode: String? = null,
    val icon: String? = null,
    /** Release year from the guide (XMLTV <date>, or "(1990)" in the title/description). */
    val year: Int? = null,
    /** Actors and directors from the guide's <credits>, when it has them. */
    val people: List<String> = emptyList()
) {
    fun isLive(nowMs: Long): Boolean = nowMs in startMs until stopMs
    fun progress(nowMs: Long): Float {
        val span = (stopMs - startMs).coerceAtLeast(1L)
        return ((nowMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

enum class ZapMode { GROUP, ALL }

enum class ChannelSort { PLAYLIST, NUMBER, NAME }

data class CustomGroup(
    val id: String,
    val name: String,
    val channelKeys: List<String> = emptyList()
)

/** A provider account's details (Xtream user_info), when the provider gives them. */
data class PlaylistAccount(
    /** When the account runs out (0: never, or not given). */
    val expiresAtMs: Long = 0L,
    val status: String = "",
    val maxConnections: Int = 0,
    val activeConnections: Int = 0,
    val checkedAtMs: Long = 0L
) {
    /** Whole days left (0 on the last day), or null with no expiry date. */
    fun daysLeft(now: Long = LiveTvClock.nowMs()): Long? =
        if (expiresAtMs > 0) ((expiresAtMs - now) / 86_400_000L).coerceAtLeast(0) else null

    /** Seven days or less to go. */
    fun expiringSoon(now: Long = LiveTvClock.nowMs()): Boolean = (daysLeft(now) ?: Long.MAX_VALUE) <= 7

    /** "expired", "today", "1 day" or "148 days"; null with no expiry date. */
    fun daysLeftLabel(now: Long = LiveTvClock.nowMs()): String? {
        val days = daysLeft(now) ?: return null
        return when {
            expiresAtMs <= now -> "expired"
            days == 0L -> "today"
            days == 1L -> "1 day"
            else -> "$days days"
        }
    }
}

/** The clock Live TV's data layer reads the time from. */
object LiveTvClock {
    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

data class LiveTvSettings(
    val showInSidebar: Boolean = true,
    val showChannelNames: Boolean = true,
    val showChannelNumbers: Boolean = true,
    /** A red outline around the channel you're watching, in the guide's channel list. */
    val markPlayingChannel: Boolean = true,
    val showChannelLogos: Boolean = true,
    val showPreview: Boolean = true,
    val showProgramDetails: Boolean = true,
    val showFavoritesGroup: Boolean = true,
    val showRecentGroup: Boolean = true,
    val compactRows: Boolean = false,
    /** Each show's start and end time under its title in the guide (taller rows). */
    val showProgramTimes: Boolean = false,
    /** Episode numbers ("S2 E5") in the guide, show info and the player's banner. */
    val showEpisodeNumbers: Boolean = true,
    /** Guide Size: COMPACT, STANDARD, LARGE or XLARGE (blank = from the old Slimmer Rows switch). */
    val guideSize: String = "",
    val epgOffsetMinutes: Int = 0,
    val reverseZap: Boolean = false,
    val zapMode: ZapMode = ZapMode.GROUP,
    val autoPlayLastChannel: Boolean = false,
    val rememberLastGroup: Boolean = true,
    val openFullscreenOnSelect: Boolean = false,
    val infoBannerSeconds: Int = 5,
    val playlistRefreshHours: Int = 24,
    val epgRefreshHours: Int = 12,
    val epgPastHours: Int = 24,
    val epgFutureDays: Int = 3,
    val use24HourClock: Boolean = false,
    val autoReconnect: Boolean = true,
    /** A channel that won't play: switch to another channel by the same name (from another group or playlist). */
    val autoBackupChannel: Boolean = true,
    /** Catch-up replays saved to the device while they play, for instant skipping. */
    val catchupCache: Boolean = true,
    val channelSort: ChannelSort = ChannelSort.PLAYLIST,
    /** Fill the focused cell with the accent color instead of outlining it. */
    val solidHighlight: Boolean = false,
    /** Show matching channels and programs in Nuvio's search. */
    val showInSearch: Boolean = true,
    /**
     * What search shows when opened from the guide, and from On Demand. Search opened from
     * anywhere else (Home, the side menu) always shows everything.
     */
    val guideSearchLive: Boolean = true,
    val guideSearchCatalog: Boolean = false,
    val guideSearchOnDemand: Boolean = false,
    val onDemandSearchLive: Boolean = false,
    val onDemandSearchCatalog: Boolean = false,
    val onDemandSearchOnDemand: Boolean = true,
    val showAllChannelsGroup: Boolean = true,
    /** When Live TV opens, start the last channel in the preview window and highlight it. */
    val resumeLastInPreview: Boolean = true,
    /** Show how many channels each group has in the guide's group list. */
    val showGroupCounts: Boolean = true,
    /** Smaller info panel and preview at the top of the guide, so more channels fit. */
    val smallHeader: Boolean = false,
    /** Full-screen picture size, using Nuvio's own modes (Fit, Crop, Stretch, Cinema Zoom, …). */
    val aspectMode: String = "ORIGINAL",
    /** Number channels 1, 2, 3… in the order they appear in each group (TiviMate's override). */
    val sequentialNumbers: Boolean = false,
    /** Left while watching opens overlay mode; off: Left returns to the guide's group list. */
    val overlayMode: Boolean = true,
    /** Open Live TV instead of Nuvio's home screen when the app starts (older setting). */
    val startOnLiveTv: Boolean = false,
    /** The page Nuvio opens on: HOME, LIVE_TV, ON_DEMAND, SEARCH, LIBRARY or DISCOVER. */
    val startPage: String = "",
    /**
     * The page the app opens on: HOME, LIVE_TV, ON_DEMAND or SPORTS. A new setting (not
     * [startPage], an older one that was taken out), so nobody starts somewhere they didn't pick.
     */
    val openOn: String = "HOME",
    /** The shape of the cards in the launcher's home-screen rows: WIDE, TALL or SQUARE. */
    val launcherCardShape: String = "WIDE",
    /** Change Logo's sources turned off, by key ("playlists", "guides", "tv-logos", "iptv-org", "tmdb"), comma-separated. */
    val logoSourcesOff: String = "",
    /** Xtream catch-up: ask for HLS (.m3u8) first, which gives replays a length for seeking. */
    val catchupPreferHls: Boolean = true,
    /**
     * What the phone shows for each channel (guide channel column, Live TV list). Kept on this
     * device only, so the TV's own channel display isn't changed. At least one stays on.
     */
    val phoneChannelLogos: Boolean = true,
    val phoneChannelNames: Boolean = true,
    val phoneChannelNumbers: Boolean = true,
    /**
     * Moves the time asked for in catch-up (and catch-up saves) later (+) or earlier (−), for
     * providers whose archive runs on a different clock than their guide: replays that start
     * the show before (or after) the one picked.
     */
    val catchupShiftMinutes: Int = 0,
    /** Parental controls: a 4-digit PIN; empty = parental controls off. */
    val parentalPin: String = "",
    /** With a PIN set, lock adult groups and categories automatically. */
    val lockAdultContent: Boolean = true,
    /** Small 4K / FHD / HD / SD badges next to channels you've watched. */
    val showQualityBadges: Boolean = true,
    /** The frame rate ("50 FPS") next to the picture size on the info bar. */
    val showFrameRate: Boolean = true,
    /**
     * Switch the display's refresh rate to match the video (auto frame rate), as Nuvio's own player
     * setting: "OFF", "START" (switch when playback starts) or "START_STOP" (and put it back after).
     */
    val frameRateMode: String = "OFF",
    /** Show On Demand in Nuvio's side menu (when there's something to show). */
    val onDemandInSidebar: Boolean = true,
    /** On Demand at all (TV: Menu & Features → On Demand → Off). Missing on older setups: on. */
    val onDemandEnabled: Boolean = true,
    /** Recordings in the side menu (once anything is recorded or set to record). */
    val recordingsInSidebar: Boolean = true,
    /**
     * The side menu's order (Settings → Menu & Features): page ids, comma separated ("search,library,
     * calendar,livetv,sports,ondemand,discover"). Home stays first and Settings last. Empty = the usual order.
     */
    val sideMenuOrder: String = "",
    /** Pages taken off the side menu (ids as above; Live TV, Sports and On Demand use their own switches). */
    val sideMenuHidden: String = "",
    /** Calendar in the side menu even before any show's bell is rung. */
    val calendarInMenu: Boolean = false,
    /** Calendar: your shows' next episodes in Home's Upcoming row. */
    val calendarUpcomingRow: Boolean = true,
    /** Calendar: a "new episode" card in Continue Watching for shows you rang the bell on. */
    val calendarNewEpisodeCards: Boolean = true,
    /** Calendar: hide titles and pictures of episodes you haven't reached yet. */
    val calendarHideSpoilers: Boolean = true,
    /** Calendar: the country for Airing Today and movie release dates (two letters). */
    val calendarCountry: String = "US",
    /** Calendar: Airing Today leaves out news, talk and sports shows. */
    val calendarSkipNewsTalk: Boolean = true,
    /** Calendar: games for the teams you follow in Sports. */
    val calendarSports: Boolean = true,
    /** Calendar: opens in "LIST" or "MONTH" view. */
    val calendarView: String = "LIST",
    /** OK on an episode that's in your Live TV guide: "LIVE" (Live TV options) or "PAGE" (the show page). */
    val calendarOkAction: String = "LIVE",
    /** Recently aired: only what you haven't watched. */
    val calendarHideWatched: Boolean = false,
    /** Offer "Watch On Demand" in Nuvio's stream list for movies and episodes. */
    val onDemandInStreams: Boolean = true,
    /** Where the On Demand tab sits in the stream list: FIRST, AFTER_FIRST (the first addon) or LAST. */
    val onDemandStreamsPosition: String = "LAST",
    /** Auto-play may pick your provider's copy (only when its tab is first). */
    val onDemandAutoPlay: Boolean = false,
    /** The order of Nuvio's search results: "live", "ondemand" and "catalog", comma separated. */
    val searchOrder: String = "live,ondemand,catalog",
    /** OK on a channel in search plays it behind the results first (OK again: full screen). */
    val searchPreview: Boolean = true,
    /** On Demand posters from your own addons (off = the provider's images only). */
    val onDemandAddonPosters: Boolean = true,
    /** Several playlists: list each playlist's groups under a foldable heading. */
    val groupPlaylistHeadings: Boolean = true,
    /** Channel name editor (TiviMate style): prefixes/suffixes to remove, comma-separated. */
    val nameRemovals: String = "",
    /** Hidden developer tools (Test poster lookup). Turned on with a secret word. */
    val developerTools: Boolean = false,
    /** Satellite-box style: picking a group lands on the channel names (OK plays, Right opens the schedule). */
    val browseByChannelName: Boolean = false,
    /** Show which playlist the highlighted channel comes from in the info panel. */
    val showPlaylistInInfo: Boolean = true,
    /** The vertical line marking the current time in the guide. */
    val showNowLine: Boolean = true,
    /** Send Dolby / DTS audio untouched to the TV or speaker (off: the app decodes it). */
    val audioPassthrough: Boolean = true,
    /** Show posters for what's on; off: just the channel logo (no poster lookups at all). */
    val showPosters: Boolean = true,
    /** On Demand: show each title once, even when the provider lists several copies. */
    val vodMergeDuplicates: Boolean = true,
    /** On Demand: how categories are sorted unless you picked one for that category (VodSort name). */
    val vodDefaultSort: String = "DEFAULT",
    /** Recordings: where new ones are saved ("" = this device, else a storage volume's id). */
    val recordingStorage: String = "",
    /** Recordings: when storage runs low, "STOP" (stop recording) or "DELETE_OLDEST". */
    val recordingSpaceRule: String = "STOP",
    /** Recordings start this many minutes before the show and keep going this many after. */
    val recordPreMinutes: Int = 2,
    val recordPostMinutes: Int = 5,
    /** Series rules made here keep only the newest this many episodes (0 = keep them all). */
    val recordSeriesKeep: Int = 0,
    // ---- Live TV row on Nuvio's home screen
    /** Show the Live TV row on the home screen at all. */
    val homeRowEnabled: Boolean = true,
    /** Nuvio profiles (by id) that have the Live TV home row turned off; on for every other profile. */
    val homeRowHiddenProfiles: Set<Int> = emptySet(),
    /** Above (true) or below (false) Continue watching. */
    val homeRowAboveContinueWatching: Boolean = false,
    /** What it shows: "favorites", "recent", or a group id (e.g. one of your own groups). */
    val homeRowSource: String = "favorites",
    /** Row title; empty = "Live TV". */
    val homeRowTitle: String = "",
    /** "yours" (as in the guide), "number" or "ending" (ending soonest first). */
    val homeRowSort: String = "yours",
    /** How many channels (0 = all). */
    val homeRowLimit: Int = 20,
    /** Leave out channels showing only "Programming" / no information. */
    val homeRowHidePlaceholders: Boolean = true,
    /** OK opens the guide on the channel instead of playing it full screen. */
    val homeRowOpensGuide: Boolean = false,
    /** A "Next: 9:00 The News" line on each card. */
    val homeRowShowNext: Boolean = false,
    /** Compact cards: logo and show name only. */
    val homeRowCompact: Boolean = false,
    /** Live TV buffer: "none" (the least the player can run on), "small" (fast channel changes), "normal", "large", "xlarge". */
    val bufferSize: String = "normal",
    /** Group list order: "custom" (yours, from Reorder groups), "playlist" or "name" (A–Z). */
    val groupSort: String = "custom",
    /** Pause and rewind live TV (channels without catch-up): record while paused. */
    val timeshiftEnabled: Boolean = true,
    /** How much to keep, in minutes. */
    val timeshiftMinutes: Int = 10,
    /** Rewind anytime: a rolling buffer of the channel you watch full screen (not only after pausing). */
    val rewindAlways: Boolean = false,
    /** Turn closed captions on automatically when a channel has them. */
    val captionsByDefault: Boolean = false,
    /** On-screen clock while watching: "always", "panel" (only with the info panel) or "never". */
    val clockMode: String = "panel",
    /** Corner for the always-on clock: "top_left", "top_right", "bottom_left", "bottom_right". */
    val clockPosition: String = "top_right",
    /** "small", "medium" or "large". */
    val clockSize: String = "medium",
    /** How solid the always-on clock is, in percent. */
    val clockOpacity: Int = 70,
    /** A soft dark pill behind the always-on clock. */
    val clockBackground: Boolean = true,
    /** How dark the info panel's background is: "standard", "darker" or "solid". */
    val panelBackground: String = "darker",
    /** How dark the info panel above the guide and the guide's bars are (the play bar has its own, [panelBackground]). */
    val guideInfoBackground: String = "darker",
    /** The backdrop behind the guide, Sports hub and On Demand: "auto" (match the theme, the default), "onyx", or an app icon color: "original", "arctic", "emerald", "rose", "copper", "graphite". */
    val guideBackdrop: String = "auto",
    /** The row of recently watched channels at the bottom of the full info panel (OK). */
    val recentInPanel: Boolean = true,
    /** The row at the bottom of the full info panel: "RECENT", "FAVORITES" or "OFF". */
    val panelRow: String = "RECENT",
    /** Channel surfing: Up / Down show other channels on the info bar; OK switches. */
    val channelSurf: Boolean = false,
    /** Parts of the full-screen bar switched off (⚙ → Play bar layout), comma separated. */
    val barHidden: String = "",
    /** Parts of the guide's info panel that are switched off (see GuideInfoPart), comma separated. */
    val guideInfoHidden: String = "",
    /** Rows on the launcher home screen: "sports", "favorites", "recent" (comma separated; empty = none). */
    val launcherRows: String = ""
)

data class LiveUserState(
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val favorites: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val lastChannelKey: String? = null,
    val previousChannelKey: String? = null,
    val lastGroupId: String? = null,
    val channelNames: Map<String, String> = emptyMap(),
    /** Channel key -> the logo picked with "Change Logo" (an address). */
    val channelLogos: Map<String, String> = emptyMap(),
    val channelNumbers: Map<String, Int> = emptyMap(),
    val groupNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val customGroups: List<CustomGroup> = emptyList(),
    /** Channel key -> EPG assigned by hand from the long-press menu. */
    val epgOverrides: Map<String, EpgAssignment> = emptyMap(),
    /** Group id -> channel keys in the order you set with "Reorder channels". */
    val channelOrder: Map<String, List<String>> = emptyMap(),
    /** Group id -> channels copied into it from other groups ("Copy channel"). */
    val channelCopies: Map<String, List<String>> = emptyMap(),
    /** Groups (and On Demand categories, prefixed "vod:") that need the parental PIN. */
    val lockedGroups: Set<String> = emptySet(),
    /** Shows you asked to be reminded about. */
    val reminders: List<Reminder> = emptyList(),
    /** Channel key -> picture quality seen when it last played ("4K", "FHD", "HD", "SD"). */
    val channelQuality: Map<String, String> = emptyMap(),
    /** Channel key -> the audio track you picked there ("language|label|channels"). */
    val channelAudio: Map<String, String> = emptyMap(),
    /** On Demand categories you hid ("movie:<playlist>:<category>" / "series:…"). */
    val vodHiddenCategories: Set<String> = emptySet(),
    /** On Demand page: your order of the provider's groups (category uids), and your names for them. */
    val vodGroupOrder: List<String> = emptyList(),
    val vodGroupNames: Map<String, String> = emptyMap(),
    /** On Demand: the sort you picked for a category (category uid -> VodSort name). */
    val vodSorts: Map<String, String> = emptyMap()
)

/** "Remind me" on an upcoming show. */
data class Reminder(
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long
)

/** A channel's guide, picked by hand: which EPG source, and which channel inside it. */
data class EpgAssignment(val sourceId: String, val xmltvId: String) {
    /** "Unassign" in Assign EPG: the channel gets no guide at all (not even an automatic match). */
    val isNone: Boolean get() = sourceId == NONE

    companion object {
        const val NONE = "__none__"
        val None = EpgAssignment(NONE, "none")
    }
}

/** One loaded EPG source and its channel list, for the "Change EPG" picker. */
data class EpgSourceChannels(
    val sourceId: String,
    val name: String,
    /** Short label shown under each channel in the picker, e.g. "github.com". */
    val label: String,
    val channels: List<EpgChannelEntry>,
    /** Guide channel ids already feeding a playlist channel (matched or assigned). */
    val usedIds: Set<String> = emptySet()
)

data class EpgChannelEntry(
    val id: String,
    val names: List<String>,
    val icon: String?
) {
    val displayName: String get() = names.firstOrNull() ?: id
}

/** A row in the TiviMate-style group panel. */
data class ChannelGroup(
    val id: String,
    val title: String,
    val count: Int,
    val special: Boolean = false,
    /** The playlist a playlist group comes from (null for special and your own groups). */
    val sourceId: String? = null,
    /** Needs the parental PIN (and its channels are kept out of other lists). */
    val locked: Boolean = false
) {
    companion object {
        const val ALL = "__all__"
        const val FAVORITES = "__fav__"
        const val RECENT = "__recent__"
        const val SEARCH = "__search__"
        const val CUSTOM_PREFIX = "custom:"
    }
}

/** The start page, including the older "Open Live TV when Nuvio starts" switch. */
val LiveTvSettings.effectiveStartPage: String
    get() = startPage.ifBlank { if (startOnLiveTv) "LIVE_TV" else "HOME" }

/** Guide Size, with the old "Slimmer Rows" switch standing in until a size is picked. */
val LiveTvSettings.effectiveGuideSize: String
    get() = guideSize.ifBlank { if (compactRows) "COMPACT" else "STANDARD" }

/** Match Frame Rate is on (either way). */
val LiveTvSettings.matchFrameRate: Boolean get() = frameRateMode != "OFF"

/**
 * Channel name editor, TiviMate style: removes each listed prefix or suffix (like "USA", "US:",
 * "24/7", "FHD") from the start or end of channel names, along with the separator next to it
 * (":", "|", "-", "/", brackets or spaces). Matching ignores case; the text must be a whole word
 * or symbol group, so "US" never cuts the start of "USA Network".
 */
object ChannelNameEditor {

    fun terms(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /** The prefix / suffix / suffix-check patterns for one term, built once and reused. */
    private class Compiled(val prefix: Regex, val suffix: Regex, val suffixCheck: Regex)

    @Volatile private var compiledFor: List<String> = emptyList()
    @Volatile private var compiled: List<Compiled> = emptyList()
    @Volatile private var lowerFor: List<String> = emptyList()
    @Volatile private var lowerTerms: List<String> = emptyList()

    private fun lowered(terms: List<String>): List<String> {
        if (terms == lowerFor) return lowerTerms
        val l = terms.map { it.lowercase() }
        lowerTerms = l
        lowerFor = terms
        return l
    }

    /**
     * Whether any term sits at the very start or end of the name (ignoring brackets and spaces),
     * the only places it can be removed from. Simply containing a term isn't enough: "us" is in
     * "Music" and "Discovery", "hd" and "sd" in plenty of words, which sent nearly every channel
     * through all the patterns and made channel lists with these terms slow to load.
     */
    private fun atEdge(lower: String, terms: List<String>): Boolean {
        // Providers wrap names in all sorts of symbols ("US ➤ ESPN", "★ UK | Sky", "|US| ESPN"),
        // so look past any leading or trailing symbols, not just brackets.
        val start = lower.trimStart { !it.isLetterOrDigit() }
        val end = lower.trimEnd { !it.isLetterOrDigit() }
        val rawStart = lower.trimStart()
        val rawEnd = lower.trimEnd()
        return terms.any { t ->
            start.startsWith(t) || end.endsWith(t) || rawStart.startsWith(t) || rawEnd.endsWith(t)
        }
    }

    private fun compile(terms: List<String>): List<Compiled> {
        if (terms == compiledFor) return compiled
        val list = terms.map { t ->
            val q = Regex.escape(t)
            val opts = setOf(RegexOption.IGNORE_CASE)
            // A plain ASCII word or number must stand alone ("HD" is not the end of "XHD", "4K"
            // is not the start of "4KIDS"). Tags written in special letters (ᴴᴰ) can be stuck on.
            val ascii = t.all { it.code < 128 && it.isLetterOrDigit() }
            val before = if (ascii) """(?<![\p{L}\p{N}])""" else ""
            val after = if (ascii) """(?![\p{L}\p{N}])""" else ""
            // Prefix: "USA: ESPN", "[USA] ESPN", "|US| ESPN", "USA - ESPN", "US ➤ ESPN". A plain
            // word needs a symbol after it (closing bracket, colon, bar, arrow...), so "USA"
            // never eats "USA Network".
            val prefix = if (t.all { it.isLetter() }) {
                Regex(
                    """^[^\p{L}\p{N}]*$q\s*[^\p{L}\p{N}\s(\[{<][^\p{L}\p{N}(\[{<]*""",
                    opts
                )
            } else {
                Regex("""^[^\p{L}\p{N}]*$q$after[^\p{L}\p{N}(\[{<]*""", opts)
            }
            // Suffix: "ESPN HD", "ESPN (US)", "ESPN | FHD", "ESPN 24/7", "ESPN ➤ US"
            Compiled(
                prefix,
                Regex("""[^\p{L}\p{N}+&)\]}]*$before$q[^\p{L}\p{N}]*$""", opts),
                Regex("""$before$q[^\p{L}\p{N}]*$""", opts)
            )
        }
        compiled = list
        compiledFor = terms
        return list
    }

    /**
     * Terms a playlist uses as a prefix with just a space after it ("USA ESPN", "USA CNN"): five
     * or more of its channels start that way. Then the space alone counts as the separator.
     * Otherwise it doesn't, so a lone "USA Network" keeps its name.
     */
    fun spacePrefixes(names: Collection<String>, terms: List<String>): Set<String> {
        val words = terms.filter { t -> t.all { it.isLetter() } }.map { it.lowercase() }
        if (words.isEmpty()) return emptySet()
        val counts = HashMap<String, Int>()
        for (n in names) {
            val l = n.trimStart { !it.isLetterOrDigit() }.lowercase()
            for (w in words) if (l.startsWith("$w ") && l.length > w.length + 1) counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.filterValues { it >= 5 }.keys
    }

    fun clean(name: String, terms: List<String>, spacePrefixes: Set<String> = emptySet()): String {
        if (terms.isEmpty()) return name
        var start = name.trim()
        // A playlist-wide "USA " prefix: off once ("USA USA Network" keeps "USA Network").
        if (spacePrefixes.isNotEmpty()) {
            val body = start.trimStart { !it.isLetterOrDigit() }
            val l = body.lowercase()
            spacePrefixes.firstOrNull { l.startsWith("$it ") }?.let { w ->
                val rest = body.substring(w.length).trim()
                if (rest.isNotBlank()) start = rest
            }
        }
        // Fast path: most names have none of the terms at either end, so skip the patterns.
        val lowerTerms = lowered(terms)
        if (!atEdge(start.lowercase(), lowerTerms)) return start
        val patterns = compile(terms)
        var out = start
        var changed = true
        var guard = 0
        while (changed && guard++ < 6) {
            // Nothing left at either end to remove: done.
            if (guard > 1 && !atEdge(out.lowercase(), lowerTerms)) break
            changed = false
            for (c in patterns) {
                val p = c.prefix.replace(out, "").trim()
                if (p != out && p.isNotBlank()) { out = p; changed = true }
                if (c.suffixCheck.containsMatchIn(out)) {
                    val sfx = c.suffix.replace(out, "").trim()
                    if (sfx != out && sfx.isNotBlank()) { out = sfx; changed = true }
                }
            }
        }
        return out
    }

    /** Common ones, for "Add common prefixes and suffixes". */
    const val COMMON = "USA, US, UK, CA, AU, 24/7, FHD, HD, SD, UHD, 4K, HEVC, RAW, VIP, ᴴᴰ, ᵁᴴᴰ, ᶠᴴᴰ"
}
