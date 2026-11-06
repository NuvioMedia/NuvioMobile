package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.NowNext
import com.nuvio.app.features.livetv.data.VideoPlaying
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.parental.ParentalControls
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Everything behind the Sports hub: today's games from ESPN (refreshed every 30 seconds while
 * a game is on and you're looking, much less otherwise), sports found in your guide, and which
 * of your channels show each one. Kept on the device so the page opens straight away.
 *
 * The TV app's SportsRepository, on the phone's stores: Live TV's preferences hold the settings
 * and follows (under the TV's keys, so they sync through the Drive backup), the guide database
 * answers the channel search, and the saved feeds live in Live TV's files.
 */
@OptIn(FlowPreview::class)
internal object SportsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = LiveTvPreferences
    private val liveRepo = LiveTvRepository
    private val feeds = SportsFeeds()
    private val matcher = SportsMatcher()
    private const val DIR = LiveTvDirs.SPORTS

    val settings: StateFlow<SportsSettings> = prefs.sportsSettingsJson
        .map { SportsSettings.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsSettings())

    val follows: StateFlow<SportsFollows> = prefs.sportsMineJson
        .map { SportsFollows.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsFollows())

    /**
     * League Networks: the channels whose names say they're a league's own channel (NFL Network,
     * NBA TV…), by network id. Found by name only, so it's redone only when the channel list
     * changes; channels you've hidden or locked are left out.
     */
    val leagueNetworks: StateFlow<Map<String, List<LiveChannel>>> = combine(
        liveRepo.displayChannels
            .distinctUntilChangedBy { list -> list.size to list.fold(0) { h, c -> h * 31 + c.name.hashCode() } }
            .map { LeagueNetworks.find(it) }
            .flowOn(Dispatchers.Default),
        prefs.userState,
        prefs.settings,
    ) { found, user, live ->
        found.mapValues { (_, list) ->
            list.filter { ch ->
                ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups &&
                    !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                    !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private class LeagueData(val events: List<SportsEvent>, val fetchedAt: Long, val error: String?)

    private val leagueData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** ESPN games from the day after tomorrow to a week ahead (updated a few times a day). */
    private val weekData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val fixtureData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Leagues you follow or show on My Sports: a week ahead to five weeks ahead. */
    private val aheadData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Followed teams' whole seasons from ESPN, by followed team key. */
    private val scheduleData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Non-league football (nuvio-data files): whole seasons, by league id. */
    private val tsdbData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val tsdbTeams = SyncMap<String, List<TeamInfo>>()
    /** The app's own leagues filled from the nuvio-data files (WWE, MotoGP…): league id -> TheSportsDB id. */
    @Volatile private var tsdbAlias: Map<String, String> = emptyMap()
    private fun fromTsdb(id: String) = id.startsWith("tsdb/") || id in tsdbAlias
    private fun tsdbFileId(id: String) = tsdbAlias[id] ?: id.removePrefix("tsdb/")
    /** Games of teams looked up on a table (their card), by team key: so their game pages open. */
    private val lookedUp = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Shows found in the guide, by day (the day's start). */
    private val guideData = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())

    /** TheSportsDB's TV listings (nuvio-data's sportsdb/tv.json): channels in many countries, for [events]. */
    private val tvData = MutableStateFlow<List<TvListing>>(emptyList())
    private val tvCache = SportsTvListings.Cache()
    private val tvCountries = settings.map { SportsTvListings.countriesFor(it) }.distinctUntilChanged()

    /** Every game and event on the page's days (a week back to a week ahead), before the TV listings. */
    private val plainEvents: StateFlow<List<SportsEvent>> = combine(leagueData, weekData, fixtureData, guideData, tsdbData) { l, w, f, g, t ->
        val now = LiveTvClock.nowMs()
        val guide = mergeWrestlingAirings(g.values.flatten()).map { timed(it, now) }
        val from = dayStart(-DAYS_BACK - 1)
        val to = dayStart(DAYS_AHEAD + 2)
        val tsdbAll = t.values.flatMap { it.events }.filter { it.startMs in from until to }.map { timed(it, now) }
        // A league with TheSportsDB's schedule (WWE, MotoGP…) doesn't also take the older one.
        val tsdbLeagues = t.filterValues { it.events.isNotEmpty() }.keys
        val (tsdbNamed, tsdb) = tsdbAll.partition { it.home == null }
        // A scheduled event (sporting-events.org, TheSportsDB's races and shows) gives way to the
        // same event found in the guide: one of its league within 3 hours of its start (or, for
        // events with a date only, that day). Anything else in the guide doesn't hide it.
        val guideByLeague = guide.groupBy { it.leagueId }
        // Fights: only a listing of the same fight (both fighters named) counts as the same event;
        // the guide's general "Boxing" or another fight doesn't hide it. And when that listing has
        // already ended (a preview or early replay) while the fight is still on, the schedule's
        // entry is the one kept, so the fight shows as live with its own channels.
        val replacedGuide = HashSet<String>()
        val fixtures = (f.values.flatMap { it.events }.map { timed(it, now) }.filter { it.leagueId !in tsdbLeagues } + tsdbNamed).filter { fx ->
            val same = guideByLeague[fx.leagueId].orEmpty()
            if (fx.hasTime) {
                val near = same.filter { kotlin.math.abs(it.startMs - fx.startMs) <= 3 * HOUR || sameShowShifted(it, fx) }
                val sides = if (SportsCatalog.league(fx.leagueId)?.sport == Sport.FIGHTING) SportsCatalog.fightSides(fx.title) else null
                if (sides == null) near.isEmpty()
                else {
                    val sameFight = near.filter { gEv ->
                        val text = SportsCatalog.norm(gEv.title + " " + (gEv.subtitle ?: ""))
                        sides.first.any { SportsCatalog.hasWord(text, it) } && sides.second.any { SportsCatalog.hasWord(text, it) }
                    }
                    val ended = sameFight.filter { it.state == GameState.POST && fx.state != GameState.POST }
                    if (sameFight.isNotEmpty() && ended.size == sameFight.size) { ended.forEach { replacedGuide += it.id }; true }
                    else sameFight.isEmpty()
                }
            }
            else same.none { dayOf(it.startMs) == dayOf(fx.startMs) }
        }
        val espn = l.values.flatMap { it.events } + w.values.flatMap { it.events }
        // A match ESPN lists, found again in the guide (or a schedule file) under a guide-only
        // league of the same sport, shows once: the ESPN one, with its score.
        val espnBySport = (espn + tsdb).filter { it.away != null && it.home != null }
            .groupBy { SportsCatalog.league(it.leagueId)?.sport }
        fun coveredByEspn(ev: SportsEvent): Boolean {
            val league = SportsCatalog.league(ev.leagueId) ?: return false
            if (league.espnPath != null) return false
            val same = espnBySport[league.sport] ?: return false
            val t = SportsCatalog.norm(ev.title + " " + (ev.subtitle ?: ""))
            return same.any { r ->
                kotlin.math.abs(r.startMs - ev.startMs) <= 3 * HOUR &&
                    listOfNotNull(r.away, r.home).any { sd -> SportsCatalog.norm(sd.team.name).trim().let { it.length >= 3 && t.contains(it) } }
            }
        }
        (espn + (fixtures + guide.filterNot { it.id in replacedGuide }).filterNot(::coveredByEspn) + tsdb).distinctBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every game and event on the page's days (a week back to a week ahead), with your country's channels from the TV listings. */
    val events: StateFlow<List<SportsEvent>> = combine(plainEvents, tvData, tvCountries) { ev, tv, countries ->
        runCatching { SportsTvListings.addChannels(ev, tv, countries, tvCache) }.getOrDefault(ev)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * A scheduled or guide event's state from the clock: these were worked out once, when the
     * file or guide was read (schedule files only every 12 hours), so a fight read in the morning
     * never went live, and one read mid-card could stay live too long.
     */
    private fun timed(ev: SportsEvent, now: Long): SportsEvent {
        // Only events without two teams (fights, races, shows): a team game's state comes from its feed.
        if (ev.source == EventSource.ESPN || !ev.hasTime || ev.state == GameState.OFF || ev.home != null || ev.away != null) return ev
        val end = ev.endMs ?: return ev
        val state = when {
            now >= end -> GameState.POST
            now >= ev.startMs -> GameState.LIVE
            else -> GameState.PRE
        }
        return if (state == ev.state) ev else ev.copy(state = state)
    }

    /**
     * Everything we know, for My Sports: the page's days plus your teams' whole seasons and the
     * non-league seasons. A game in both counts once (the fresher copy, from the page's days).
     */
    val known: StateFlow<List<SportsEvent>> = combine(events, scheduleData, tsdbData, aheadData, lookedUp) { ev, s, t, a, lu ->
        val seen = HashSet<String>(ev.size * 2)
        ev.forEach { seen += dedupeKey(it) }
        val extra = (a.values.flatMap { it.events } + s.values.flatMap { it.events } + t.values.flatMap { it.events } + lu.values.flatMap { it.events })
            .filter { seen.add(dedupeKey(it)) }
        ev + extra
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun sameShowShifted(guide: SportsEvent, fx: SportsEvent): Boolean {
        if (SportsCatalog.league(fx.leagueId)?.sport != Sport.WRESTLING) return false
        if (kotlin.math.abs(guide.startMs - fx.startMs) > 30 * HOUR) return false
        val a = SportsCatalog.norm(wrestlingHeadline(guide.title)).trim()
        val b = SportsCatalog.norm(wrestlingHeadline(fx.title)).trim()
        return a.length >= 3 && b.length >= 3 && (a == b || a.contains(b) || b.contains(a))
    }

    /** One wrestling show aired at different times: one entry, at the earliest airing, with every channel. */
    private fun mergeWrestlingAirings(guide: List<SportsEvent>): List<SportsEvent> {
        val (wrestling, rest) = guide.partition { SportsCatalog.league(it.leagueId)?.sport == Sport.WRESTLING }
        if (wrestling.size < 2) return guide
        val out = ArrayList<SportsEvent>(wrestling.size)
        val byShow = wrestling.groupBy { it.leagueId + "|" + SportsCatalog.norm(wrestlingHeadline(it.title)).trim() }
        for ((_, airings) in byShow) {
            var run = ArrayList<SportsEvent>()
            fun flush() {
                if (run.isEmpty()) return
                val first = run.first()
                out += if (run.size == 1) first else first.copy(
                    state = if (run.any { it.state == GameState.LIVE }) GameState.LIVE else first.state,
                    guideHits = run.flatMap { it.guideHits }.distinctBy { (key, p) -> key + "|" + p.startMs },
                    endMs = run.mapNotNull { it.endMs }.maxOrNull() ?: first.endMs,
                    subtitle = first.subtitle ?: run.firstNotNullOfOrNull { it.subtitle },
                    word = first.word ?: run.firstNotNullOfOrNull { it.word }
                )
                run = ArrayList()
            }
            for (ev in airings.sortedBy { it.startMs }) {
                if (run.isNotEmpty() && ev.startMs - run.first().startMs > 12 * HOUR) flush()
                run += ev
            }
            flush()
        }
        return rest + out
    }

    private fun dedupeKey(ev: SportsEvent): String = ev.espnId?.let { "e:$it" } ?: ev.id

    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    private val _addonLive = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /**
     * Event id -> the channels showing it, best first, with your "Channels from" region's
     * channels moved up (done here, on what's already matched: changing the region is instant).
     */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> = combine(
        _matches,
        settings.map { it.channelRegion }.distinctUntilChanged(),
        _addonLive
    ) { m0, region, addon ->
        val m = if (region.isBlank()) m0 else m0.mapValues { (_, list) -> ChannelRegions.regionFirst(list, region) }
        // Streams from your sports addons (HighFly…) go after your own channels.
        if (addon.isEmpty()) m else {
            val out = HashMap(m)
            for ((id, extra) in addon) if (extra.isNotEmpty()) out[id] = m[id].orEmpty() + extra
            out
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // ---- games from the sports addons you have installed (see SportsAddonSource)

    private val _addonVod = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Finished games: event id -> their replays and highlights from your sports addons. */
    val vodMatches: StateFlow<Map<String, List<ChannelMatch>>> = _addonVod.asStateFlow()
    private val addonJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * A game page opened: look for it in your sports addons (live streams, or for a finished game its
     * replays and highlights). Nothing is asked until a game is opened; while it's open and nothing
     * is found yet, it looks again every minute and a half (streams appear close to the start).
     */
    fun openGame(ev: SportsEvent) {
        addonJobs.remove(ev.id)?.cancel()
        addonJobs[ev.id] = scope.launch {
            var round = 0
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val found = runCatching { SportsAddonSource.find(ev) }.getOrNull()
                if (found != null) {
                    _addonLive.update { it + (ev.id to found.live) }
                    _addonVod.update { it + (ev.id to found.vod) }
                }
                val got = found != null && (found.live.isNotEmpty() || found.vod.isNotEmpty())
                if (ev.state == GameState.POST || got || ++round >= 20) break
                delay(90_000L)
            }
        }
    }

    /** The game page was closed: stop looking. */
    fun closeGame(eventId: String) {
        addonJobs.remove(eventId)?.cancel()
    }

    private val backupOn: StateFlow<Boolean> = prefs.settings.map { it.autoBackupChannel }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * The next channel for the game [failedKey] is showing: its other copies (FHD, HD, SD) first,
     * then the other channels sure to be showing it, in the game page's order (your region first).
     */
    private fun nextChannelForGame(failedKey: String, tried: Set<String>): LiveChannel? {
        if (!backupOn.value) return null
        val now = LiveTvClock.nowMs()
        val evs = events.value.associateBy { it.id }
        val list = matches.value.entries.firstOrNull { (id, l) ->
            val ev = evs[id] ?: return@firstOrNull false
            val on = ev.state == GameState.LIVE || (ev.state == GameState.PRE && ev.startMs - now < 15 * MINUTE)
            on && l.any { it.channel.key == failedKey && (it.sure || it.score >= 150) }
        }?.value ?: return null
        val failed = list.first { it.channel.key == failedKey }
        val copyKey = SportsMatcher.copyKey(failed.channel)
        val left = list.filter { it.channel.key != failedKey && it.channel.key !in tried }
        val copies = left.filter { SportsMatcher.copyKey(it.channel) == copyKey }
        val others = left.filter { (it.sure || it.score >= 150) && it !in copies }
        return (copies + others).firstOrNull()?.channel
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels and guide (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Something went wrong getting scores (shown quietly on the page). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val viewers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)
    /** Live scores are being watched in the background (a team you follow is playing, see [SportsLiveWatch]). */
    private val watching = MutableStateFlow(false)
    /** The games saved last time have been read back (see [awaitSaved]). */
    private val cacheReady = MutableStateFlow(false)
    private val kick = MutableStateFlow(0)
    @Volatile private var started = false

    /** Starts the background updates (once). Called by the app as it starts. */
    fun start() {
        if (started) return
        started = true
        LiveTvPlayback.gameFailover = { key, tried -> nextChannelForGame(key, tried) }
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { loadCache() } }
            cacheReady.value = true
            runCatching { updateLoop() }
        }
        scope.launch { runCatching { matchLoop() } }
    }

    /** The Sports page (or a game) is on screen: keep scores fresh. */
    fun setViewing(on: Boolean) {
        viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) {
            liveRepo.ensureLoaded(updateCheckDelayMs = 20_000)
            kick.value++
            // Nothing matched yet, one was put off (app away, video playing), or the last is hours old.
            if (_matches.value.isEmpty() || matchMissed || LiveTvClock.nowMs() - lastMatchAt > SAFETY_ROUND) matchKick.value++
        }
    }

    /**
     * Starts the updates if they aren't running and waits (a few seconds at most) for the games
     * saved last time: an alarm can wake the app before anything is loaded.
     */
    suspend fun awaitSaved() {
        start()
        withTimeoutOrNull(3_000) { cacheReady.first { it } }
    }

    /** Your follows and reminders as stored (right after the app wakes, [follows] may still be catching up). */
    suspend fun storedFollows(): SportsFollows = SportsFollows.fromJson(prefs.sportsMineJson.first())

    /** The Sports settings as stored (see [storedFollows]). */
    suspend fun storedSettings(): SportsSettings = SportsSettings.fromJson(prefs.sportsSettingsJson.first())

    /** Keeps the scores of your teams' games coming while the app is in the background. */
    fun setWatching(on: Boolean) {
        if (watching.value == on) return
        watching.value = on
        if (on) kick.value++
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) {
            kick.value++
            // Something changed while the app was away: find channels for it now.
            if (matchMissed) matchKick.value++
        }
    }

    /** Update now (the page's refresh, a day picked). */
    fun refreshNow() {
        forceAll = true
        forceExtras = true
        kick.value++
    }

    @Volatile private var forceAll = false
    @Volatile private var forceExtras = false

    // ------------------------------------------------------------------ which leagues

    /**
     * Leagues shown on the Sports page by default (your region), plus any league you follow a
     * team or the whole league in, or have chosen to show from the Leagues dialog.
     */
    fun activeLeagues(s: SportsSettings = settings.value, f: SportsFollows = follows.value): List<League> {
        val mine = followedLeagueIds(f)
        return SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) || it.id in mine }
    }

    /** Every built-in league ESPN has data for, region and follows aside: the fetch scope. */
    fun allEspnLeagues(): List<League> = SportsCatalog.leagues.filter { it.espnPath != null }

    /** Leagues you follow something in: your teams' leagues and the competitions they play in, whole leagues, top-bar leagues. */
    fun followedLeagueIds(f: SportsFollows = follows.value): Set<String> {
        val out = LinkedHashSet<String>()
        f.teams.forEach { t -> out += t.leagueId; out += SportsCatalog.relatedLeagues(t.leagueId) }
        out += f.leagues
        out += f.pins
        return out
    }

    private fun isMine(l: League, f: SportsFollows) = l.id in followedLeagueIds(f)

    // ------------------------------------------------------------------ updates

    private suspend fun updateLoop() {
        var lastKick = -1
        var lastFixtures = 0L
        var lastTv = 0L
        while (true) {
            val full = foreground.value && (settings.value.showInSidebar || viewers.value > 0)
            // Watching your teams' games in the background: only the scores, nothing else.
            val active = full || watching.value
            if (active) runCatching { updateOnce(LiveTvClock.nowMs()) }
            if (full) {
                val force = forceExtras.also { forceExtras = false }
                runCatching { updateWeek(LiveTvClock.nowMs(), force) }
                runCatching { updateAhead(LiveTvClock.nowMs(), force) }
                runCatching { updatePast(LiveTvClock.nowMs()) }
                runCatching { updateTsdb(LiveTvClock.nowMs(), force) }
                runCatching { updateSchedules(LiveTvClock.nowMs(), force) }
            }
            if (full && LiveTvClock.nowMs() - lastTv > 4 * HOUR) {
                lastTv = LiveTvClock.nowMs()
                runCatching { updateTv() }
            }
            if (full && LiveTvClock.nowMs() - lastFixtures > 12 * HOUR) {
                lastFixtures = LiveTvClock.nowMs()
                runCatching { updateFixtures() }
            }
            val wait = if (viewers.value > 0) 10_000L else 30_000L
            lastKick = kick.value
            withTimeoutOrNull(wait) { kick.first { it != lastKick } }
        }
    }

    private suspend fun updateOnce(now: Long) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val force = forceAll.also { forceAll = false }
        val data = leagueData.value
        val playingKey = LiveTvPlayback.currentKey
        val playingLeagues = if (playingKey == null) emptySet() else _matches.value
            .filter { (_, list) -> list.any { it.channel.key == playingKey && it.sure } }
            .keys.mapNotNull { id -> events.value.firstOrNull { it.id == id && it.state == GameState.LIVE }?.leagueId }.toSet()
        // A league counts as "fast" when it needs to be right up to date: one you follow, one
        // whose game is on screen, or one on the hub's default view while you're looking at it.
        // Every other league is kept warm through the shared cache in nuvio-data instead.
        fun fastLeague(l: League) = isMine(l, f) || l.id in playingLeagues || SportsCatalog.shown(l, s)
        val due = allEspnLeagues().filter { l ->
            val d = data[l.id]
            val age = now - (d?.fetchedAt ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            val mine = isMine(l, f)
            when {
                force -> age > 15_000
                viewing && fastLeague(l) -> age > (if (live) 30_000 else 5 * MINUTE)
                l.id in playingLeagues && s.scoreOnInfoBar -> age > 60_000
                mine && live -> age > (if (s.scoringAlerts) 45_000 else 90_000)
                mine -> age > 30 * MINUTE
                d == null -> age > 6 * HOUR
                else -> age > 45 * MINUTE
            }
        }
        if (due.isEmpty()) return
        _loading.value = true
        try {
            val days = espnDays(now)
            val gate = Semaphore(4)
            val failed = ArrayList<String>()
            val reasons = LinkedHashSet<String>()
            val failLock = Mutex()
            coroutineScopeFetch(due) { l ->
                gate.withPermit {
                    val useDirect = isMine(l, f) || l.id in playingLeagues || (viewing && SportsCatalog.shown(l, s))
                    val r = runCatching { if (useDirect) fetchScoreboard(l, days) else fetchViaSharedCache(l, days, now) }
                    r.onSuccess { raws ->
                        val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrElse { emptyList() } }
                            .distinctBy { it.id }
                        val before = leagueData.value[l.id]?.events
                        leagueData.update { it + (l.id to LeagueData(parsed, LiveTvClock.nowMs(), null)) }
                        runCatching { LiveTvFiles.writeSmall(DIR, "sb_${safe(l.id)}.json", "${LiveTvClock.nowMs()}\n" + raws.joinToString(RAW_SEP)) }
                        if (before != null) noticeScoring(l, before, parsed)
                    }.onFailure { e ->
                        failLock.lock()
                        try { failed += l.name; reasons += describe(e) } finally { failLock.unlock() }
                        leagueData.update { m ->
                            val old = m[l.id]
                            m + (l.id to LeagueData(old?.events.orEmpty(), old?.fetchedAt ?: (LiveTvClock.nowMs() - 4 * MINUTE), e.message))
                        }
                    }
                }
            }
            val why = reasons.take(2).joinToString(", ")
            _problem.value = if (failed.size >= due.size && due.isNotEmpty()) "Couldn't reach the scores service ($why). Showing what was saved." else null
            _updatedAt.value = LiveTvClock.nowMs()
            rebuildLiveScores()
        } finally {
            _loading.value = false
        }
    }

    // ------------------------------------------------------------------ the week ahead, seasons

    /** ESPN games from the day after tomorrow to a week ahead: a few times a day. */
    private suspend fun updateWeek(now: Long, force: Boolean) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val due = allEspnLeagues().filter { l ->
            val age = now - (weekData.value[l.id]?.fetchedAt ?: 0L)
            when {
                force -> age > 30 * MINUTE
                viewing && (isMine(l, f) || SportsCatalog.shown(l, s)) -> age > 3 * HOUR
                isMine(l, f) -> age > 6 * HOUR
                else -> age > 5 * HOUR
            }
        }
        if (due.isEmpty()) return
        val singleDays = (2..DAYS_AHEAD).map { d -> SportsDates.dayKey(now + d * DAY) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val useDirect = isMine(l, f) || (viewing && SportsCatalog.shown(l, s))
                // A day at a time (ESPN turns down a range of days).
                val raws: List<String> = if (useDirect) singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                else fetchWeekViaSharedCache(l, singleDays, now)
                val at = LiveTvClock.nowMs()
                if (raws.isEmpty()) {
                    // Try again in an hour, not straight away.
                    weekData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 2 * HOUR, "failed")) }
                    return@withPermit
                }
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + (l.id to LeagueData(parsed, at, null)) }
                runCatching { LiveTvFiles.writeSmall(DIR, "wk_${safe(l.id)}.json", "$at\n" + raws.joinToString(RAW_SEP)) }
            }
        }
    }

    /** Leagues you follow or show on My Sports: up to five weeks ahead (one request each, a few times a day). */
    private suspend fun updateAhead(now: Long, force: Boolean) {
        val f = follows.value
        val noSchedule = f.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) }
            .filter { it.startsWith("rugby/") || it.startsWith("rugby-league/") || it.startsWith("australian-football/") }
        val ids = (f.pins + f.leagues + noSchedule).toSet()
        if (aheadData.value.keys.any { it !in ids }) aheadData.update { m -> m.filterKeys { it in ids } }
        val due = ids.mapNotNull { SportsCatalog.league(it) }.filter { l ->
            l.espnPath != null && now - (aheadData.value[l.id]?.fetchedAt ?: 0L) > (if (force) HOUR else 6 * HOUR)
        }
        if (due.isEmpty()) return
        // ESPN takes a whole month ("202611") though not a range of days.
        val wanted = listOf(now + (DAYS_AHEAD + 1) * DAY, now + 35 * DAY).map { SportsDates.monthKey(it) }.distinct()
        val keepFrom = dayStart(DAYS_AHEAD + 1)
        val keepTo = now + 36 * DAY
        for (l in due) {
            val raws = wanted.mapNotNull { m -> runCatching { feeds.scoreboardDayRaw(l, m) }.getOrNull() }
            val at = LiveTvClock.nowMs()
            if (raws.isEmpty()) {
                aheadData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                .filter { it.startMs in keepFrom until keepTo }.distinctBy { it.id }
            aheadData.update { it + (l.id to LeagueData(parsed, at, null)) }
        }
    }

    @Volatile private var pastWanted = 0L

    /** A day before yesterday is on screen: get the week back (once, then every couple of hours while looked at). */
    fun wantPast() {
        val now = LiveTvClock.nowMs()
        if (now - pastWanted > 60_000L) {
            pastWanted = now
            kick.value++
        }
    }

    /** The week before yesterday, for the page's past days: only when someone has looked at one. */
    private suspend fun updatePast(now: Long) {
        if (now - pastWanted > 30 * MINUTE) return
        val due = activeLeagues().filter { l ->
            l.espnPath != null && now - (weekData.value["p|" + l.id]?.fetchedAt ?: 0L) > 2 * HOUR
        }
        if (due.isEmpty()) return
        val singleDays = (2..DAYS_BACK).map { d -> SportsDates.dayKey(now - d * DAY) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = LiveTvClock.nowMs()
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + ("p|" + l.id to LeagueData(parsed, if (raws.isEmpty()) at - HOUR else at, null)) }
            }
        }
    }

    /** Your teams' whole seasons (every competition), from ESPN: every 6 hours. */
    private suspend fun updateSchedules(now: Long, force: Boolean) {
        val f = follows.value
        val keys = f.teams.map { it.key }.toSet()
        if (scheduleData.value.keys.any { it !in keys }) scheduleData.update { m -> m.filterKeys { it in keys } }
        for (t in f.teams) {
            val l = SportsCatalog.league(t.leagueId) ?: continue
            val path = l.espnPath ?: continue
            val cur = scheduleData.value[t.key]
            if (cur != null && now - cur.fetchedAt < (if (force) HOUR else 6 * HOUR)) continue
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = false) }.getOrNull()?.let { raws += l to it }
            if (path.startsWith("soccer/") || l.national) {
                runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = true) }.getOrNull()?.let { raws += l to it }
                for (rid in SportsCatalog.relatedLeagues(l.id)) {
                    val rl = SportsCatalog.league(rid) ?: continue
                    val rp = rl.espnPath ?: continue
                    runCatching { feeds.teamScheduleRaw(rp, t.teamId, fixtures = true) }.getOrNull()?.let { raws += rl to it }
                }
            }
            val at = LiveTvClock.nowMs()
            if (raws.isNotEmpty()) runCatching { leagueTeams(l) }
            if (raws.isEmpty()) {
                scheduleData.update { m -> m + (t.key to LeagueData(m[t.key]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val list = mergeSchedule(raws)
            scheduleData.update { it + (t.key to LeagueData(list, at, null)) }
            runCatching {
                LiveTvFiles.writeSmall(DIR, "ts_${safe(t.key)}.json", "$at\n" + raws.joinToString(RAW_SEP) { (rl, raw) -> rl.id + "\t" + raw })
            }
        }
    }

    /** One list from a team's schedule answers: each game once, under the first competition it came with. */
    private fun mergeSchedule(raws: List<Pair<League, String>>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for ((rl, raw) in raws) {
            runCatching { SportsFeeds.parseSchedule(rl, raw) }.getOrDefault(emptyList()).forEach { ev ->
                val k = ev.espnId ?: ev.id
                if (k !in byId) byId[k] = ev
            }
        }
        // A team's schedule from ESPN leaves out the teams' colours: filled in from the league's team list and games we know.
        val known = HashMap<String, TeamInfo>()
        fun learn(t: TeamInfo) {
            if (t.color == null && t.altColor == null) return
            val k = SportsCatalog.teamFamily(t.leagueId) + "|" + t.id
            if (k !in known) known[k] = t
        }
        raws.map { it.first }.distinctBy { it.id }.forEach { l -> cachedTeams(l).forEach(::learn) }
        (leagueData.value.values + weekData.value.values).forEach { d -> d.events.forEach { ev -> ev.sides.forEach { learn(it.team) } } }
        teamCache.values().forEach { list -> list.forEach(::learn) }
        fun fill(side: Side?): Side? {
            if (side == null || side.team.color != null) return side
            val k = known[SportsCatalog.teamFamily(side.team.leagueId) + "|" + side.team.id] ?: return side
            return side.copy(team = side.team.copy(color = k.color, altColor = k.altColor, logo = side.team.logo ?: k.logo))
        }
        return byId.values.map { ev -> if (ev.sides.all { it.team.color != null }) ev else ev.copy(away = fill(ev.away), home = fill(ev.home)) }
            .sortedBy { it.startMs }
    }

    /** A league's teams as already saved (no download): for colours. */
    private fun cachedTeams(league: League): List<TeamInfo> {
        teamCache[league.id]?.let { return it }
        val text = LiveTvFiles.readSmall(DIR, "teams_${safe(league.id)}.json") ?: return emptyList()
        return runCatching { SportsFeeds.parseTeams(league, text) }.getOrDefault(emptyList())
    }

    @Volatile private var tsdbIndexAt = 0L

    /** Non-league football and the other TheSportsDB leagues: the list (twice a day) and the seasons. */
    private suspend fun updateTsdb(now: Long, force: Boolean) {
        val viewing = viewers.value > 0
        val f = follows.value
        val indexAge = now - tsdbIndexAt
        if ((viewing && indexAge > 12 * HOUR) || (force && indexAge > 30 * MINUTE)) {
            runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = LiveTvClock.nowMs()
                    runCatching { LiveTvFiles.writeSmall(DIR, "tsdb_index.json", raw) }
                }
            } ?: run { tsdbIndexAt = now - 11 * HOUR }
        }
        val wanted = (followedLeagueIds(f) + SportsCatalog.all.map { it.id }).distinct().filter { fromTsdb(it) }
        for (id in wanted) {
            val l = SportsCatalog.league(id) ?: continue
            val cur = tsdbData.value[id]
            val busy = cur?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 20 * MINUTE)..(now + 15 * MINUTE)) } == true
            val maxAge = when {
                busy && (viewing || force) -> 4 * MINUTE
                busy -> 10 * MINUTE
                force -> 30 * MINUTE
                else -> 3 * HOUR
            }
            if (cur != null && now - cur.fetchedAt < maxAge) continue
            val tid = tsdbFileId(id)
            val raw = runCatching { feeds.footballLeagueRaw(tid) }.getOrNull()
            val at = LiveTvClock.nowMs()
            if (raw == null) {
                tsdbData.update { m -> m + (id to LeagueData(m[id]?.events.orEmpty(), at - maxAge + 5 * MINUTE, "failed")) }
                continue
            }
            val teams = tsdbTeams[id].orEmpty().associateBy { it.id }
            val list = runCatching { SportsFeeds.parseFootballLeague(l, raw, teams) }.getOrDefault(emptyList())
            tsdbData.update { it + (id to LeagueData(list, at, null)) }
            runCatching { LiveTvFiles.writeSmall(DIR, "tsdb_$tid.json", raw) }
        }
        if (tsdbData.value.keys.any { it !in wanted }) tsdbData.update { m -> m.filterKeys { it in wanted } }
    }

    /** The non-league leagues and their teams, from the list file. False if it couldn't be read. */
    private fun applyTsdbIndex(raw: String): Boolean {
        val list = runCatching { SportsFeeds.parseFootballIndex(raw) }.getOrNull() ?: return false
        if (list.isEmpty()) return false
        list.forEach { if (!it.alias) tsdbTeams[it.league.id] = it.teams }
        tsdbAlias = list.filter { it.alias }.associate { it.league.id to it.tsdbId }
        SportsCatalog.setExtra(list.filterNot { it.alias }.map { it.league })
        return true
    }

    /** A league's games for the three days around today: a day at a time; failing that, whatever day the feed picks. */
    private suspend fun fetchScoreboard(l: League, days: List<String>): List<String> {
        var firstError: Throwable? = null
        val singles = days.mapNotNull { d ->
            runCatching { feeds.scoreboardDayRaw(l, d) }.onFailure { if (firstError == null) firstError = it }.getOrNull()
        }
        if (singles.isNotEmpty()) return singles
        runCatching { feeds.scoreboardDefaultRaw(l) }.getOrNull()?.let { return listOf(it) }
        throw firstError ?: FeedError("no answer")
    }

    /** [l]'s scoreboard for [days] from the shared cache nuvio-data keeps; ESPN itself only for a day the cache hasn't reached. */
    private suspend fun fetchViaSharedCache(l: League, days: List<String>, now: Long): List<String> {
        val shared = feeds.espnSharedRaw(l.id)
        val got = feeds.espnSharedDays(shared, days, 90 * MINUTE, now) { i -> if (i == 1) 20 * MINUTE else 90 * MINUTE }
        val byDay = days.map { d -> d to got[d] }
        val have = byDay.mapNotNull { it.second }
        val missing = byDay.filter { it.second == null }.map { it.first }
        if (missing.isEmpty()) return have
        val longStale = now - (leagueData.value[l.id]?.fetchedAt ?: 0L) > 90 * MINUTE
        val direct = if (longStale || have.isEmpty()) missing.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() } else emptyList()
        val all = have + direct
        if (all.isEmpty()) throw FeedError("not in the shared cache yet, and no answer from ESPN")
        return all
    }

    private suspend fun fetchWeekViaSharedCache(l: League, singleDays: List<String>, now: Long): List<String> {
        val shared = feeds.espnSharedRaw(l.id)
        val got = feeds.espnSharedDays(shared, singleDays, 6 * HOUR, now)
        val byDay = singleDays.map { d -> d to got[d] }
        val have = byDay.mapNotNull { it.second }
        val missing = byDay.filter { it.second == null }.map { it.first }
        if (missing.isEmpty()) return have
        val longStale = now - (weekData.value[l.id]?.fetchedAt ?: 0L) > 8 * HOUR
        val direct = if (longStale || have.isEmpty()) missing.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() } else emptyList()
        return have + direct
    }

    private fun describe(e: Throwable): String = e.message?.take(80)?.ifBlank { null } ?: "unknown problem"

    private suspend fun coroutineScopeFetch(list: List<League>, block: suspend (League) -> Unit) {
        coroutineScope { list.map { l -> async { block(l) } }.awaitAll() }
    }

    /** TheSportsDB's TV listings (a few hundred games' channels in many countries); the last saved copy when the file can't be reached. */
    private suspend fun updateTv() {
        val raw = runCatching { feeds.tvListingsRaw() }.onSuccess { runCatching { LiveTvFiles.writeSmall(DIR, "tv.json", it) } }.getOrNull() ?: return
        val list = runCatching { SportsTvListings.parse(raw) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) tvData.value = list
    }

    private suspend fun updateFixtures() {
        val leagues = SportsCatalog.all.filter { it.fixtureSlug != null && it.id !in tsdbAlias }
        for (l in leagues) {
            val slug = l.fixtureSlug ?: continue
            val name = "fx_$slug.json"
            val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { LiveTvFiles.writeSmall(DIR, name, it) } }.getOrNull()
                ?: LiveTvFiles.readSmall(DIR, name) ?: continue
            val list = runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList())
            fixtureData.update { it + (l.id to LeagueData(list, LiveTvClock.nowMs(), null)) }
        }
        // Wrestling's schedule (and boxing's fight cards), one file for all of them.
        val wrestling = SportsCatalog.all.filter { (it.sport == Sport.WRESTLING || it.id == "guide/boxing") && it.id !in tsdbAlias }.map { it.id }.toSet()
        if (wrestling.isNotEmpty()) {
            val raw = runCatching { feeds.wrestlingRaw() }.onSuccess { runCatching { LiveTvFiles.writeSmall(DIR, "fx_wrestling.json", it) } }.getOrNull()
                ?: LiveTvFiles.readSmall(DIR, "fx_wrestling.json")
            if (raw != null) {
                val byLeague = runCatching { SportsFeeds.parseWrestling(raw) }.getOrDefault(emptyMap())
                val at = LiveTvClock.nowMs()
                fixtureData.update { cur -> cur + byLeague.filterKeys { it in wrestling }.mapValues { (_, list) -> LeagueData(list, at, null) } }
            }
        }
    }

    private fun loadCache() {
        val now = LiveTvClock.nowMs()
        val m = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val text = LiveTvFiles.readSmall(DIR, "sb_${safe(l.id)}.json") ?: continue
            runCatching {
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 2 * DAY) return@runCatching
                m[l.id] = LeagueData(text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null)
            }
        }
        runCatching { LiveTvFiles.readSmall(DIR, "tsdb_index.json")?.let { applyTsdbIndex(it) } }
        val fx = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            val slug = l.fixtureSlug ?: continue
            val text = LiveTvFiles.readSmall(DIR, "fx_$slug.json") ?: continue
            runCatching { fx[l.id] = LeagueData(SportsFeeds.parseFixtures(l, text), 0L, null) }
        }
        runCatching {
            LiveTvFiles.readSmall(DIR, "fx_wrestling.json")?.let { text -> SportsFeeds.parseWrestling(text).forEach { (id, list) -> fx[id] = LeagueData(list, 0L, null) } }
        }
        leagueData.value = m
        fixtureData.value = fx
        val wk = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val text = LiveTvFiles.readSmall(DIR, "wk_${safe(l.id)}.json") ?: continue
            runCatching {
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 3 * DAY) return@runCatching
                wk[l.id] = LeagueData(text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null)
            }
        }
        weekData.value = wk
        val f = follows.value
        val sched = HashMap<String, LeagueData>()
        for (t in f.teams) {
            val text = LiveTvFiles.readSmall(DIR, "ts_${safe(t.key)}.json") ?: continue
            runCatching {
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                val raws = text.substring(nl + 1).split(RAW_SEP).mapNotNull { part ->
                    val tab = part.indexOf('\t')
                    if (tab <= 0) null else SportsCatalog.league(part.substring(0, tab))?.let { it to part.substring(tab + 1) }
                }
                if (raws.isNotEmpty()) sched[t.key] = LeagueData(mergeSchedule(raws), at, null)
            }
        }
        scheduleData.value = sched
        val ts = HashMap<String, LeagueData>()
        for (id in (followedLeagueIds(f).filter { it.startsWith("tsdb/") } + tsdbAlias.keys).distinct()) {
            val l = SportsCatalog.league(id) ?: continue
            val text = LiveTvFiles.readSmall(DIR, "tsdb_${tsdbFileId(id)}.json") ?: continue
            runCatching { ts[id] = LeagueData(SportsFeeds.parseFootballLeague(l, text, tsdbTeams[id].orEmpty().associateBy { it.id }), 0L, null) }
        }
        tsdbData.value = ts
        runCatching {
            LiveTvFiles.readSmall(DIR, "tv.json")?.let { raw ->
                val list = SportsTvListings.parse(raw)
                if (list.isNotEmpty()) tvData.value = list
            }
        }
    }

    // ------------------------------------------------------------------ matching channels

    /**
     * Finding channels for the games is heavy (it reads the whole guide), so it only runs when
     * something it depends on has really changed: the games (a new game, a start time moved, a
     * network added), your channels, the guide being replaced, Sports settings or follows, a new
     * day, or a Fix channel pick. Plus one round every few hours as a safety net (it used to run
     * every 15 minutes). Never while the player is open, unless you're on the Sports page.
     */
    private suspend fun matchLoop() {
        val trigger = combine(
            // The games: only what changes the search (not scores or clocks), as one number.
            events.map { list ->
                var h = 17L
                var n = 0
                for (e in list) {
                    if (e.source == EventSource.GUIDE) continue
                    h = h * 31 + e.id.hashCode(); h = h * 31 + e.startMs; h = h * 31 + e.broadcasts.size
                    n++
                }
                n to h
            }.distinctUntilChanged(),
            // Your channels (any added, removed or renamed in a playlist update).
            liveRepo.displayChannels.map { list ->
                var h = 17L
                for (c in list) h = h * 31 + c.key.hashCode()
                list.size to h
            }.distinctUntilChanged(),
            settings.map { listOf(it.networkChannels, it.eventChannels, it.teamChannels, it.leagueChannels, it.blocked, it.bestChannelFirst, it.hiddenLeagues, it.region, it.preferredGroups) }.distinctUntilChanged(),
            follows.map { it.words to it.leagues }.distinctUntilChanged(),
            liveRepo.guideGeneration
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
        var lastRun = 0L
        scope.launch {
            // Safety net: a round every few hours, in case something changed that we didn't hear about.
            while (true) {
                delay(15 * MINUTE)
                if (LiveTvClock.nowMs() - lastRun > SAFETY_ROUND) matchKick.value++
            }
        }
        scope.launch {
            // A new day: "yesterday to tomorrow" moves on, so other games come into range.
            while (true) {
                delay((dayStart(1) + MINUTE - LiveTvClock.nowMs()).coerceAtLeast(MINUTE))
                matchKick.value++
            }
        }
        scope.launch {
            // A round put off because Live TV was busy updating: run it once the update is done.
            liveRepo.status.map { it.loading }.distinctUntilChanged().collect { loading ->
                if (!loading && matchMissed) matchKick.value++
            }
        }
        // Last time's matches straight away, so the page isn't empty while the new round runs.
        scope.launch {
            val saved = LiveTvFiles.readSmall(DIR, "matches.json")
            if (saved != null) {
                val chans = liveRepo.displayChannels.first { it.isNotEmpty() }.associateBy { it.key }
                if (_matches.value.isEmpty()) runCatching { restoreMatches(saved, chans) }
            }
        }
        combine(trigger, matchKick) { t, k -> t to k }.debounce { if (_matches.value.isEmpty()) 0L else 1_500L }.collect {
            // Put off (not lost): it runs when the app is back in front / the update is done.
            if (!foreground.value && viewers.value == 0) { matchMissed = true; return@collect }
            if (!settings.value.showInSidebar && viewers.value == 0) { matchMissed = true; return@collect }
            val status = liveRepo.status.value
            // Never the very first match against a guide that's still being built.
            if (!status.loadedOnce) { matchMissed = true; return@collect }
            if (status.loading && _matches.value.isNotEmpty() && !pickPending) { matchMissed = true; return@collect }
            pickPending = false
            if (_matches.value.isEmpty() && status.loading) {
                _matching.value = true
                withTimeoutOrNull(10 * MINUTE) { liveRepo.status.first { !it.loading } }
                _matching.value = false
            }
            // Not while the player is open (unless you're on the Sports page).
            awaitNotWatching()
            matchMissed = false
            lastRun = LiveTvClock.nowMs()
            runCatching { runMatch() }
            lastMatchAt = LiveTvClock.nowMs()
            runCatching { saveMatches() }
        }
    }

    private val matchKick = MutableStateFlow(0)
    @Volatile private var lastMatchAt = 0L
    /** A round was put off (app in the background, Live TV updating): run it at the next chance. */
    @Volatile private var matchMissed = false

    /** Waits while a player screen is open; the Sports page being open ends the wait. */
    private suspend fun awaitNotWatching() {
        if (viewers.value > 0 || VideoPlaying.open.value == 0) return
        combine(VideoPlaying.open, viewers) { playing, v -> v > 0 || playing == 0 }.first { it }
        // Back from the video: a moment for the screen to settle first.
        if (viewers.value == 0) delay(5_000)
    }

    private fun saveMatches() {
        val arr = JSONArray()
        for ((id, list) in _matches.value) {
            val o = JSONObject().put("id", id)
            val items = JSONArray()
            list.forEach { m ->
                items.put(JSONObject().put("k", m.channel.key).put("s", m.score).put("h", m.how)
                    .put("q", m.quality ?: JSONObject.NULL).put("c", m.catchup))
            }
            arr.put(o.put("m", items))
        }
        LiveTvFiles.writeSmall(DIR, "matches.json", arr.toString())
    }

    private fun restoreMatches(text: String, chans: Map<String, LiveChannel>) {
        val arr = JSONArray(text)
        val out = HashMap<String, List<ChannelMatch>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("m")
            val list = ArrayList<ChannelMatch>()
            for (j in 0 until items.length()) {
                val it = items.getJSONObject(j)
                val ch = chans[it.getString("k")] ?: continue
                list += ChannelMatch(ch, it.getInt("s"), it.getString("h"), null, if (it.isNull("q")) null else it.getString("q"), it.optBoolean("c"))
            }
            if (list.isNotEmpty()) out[o.getString("id")] = list
        }
        if (out.isNotEmpty() && _matches.value.isEmpty()) _matches.value = out
    }

    @Volatile private var pickPending = false

    private suspend fun runMatch() = withContext(Dispatchers.Default) {
        val live = prefs.currentSettings()
        val user = prefs.currentUserState()
        val sp = settings.value
        // Channels you picked yourself (Fix channel) count even in a hidden group.
        val picked = HashSet<String>().apply {
            sp.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
            addAll(sp.networkChannels.values); addAll(sp.teamChannels.values); addAll(sp.leagueChannels.values)
        }
        val channels: List<LiveChannel> = liveRepo.displayChannels.value.filter { ch ->
            (ch.key in picked || (ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups)) &&
                !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
        }
        if (channels.isEmpty()) return@withContext
        _matching.value = true
        try {
            val now = LiveTvClock.nowMs()
            val shift = live.epgOffsetMinutes * MINUTE
            val s = settings.value
            val f = follows.value
            val guideLeagues = SportsCatalog.all.filter { it.guideOnly }
            val keys = channels.associateBy { it.key }
            val days = HashMap<Long, List<SportsEvent>>()
            for (offset in -1..1) {
                val start = dayStart(offset)
                days[start] = runCatching {
                    matcher.guideEvents(start, dayStart(offset + 1), guideLeagues, f.words, keys, shift, now)
                }.getOrDefault(emptyList())
            }
            guideData.value = days
            val result = matcher.match(
                SportsMatcher.Input(
                    events = events.value,
                    channels = channels,
                    quality = user.channelQuality,
                    shiftMs = shift,
                    settings = s,
                    now = now,
                    // Nobody is looking at the Sports page: take short breaks so the phone stays smooth.
                    gentle = viewers.value == 0
                )
            )
            _matches.value = result.matches
            rebuildLiveScores()
        } finally {
            _matching.value = false
        }
    }

    // ------------------------------------------------------------------ scores elsewhere in the app

    /** Channel key -> the live game it's showing (a sure match, or the only live game that channel could be showing). */
    private fun rebuildLiveScores() {
        val evs = events.value.associateBy { it.id }
        val perChannel = HashMap<String, MutableList<Pair<SportsEvent, ChannelMatch>>>()
        for ((id, list) in _matches.value) {
            val ev = evs[id] ?: continue
            if (ev.state != GameState.LIVE) continue
            list.filter { it.score >= LIKELY_SCORE }.forEach { m -> perChannel.getOrPut(m.channel.key) { ArrayList() } += ev to m }
        }
        val out = HashMap<String, SportsEvent>()
        val cards = HashMap<String, SportsEvent>()
        for ((key, found) in perChannel) {
            val sure = found.filter { it.second.sure }
            val ev = when {
                sure.isNotEmpty() -> sure.maxByOrNull { it.second.score }?.first
                found.map { it.first.id }.distinct().size == 1 -> found.first().first
                else -> null
            } ?: continue
            cards[key] = ev
            if (ev.hasScore) out[key] = ev
        }
        SportsLive.byChannel.value = out
        SportsLive.cardByChannel.value = cards
    }

    /** A team you follow scored: tell the app (Scoring Alerts). */
    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        if (!settings.value.scoringAlerts) return
        val f = follows.value
        val old = before.associateBy { it.id }
        for (ev in after) {
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            val prev = old[ev.id] ?: continue
            if (!prev.hasScore) continue
            for (side in ev.sides) {
                if (!f.followsTeam(league.id, side.team.id) && league.id !in f.leagues) continue
                val was = prev.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
                val nowScore = side.score?.toDoubleOrNull() ?: continue
                if (nowScore > was) SportsLive.alert(SportsAlert(ev, side.team, league))
            }
        }
    }

    // ------------------------------------------------------------------ tables

    private val teamCache = SyncMap<String, List<TeamInfo>>()
    private val _standings = MutableStateFlow<Map<String, Standings>>(emptyMap())
    /** League tables looked at (or needed for My Sports' row headers), by league id. */
    val standings: StateFlow<Map<String, Standings>> = _standings.asStateFlow()
    private val noTable = SyncMap<String, Long>()
    private val tableLocks = SyncMap<String, Mutex>()

    /** Whether [league] might have a table (it's asked once; one without is left out after that). */
    fun mayHaveTable(league: League): Boolean {
        if (noTable.containsKey(league.id) && LiveTvClock.nowMs() - (noTable[league.id] ?: 0L) < 12 * HOUR) return false
        if (league.guideOnly) return false
        if (league.id.startsWith("tsdb/")) return league.kind != LeagueKind.EVENT
        if (league.espnPath == null) return false
        if (league.espnPath == "cricket/panel") return false
        if (league.kind == LeagueKind.EVENT) return league.sport == Sport.RACING && league.id in RACING_TABLES
        return league.sport !in setOf(Sport.GOLF, Sport.TENNIS, Sport.FIGHTING, Sport.WRESTLING, Sport.DARTS, Sport.SNOOKER)
    }

    /** Leagues for the Tables page, in My Sports' order. */
    fun tableLeagues(f: SportsFollows = follows.value): List<League> {
        val ids = LinkedHashSet<String>()
        for (key in SportsRows.rowKeys(f)) {
            when {
                key.startsWith("t:") -> f.teams.firstOrNull { "t:${it.key}" == key }?.let { t ->
                    ids += t.leagueId
                    SportsCatalog.relatedLeagues(t.leagueId).filter { it in EUROPEAN_TABLES }.forEach { ids += it }
                }
                key.startsWith("l:") -> ids += key.removePrefix("l:")
            }
        }
        return ids.mapNotNull { SportsCatalog.league(it) }.filter { mayHaveTable(it) }
    }

    /**
     * [leagueId]'s table: from memory when it's under an hour old ([force]: fetched again),
     * otherwise from ESPN or the nuvio-data file, with recent form and places moved since the
     * last round. Null when the league has no table (or it can't be had right now).
     */
    suspend fun standings(leagueId: String, force: Boolean = false): Standings? = withContext(Dispatchers.Default) {
        val league = SportsCatalog.league(leagueId) ?: return@withContext null
        val lock = tableLocks.getOrPut(leagueId) { Mutex() }
        lock.lock()
        try {
            val now = LiveTvClock.nowMs()
            _standings.value[leagueId]?.let { if (!force && now - it.fetchedAt < HOUR) return@withContext it }
            if (!mayHaveTable(league)) return@withContext null
            val fileName = "table_${safe(leagueId)}.json"
            val raw = runCatching {
                if (leagueId.startsWith("tsdb/")) feeds.footballLeagueRaw(leagueId.removePrefix("tsdb/"))
                else feeds.standingsRaw(Standings.espnUrl(league) ?: return@withContext null)
            }.getOrElse {
                // No connection: the last copy (it says how old it is on the page).
                val old = LiveTvFiles.readSmall(DIR, fileName)
                val at = old?.substringBefore('\n')?.toLongOrNull() ?: return@withContext _standings.value[leagueId]
                return@withContext (parseTable(league, old.substringAfter('\n'), at) ?: _standings.value[leagueId])
                    ?.also { st -> _standings.update { it + (leagueId to st) } }
            }
            var table = parseTable(league, raw, now)
            if (table == null) {
                noTable[leagueId] = now
                return@withContext null
            }
            runCatching { LiveTvFiles.writeSmall(DIR, fileName, "$now\n$raw") }
            // Form (the last five as squares): from the last six weeks' results (one request).
            if (table.kind != StandingsKind.RACING && table.source == "ESPN") {
                val recent = known.value.filter { it.leagueId == leagueId }
                val past = runCatching {
                    SportsFeeds.parseScoreboard(league, feeds.scoreboardRaw(league, SportsDates.dayKey(now - 42 * DAY), SportsDates.dayKey(now)))
                }.getOrDefault(emptyList())
                table = Standings.withForm(table, Standings.formFrom((past + recent).distinctBy { it.espnId ?: it.id }, leagueId))
            }
            // Team colours for the rows: the league's team list (kept for a week), else games we know.
            if (table.kind != StandingsKind.RACING) {
                val listed = if (league.espnPath != null) runCatching { leagueTeams(league) }.getOrDefault(emptyList()).associateBy { it.id } else emptyMap()
                val fam = SportsCatalog.teamFamily(leagueId)
                val seen = HashMap<String, TeamInfo>()
                if (listed.size < table.groups.sumOf { it.rows.size }) known.value.forEach { ev ->
                    ev.sides.forEach { sd -> if (sd.team.color != null && SportsCatalog.teamFamily(sd.team.leagueId) == fam && sd.team.id !in seen) seen[sd.team.id] = sd.team }
                }
                table = table.copy(groups = table.groups.map { g ->
                    g.copy(rows = g.rows.map { r ->
                        val t = listed[r.id]?.takeIf { it.color != null || it.altColor != null } ?: seen[r.id]
                        if (t == null) r else r.copy(color = t.color, altColor = t.altColor, logo = r.logo ?: t.logo)
                    })
                })
            }
            // Places moved since the last round (remembered on this device).
            val memName = "table_moves_${safe(leagueId)}.json"
            val (moved, save) = Standings.withChanges(table, LiveTvFiles.readSmall(DIR, memName))
            runCatching { LiveTvFiles.writeSmall(DIR, memName, save) }
            _standings.update { it + (leagueId to moved) }
            moved
        } finally {
            lock.unlock()
        }
    }

    private fun parseTable(league: League, raw: String, at: Long): Standings? = runCatching {
        if (league.id.startsWith("tsdb/")) Standings.parseTsdb(league, raw, at) else Standings.parseEspn(league, raw, at)
    }.getOrNull()?.takeIf { s -> s.groups.any { it.rows.isNotEmpty() } }

    /** The tables My Sports' row headers need ("2nd in Premier League"): your teams' own leagues. */
    suspend fun loadTeamTables() {
        val ids = follows.value.teams.map { it.leagueId }.distinct()
        for (id in ids) {
            val l = SportsCatalog.league(id) ?: continue
            if (!mayHaveTable(l)) continue
            runCatching { standings(id) }
        }
    }

    /** A team as the app knows it (colours and logo from its league's team list), for a row of a table. */
    fun teamFor(leagueId: String, row: StandingsRow): TeamInfo {
        val league = SportsCatalog.league(leagueId)
        val info = league?.let { l -> (teamCache[l.id] ?: cachedTeams(l)).firstOrNull { it.id == row.id } }
            ?: tsdbTeams[leagueId]?.firstOrNull { it.id == row.id }
            ?: known.value.asSequence().flatMap { it.sides.asSequence() }.map { it.team }
                .firstOrNull { it.id == row.id && SportsCatalog.teamFamily(it.leagueId) == SportsCatalog.teamFamily(leagueId) }
        return info?.copy(leagueId = leagueId, logo = info.logo ?: row.logo)
            ?: TeamInfo(leagueId, row.id, row.shortName, row.name, row.abbr, null, null, null, row.logo)
    }

    /** A team's games for its card: results and games to come (another team's season is fetched and kept for an hour). */
    suspend fun teamGames(team: TeamInfo): List<SportsEvent> = withContext(Dispatchers.Default) {
        val fam = SportsCatalog.teamFamily(team.leagueId)
        fun mine(ev: SportsEvent) = ev.sides.any { it.team.id == team.id && SportsCatalog.teamFamily(it.team.leagueId) == fam }
        val now = LiveTvClock.nowMs()
        val league = SportsCatalog.league(team.leagueId)
        val followed = follows.value.teams.any { it.leagueId == team.leagueId && it.teamId == team.id }
        val cur = lookedUp.value[team.key]
        val path = league?.espnPath
        if (!followed && league != null && path != null && (cur == null || now - cur.fetchedAt > HOUR)) {
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = false) }.getOrNull()?.let { raws += league to it }
            if (path.startsWith("soccer/") || league.national) {
                runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = true) }.getOrNull()?.let { raws += league to it }
            }
            if (raws.isNotEmpty()) {
                val list = mergeSchedule(raws)
                lookedUp.update { m -> (m + (team.key to LeagueData(list, now, null))).entries.sortedByDescending { it.value.fetchedAt }.take(6).associate { it.key to it.value } }
            }
        }
        known.value.filter(::mine).distinctBy { dedupeKey(it) }.sortedBy { it.startMs }
    }

    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.Default) {
        if (league.id.startsWith("tsdb/")) {
            if (tsdbTeams[league.id] == null) runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = LiveTvClock.nowMs()
                    runCatching { LiveTvFiles.writeSmall(DIR, "tsdb_index.json", raw) }
                }
            }
            return@withContext tsdbTeams[league.id].orEmpty()
        }
        // Countries: the same list whichever international competition you open.
        if (league.national) {
            val all = LinkedHashMap<String, TeamInfo>()
            for (l in SportsCatalog.leagues.filter { it.national && it.nationalGroup == league.nationalGroup }) {
                leagueTeams(l).forEach { t -> if (t.id !in all) all[t.id] = if (l.id == league.id) t else t.copy(leagueId = league.id) }
            }
            return@withContext all.values.sortedBy { it.fullName.lowercase() }
        }
        leagueTeams(league)
    }

    private suspend fun leagueTeams(league: League): List<TeamInfo> {
        teamCache[league.id]?.let { return it }
        if (league.espnPath == null) return emptyList()
        val name = "teams_${safe(league.id)}.json"
        val fresh = LiveTvFiles.exists(DIR, name) && LiveTvClock.nowMs() - LiveTvFiles.lastModified(DIR, name) < 7 * DAY
        val raw = (if (fresh) LiveTvFiles.readSmall(DIR, name) else null)
            ?: runCatching { feeds.teamsRaw(league).also { runCatching { LiveTvFiles.writeSmall(DIR, name, it) } } }.getOrNull()
            ?: LiveTvFiles.readSmall(DIR, name)
            ?: return emptyList()
        val list = runCatching { SportsFeeds.parseTeams(league, raw) }.getOrDefault(emptyList())
            .sortedBy { (it.location ?: it.fullName).lowercase() }
        if (list.isNotEmpty()) teamCache[league.id] = list
        return list
    }

    /** A game's summary as last asked: when, the score then, its scoring plays and the rest. */
    private class GameSummary(val at: Long, val sig: String, val plays: List<ScoringPlay>, val extras: GameExtras)
    private val summaryCache = SyncMap<String, GameSummary>()

    private suspend fun summaryFor(ev: SportsEvent): GameSummary? = withContext(Dispatchers.Default) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext null
        if (league.kind != LeagueKind.TEAMS) return@withContext null
        val tsdbId = if (league.espnPath == null && ev.id.startsWith("tsdb:") && ev.home != null) ev.id.substringAfterLast(':') else null
        val id = ev.espnId ?: tsdbId ?: return@withContext null
        if (league.espnPath == null && tsdbId == null) return@withContext null
        val sig = "${ev.away?.score}-${ev.home?.score}-${ev.state}"
        val cached = summaryCache[ev.id]
        if (cached != null && cached.sig == sig && LiveTvClock.nowMs() - cached.at < (if (ev.state == GameState.LIVE) 25_000 else 30 * MINUTE)) return@withContext cached
        if (tsdbId != null && ev.state == GameState.PRE) return@withContext null
        val root = runCatching {
            if (tsdbId != null) SportsFeeds.tsdbSummary(feeds.tsdbGameRaw(tsdbId), ev) else feeds.summaryJson(league, id)
        }.getOrNull() ?: return@withContext cached
        val plays = if (ev.state == GameState.PRE) emptyList() else runCatching { SportsFeeds.parseSummary(root) }.getOrDefault(emptyList())
        val extras = runCatching { SportsExtras.parse(root, ev, league.sport) }.getOrDefault(GameExtras())
        GameSummary(LiveTvClock.nowMs(), sig, plays, extras).also { summaryCache[ev.id] = it }
    }

    /** The game's scoring plays (touchdowns, goals…), when ESPN has them. */
    suspend fun scoringPlays(ev: SportsEvent): List<ScoringPlay> =
        if (ev.state == GameState.PRE) emptyList() else summaryFor(ev)?.plays.orEmpty()

    /** Everything else about the game ESPN has (see [GameExtras]); null when there's nothing to ask. */
    suspend fun gameExtras(ev: SportsEvent): GameExtras? = summaryFor(ev)?.extras

    // ------------------------------------------------------------------ your choices

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) {
        prefs.editSportsSettings { raw -> transform(SportsSettings.fromJson(raw)).toJson() }
    }

    /** Turn a league on or off for All Events (Choose leagues); a league just turned on is fetched right away. */
    fun toggleLeagueShown(league: League) {
        updateSettings { SportsCatalog.toggleShown(league, it) }
        kick.value++
    }

    /** In your region and switched off (not counting leagues you follow): the "hidden" count on All Events. */
    fun hiddenLeagueCount(s: SportsSettings = settings.value, f: SportsFollows = follows.value): Int {
        val region = SportsCatalog.regionOf(s)
        val mine = followedLeagueIds(f)
        return SportsCatalog.all.count { region in it.regions && !SportsCatalog.shown(it, s) && it.id !in mine }
    }

    fun updateFollows(transform: (SportsFollows) -> SportsFollows) {
        prefs.editSportsMine { raw -> transform(SportsFollows.fromJson(raw)).toJson() }
        kick.value++
    }

    fun toggleTeam(team: TeamInfo) {
        val adding = follows.value.followedTeam(team) == null
        updateFollows { f ->
            val had = f.followedTeam(team)
            if (had != null) f.copy(teams = f.teams - had)
            else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
        }
        if (adding) GameAlerts.onFollowed(team.name)
    }

    /** Moves a row of My Sports from [from] to [to] (Arrange My Sports). */
    fun moveRow(from: Int, to: Int) = updateFollows { f ->
        val keys = SportsRows.rowKeys(f).toMutableList()
        if (from !in keys.indices || to !in keys.indices) f
        else {
            keys.add(to, keys.removeAt(from))
            f.copy(order = keys)
        }
    }

    /** A league's own row under My Sports (and its button in All Events): on or off. */
    fun togglePin(leagueId: String) {
        val adding = leagueId !in follows.value.pins
        updateFollows { f ->
            if (leagueId in f.pins) f.copy(pins = f.pins - leagueId, unpinned = f.unpinned + leagueId)
            else f.copy(pins = f.pins + leagueId, unpinned = f.unpinned - leagueId, setUp = true)
        }
        if (adding) SportsCatalog.league(leagueId)?.let { GameAlerts.onFollowed(it.name) }
    }

    fun toggleLeague(leagueId: String) {
        val adding = leagueId !in follows.value.leagues
        updateFollows { f ->
            f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
        }
        if (adding) SportsCatalog.league(leagueId)?.let { GameAlerts.onFollowed(it.name) }
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }

    fun markSetUp() = updateFollows { it.copy(setUp = true) }

    fun isReminded(ev: SportsEvent): Boolean = follows.value.reminders.any { it.eventId == ev.id }

    fun toggleReminder(ev: SportsEvent) {
        val adding = !isReminded(ev)
        updateFollows { f ->
            if (f.reminders.any { it.eventId == ev.id }) f.copy(reminders = f.reminders.filterNot { it.eventId == ev.id })
            else f.copy(reminders = (f.reminders.filter { it.startMs > LiveTvClock.nowMs() - 3 * HOUR } + SportsReminder(ev.id, ev.title, ev.startMs)))
        }
        // A reminder can't show without notifications: ask for them now.
        if (adding) GameAlerts.askForNotifications()
    }

    /** This game's reminder: open the game by itself when it starts, or just tell you (sets a reminder if there wasn't one). */
    fun setAutoSwitch(ev: SportsEvent, on: Boolean) {
        updateFollows { f ->
            val has = f.reminders.any { it.eventId == ev.id }
            f.copy(reminders = if (has) f.reminders.map { if (it.eventId == ev.id) it.copy(autoSwitch = on) else it }
                else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs, on))
        }
        GameAlerts.askForNotifications()
    }

    /** Whether [ev]'s reminder opens the game by itself: its own choice, else the setting. */
    fun switchesFor(ev: SportsEvent): Boolean =
        follows.value.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: settings.value.autoSwitch

    fun dropReminder(eventId: String) = updateFollows { f -> f.copy(reminders = f.reminders.filterNot { it.eventId == eventId }) }

    /** "This is the right channel" for [ev]. With [forNetworks], also for every game the same network has from now on. */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetworks: Boolean, forTeam: TeamInfo? = null, forLeague: Boolean = false) {
        updateSettings { s ->
            val keep = s.eventChannels.filterValues { v -> (v.substringAfterLast('|').toLongOrNull() ?: 0L) > LiveTvClock.nowMs() - 3 * DAY }
            s.copy(
                eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
                networkChannels = if (forNetworks) s.networkChannels + ev.broadcasts.associate { SportsMatcher.networkKey(it) to channelKey } else s.networkChannels,
                teamChannels = if (forTeam != null) s.teamChannels + (teamChannelKey(ev.leagueId, forTeam.id) to channelKey) else s.teamChannels,
                leagueChannels = if (forLeague) s.leagueChannels + (ev.leagueId to channelKey) else s.leagueChannels,
                blocked = s.blocked - "${ev.id}|$channelKey"
            )
        }
        // Show it on the game straight away, and search again now.
        liveRepo.displayChannels.value.firstOrNull { it.key == channelKey }?.let { ch ->
            val mine = ChannelMatch(ch, 200, "Your pick", null, SportsMatcher.nameQuality(ch.name), false)
            _matches.value = _matches.value + (ev.id to (listOf(mine) + _matches.value[ev.id].orEmpty().filter { it.channel.key != channelKey }))
        }
        pickPending = true
        matchKick.value++
    }

    /** "Not this channel" for [ev] (and it stops being used for its network). */
    fun blockChannel(ev: SportsEvent, channelKey: String) {
        updateSettings { s ->
            s.copy(
                blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
                eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBeforeLast('|') == channelKey },
                networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } },
                teamChannels = s.teamChannels.filterNot { (t, key) -> key == channelKey && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) == t } },
                leagueChannels = s.leagueChannels.filterNot { (l, key) -> key == channelKey && l == ev.leagueId }
            )
        }
        _matches.value[ev.id]?.let { l -> _matches.value = _matches.value + (ev.id to l.filter { it.channel.key != channelKey }) }
        pickPending = true
        matchKick.value++
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet(), teamChannels = emptyMap(), leagueChannels = emptyMap()) }

    /** The channel to tune for a reminder or "watch now": the best match, if any. */
    fun bestChannel(eventId: String): ChannelMatch? =
        _matches.value[eventId]?.let { ChannelRegions.regionFirst(it, settings.value.channelRegion) }?.firstOrNull()

    /** The event with this id, from everything known (null once it's dropped out of the window). */
    fun eventById(id: String): SportsEvent? = events.value.firstOrNull { it.id == id } ?: known.value.firstOrNull { it.id == id }

    fun channelsForPicker(): List<LiveChannel> = liveRepo.displayChannels.value

    /** League Networks: use this channel for the network (saved, and synced with the other Sports settings). */
    fun pickLeagueNetwork(networkId: String, channelKey: String) =
        updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels + (networkId to channelKey)) }

    /** League Networks: back to the automatic channel. */
    fun resetLeagueNetwork(networkId: String) =
        updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels - networkId) }

    /**
     * What's on now and what's next on each channel, from the stored guide (a week ahead, so a
     * channel that's only on some nights still says when). Times are shifted by the guide offset.
     */
    suspend fun nowAndNext(keys: Collection<String>): Map<String, NowNext> {
        if (keys.isEmpty()) return emptyMap()
        val shift = prefs.currentSettings().epgOffsetMinutes * MINUTE
        val now = LiveTvClock.nowMs()
        val listings = runCatching { liveRepo.programsIn(keys, now - shift, now - shift + 7 * DAY) }.getOrDefault(emptyMap())
        return keys.associateWith { k ->
            val list = listings[k].orEmpty()
                .map { p -> if (shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift) }
                .sortedBy { it.startMs }
            NowNext(list.firstOrNull { now >= it.startMs && now < it.stopMs }, list.firstOrNull { it.startMs > now })
        }
    }

    // ------------------------------------------------------------------ days

    fun dayStart(offset: Int): Long = SportsDates.dayStart(offset)

    private fun dayOf(ms: Long): Long = SportsDates.dayOf(ms)

    /** The ESPN (US Eastern) dates for yesterday, today and tomorrow. */
    private fun espnDays(now: Long): List<String> = listOf(now - DAY, now, now + DAY).map { SportsDates.dayKey(it) }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    /** Racing series with a drivers' table on ESPN. */
    private val RACING_TABLES = setOf("racing/f1", "racing/nascar-premier", "racing/nascar-secondary", "racing/nascar-truck", "racing/irl")
    /** Club competitions with a table of their own (the league phase), shown with your team's league. */
    private val EUROPEAN_TABLES = setOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    /** The safety-net round: this long after the last one. */
    private const val SAFETY_ROUND = 4 * HOUR
    private const val DAY = 24 * HOUR
    /** The page's days run from this many days back to [DAYS_AHEAD] ahead. */
    const val DAYS_AHEAD = 7
    const val DAYS_BACK = 7
    /** Between saved answers when a league was asked one day at a time. */
    private const val RAW_SEP = "\n\u0001\n"
    /** A "maybe" channel match good enough to show its game's score when nothing else could be on it. */
    private const val LIKELY_SCORE = 52
}

/** A team you follow just scored (shown for a few seconds wherever you are). */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = LiveTvClock.nowMs())

/** Sports data the rest of the app shows: scores on channel rows, scoring alerts. */
object SportsLive {
    val byChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    /** Channel key -> the live game on it (with or without a score). */
    val cardByChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    val alerts = MutableStateFlow<SportsAlert?>(null)
    fun alert(a: SportsAlert) { alerts.value = a }
}
