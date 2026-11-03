package com.nuvio.app.features.livetv.player

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.XtreamClient
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withTimeoutOrNull

/** One stream the player is told to open: the link, its headers and what kind of stream it is. */
internal data class LivePlayRequest(
    val url: String,
    val headers: Map<String, String>,
    /** "hls" for .m3u8 links, null for anything else (the player sniffs it). */
    val streamType: String?,
)

/** What to play instead when a live stream fails: another link for the same channel, or another channel. */
internal data class LiveFallback(
    val channel: LiveChannel,
    val request: LivePlayRequest,
    /** Set when it's a different channel ("ESPN Backup"), for a notice to the viewer. */
    val switchedFrom: LiveChannel? = null,
)

/**
 * Builds what the app's own player needs to play a channel, and decides what to try when a
 * channel won't play, the TV app's way:
 *  1. an Xtream HLS link that can't be read is played as TS once (and remembered for this run);
 *  2. then another channel with the same name from another group or playlist, when
 *     "Switch to a backup channel" is on, each tried once.
 * The audio track picked on a channel is kept by the player under the channel's key (the
 * launch's content id), so it's used again the next time that channel plays.
 */
internal object LiveTvPlayback {
    const val PROVIDER_ID = "livetv"
    const val CONTENT_TYPE = "livetv"

    private val lock = SynchronizedObject()

    /** Live channels whose HLS link couldn't be read: played as TS (until the app restarts). */
    private val tsInstead = HashSet<String>()

    /** Channels already tried in a row of backup switches (cleared when you pick a channel). */
    private val backupTried = HashSet<String>()

    /** The channel behind each source URL handed to the player (fallbacks included). */
    private val playing = HashMap<String, LiveChannel>()

    /** Channels by their "backup name", so a failed channel's stand-ins are found quickly. */
    private var backupIndex: Map<String, List<LiveChannel>> = emptyMap()
    private var backupIndexFor: List<LiveChannel> = emptyList()

    /** A catch-up replay the player was handed: the show, and the other links still to try. */
    private class CatchupContext(val channel: LiveChannel, val program: EpgProgram, val remaining: ArrayDeque<String>)

    /** The catch-up replay behind each source URL handed to the player (fallback links included). */
    private val catchups = HashMap<String, CatchupContext>()

    /** TS catch-up links being tried because the HLS one failed: URL -> playlist id. */
    private val hlsFailedOn = HashMap<String, String>()

    /** Playlists whose server time zone has been asked for (once per run). */
    private val zoneAsked = HashSet<String>()

    private val xtream: XtreamClient by lazy { XtreamClient() }

    /**
     * The Sports hub's answer to "this channel showing a live game won't play": the next channel
     * showing the same game (the failed channel's key, and the keys already tried).
     */
    var gameFailover: ((failedKey: String, tried: Set<String>) -> LiveChannel?)? = null

    /** The key of the channel last handed to the player (for the Sports hub's score on the info bar). */
    @kotlin.concurrent.Volatile
    var currentKey: String? = null
        private set

    /** The request for a channel, with its HLS→TS memory applied. */
    fun requestFor(channel: LiveChannel): LivePlayRequest {
        val url = synchronized(lock) { if (channel.key in tsInstead) tsLinkFor(channel.url) ?: channel.url else channel.url }
        return request(channel, url)
    }

