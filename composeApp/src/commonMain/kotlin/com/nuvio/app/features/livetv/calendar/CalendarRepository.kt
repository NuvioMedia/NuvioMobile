package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.nuvio.app.features.livetv.data.LiveTvRepository
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The Calendar: upcoming episodes of the shows in your Library and watch history, and release
 * dates of the movies in your Library. Dates come from [CalendarSources]; what you've seen comes
 * from your watch history, so watched episodes are marked and episodes ahead of you can be hidden.
 */
internal object CalendarRepository {
    private const val MAX_SHOWS = 400
    private const val MAX_MOVIES = 200
    private const val DAY = 24 * 3_600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private val _follows = MutableStateFlow<List<CalendarFollow>>(emptyList())
    val follows: StateFlow<List<CalendarFollow>> = _follows.asStateFlow()
    private val _hidden = MutableStateFlow<List<CalendarHidden>>(emptyList())
    val hidden: StateFlow<List<CalendarHidden>> = _hidden.asStateFlow()
    private val _airing = MutableStateFlow(AiringToday())
    val airing: StateFlow<AiringToday> = _airing.asStateFlow()
    private var listsLoaded = false

    private fun str(o: JsonObject, k: String) = o[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

    private fun decodeFollows(raw: String): List<CalendarFollow> = runCatching {
        Json.parseToJsonElement(raw.ifBlank { "[]" }).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // A movie the TV put on the Calendar from its guide ("airing") is kept in the list but is TV-only.
            if (str(o, "type") == "airing") return@mapNotNull null
            val id = str(o, "id").ifBlank { return@mapNotNull null }
            CalendarFollow(id, str(o, "type").ifBlank { "series" }, str(o, "name"), str(o, "poster").ifBlank { null },
                str(o, "background").ifBlank { null }, str(o, "imdb").ifBlank { null }, str(o, "addon").ifBlank { null },
                o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }.getOrDefault(emptyList())

    private fun followJson(f: CalendarFollow): JsonObject = buildJsonObject {
        put("id", f.id); put("type", f.type); put("name", f.name); put("poster", f.poster.orEmpty())
        put("background", f.background.orEmpty()); put("imdb", f.imdbId.orEmpty()); put("addon", f.addonBaseUrl.orEmpty())
        put("at", f.addedAt)
    }

    /** [mine] is the phone's view of the list; entries it doesn't show (the TV's "airing" ones, extra fields) stay as the TV saved them. */
    private fun mergeFollows(raw: String, mine: List<CalendarFollow>): String {
        val old = runCatching { Json.parseToJsonElement(raw.ifBlank { "[]" }).jsonArray }.getOrDefault(JsonArray(emptyList()))
        val wanted = mine.map { it.id }.toSet()
        val keep = old.filter { el ->
            val o = el as? JsonObject ?: return@filter false
            str(o, "type") == "airing" || str(o, "id") in wanted
        }
        val have = keep.mapNotNull { (it as? JsonObject)?.let { o -> str(o, "id") } }.toSet()
        return JsonArray(keep + mine.filter { it.id !in have }.map(::followJson)).toString()
    }

    private fun decodeHidden(raw: String): List<CalendarHidden> = runCatching {
        Json.parseToJsonElement(raw.ifBlank { "[]" }).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CalendarHidden(str(o, "id").ifBlank { return@mapNotNull null }, str(o, "name"))
        }
    }.getOrDefault(emptyList())

    private fun encodeHidden(list: List<CalendarHidden>): String =
        buildJsonArray { for (h in list) add(buildJsonObject { put("id", h.id); put("name", h.name) }) }.toString()

    private fun loadLists() {
        if (listsLoaded) return
        listsLoaded = true
        // Followed and hidden shows used to be kept in files on the phone only. Move what's there into the
        // synced setting once (the files are emptied afterwards), so it follows the Nuvio profile like on the TV.
        runCatching {
            val oldFollows = LiveTvFiles.readSmall(LiveTvDirs.CALENDAR, "follows.json")?.let(::decodeFollows).orEmpty()
            val oldHidden = LiveTvFiles.readSmall(LiveTvDirs.CALENDAR, "hidden.json")?.let(::decodeHidden).orEmpty()
            if (oldFollows.isNotEmpty()) LiveTvPreferences.editCalendarFollows { raw ->
                val there = decodeFollows(raw)
                mergeFollows(raw, there + oldFollows.filter { o -> there.none { it.id == o.id } })
            }
            if (oldHidden.isNotEmpty()) LiveTvPreferences.editCalendarHiddenShows { raw ->
                val there = decodeHidden(raw)
                encodeHidden(there + oldHidden.filter { o -> there.none { it.id == o.id } })
            }
            if (oldFollows.isNotEmpty()) LiveTvFiles.writeSmall(LiveTvDirs.CALENDAR, "follows.json", "[]")
            if (oldHidden.isNotEmpty()) LiveTvFiles.writeSmall(LiveTvDirs.CALENDAR, "hidden.json", "[]")
        }
        _follows.value = decodeFollows(LiveTvPreferences.calendarFollowsNow())
        _hidden.value = decodeHidden(LiveTvPreferences.calendarHiddenShowsNow())
        // Follow the synced setting from here on: a sync from the TV, or another Nuvio profile, updates the lists.
        scope.launch {
            LiveTvPreferences.calendarFollows.collect { raw ->
                val list = decodeFollows(raw)
                if (list != _follows.value) { _follows.value = list; refresh(force = false) }
            }
        }
        scope.launch {
            LiveTvPreferences.calendarHiddenShows.collect { raw ->
                val list = decodeHidden(raw)
                if (list != _hidden.value) { _hidden.value = list; refresh(force = false) }
            }
        }
    }

    private fun saveLists() {
        val follows = _follows.value
        val hidden = _hidden.value
        LiveTvPreferences.editCalendarFollows { raw -> mergeFollows(raw, follows) }
        LiveTvPreferences.editCalendarHiddenShows { encodeHidden(hidden) }
    }

    /** Reads the followed / hidden lists from disk (once). */
    fun ensureLoaded() = loadLists()

    fun isFollowed(id: String): Boolean = _follows.value.any { it.id == id || it.imdbId == id }

    /** Follows [e]'s show or movie if it isn't followed yet, otherwise stops following it. Returns whether it's followed now. */
    fun toggleFollow(e: CalendarEntry): Boolean {
        loadLists()
        val existing = _follows.value.firstOrNull { it.id == e.contentId || it.imdbId == e.contentId }
        val on = existing == null
        _follows.value = if (existing != null) _follows.value - existing else _follows.value + CalendarFollow(
            id = e.contentId, type = e.type, name = e.title, poster = e.poster, background = e.background,
            imdbId = e.contentId.takeIf { it.startsWith("tt") }, addonBaseUrl = e.addonBaseUrl, addedAt = LiveTvClock.nowMs(),
        )
        saveLists()
        refresh(force = false)
        return on
    }

    fun toggleFollow(f: CalendarFollow) {
        loadLists()
        _follows.value = if (_follows.value.any { it.id == f.id }) _follows.value.filter { it.id != f.id } else _follows.value + f
        saveLists()
        refresh(force = false)
    }

    // ---- the bell on a show's page / in Live TV

    /** Is the show or movie with this id (Nuvio id or IMDb id) on your Calendar by bell? */
    fun isBellOn(id: String, imdb: String?): Boolean {
        loadLists()
        return _follows.value.any { it.id == id || it.imdbId == id || (imdb != null && (it.id == imdb || it.imdbId == imdb)) }
    }

    /** Rings or silences the bell. Returns whether it's on now. */
    fun toggleBell(id: String, type: String, name: String, poster: String?, background: String?, imdb: String?): Boolean {
        loadLists()
        val existing = _follows.value.firstOrNull { it.id == id || it.imdbId == id || (imdb != null && (it.id == imdb || it.imdbId == imdb)) }
        if (existing != null) toggleFollow(existing)
        else toggleFollow(CalendarFollow(id, type, name, poster, background, imdb ?: id.takeIf { it.startsWith("tt") }, null, LiveTvClock.nowMs()))
        return existing == null
    }

    // ---- channels you told the Calendar to use ("Always use this channel")

    /** Saved picks: "show|<id>" -> channel key (for one show), or a squashed network name -> channel key. */
    val channelPicks: StateFlow<Map<String, String>> = LiveTvPreferences.calendarChannelPicks
        .map { raw ->
            runCatching {
                Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.getOrDefault(emptyMap())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun showPickKey(contentId: String) = "show|$contentId"
    fun pickKey(network: String?): String? = network?.let { LiveTvRepository.normalize(it) }?.takeIf { it.isNotEmpty() }
    fun savedKey(e: CalendarEntry): String? = channelPicks.value[showPickKey(e.contentId)] ?: pickKey(e.network)?.let { channelPicks.value[it] }

    private fun editPicks(change: (MutableMap<String, String>) -> Unit) {
        LiveTvPreferences.editCalendarChannelPicks { raw ->
            val m = runCatching {
                Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
            }.getOrDefault(mutableMapOf())
            change(m)
            buildJsonObject { m.forEach { (k, v) -> put(k, v) } }.toString()
        }
    }

    /** "Always use this channel" for [e]'s show (null: forget it, for the show and its network). */
    fun setAlways(e: CalendarEntry, hit: CalendarGuideHit?) {
        val net = pickKey(e.network)
        val sk = showPickKey(e.contentId)
        editPicks { m ->
            if (hit == null) { m.remove(sk); if (net != null) m.remove(net) }
            else { m[sk] = hit.channelKey; if (net != null) m.remove(net) }
        }
    }

    fun forgetSaved(pickKey: String) = editPicks { it.remove(pickKey) }

    /** What's saved for [e], in words, and where it's kept (to forget it). Null: nothing saved. */
    fun savedChoice(e: CalendarEntry): Pair<String, String>? {
        val chans = LiveTvRepository.channels.value
        fun name(key: String) = chans.firstOrNull { it.key == key }?.name ?: "a channel no longer in your playlist"
        channelPicks.value[showPickKey(e.contentId)]?.let { return "Using ${name(it)} for ${e.title}" to showPickKey(e.contentId) }
        val k = pickKey(e.network) ?: return null
        val v = channelPicks.value[k] ?: return null
        return "Always using ${name(v)} for ${e.network}" to k
    }

    data class SavedChannel(val pickKey: String, val label: String, val forShow: Boolean, val channel: String)

    /** Every channel you've told the Calendar to use (Settings > Calendar > Saved Channels). */
    fun savedChannels(): List<SavedChannel> {
        val chans = LiveTvRepository.channels.value.associateBy { it.key }
        val names = (_state.value.entries + _airing.value.entries).associate { it.contentId to it.title } +
            _follows.value.associate { (it.imdbId ?: it.id) to it.name }
        return channelPicks.value.map { (k, v) ->
            val chText = chans[v]?.name ?: "a channel no longer in your playlist"
            if (k.startsWith("show|")) SavedChannel(k, names[k.removePrefix("show|")] ?: k.removePrefix("show|"), true, chText)
            else SavedChannel(k, k, false, chText)
        }.sortedWith(compareBy<SavedChannel> { it.forShow }.thenBy { it.label.lowercase() })
    }

    /** Takes a show or movie off the Calendar (it's also dropped from Following). */
    fun hide(e: CalendarEntry) {
        loadLists()
        if (_hidden.value.none { it.id == e.contentId }) _hidden.value = _hidden.value + CalendarHidden(e.contentId, e.title)
        _follows.value = _follows.value.filter { it.id != e.contentId && it.imdbId != e.contentId }
        saveLists()
        refresh(force = false)
    }

    fun unhide(id: String) {
        loadLists()
        _hidden.value = _hidden.value.filter { it.id != id }
        saveLists()
        refresh(force = false)
    }

    /** Every show with a new episode on the day starting at [dayMs] (TV in your country, and streaming). */
    fun loadAiring(dayMs: Long = SportsDates.dayStart(0), force: Boolean = false) {
        val cur = _airing.value
        if (!force && cur.dayMs == dayMs && (cur.loading || cur.entries.isNotEmpty())) return
        _airing.value = AiringToday(dayMs, cur.entries.takeIf { cur.dayMs == dayMs }.orEmpty(), loading = true)
        scope.launch {
            loadLists()
            val s = LiveTvPreferences.settings.value
            val list = runCatching { CalendarSources.airingOn(dayMs, s.calendarCountry.ifBlank { "US" }) }.getOrNull()
            if (list == null) {
                _airing.value = AiringToday(dayMs, emptyList(), false, "Couldn't get today's shows. Check your connection.")
                return@launch
            }
            val followed = _follows.value.flatMap { listOfNotNull(it.id, it.imdbId) }.toSet()
            val skipTypes = if (s.calendarSkipNewsTalk) setOf("News", "Talk Show", "Sports", "Panel Show") else emptySet()
            val hiddenIds = _hidden.value.map { it.id }.toSet()
            val entries = list.filter { (show, _) -> show.type !in skipTypes && (show.imdb == null || show.imdb !in hiddenIds) }
                .groupBy { it.first.id }
                .mapNotNull { (_, eps) ->
                    val (show, e) = eps.first()
                    val lastNumber = eps.maxOf { it.second.number }
                    val local = CalendarAirTimes.local(e.airMs, show.zone, show.streaming)
                    val id = show.imdb ?: return@mapNotNull null
                    CalendarEntry(
                        key = "today|$id|${e.season}|${e.number}",
                        kind = CalendarKind.EPISODE,
                        contentId = id,
                        type = "series",
                        title = show.name,
                        poster = show.image,
                        background = show.backdrop ?: CalendarSources.knownBackdrop(id),
                        season = e.season,
                        episode = e.number,
                        lastEpisode = lastNumber.takeIf { eps.size > 1 && it > e.number },
                        episodeTitle = if (eps.size > 1) null else e.name,
                        episodeImage = e.image,
                        description = e.summary.takeIf { eps.size == 1 },
                        airMs = local,
                        dayMs = local?.let { SportsDates.dayOf(it) } ?: e.dayMs ?: dayMs,
                        network = show.network,
                        streaming = show.streaming || e.airMs == null,
                        tags = buildList {
                            if (e.number == 1 && e.season == 1) add(CalendarTag.SERIES_PREMIERE)
                            else if (e.number == 1) add(CalendarTag.SEASON_PREMIERE)
                        },
                        reasons = if (id in followed) setOf(CalendarReason.AIRING_TODAY, CalendarReason.BELL) else setOf(CalendarReason.AIRING_TODAY),
                        runtimeMin = e.runtime,
                    )
                }
                .distinctBy { it.key }
                .sortedWith(compareBy<CalendarEntry> { it.streaming }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
            _airing.value = AiringToday(dayMs, entries, false, null)
        }
    }

    /** Shows the saved dates at once, then looks up whatever is due again. [force] looks everything up again. */
    fun refresh(force: Boolean = false) {
        if (job?.isActive == true && !force) return
        job = scope.launch {
            build(fetch = false, force = false)
            build(fetch = true, force = force)
        }
    }

    private class Candidate(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val imdb: String?,
        val tmdb: Int?,
        val addonBaseUrl: String?,
        val reasons: MutableSet<CalendarReason>,
        val year: Int?,
        /** When you last watched it (for which shows come first). */
        val lastWatched: Long = 0L,
    )

    /** Episodes you've seen of each show: the furthest one, and every one marked watched. */
    private class Seen(val furthest: Pair<Int, Int>?, val watched: Set<Pair<Int, Int>>)

    private fun isSeries(type: String) = type.equals("series", true) || type.equals("tv", true) ||
        type.equals("show", true) || type.equals("anime", true)

    private fun tmdbOf(id: String): Int? =
        if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() else null

    private fun yearOf(s: String?): Int? = s?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    private fun compare(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

    /** Further than the next episode after [furthest] (the next season's first counts as next). */
    private fun isAhead(pos: Pair<Int, Int>, furthest: Pair<Int, Int>): Boolean {
        if (compare(pos, furthest) <= 0) return false
        if (pos.first == furthest.first && pos.second == furthest.second + 1) return false
        if (pos.first == furthest.first + 1 && pos.second == 1) return false
        return true
    }

    private fun candidates(): List<Candidate> {
        loadLists()
        val out = LinkedHashMap<String, Candidate>()
        fun keyOf(id: String, imdb: String?) = imdb?.takeIf { it.startsWith("tt") } ?: id
        fun add(c: Candidate) {
            val k = keyOf(c.id, c.imdb)
            val old = out[k]
            if (old == null) out[k] = c
            else {
                old.reasons += c.reasons
                if (old.poster == null && c.poster != null) {
                    out[k] = Candidate(old.id, old.type, old.name, c.poster, old.background, old.imdb, old.tmdb, old.addonBaseUrl,
                        old.reasons, old.year, old.lastWatched)
                }
            }
        }
        for (f in _follows.value) {
            add(Candidate(f.id, f.type, f.name, f.poster, f.background, f.imdbId ?: f.id.takeIf { it.startsWith("tt") }, tmdbOf(f.id),
                f.addonBaseUrl, mutableSetOf(CalendarReason.BELL), null))
        }
        for (e in LibraryRepository.uiState.value.items) {
            val type = if (e.type.equals("movie", true)) "movie" else if (isSeries(e.type)) "series" else continue
            add(Candidate(e.id, type, e.name, e.poster, e.banner ?: e.landscapePoster, e.imdbId ?: e.id.takeIf { it.startsWith("tt") },
                e.tmdbId ?: tmdbOf(e.id), e.addonBaseUrl, mutableSetOf(CalendarReason.LIBRARY), yearOf(e.releaseInfo)))
        }
        // Every show in your watch history, however long ago: the ones between seasons are the
        // ones you most want to hear about. Ended shows simply have nothing coming.
        val progress = WatchProgressRepository.uiState.value
        progress.entries.filter { isSeries(it.parentMetaType) && it.parentMetaId !in progress.hiddenContentIds }
            .groupBy { it.parentMetaId }
            .forEach { (id, items) ->
                val p = items.maxBy { it.lastUpdatedEpochMs }
                add(Candidate(id, "series", p.title, p.poster, p.background, id.takeIf { it.startsWith("tt") }, tmdbOf(id), null,
                    mutableSetOf(CalendarReason.WATCHING), null, p.lastUpdatedEpochMs))
            }
        WatchedRepository.uiState.value.items.filter { it.season != null && isSeries(it.type) }
            .groupBy { it.id }
            .forEach { (id, items) ->
                val w = items.maxBy { it.markedAtEpochMs }
                add(Candidate(id, "series", w.name, w.poster, null, id.takeIf { it.startsWith("tt") }, tmdbOf(id), null,
                    mutableSetOf(CalendarReason.WATCHING), null, w.markedAtEpochMs))
            }
        val hiddenIds = _hidden.value.map { it.id }.toSet()
        return out.values.filter { it.id !in hiddenIds && (it.imdb == null || it.imdb !in hiddenIds) }
    }

    private fun seenEpisodes(): Map<String, Seen> {
        val furthest = HashMap<String, Pair<Int, Int>>()
        val marks = HashMap<String, MutableSet<Pair<Int, Int>>>()
        fun note(id: String, s: Int?, e: Int?, done: Boolean) {
            if (s == null || e == null) return
            val p = s to e
            val f = furthest[id]
            if (f == null || compare(p, f) > 0) furthest[id] = p
            if (done) marks.getOrPut(id) { HashSet() } += p
        }
        for (p in WatchProgressRepository.uiState.value.entries) {
            note(p.parentMetaId, p.seasonNumber, p.episodeNumber, p.isCompleted)
            // Movies watched: (0, 0).
            if (p.contentType.equals("movie", true) && p.isCompleted) marks.getOrPut(p.parentMetaId) { HashSet() } += (0 to 0)
        }
        for (w in WatchedRepository.uiState.value.items) {
            note(w.id, w.season, w.episode, true)
            if (w.type.equals("movie", true)) marks.getOrPut(w.id) { HashSet() } += (0 to 0)
        }
        return (furthest.keys + marks.keys).associateWith { Seen(furthest[it], marks[it].orEmpty()) }
    }

    private fun stale(d: CalendarSources.ShowData): Boolean {
        val now = LiveTvClock.nowMs()
        val age = now - d.fetchedAt
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // An episode out in the next day or so: checked more often (times and dates move).
        val soon = d.episodes.any { e ->
            val t = e.airMs ?: e.dayMs ?: return@any false
            kotlin.math.abs(t - now) < 36 * CalendarSources.HOUR
        }
        return when {
            ended -> age > 7 * CalendarSources.DAY
            soon -> age > 3 * CalendarSources.HOUR
            // Shows between seasons: once a day.
            else -> age > 20 * CalendarSources.HOUR
        }
    }

    private suspend fun build(fetch: Boolean, force: Boolean) = mutex.withLock {
        runCatching { buildLocked(fetch, force) }.onFailure {
            _state.value = _state.value.copy(loading = false, progress = null, problem = it.message)
        }
    }

    private suspend fun buildLocked(fetch: Boolean, force: Boolean) {
        val s = LiveTvPreferences.settings.value
        val all = candidates()
        // What you're watching first, then your Library (newest first): a Library of hundreds of
        // titles doesn't mean hundreds of look-ups every time.
        val shows = all.filter { it.type == "series" }
            .sortedWith(compareByDescending<Candidate> { CalendarReason.BELL in it.reasons }.thenByDescending { CalendarReason.WATCHING in it.reasons }.thenByDescending { it.lastWatched })
            .take(MAX_SHOWS)
        val thisYear = SportsDates.localYearMonth().first
        val movies = all.filter { it.type == "movie" && (CalendarReason.BELL in it.reasons || it.year == null || it.year >= thisYear - 1) }.take(MAX_MOVIES)
        if (fetch) _state.value = _state.value.copy(loading = true, progress = 0 to (shows.size + movies.size), problem = null)
        val seen = seenEpisodes()
        val fromMs = SportsDates.dayStart(-30)
        val toMs = SportsDates.dayStart(121)
        val entries = ArrayList<CalendarEntry>()
        var done = 0
        // The same show can come in twice under different ids: each show is only taken once.
        val usedShows = HashSet<Int>()
        for (c in shows) {
            val tvmId = if (fetch) CalendarSources.tvmazeId(c.imdb, c.tmdb, c.name) else CalendarSources.knownTvmazeId(c.imdb, c.tmdb)
            if (tvmId != null && !usedShows.add(tvmId)) { done++; continue }
            var data = tvmId?.let { CalendarSources.cachedShow(it) }
            if (fetch && tvmId != null && (force || data == null || stale(data))) {
                data = CalendarSources.fetchShow(tvmId) ?: data
            }
            done++
            if (fetch && done % 5 == 0) _state.value = _state.value.copy(progress = done to (shows.size + movies.size))
            if (data == null) continue
            entries += episodesOf(c, data, seen[c.id] ?: c.imdb?.let { seen[it] }, s, fromMs, toMs)
        }
        // Shows with no wide picture (TVmaze only had a poster): TMDB's, looked up once and kept.
        if (fetch) {
            val missing = entries.filter { it.kind == CalendarKind.EPISODE && it.background == null && it.contentId.startsWith("tt") }
                .map { it.contentId }.distinct().filter { !CalendarSources.backdropLooked(it) }.take(150)
            for (id in missing) runCatching { CalendarSources.showBackdrop(id) }
            if (missing.isNotEmpty()) {
                CalendarSources.saveBackdrops()
                for (i in entries.indices) {
                    val e = entries[i]
                    if (e.background == null) CalendarSources.knownBackdrop(e.contentId)?.let { entries[i] = e.copy(background = it) }
                }
            }
        }
        for (m in movies) {
            val dates = runCatching {
                CalendarSources.movieDates(m.imdb, m.tmdb, s.calendarCountry.ifBlank { "US" }, cachedOnly = !fetch)
            }.getOrNull()
            done++
            if (dates == null) continue
            entries += moviesOf(m, dates, seen[m.id] ?: m.imdb?.let { seen[it] }, fromMs, toMs)
        }
        CalendarSources.saveMovies()
        // Never the same entry twice (its reasons are put together).
        val merged = entries.groupBy { it.key }.map { (_, same) ->
            if (same.size == 1) same[0] else same[0].copy(reasons = same.flatMap { it.reasons }.toSet())
        }
        val sorted = merged.sortedWith(compareBy<CalendarEntry> { it.dayMs }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
        _state.value = CalendarState(
            entries = sorted,
            loading = false,
            progress = null,
            updatedAt = if (fetch) LiveTvClock.nowMs() else _state.value.updatedAt.takeIf { it > 0 } ?: LiveTvClock.nowMs(),
            showCount = shows.size + movies.size,
        )
    }

    private fun episodesOf(
        c: Candidate,
        d: CalendarSources.ShowData,
        seen: Seen?,
        s: LiveTvSettings,
        fromMs: Long,
        toMs: Long,
    ): List<CalendarEntry> {
        val now = LiveTvClock.nowMs()
        val eps = d.episodes.filter { e -> e.dayMs != null && e.dayMs >= fromMs && e.dayMs < toMs }
            .sortedWith(compareBy<CalendarSources.Episode> { it.season }.thenBy { it.number })
        if (eps.isEmpty()) return emptyList()
        val lastSeason = d.episodes.maxOfOrNull { it.season }
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // Several episodes out at once (a streaming season) make one entry.
        val groups = ArrayList<MutableList<CalendarSources.Episode>>()
        for (e in eps) {
            val g = groups.lastOrNull()
            val prev = g?.last()
            if (prev != null && prev.season == e.season && prev.dayMs == e.dayMs && prev.number + 1 == e.number &&
                (prev.airMs == e.airMs || d.show.streaming)
            ) g.add(e) else groups += mutableListOf(e)
        }
        val contentId = c.imdb ?: d.show.imdb ?: c.id
        return groups.map { g ->
            val e = g.first()
            val last = g.last()
            val tags = ArrayList<CalendarTag>()
            when {
                e.number == 1 && e.season == 1 -> tags += CalendarTag.SERIES_PREMIERE
                e.number == 1 -> tags += CalendarTag.SEASON_PREMIERE
            }
            val total = last.seasonEpisodes
            if (total != null && last.number >= total && !(e.number == 1 && g.size == 1 && total > 1)) {
                tags += if (ended && last.season == lastSeason) CalendarTag.SERIES_FINALE else CalendarTag.SEASON_FINALE
            }
            // West Coast: the delayed airing's time (see CalendarAirTimes).
            val local = CalendarAirTimes.local(e.airMs, d.show.zone, d.show.streaming)
            val aired = (local ?: e.dayMs!!) <= now
            val pos = e.season to e.number
            val watched = seen != null && (pos in seen.watched || (seen.furthest != null && compare(pos, seen.furthest) < 0))
            val fresh = aired && !watched && e.dayMs!! >= SportsDates.dayStart(-1)
            if (fresh) tags.add(0, CalendarTag.NEW)
            // Ahead of where you are: no title or picture (the next one you'd watch is fine).
            val spoiler = s.calendarHideSpoilers && seen?.furthest != null && !watched && isAhead(pos, seen.furthest)
            CalendarEntry(
                key = "ep|$contentId|${e.season}|${e.number}",
                kind = CalendarKind.EPISODE,
                contentId = contentId,
                type = "series",
                title = c.name.ifBlank { d.show.name },
                poster = c.poster ?: d.show.image,
                background = c.background ?: d.show.backdrop,
                addonBaseUrl = c.addonBaseUrl,
                season = e.season,
                episode = e.number,
                lastEpisode = last.number.takeIf { g.size > 1 },
                episodeTitle = if (g.size > 1) null else e.name,
                episodeImage = e.image,
                description = e.summary.takeIf { g.size == 1 },
                airMs = local,
                dayMs = local?.let { SportsDates.dayOf(it) } ?: e.dayMs!!,
                network = d.show.network,
                streaming = d.show.streaming || e.airMs == null,
                tags = tags,
                reasons = c.reasons.toSet(),
                watched = watched,
                spoiler = spoiler,
                runtimeMin = e.runtime,
            )
        }
    }

    private fun moviesOf(
        m: Candidate,
        dates: CalendarSources.MovieDates,
        seen: Seen?,
        fromMs: Long,
        toMs: Long,
    ): List<CalendarEntry> {
        val out = ArrayList<CalendarEntry>()
        val contentId = m.imdb ?: m.id
        val watched = seen?.watched?.contains(0 to 0) == true
        fun fmt(ms: Long) = SportsDates.shortDayLabel(ms).substringAfter(' ').let { d -> "${SportsDates.monthLabel(ms)} $d" }
        fun inWindow(ms: Long?) = ms != null && ms >= fromMs && ms < toMs
        fun entry(day: Long, tag: CalendarTag, note: String?) = CalendarEntry(
            key = "mv|$contentId|$tag", kind = CalendarKind.MOVIE, contentId = contentId, type = "movie",
            title = m.name.ifBlank { dates.title.orEmpty() }, poster = m.poster ?: dates.poster, background = m.background ?: dates.backdrop,
            description = dates.overview, addonBaseUrl = m.addonBaseUrl, dayMs = day, tags = listOf(tag), note = note,
            reasons = m.reasons.toSet(), watched = watched,
        )
        val home = dates.digitalMs
        if (inWindow(dates.theaterMs)) {
            out += entry(dates.theaterMs!!, CalendarTag.IN_THEATERS, home?.let { "At home ${fmt(it)}" } ?: "Not out to watch at home yet")
        }
        if (inWindow(home)) {
            out += entry(home!!, CalendarTag.AT_HOME, dates.theaterMs?.let { "In theaters ${fmt(it)}" })
        }
        if (home == null && inWindow(dates.discMs)) {
            out += entry(dates.discMs!!, CalendarTag.ON_DISC, dates.theaterMs?.let { "In theaters ${fmt(it)}" })
        }
        return out
    }
}
