package com.nuvio.app.features.livetv.sports

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.livetv.LiveTvAlarms
import com.nuvio.app.features.livetv.LiveTvDeepLinks
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.record.RecordingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Game reminders and scoring alerts on the phone. The TV app shows a pop-up and can switch to
 * the game by itself; here an alarm wakes the app a little before kick-off (the Sports "remind
 * me" lead) and a notification says the game is about to start, with Open taking you to it: to
 * the channel when "switch to the game automatically" is on and a channel is sure, otherwise to
 * the game's page. Teams you follow scoring (Scoring alerts) post a notification too.
 */
@OptIn(FlowPreview::class)
internal object SportsReminders {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = SportsRepository
    @Volatile private var started = false

    /** Event id -> when its alarm is set for. */
    private val armed = SyncMap<String, Long>()
    /** Reminders already shown this run (a game that moved a little doesn't ring twice). */
    private val shown = SyncSet<String>()

    fun start() {
        if (started) return
        started = true
        repo.start()
        scope.launch {
            combine(repo.follows, repo.settings, repo.events) { f, s, e -> Triple(f, s, e) }
                .debounce(2_000)
                .collect { runCatching { rearm() } }
        }
        scope.launch {
            SportsLive.alerts.filterNotNull().collect { a -> runCatching { noticeScore(a) } }
        }
    }

    // ------------------------------------------------------------------ what to remind about

    private class Due(val eventId: String, val title: String, val startMs: Long)

    /** Games to remind about: the ones you set, and (Remind me about my teams) your teams' games coming up. */
    private fun due(now: Long): List<Due> {
        val s = repo.settings.value
        val f = repo.follows.value
        val events = repo.events.value
        val out = LinkedHashMap<String, Due>()
        f.reminders.forEach { r ->
            val ev = events.firstOrNull { it.id == r.eventId }
            val start = ev?.startMs ?: r.startMs
            out[r.eventId] = Due(r.eventId, ev?.title ?: r.title, start)
        }
        if (s.remindMyTeams) events.forEach { ev ->
            if (ev.state != GameState.PRE || !ev.hasTime || ev.source == EventSource.GUIDE) return@forEach
            val mine = ev.leagueId in f.leagues || ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
                (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT)
            if (mine && ev.id !in out) out[ev.id] = Due(ev.id, ev.title, ev.startMs)
        }
        return out.values.filter { it.startMs + 10 * MINUTE > now && it.eventId !in shown }.sortedBy { it.startMs }
    }

    private fun leadMs(): Long = repo.settings.value.remindMinutes.coerceAtLeast(0) * MINUTE

    /** When [startMs]'s reminder fires: the lead before the start (now, when that's already passed). */
    fun fireTime(startMs: Long, leadMs: Long, now: Long): Long = maxOf(startMs - leadMs, now + 1_000L)

    /** Sets an alarm for each game due in the next few days, and drops alarms for games no longer wanted. */
    private fun rearm() {
        val now = LiveTvClock.nowMs()
        val lead = leadMs()
        val wanted = due(now).filter { it.startMs < now + 3 * DAY }.take(40)
        val keep = wanted.mapTo(HashSet()) { it.eventId }
        armed.snapshot().keys.filter { it !in keep }.forEach { id ->
            armed.remove(id)
            runCatching { RecordingPlatform.cancelAlarm(LiveTvAlarms.SPORTS + id) }
        }
        wanted.forEach { d ->
            val at = fireTime(d.startMs, lead, now)
            if (armed[d.eventId] == at) return@forEach
            armed[d.eventId] = at
            runCatching { RecordingPlatform.scheduleAlarm(LiveTvAlarms.SPORTS + d.eventId, at) }
        }
    }

    // ------------------------------------------------------------------ the alarm went off

    /** The reminder for [eventId] is due: a notification (the app may not be open). */
    fun fire(eventId: String) {
        if (!shown.add(eventId)) return
        armed.remove(eventId)
        val now = LiveTvClock.nowMs()
        val ev = repo.eventById(eventId)
        val saved = repo.follows.value.reminders.firstOrNull { it.eventId == eventId }
        val title = ev?.title ?: saved?.title ?: return
        val start = ev?.startMs ?: saved?.startMs ?: return
        if (start + 10 * MINUTE < now) return
        val mins = ((start - now) / MINUTE).toInt()
        val heading = when {
            mins <= 0 -> "Starting now"
            mins == 1 -> "Starting in a minute"
            else -> "Starting in $mins minutes"
        }
        val best = repo.bestChannel(eventId)
        val switch = ev != null && best != null && best.sure && repo.switchesFor(ev)
        val league = ev?.let { SportsCatalog.league(it.leagueId) }
        val text = listOfNotNull(
            title,
            best?.let { "On ${it.channel.name}" + (if (switch) " · Open plays it" else "") },
            league?.name,
            clockText(start)
        ).joinToString(" · ")
        val url = when {
            switch -> LiveTvDeepLinks.channelUrl(best!!.channel.key)
            ev != null -> LiveTvDeepLinks.sportsGameUrl(ev.id)
            else -> LiveTvDeepLinks.sportsUrl()
        }
        RecordingPlatform.notifyReminder("sport:$eventId", heading, text, url, important = true)
        // A reminder you set is done once it's rung.
        if (saved != null) repo.dropReminder(eventId)
    }

    // ------------------------------------------------------------------ scoring alerts

    private fun noticeScore(a: SportsAlert) {
        if (repo.settings.value.hideScores) return
        val ev = a.event
        val sport = a.league.sport
        val sides = ev.ordered(sport)
        val score = sides.joinToString("  –  ") { "${it.team.abbr.ifBlank { it.team.name }} ${it.score ?: ""}".trim() }
        val what = when (sport) {
            Sport.FOOTBALL, Sport.HOCKEY -> "GOAL"
            Sport.AMERICAN_FOOTBALL -> "SCORE"
            Sport.BASEBALL -> "RUN"
            else -> "SCORE"
        }
        val heading = "$what · ${a.team.name}"
        val text = score + (ev.status?.let { " · $it" } ?: "")
        NuvioToastController.show("$heading  $text", durationMillis = 4_000L)
        RecordingPlatform.notifyReminder("sport-alert:${ev.id}", heading, text, LiveTvDeepLinks.sportsGameUrl(ev.id), important = false)
        if (SportsLive.alerts.value == a) SportsLive.alerts.value = null
    }

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
}