    private fun request(channel: LiveChannel, url: String): LivePlayRequest {
        val headers = LinkedHashMap<String, String>()
        channel.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) headers[k] = v }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) headers["User-Agent"] = XtreamClient.DEFAULT_UA
        return LivePlayRequest(url = url, headers = headers, streamType = streamTypeOf(url, channel))
    }

    /** "hls" for .m3u8 links; null otherwise, so the player works the format out itself. */
    private fun streamTypeOf(url: String, channel: LiveChannel): String? {
        val base = url.substringBefore('?').lowercase()
        return when {
            base.endsWith(".m3u8") -> "hls"
            base.endsWith(".mpd") || channel.drm?.manifestType == "mpd" -> "dash"
            else -> null
        }
    }

    /**
     * The player launch for a channel, as the rest of the app opens streams. [profileId] is the
     * active profile. [nowTitle] is what's on, shown under the channel's name.
     */
    fun launchFor(channel: LiveChannel, profileId: Int, nowTitle: String? = null): PlayerLaunch {
        val req = requestFor(channel)
        synchronized(lock) {
            // A channel you picked yourself: the backup list starts afresh.
            backupTried.clear()
            playing[req.url] = channel
        }
        currentKey = channel.key
        return PlayerLaunch(
            profileId = profileId,
            title = channel.name,
            sourceUrl = req.url,
            sourceHeaders = req.headers,
            streamType = req.streamType,
            logo = channel.logo,
            poster = channel.logo,
            streamTitle = channel.name,
            streamSubtitle = nowTitle ?: channel.group,
            pauseDescription = nowTitle,
            providerName = channel.sourceName,
            providerAddonId = PROVIDER_ID,
            contentType = CONTENT_TYPE,
            videoId = channel.key,
            parentMetaId = channel.key,
            parentMetaType = CONTENT_TYPE,
        )
    }

    /** Puts the launch in the store and returns its id, for a PlayerRoute. */
    fun prepare(channel: LiveChannel, profileId: Int, nowTitle: String? = null): Long {
        // Adult channels never go in Recently watched.
        LiveTvPreferences.recordWatched(channel.key, addToRecent = !com.nuvio.app.features.livetv.parental.ParentalControls.isAdultChannel(channel))
        return PlayerLaunchStore.put(launchFor(channel, profileId, nowTitle))
    }

    /**
     * Changing channel inside the player (its channel panel or a swipe on the video): the same
     * bookkeeping as picking the channel from a list, minus the new player screen.
     */
    fun switchTo(channel: LiveChannel): LivePlayRequest {
        LiveTvPreferences.recordWatched(channel.key, addToRecent = !com.nuvio.app.features.livetv.parental.ParentalControls.isAdultChannel(channel))
        val req = requestFor(channel)
        synchronized(lock) {
            backupTried.clear()
            playing[req.url] = channel
        }
        currentKey = channel.key
        return req
    }

    /** Set while a Live TV channel is on screen: a swipe up (true) or down (false) on the video changes channel. */
    @kotlin.concurrent.Volatile
    var channelSwipe: ((next: Boolean) -> Unit)? = null

    /** Whether this provider id is Live TV's (the player asks before trying a fallback). */
    fun owns(providerAddonId: String?): Boolean = providerAddonId == PROVIDER_ID

    // ---------------------------------------------------------------- catch-up

    /**
     * Puts a catch-up replay of [program] on [channel] in the launch store (from [offsetMs] into
     * the show) and returns its id for a PlayerRoute, or null when the show can't be replayed.
     * An Xtream server whose time zone isn't known yet is asked for it first (once, a few
     * seconds at most), so the replay starts at the right time.
     */
    suspend fun prepareCatchup(channel: LiveChannel, program: EpgProgram, profileId: Int, offsetMs: Long = 0L): Long? {
        val prefs = LiveTvPreferences
        val stored = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId }
        val askZone = stored != null && stored.serverTimezone.isBlank() && XtreamClient.xtreamLink.containsMatchIn(channel.url) &&
            synchronized(lock) { zoneAsked.add(channel.sourceId) }
        var playlist = stored
        if (askZone && stored != null) {
            val userAgent = stored.userAgent
            val tz = runCatching { withTimeoutOrNull(4_000) { xtream.serverTimezoneFor(channel.url, userAgent) } }.getOrNull()
            if (tz != null) {
                prefs.updatePlaylists { l -> l.map { if (it.id == channel.sourceId) it.copy(serverTimezone = tz) else it } }
                playlist = stored.copy(serverTimezone = tz)
            }
        }
        val now = LiveTvClock.nowMs()
        val plan = LiveTvCatchup.plan(channel, program, prefs.currentSettings(), playlist, now, offsetMs) ?: return null
        val req = request(channel, plan.url)
        synchronized(lock) {
            backupTried.clear()
            playing[req.url] = channel
            catchups[req.url] = CatchupContext(channel, program, ArrayDeque(plan.fallbacks))
        }
        prefs.recordWatched(channel.key)
        val launch = PlayerLaunch(
            profileId = profileId,
            title = program.title,
            sourceUrl = req.url,
            sourceHeaders = req.headers,
            streamType = req.streamType,
            logo = channel.logo,
            poster = program.icon ?: channel.logo,
            streamTitle = program.title,
            streamSubtitle = channel.name,
            pauseDescription = program.description,
            providerName = channel.sourceName,
            providerAddonId = PROVIDER_ID,
            contentType = CONTENT_TYPE,
            videoId = channel.key,
            parentMetaId = channel.key,
            parentMetaType = CONTENT_TYPE,
        )
        return PlayerLaunchStore.put(launch)
    }

    /**
     * The player reports what it's playing: a catch-up that plays as TS after its HLS link failed
     * means this provider has no HLS replays (TS is asked for straight away for a week); one
     * playing as HLS means it does, so a note from before is cleared.
     */
    fun onPlaying(url: String?) {
        if (url == null) return
        val ctx = synchronized(lock) { catchups[url] } ?: return
        val sourceId = ctx.channel.sourceId
        if (LiveTvCatchup.isTsTimeshift(url)) {
            val failedHls = synchronized(lock) { hlsFailedOn.remove(url) } != null
            if (failedHls) {
                val now = LiveTvClock.nowMs()
                LiveTvPreferences.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = now) else it } }
            }
        } else if (LiveTvCatchup.isHlsTimeshift(url)) {
            if (LiveTvPreferences.currentPlaylists().any { it.id == sourceId && it.catchupNoHlsSince > 0L }) {
                LiveTvPreferences.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = 0L) else it } }
            }
        }
    }

    /** The next form of a catch-up request to try after [failedUrl], or null when none is left. */
    private fun catchupFallback(failedUrl: String, ctx: CatchupContext): LiveFallback? {
        val next = synchronized(lock) { ctx.remaining.removeFirstOrNull() } ?: return null
        val req = request(ctx.channel, next)
        synchronized(lock) {
            playing[req.url] = ctx.channel
            catchups[req.url] = ctx
            if (LiveTvCatchup.isHlsTimeshift(failedUrl) && LiveTvCatchup.isTsTimeshift(req.url)) hlsFailedOn[req.url] = ctx.channel.sourceId
        }
        return LiveFallback(ctx.channel, req)
    }

    /**
     * What to play after [failedUrl] failed: for a catch-up replay, the next form of the same
     * request; for a live channel, the TS link for an HLS one, then a backup channel; or null
     * when nothing is left (the player shows the error).
     */
    fun fallbackFor(failedUrl: String): LiveFallback? {
        synchronized(lock) { catchups[failedUrl] }?.let { return catchupFallback(failedUrl, it) }
        val channel = synchronized(lock) { playing[failedUrl] } ?: return null
        // 1. Xtream HLS that won't play: the same channel as TS, once.
        val ts = synchronized(lock) {
            if (channel.key in tsInstead) null
            else tsLinkFor(failedUrl)?.takeIf { it != failedUrl }?.also { tsInstead += channel.key }
        }
        if (ts != null) {
            val req = request(channel, ts)
            synchronized(lock) { playing[req.url] = channel }
            return LiveFallback(channel, req)
        }
        // 2. Another channel by the same name; 3. another channel showing the same game (Sports).
        val next = backupFor(channel) ?: gameFailoverFor(channel) ?: return null
        val req = requestFor(next)
        synchronized(lock) { playing[req.url] = next; backupTried += next.key }
        currentKey = next.key
        LiveTvPreferences.recordWatched(next.key, addToRecent = false)
        return LiveFallback(next, req, switchedFrom = channel)
    }

    private fun backupFor(failed: LiveChannel): LiveChannel? {
        if (!LiveTvPreferences.currentSettings().autoBackupChannel) return null
        val index = indexNow()
        synchronized(lock) { backupTried += failed.key }
        val name = backupName(failed.name)
        if (name.length < 2) return null
        val tried = synchronized(lock) { backupTried.toSet() }
        val hidden = LiveTvPreferences.currentUserState()
        return index[name].orEmpty().asSequence()
            .filter { it.key !in tried && it.key != failed.key }
            .filter { it.key !in hidden.hiddenChannels && it.groupId !in hidden.hiddenGroups }
            // Local stations: "FOX (WNYW)" isn't a backup for "FOX (KTTV)".
            .filter { c -> val a = stationNote(failed.name); val b = stationNote(c.name); a == null || b == null || a == b }
            // Other playlists first.
            .sortedBy { if (it.sourceId != failed.sourceId) 0 else 1 }
            .firstOrNull()
    }

    private fun gameFailoverFor(failed: LiveChannel): LiveChannel? {
        if (!LiveTvPreferences.currentSettings().autoBackupChannel) return null
        val tried = synchronized(lock) { backupTried += failed.key; backupTried.toSet() }
        return runCatching { gameFailover?.invoke(failed.key, tried) }.getOrNull()?.takeIf { it.key != failed.key && it.key !in tried }
    }

    private fun indexNow(): Map<String, List<LiveChannel>> {
        val list = LiveTvRepository.displayChannels.value
        synchronized(lock) {
            if (backupIndexFor !== list) {
                backupIndex = list.groupBy { backupName(it.name) }
                backupIndexFor = list
            }
            return backupIndex
        }
    }

    /** A plain-English reason for the player's error text, the TV app's wording where it fits. */
    fun describeError(message: String?, url: String? = null): String {
        val m = message.orEmpty()
        val code = Regex("""\b(4\d\d|5\d\d|884)\b""").find(m)?.value?.toIntOrNull()
        val catchup = url != null && synchronized(lock) { catchups.containsKey(url) }
        return when {
            catchup -> "This show couldn't be replayed. The provider may not have it in its catch-up" + (code?.let { " (HTTP $it)" } ?: "")
            code == 401 || code == 403 -> "Provider refused the stream (HTTP $code): account limit, expired, or blocked"
            code == 404 -> "Stream not found (HTTP 404). Try another stream format in the playlist's settings"
            code == 429 || code == 456 || code == 458 || code == 509 -> "Too many connections on this account (HTTP $code)"
            code != null && code >= 500 -> "Provider's server error (HTTP $code)"
            m.contains("malformed", true) || m.contains("parsing", true) || m.contains("manifest", true) ->
                "The provider isn't sending this channel properly right now"
            m.contains("timeout", true) || m.contains("Unable to connect", true) || m.contains("network", true) ->
                "Can't reach the provider right now"
            m.contains("decoder", true) || m.contains("codec", true) -> "This phone couldn't play this channel's picture or sound"
            m.contains("drm", true) || m.contains("license", true) -> "This channel is protected and couldn't be unlocked"
            else -> "This channel isn't playing right now"
        }
    }

    // ---------------------------------------------------------------- link and name helpers

    /** Xtream live links: ".../live/user/pass/123.m3u8" or "host/user/pass/123.m3u8". */
    private val XTREAM_HLS = Regex("""/(?:live/)?[^/]+/[^/]+/\d+\.m3u8$""", RegexOption.IGNORE_CASE)
    /** An Xtream live link with no ending: ".../live/user/pass/123" or ".../user/pass/123". */
    private val XTREAM_BARE = Regex("""/(?:live/)?[^/]+/[^/]+/\d+$""")

    /**
     * The TS version of an Xtream live link: ".../123.m3u8" -> ".../123.ts", and a link with no
     * ending at all (which the server answers as HLS) -> ".../123.ts". Null if it isn't one.
     */
    fun tsLinkFor(url: String): String? {
        val base = url.substringBefore('?')
        if (base.endsWith(".m3u8", ignoreCase = true) && XTREAM_HLS.containsMatchIn(base))
            return base.dropLast(5) + ".ts" + url.substring(base.length)
        if (XTREAM_BARE.containsMatchIn(base)) return base + ".ts" + url.substring(base.length)
        return null
    }

    private val BACKUP_PREFIX = Regex("""^\s*[a-z]{2,4}\s*[:|]\s*""")
    private val BACKUP_PREFIX_DASH = Regex("""^\s*[a-z]{2,3}\s+-\s+""")
    private val BACKUP_BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    private val BACKUP_NOISE = Regex("""\b(hd|fhd|uhd|sd|4k|8k|hevc|h\.?26[45]|1080[pi]?|720p?|2160p?|\d{2}\s?fps|backup|bkp|bk|alt|alternate|raw|ᴴᴰ)\b""")

    /**
     * A channel's name without what differs between copies of the same channel: a country or
     * provider prefix ("US: ", "UK | "), bracketed notes and quality or backup words. "US: ESPN
     * HD" and "ESPN (Backup)" both give "espn".
     */
    internal fun backupName(name: String): String {
        var s = name.lowercase()
        s = s.replace(BACKUP_PREFIX, "")
        s = s.replace(BACKUP_PREFIX_DASH, "")
        s = s.replace(BACKUP_BRACKETS, " ")
        s = s.replace(BACKUP_NOISE, " ")
        return s.filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotBlank() }.joinToString(" ")
    }

    private val STATION_NOTE = Regex("""[\[(]([^\])]*)[\])]""")

    /** The call sign or region in brackets ("FOX (WNYW)" -> "wnyw"), not quality notes like "(HD)". */
    internal fun stationNote(name: String): String? =
        STATION_NOTE.findAll(name.lowercase()).map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotBlank() && backupName(it).isNotBlank() }
}
