package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.ChannelNameEditor
import com.nuvio.app.features.livetv.model.EpgAssignment
import com.nuvio.app.features.livetv.model.EpgChannelEntry
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.EpgSource
import com.nuvio.app.features.livetv.model.EpgSourceChannels
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class LiveTvStatus(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val loadedOnce: Boolean = false,
)

/**
 * Live TV's data: downloads playlists (M3U links and Xtream logins) and guides (XMLTV) on a
 * schedule, builds the channel list, matches each channel to its listings and keeps the guide
 * in [EpgStore]. The behaviour and status fields follow the TV app's LiveTvRepository so a
 * setup synced from a TV behaves the same here.
 */
internal object LiveTvRepository {
    private val prefs = LiveTvPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val xtream = XtreamClient()

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    /** Every channel from every enabled playlist, as the playlists have them. */
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /**
     * Listings around now (a few hours back, half a day ahead), by channel key, title and times
     * only. The full guide is read from [EpgStore] as screens need it.
     */
    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Channel key -> the guide channel picked automatically (shown in Assign EPG). */
    private val _autoMatches = MutableStateFlow<Map<String, EpgAssignment>>(emptyMap())
    val autoMatches: StateFlow<Map<String, EpgAssignment>> = _autoMatches.asStateFlow()

    /** Every loaded guide with its channel list (for Assign EPG). */
    private val _epgSources = MutableStateFlow<List<EpgSourceChannels>>(emptyList())
    val epgSources: StateFlow<List<EpgSourceChannels>> = _epgSources.asStateFlow()
    private val _epgListsLoading = MutableStateFlow(false)
    val epgListsLoading: StateFlow<Boolean> = _epgListsLoading.asStateFlow()

    /** Channel key -> logo from the guide it was matched to. */
    private val _epgLogos = MutableStateFlow<Map<String, String>>(emptyMap())
    val epgLogos: StateFlow<Map<String, String>> = _epgLogos.asStateFlow()

    private val _status = MutableStateFlow(LiveTvStatus())
    val status: StateFlow<LiveTvStatus> = _status.asStateFlow()

    /** Goes up each time the stored guide changes (screens reading from [EpgStore] reload). */
    private val _guideGeneration = MutableStateFlow(0)
    val guideGeneration: StateFlow<Int> = _guideGeneration.asStateFlow()

