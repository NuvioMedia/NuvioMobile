package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.LiveTvDeepLinks
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Scores for my teams while they play (Game Alerts > Scores for my teams). While a team you
 * follow is playing, the score stays at the top of the screen (see [SportsLivePlatform]) and the
 * app keeps checking it in the background, so goal alerts arrive with the app closed. An alarm at
 * kick-off starts it when the app isn't open; it ends by itself at full time.
 */
@OptIn(FlowPreview::class)
internal object SportsLiveWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = SportsRepository
    @Volatile private var started = false
    @Volatile private var showing = false

    /** Games you pressed Stop on (until they're over). */
    private val stopped = SyncSet<String>()

    private const val MINUTE = 60_000L
    /** How long a game that should have started is waited for before giving up. */
    private const val WAIT_FOR_START = 30 * MINUTE

    fun start() {
        if (started) return
        started = true
        repo.start()
        scope.launch {
            combine(repo.events, repo.follows, repo.settings) { e, f, s -> Triple(e, f, s) }
                .debounce(1_000)
                .collect { (e, f, s) -> runCatching { refresh(e, f, s) } }
        }
        // The list above only changes with new scores: check the clock now and then too (a game
        // that never started, the notice for one that ended).
        scope.launch {
            while (true) {
                delay(MINUTE)
                runCatching { refresh(repo.events.value, repo.follows.value, repo.settings.value) }
            }
        }
    }

    /** The kick-off alarm went off (the app may not be open): show the notice straight away. */
    fun fromAlarm(eventId: String) {
        scope.launch {
            runCatching {
                val s = repo.storedSettings()
                if (!s.scoringAlerts || s.hideScores || !GameAlerts.notificationsAllowed()) return@launch
                // Started inside the alarm's few seconds, when Android allows it from the background.
                val ev = repo.eventById(eventId)
                showing = SportsLivePlatform.show(ev?.title ?: "Your game", "Starting now", ev?.let { LiveTvDeepLinks.sportsGameUrl(it.id) })
                repo.awaitSaved()
                start()
                refresh(repo.events.value, repo.storedFollows(), s)
            }
        }
    }

    /** The notice's Stop button: no more live score for the games on now. */
    fun stopForNow() {
        games(repo.events.value, repo.follows.value, repo.settings.value).forEach { stopped.add(it.id) }
        showing = false
        repo.setWatching(false)
        SportsLivePlatform.stop()
    }

    /** Your teams' games to watch: on now, or due to start any minute. */
    fun games(events: List<SportsEvent>, f: SportsFollows, s: SportsSettings, now: Long = LiveTvClock.nowMs()): List<SportsEvent> {
        if (!s.scoringAlerts || s.hideScores) return emptyList()
        return events.filter { ev ->
            ev.id !in stopped && isMine(ev, f) && when (ev.state) {
                GameState.LIVE -> true
                GameState.PRE -> ev.hasTime && ev.startMs in (now - WAIT_FOR_START)..(now + 2 * MINUTE)
                else -> false
            }
        }.sortedWith(compareBy({ it.state != GameState.LIVE }, { it.startMs }))
    }

    /** A game of a team (or a whole league) you follow: the same ones Scoring alerts are for. */
    fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
        ev.leagueId in f.leagues || ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) }

    private fun refresh(events: List<SportsEvent>, f: SportsFollows, s: SportsSettings) {
        val list = games(events, f, s)
        if (list.isEmpty() || !GameAlerts.notificationsAllowed()) {
            repo.setWatching(false)
            if (showing) { showing = false; SportsLivePlatform.stop() }
            return
        }
        repo.setWatching(true)
        val first = list.first()
        val title = line(first)
        val text = buildList {
            add(listOfNotNull(detail(first), SportsCatalog.league(first.leagueId)?.name).joinToString(" · "))
            list.drop(1).take(3).forEach { add(line(it) + (detail(it)?.let { d -> " · $d" } ?: "")) }
        }.filter { it.isNotBlank() }.joinToString("\n")
        val url = if (list.size == 1) LiveTvDeepLinks.sportsGameUrl(first.id) else LiveTvDeepLinks.sportsUrl()
        showing = SportsLivePlatform.show(title, text, url) || showing
    }

    /** "ARS 1 – 0 CHE", or the game's name before it has a score. */
    private fun line(ev: SportsEvent): String {
        val a = ev.away
        val h = ev.home
        if (a == null || h == null || !ev.hasScore) return ev.title
        fun name(side: Side) = side.team.abbr.ifBlank { side.team.name }
        return "${name(a)} ${a.score}  –  ${h.score} ${name(h)}"
    }

    /** "63'", "2nd Quarter 4:12", or "Starting soon". */
    private fun detail(ev: SportsEvent): String? = when (ev.state) {
        GameState.PRE -> "Starting soon"
        else -> ev.status?.takeIf { it.isNotBlank() }
    }
}