    /**
     * The channel list with your renames, numbers, group names, the channel name editor and
     * logo choices applied: what every screen shows.
     */
    val displayChannels: StateFlow<List<LiveChannel>> = combine(
        _channels, prefs.userState, prefs.settings, _epgLogos,
        prefs.playlists.map { list -> list.filter { it.logoSource == "epg" }.mapTo(HashSet()) { it.id } }.distinctUntilChanged()
    ) { channels, user, s, logos, preferEpg ->
        withPickedLogos(withLogos(named(channels, user, s), logos, preferEpg), user.channelLogos)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var initialized = false
    private var firstCheckDone = false
    private val embeddedEpgUrls = HashMap<String, List<String>>()
    private var refreshJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    /** Loads what's saved straight away, then checks for playlist and guide updates. */
    fun ensureLoaded(updateCheckDelayMs: Long = 4_000L) {
        if (initialized) return
        initialized = true
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            if (updateCheckDelayMs > 0) delay(updateCheckDelayMs)
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false) }
            firstCheckDone = true
            // Keep checking while the app stays open: the guide runs out after a day or two.
            while (true) {
                delay(STALE_CHECK_MS)
                runCatching { refreshInternal(forcePlaylists = false, forceEpg = false) }
                runCatching { withContext(Dispatchers.Default) { EpgStore.compact() } }
            }
        }
    }

    /** Opening Live TV: update anything past its update time. */
    fun refreshIfStale() {
        if (!firstCheckDone || mutex.isLocked) return
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false) } }
    }

    fun refreshAll(force: Boolean = true) {
        scope.launch { runCatching { refreshInternal(forcePlaylists = force, forceEpg = force) } }
    }

    fun refreshEpgOnly() {
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) } }
    }

    /** After a restore from Google Drive: rebuild from the new sources, downloading what's missing. */
    fun reloadAfterSync(changedPlaylists: Set<String> = emptySet()) {
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = changedPlaylists) }
        }
    }

    // ---------------------------------------------------------------- sources

    @OptIn(ExperimentalUuidApi::class)
    private fun newId(): String = Uuid.random().toString()

    fun addPlaylist(name: String, url: String, userAgent: String = ""): String {
        val id = newId()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = userAgent.trim()) }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun addXtream(name: String, server: String, username: String, password: String): String {
        val id = newId()
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass,
                importVod = false, importLive = true
            )
        }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun updatePlaylist(input: PlaylistSource) {
        val source = if (input.isXtream) {
            val fromLink = PlaylistSource.credentialsFromLink(input.xtreamServer)
            input.copy(
                xtreamServer = PlaylistSource.cleanXtreamServer(input.xtreamServer),
                xtreamUsername = input.xtreamUsername.trim().ifBlank { fromLink?.first.orEmpty() },
                xtreamPassword = input.xtreamPassword.trim().ifBlank { fromLink?.second.orEmpty() }
            )
        } else input
        val before = prefs.currentPlaylists().firstOrNull { it.id == source.id }
        prefs.updatePlaylists { list -> list.map { if (it.id == source.id) source else it } }
        if (!source.liveEnabled) {
            dropPlaylistNow(source.id)
            if (before?.liveEnabled == true) scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
            return
        }
        val credentialsChanged = before == null || before.url != source.url || before.xtreamServer != source.xtreamServer ||
            before.xtreamUsername != source.xtreamUsername || before.xtreamPassword != source.xtreamPassword ||
            before.userAgent != source.userAgent || before.streamFormat != source.streamFormat
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = if (credentialsChanged) setOf(source.id) else emptySet()) }
        }
    }

    fun updatePlaylistNow(id: String) {
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
    }

    fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        prefs.setPlaylistAccount(id, null)
        LiveTvFiles.delete(LiveTvDirs.PLAYLISTS, "$id.m3u")
        embeddedEpgUrls.remove(id)
        dropPlaylistNow(id)
        scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
    }

    private fun dropPlaylistNow(id: String) {
        val remaining = _channels.value.filterNot { it.sourceId == id }
        if (remaining.size == _channels.value.size) return
        val keys = remaining.mapTo(HashSet()) { it.key }
        _channels.value = remaining
        _programs.value = _programs.value.filterKeys { it in keys }
    }

    fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (!enabled) dropPlaylistNow(id)
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = false) }
        }
    }

    fun movePlaylist(id: String, delta: Int) {
        prefs.updatePlaylists { list ->
            val m = list.toMutableList()
            val i = m.indexOfFirst { it.id == id }
            if (i < 0) return@updatePlaylists list
            val item = m.removeAt(i)
            m.add((i + delta).coerceIn(0, m.size), item)
            m
        }
        scope.launch { runCatching { mutex.withLock { rebuildLocked() } } }
    }

    fun addEpg(name: String, url: String): String {
        val id = newId()
        prefs.updateEpgSources { it + EpgSource(id = id, name = name.ifBlank { "EPG" }, url = url.trim()) }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) } }
        return id
    }

    fun updateEpg(source: EpgSource) {
        prefs.updateEpgSources { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) } }
    }

    fun removeEpg(id: String) {
        prefs.updateEpgSources { list -> list.filterNot { it.id == id } }
        LiveTvFiles.delete(LiveTvDirs.EPG, "$id.xml")
        scope.launch {
            runCatching { mutex.withLock { rebuildLocked() } }
            runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) }
        }
    }

    fun setEpgEnabled(id: String, enabled: Boolean) {
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch { runCatching { refreshInternal(forcePlaylists = false, forceEpg = true) } }
    }

    fun setPlaylistLogoSource(id: String, logoSource: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(logoSource = logoSource) else it } }
    }

    // ---------------------------------------------------------------- refresh

    private fun setStatus(loading: Boolean, message: String?, error: String? = null) {
        _status.value = _status.value.copy(loading = loading, message = message, error = error)
    }

    private suspend fun refreshInternal(forcePlaylists: Boolean, forceEpg: Boolean, forceIds: Set<String> = emptySet()) {
        mutex.withLock {
            try {
                refreshBody(forcePlaylists, forceEpg, forceIds)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _status.value = LiveTvStatus(loading = false, loadedOnce = true, error = e.message ?: "Update failed")
            }
            runCatching { sweepLeftoverFiles() }
        }
    }

    private suspend fun refreshBody(forcePlaylists: Boolean, forceEpg: Boolean, forceIds: Set<String>) {
        val settings = prefs.currentSettings()
        val now = LiveTvClock.nowMs()
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        var changed = false
        var firstError: String? = null

        for (pl in playlists) {
            val file = playlistFile(pl.id)
            val exists = LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file)
            val stale = !exists || now - LiveTvFiles.lastModified(LiveTvDirs.PLAYLISTS, file) > settings.playlistRefreshHours.coerceAtLeast(1) * HOUR
            if (!(forcePlaylists || stale || pl.id in forceIds)) continue
            setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
            val result = runCatching {
                if (pl.isXtream) downloadXtream(pl, file)
                else LiveTvFiles.download(pl.resolvedUrl(), LiveTvDirs.PLAYLISTS, file, pl.userAgent) { bytes ->
                    setStatus(loading = true, message = "Downloading playlist \"${pl.name}\" (${sizeLabel(bytes)})…")
                }
            }
            if (!pl.isXtream) runCatching { refreshM3uAccount(pl) }
            val err = result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it else it.message ?: "Download failed" }
            prefs.updatePlaylists { list ->
                list.map { if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
            }
            if (err != null && firstError == null) firstError = "${pl.name}: $err"
            if (result.getOrNull() == true || (!exists && LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file))) changed = true
        }

        if (changed || _channels.value.isEmpty()) {
            setStatus(loading = true, message = "Reading channels…")
            buildChannels()
        }

        val targets = collectEpgTargets()
        for (target in targets) {
            val file = epgFile(target.fileId)
            val exists = LiveTvFiles.exists(LiveTvDirs.EPG, file)
            val stale = !exists || now - LiveTvFiles.lastModified(LiveTvDirs.EPG, file) > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            val forcedHere = target.sourceId in forceIds || target.playlistId in forceIds
            if (!(forceEpg || stale || forcedHere)) continue
            setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
            val result = runCatching {
                LiveTvFiles.download(target.url, LiveTvDirs.EPG, file, target.userAgent) { bytes ->
                    setStatus(loading = true, message = "Downloading guide \"${target.name}\" (${sizeLabel(bytes)})…")
                }
            }
            if (result.getOrNull() == true) changed = true
            val err = result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it else it.message ?: "Download failed" }
            if (target.sourceId != null) {
                prefs.updateEpgSources { list ->
                    list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                }
            }
            if (err != null && firstError == null) firstError = "${target.name}: $err"
        }

        if (changed || forceEpg || forceIds.isNotEmpty() || EpgStore.fingerprint() != guideFingerprint(settings, targets)) {
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, targets)
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true, error = firstError)
    }

    /** Reads the saved channels and guide (nothing downloaded). */
    private suspend fun rebuildLocked() {
        buildChannels()
        val settings = prefs.currentSettings()
        val targets = collectEpgTargets()
        if (_channels.value.isNotEmpty() && EpgStore.fingerprint() == guideFingerprint(settings, targets)) {
            loadGuideWindow()
            _autoMatches.value = withContext(Dispatchers.Default) { EpgStore.autoMatches() }
        } else if (_channels.value.isNotEmpty() && targets.any { LiveTvFiles.exists(LiveTvDirs.EPG, epgFile(it.fileId)) }) {
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, targets)
        }
        _status.value = _status.value.copy(loading = false, loadedOnce = true)
    }

    private suspend fun sweepLeftoverFiles() = withContext(Dispatchers.Default) {
        val now = LiveTvClock.nowMs()
        val oldEnough = now - 14L * 24 * HOUR
        val guideIds = collectEpgTargets().mapTo(HashSet()) { it.fileId }
        prefs.currentEpgSources().forEach { guideIds += it.id }
        val playlistIds = prefs.currentPlaylists().mapTo(HashSet()) { it.id }
        fun sweep(dir: String, idOf: (String) -> String, keep: Set<String>) {
            LiveTvFiles.list(dir).forEach { name ->
                if (LiveTvFiles.lastModified(dir, name) > oldEnough) return@forEach
                if (idOf(name) in keep) return@forEach
                LiveTvFiles.delete(dir, name)
            }
        }
        sweep(LiveTvDirs.EPG, { it.substringBefore(".xml").substringBefore(".tmp") }, guideIds)
        sweep(LiveTvDirs.PLAYLISTS, { it.substringBefore(".m3u").substringBefore(".tmp") }, playlistIds)
    }

    // ---------------------------------------------------------------- downloads

    private fun playlistFile(id: String) = "$id.m3u"
    private fun epgFile(id: String) = "$id.xml"

    /** An Xtream login: the API's channel list written as an M3U; get.php when the API won't answer. */
    private suspend fun downloadXtream(pl: PlaylistSource, file: String): Boolean {
        val viaApi = runCatching { xtream.livePlaylistM3u(pl) { count -> setStatus(loading = true, message = "Reading \"${pl.name}\": $count channels…") } }
        val playlist = viaApi.getOrNull()
        if (playlist != null) {
            playlist.account.account?.let { prefs.setPlaylistAccount(pl.id, it) }
            playlist.account.serverTimezone?.let { tz ->
                if (tz != pl.serverTimezone) prefs.updatePlaylists { l -> l.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
            }
            return LiveTvFiles.writeText(LiveTvDirs.PLAYLISTS, file, playlist.m3u)
        }
        val e = viaApi.exceptionOrNull()
        if (e is XtreamLoginError) throw e
        if (e is kotlinx.coroutines.CancellationException) throw e
        // The panel's API failed: the plain playlist link usually still works.
        return LiveTvFiles.download(pl.resolvedUrl(), LiveTvDirs.PLAYLISTS, file, pl.userAgent) { bytes ->
            setStatus(loading = true, message = "Downloading playlist \"${pl.name}\" (${sizeLabel(bytes)})…")
        }
    }

    /** M3U links from Xtream providers (get.php with a login): the account's expiry and status. */
    private suspend fun refreshM3uAccount(pl: PlaylistSource) {
        val api = xtream.m3uAccountUrl(pl.resolvedUrl()) ?: return
        val info = runCatching { xtream.accountInfo(api, pl.userAgent) }.getOrNull() ?: return
        info.account?.let { prefs.setPlaylistAccount(pl.id, it) }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    // ---------------------------------------------------------------- channels

    private suspend fun buildChannels() = withContext(Dispatchers.Default) {
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        val multi = playlists.size > 1
        val out = ArrayList<LiveChannel>()
        val usedKeys = HashSet<String>()
        var counter = 1
        for ((plIndex, pl) in playlists.withIndex()) {
            val file = playlistFile(pl.id)
            if (!LiveTvFiles.exists(LiveTvDirs.PLAYLISTS, file)) continue
            setStatus(loading = true, message = "Reading channels from \"${pl.name}\"" + (if (multi) " (${plIndex + 1} of ${playlists.size})" else "") + "…")
            val parsed = runCatching {
                LiveTvFiles.readChunks(LiveTvDirs.PLAYLISTS, file) { chunks -> M3uParser.parse(linesOf(chunks)) }
            }.getOrNull() ?: continue
            embeddedEpgUrls[pl.id] = parsed.epgUrls
            var count = 0
            for (e in parsed.entries) {
                // Many providers' M3U links also list every movie and episode: not TV channels.
                if (isVodEntry(e.url)) continue
                count++
                val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
                val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
                var key = baseKey
                var n = 2
                while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
                val number = e.chno ?: counter
                counter = maxOf(counter, number) + 1
                val headers = if (pl.userAgent.isNotBlank() && e.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    e.headers + ("User-Agent" to pl.userAgent)
                } else e.headers
                out += LiveChannel(
                    key = key,
                    sourceId = pl.id,
                    sourceName = pl.name,
                    name = e.name,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    logo = e.logo,
                    groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                    group = groupTitle,
                    number = number,
                    url = e.url,
                    headers = headers,
                    catchup = e.catchup,
                    drm = e.drm
                )
            }
            if (count != pl.channelCount) {
                prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = count) else it } }
            }
        }
        _channels.value = out
    }

    /** The chunks of a file as lines (a line may span two chunks). */
    internal fun linesOf(chunks: Sequence<String>): Sequence<String> = sequence {
        var carry = StringBuilder()
        for (chunk in chunks) {
            var start = 0
            while (true) {
                val nl = chunk.indexOf('\n', start)
                if (nl < 0) { carry.append(chunk, start, chunk.length); break }
                if (carry.isEmpty()) yield(chunk.substring(start, nl))
                else { carry.append(chunk, start, nl); yield(carry.toString()); carry = StringBuilder() }
                start = nl + 1
            }
        }
        if (carry.isNotEmpty()) yield(carry.toString())
    }

    // ---------------------------------------------------------------- guide

    private data class EpgTarget(
        val fileId: String,
        val sourceId: String?,
        val name: String,
        val url: String,
        val userAgent: String,
        val playlistId: String? = null,
    )

    private fun hostOf(u: String): String? = runCatching { Url(PlaylistSource.tidyLink(u)).host.lowercase() }.getOrNull()

    /** Named guides first (they win on conflicts), then the guides playlists point to. */
    private fun collectEpgTargets(): List<EpgTarget> {
        val targets = mutableListOf<EpgTarget>()
        val allPlaylists = prefs.currentPlaylists()
        prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.forEach { src ->
            val host = hostOf(src.url)
            val ua = allPlaylists.firstOrNull { pl ->
                pl.userAgent.isNotBlank() && host != null && hostOf(if (pl.isXtream) pl.xtreamBase() else pl.url) == host
            }?.userAgent.orEmpty()
            targets += EpgTarget(src.id, src.id, src.name, src.url, ua)
        }
        for (pl in allPlaylists.filter { it.liveEnabled && it.useEmbeddedEpg }) {
            val urls = mutableListOf<String>()
            pl.xtreamEpgUrl()?.let { urls += it }
            urls += embeddedEpgUrls[pl.id].orEmpty()
            urls.distinct().forEach { url ->
                if (targets.none { it.url == url }) {
                    targets += EpgTarget("auto_" + stableHash(url), null, "${pl.name} (built-in)", url, pl.userAgent, playlistId = pl.id)
                }
            }
        }
        return targets
    }

    private fun targetStamp(t: EpgTarget): String =
        "${t.fileId}:${LiveTvFiles.lastModified(LiveTvDirs.EPG, epgFile(t.fileId))}:${LiveTvFiles.size(LiveTvDirs.EPG, epgFile(t.fileId))}"

    private fun guideFingerprint(settings: LiveTvSettings, targets: List<EpgTarget>): String {
        val channels = _channels.value
        var h = channels.size.toLong()
        for (c in channels) h = h * 31 + c.key.hashCode() * 7L + (c.tvgId?.hashCode() ?: 0) * 3L + (c.tvgName?.hashCode() ?: 0) + c.name.hashCode()
        val overrides = prefs.currentUserState().epgOverrides
        return "v$GUIDE_VERSION|$h|${overrides.hashCode()}|${settings.epgPastHours}|${settings.epgFutureDays}|" + targets.joinToString(",") { targetStamp(it) }
    }

    private fun defaultWindow(now: Long = LiveTvClock.nowMs()) = (now - 3 * HOUR) to (now + 12 * HOUR)

    private suspend fun loadGuideWindow() = withContext(Dispatchers.Default) {
        val (from, to) = defaultWindow()
        _programs.value = EpgStore.range(from, to, light = true)
        _guideGeneration.value++
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(Dispatchers.Default) {
        val all = _channels.value
        if (all.isEmpty()) {
            _programs.value = emptyMap()
            return@withContext
        }
        val hidden = prefs.currentHiddenEverywhere()
        val channels = all.filter { c -> !hidden.hides(c.key, c.groupId) }
        val now = LiveTvClock.nowMs()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR
        val overrides = prefs.currentUserState().epgOverrides.filterKeys { key -> channels.any { it.key == key } }

        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byName = HashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            if (c.key in overrides) return@forEach
            c.tvgId?.lowercase()?.takeIf { it.isNotBlank() }?.let { byTvgId.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.tvgName ?: "").takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            c.tvgId?.let { idName(it) }?.takeIf { it.length >= 3 }?.let { k ->
                val l = byName.getOrPut(k) { ArrayList() }
                if (c !in l) l.add(c)
            }
        }

        class Candidate(val score: Int, val sourceId: String, val xmltvId: String, val programs: List<EpgProgram>, val icon: String?)
        val best = HashMap<String, Candidate>()
        val sourceLists = ArrayList<EpgSourceChannels>()

        for ((order, target) in targets.withIndex()) {
            val file = epgFile(target.fileId)
            if (!LiveTvFiles.exists(LiveTvDirs.EPG, file)) continue
            setStatus(loading = true, message = "Matching guide \"${target.name}\"" + (if (targets.size > 1) " (${order + 1} of ${targets.size})" else "") + "…")
            val src = target.fileId
            val pinnedHere = overrides.filterValues { it.sourceId == src }.entries.groupBy({ it.value.xmltvId }, { it.key })
            val idHits = HashMap<String, MutableSet<LiveChannel>>()
            val nameHits = HashMap<String, MutableSet<LiveChannel>>()
            fun matchOne(id: String, names: List<String>) {
                byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                (names.map { normalize(it) } + normalize(id) + idName(id)).distinct().forEach { k ->
                    if (k.isNotEmpty()) byName[k]?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                }
            }
            val collected = HashMap<String, MutableList<EpgProgram>>()
            var listed: Map<String, XmltvChannel> = emptyMap()
            val result = runCatching {
                LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { chunks ->
                    XmltvParser.parse(
                        chunks, windowStart, windowEnd,
                        keepUnlisted = { xid ->
                            matchOne(xid, emptyList())
                            xid in pinnedHere || idHits.containsKey(xid) || nameHits.containsKey(xid)
                        },
                        onPrograms = { xid, list -> collected.getOrPut(xid) { ArrayList() }.addAll(list) },
                        selectChannels = { chans ->
                            listed = chans
                            if (chans.isEmpty()) null
                            else {
                                chans.values.forEach { matchOne(it.id, it.displayNames) }
                                (idHits.keys + nameHits.keys + pinnedHere.keys).toSet()
                            }
                        }
                    )
                }
            }.getOrNull() ?: continue
            val entries = listed.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
                .ifEmpty { result.programChannelIds.map { EpgChannelEntry(it, emptyList(), null) } }
            val used = HashSet<String>()
            fun offer(xid: String, chans: Collection<LiveChannel>, idMatch: Boolean, pinned: Boolean) {
                val list = collected[xid]?.let { cleanListings(it) } ?: return
                if (list.isEmpty()) return
                val real = !isPlaceholderListing(list)
                val score = (if (pinned) 8 else 0) + (if (real) 2 else 0) + (if (idMatch) 1 else 0)
                val icon = listed[xid]?.icon
                chans.forEach { c ->
                    val cur = best[c.key]
                    if (cur == null || score > cur.score) {
                        best[c.key] = Candidate(score, src, xid, list, icon)
                        used += xid
                    }
                }
            }
            pinnedHere.forEach { (xid, keys) -> offer(xid, keys.mapNotNull { k -> channels.firstOrNull { it.key == k } }, idMatch = true, pinned = true) }
            idHits.forEach { (xid, chans) -> offer(xid, chans, idMatch = true, pinned = false) }
            nameHits.forEach { (xid, chans) -> offer(xid, chans, idMatch = false, pinned = false) }
            sourceLists += EpgSourceChannels(src, target.name, hostOf(target.url) ?: target.name, entries, used)
        }

        val programs = HashMap<String, List<EpgProgram>>(best.size)
        val auto = HashMap<String, EpgAssignment>(best.size)
        val logos = HashMap<String, String>()
        best.forEach { (key, c) ->
            programs[key] = c.programs
            if (key !in overrides) auto[key] = EpgAssignment(c.sourceId, c.xmltvId)
            c.icon?.takeIf { it.isNotBlank() }?.let { logos[key] = it }
        }
        setStatus(loading = true, message = "Storing the guide…")
        runCatching { EpgStore.replaceAll(guideFingerprint(settings, targets), programs, auto) }
        _autoMatches.value = auto
        _epgLogos.value = logos
        _epgSources.value = sourceLists
        val (from, to) = defaultWindow(now)
        _programs.value = programs.mapValues { (_, list) ->
            list.filter { it.stopMs > from && it.startMs < to }.map { EpgProgram(it.startMs, it.stopMs, it.title, episode = it.episode, icon = it.icon) }
        }.filterValues { it.isNotEmpty() }
        _guideGeneration.value++
    }

    /** Listings cleaned the TV app's way: sorted, overlaps resolved, placeholders over real shows dropped. */
    private fun cleanListings(list: List<EpgProgram>): List<EpgProgram> {
        if (list.isEmpty()) return list
        val sorted = list.filter { it.stopMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<EpgProgram>(sorted.size)
        var lastStop = Long.MIN_VALUE
        for (p in sorted) {
            if (p.startMs < lastStop) {
                // Overlaps the show before: a placeholder gives way; a real show cuts the one before.
                if (isPlaceholderTitle(p.title)) continue
                val prev = out.lastOrNull()
                if (prev != null && prev.stopMs > p.startMs) {
                    if (isPlaceholderTitle(prev.title)) out.removeAt(out.lastIndex) else out[out.lastIndex] = prev.copy(stopMs = p.startMs)
                }
            }
            out += p
            lastStop = p.stopMs
        }
        return out
    }

    private fun isPlaceholderListing(list: List<EpgProgram>): Boolean = list.all { isPlaceholderTitle(it.title) }

    /** Assign EPG opened: make sure every guide's channel list is there (built with the guide). */
    fun ensureSourceLists() {
        if (_epgSources.value.isNotEmpty() || _epgListsLoading.value) return
        _epgListsLoading.value = true
        scope.launch {
            runCatching {
                val targets = collectEpgTargets()
                val lists = ArrayList<EpgSourceChannels>()
                for (t in targets) {
                    val file = epgFile(t.fileId)
                    if (!LiveTvFiles.exists(LiveTvDirs.EPG, file)) continue
                    val listed = withContext(Dispatchers.Default) {
                        LiveTvFiles.readChunks(LiveTvDirs.EPG, file) { chunks ->
                            var found: Map<String, XmltvChannel> = emptyMap()
                            // Only the channel list is needed: stop at the first show.
                            XmltvParser.parse(chunks, 0L, 1L, selectChannels = { chans -> found = chans; throw ChannelListRead() })
                            found
                        }
                    }.orEmpty()
                    val used = _autoMatches.value.values.filter { it.sourceId == t.fileId }.mapTo(HashSet()) { it.xmltvId }
                    lists += EpgSourceChannels(t.fileId, t.name, hostOf(t.url) ?: t.name, listed.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }, used)
                }
                _epgSources.value = lists
            }
            _epgListsLoading.value = false
        }
    }

    private class ChannelListRead : RuntimeException()

    /** Assign EPG: these channels' guides were changed; match again. */
    fun applyEpgChanges(changes: Map<String, EpgAssignment?>) {
        changes.forEach { (key, a) -> prefs.setEpgOverride(key, a) }
        rematchEpg()
    }

    fun rematchEpg() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    setStatus(loading = true, message = "Matching guide data…")
                    buildPrograms(prefs.currentSettings(), collectEpgTargets())
                }
                _status.value = _status.value.copy(loading = false, loadedOnce = true)
            }
        }
    }

    // ---------------------------------------------------------------- guide reads

    /** What's on now and next for each channel, from the hours kept in memory. */
    fun nowNext(keys: Collection<String>, nowMs: Long = LiveTvClock.nowMs()): Map<String, NowNext> {
        val progs = _programs.value
        val out = HashMap<String, NowNext>()
        keys.forEach { k ->
            val l = progs[k] ?: return@forEach
            val cur = l.firstOrNull { nowMs >= it.startMs && nowMs < it.stopMs }
            val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: nowMs) }
            out[k] = NowNext(cur, nxt)
        }
        return out
    }

    /** Listings for these channels across [fromMs, toMs), read from the stored guide. */
    suspend fun programsIn(keys: Collection<String>, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> =
        withContext(Dispatchers.Default) { EpgStore.range(fromMs, toMs, light = true, channels = keys) }

    /** One show's full details. */
    suspend fun programDetails(channelKey: String, startMs: Long): EpgProgram? =
        withContext(Dispatchers.Default) { EpgStore.program(channelKey, startMs) }
            ?: _programs.value[channelKey]?.firstOrNull { it.startMs == startMs }

    /** A channel's whole schedule for the days the guide keeps. */
    suspend fun channelSchedule(channelKey: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        withContext(Dispatchers.Default) { EpgStore.channelRange(channelKey, fromMs, toMs) }

    /** Moves the in-memory window along (called now and then while Live TV is open). */
    fun refreshWindow() {
        scope.launch { runCatching { loadGuideWindow() } }
    }

    // ---------------------------------------------------------------- display names and logos

    private fun named(channels: List<LiveChannel>, user: LiveUserState, s: LiveTvSettings): List<LiveChannel> {
        val terms = ChannelNameEditor.terms(s.nameRemovals)
        if (user.channelNames.isEmpty() && user.channelNumbers.isEmpty() && user.groupNames.isEmpty() && terms.isEmpty()) return channels
        val spaced = if (terms.isEmpty()) emptyMap() else channels.groupBy { it.sourceId }.mapValues { (_, list) -> ChannelNameEditor.spacePrefixes(list.map { it.name }, terms) }
        val currentKeys = channels.mapTo(HashSet()) { it.key }
        val namesById = stableIndex(user.channelNames.filterKeys { it !in currentKeys })
        val numbersById = stableIndex(user.channelNumbers.filterKeys { it !in currentKeys })
        return channels.map { c ->
            val id = stableId(c.key)
            val cleaned = if (terms.isEmpty()) null else ChannelNameEditor.clean(c.name, terms, spaced[c.sourceId].orEmpty()).takeIf { it != c.name }
            c.copy(
                name = user.channelNames[c.key] ?: id?.let { namesById[it] } ?: cleaned ?: c.name,
                number = user.channelNumbers[c.key] ?: id?.let { numbersById[it] } ?: c.number,
                group = user.groupNames[c.groupId] ?: c.group
            )
        }
    }

    private fun withLogos(channels: List<LiveChannel>, epgLogos: Map<String, String>, preferEpg: Set<String>): List<LiveChannel> {
        if (epgLogos.isEmpty()) return channels
        return channels.map { c ->
            val fromGuide = epgLogos[c.key]
            val logo = if (c.sourceId in preferEpg) fromGuide ?: c.logo else c.logo?.takeIf { it.isNotBlank() } ?: fromGuide
            if (logo == c.logo) c else c.copy(logo = logo)
        }
    }

    private fun withPickedLogos(channels: List<LiveChannel>, picked: Map<String, String>): List<LiveChannel> {
        if (picked.isEmpty()) return channels
        val current = channels.mapTo(HashSet()) { it.key }
        val byId = stableIndex(picked.filterKeys { it !in current })
        return channels.map { c ->
            val logo = picked[c.key] ?: stableId(c.key)?.let { byId[it] }
            if (logo == null || logo == c.logo) c else c.copy(logo = logo)
        }
    }

    /** "playlist|tvg-id" from a channel key, or null when the channel has no tvg-id. */
    private fun stableId(key: String): String? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3 || parts[1].isBlank()) return null
        return parts[0] + "|" + parts[1]
    }

    private fun <T> stableIndex(byKey: Map<String, T>): Map<String, T> {
        val out = HashMap<String, T>()
        byKey.forEach { (k, v) -> stableId(k)?.let { if (it !in out) out[it] = v } }
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun stableHash(s: String): String {
        // FNV-1a, 64-bit: a stable file name for a guide link (no hashing library in common code).
        var h = -3750763034362895579L
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    private const val HOUR = 60L * 60L * 1000L
    private const val STALE_CHECK_MS = 30L * 60L * 1000L
    private const val GUIDE_VERSION = 1

    private val PLACEHOLDER_TITLES = Regex(
        """(programming|program|programme|no information|no info|no program information|no programme information|""" +
            """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
            """regular programming|scheduled programming|paid programming|coming soon|no program guide available)\.?"""
    )
    private val VOD_PATH = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val qualityTokens = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
    private val prefixToken = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
    private val bracketed = Regex("""\[.*?]|\(.*?\)""")
    private val countryEnd = Regex("""(\.(com|co|net|tv))?\.[a-z]{2}$""", RegexOption.IGNORE_CASE)

    /** A movie or episode in an M3U (by its link), not a live channel. */
    fun isVodEntry(url: String): Boolean = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

    /** "Programming", "No information", "To Be Announced"… (no real show). */
    fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLES.matches(title.trim().lowercase())

    /** A guide or tvg-id as a name: "GloboSP.br" -> "globosp". */
    internal fun idName(id: String): String = normalize(id.trim().replace(countryEnd, ""))

    internal fun normalize(name: String): String {
        var n = name.lowercase().trim()
        n = n.replace(prefixToken, "")
        n = n.replace(bracketed, " ")
        n = n.replace(qualityTokens, " ")
        return n.filter { it.isLetterOrDigit() }
    }
}
