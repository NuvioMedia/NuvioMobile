package com.nuvio.app.features.livetv.reminders

import com.nuvio.app.features.livetv.LiveTvAlarms
import com.nuvio.app.features.livetv.LiveTvDeepLinks
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.Reminder
import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.livetv.sports.clockText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * "Remind me" on a show in the guide. The reminders themselves live in Live TV's preferences
 * under the TV's "reminders" key (so they sync and round-trip through the Drive backup); this
 * sets an alarm for each one, a little before the show (Settings → Live TV → Reminders → lead),
 * and posts a notification whose Open plays the channel. The TV app shows a pop-up instead.
 */
@OptIn(FlowPreview::class)
internal object LiveTvReminders {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = LiveTvPreferences
    @Volatile private var started = false

    /** Reminder id -> when its alarm is set for. */
    private val armed = HashMap<String, Long>()
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** The id an alarm and a notification use for a reminder. */
    fun idOf(r: Reminder): String = r.channelKey + "|" + r.startMs

    fun parseId(id: String): Pair<String, Long>? {
        val i = id.lastIndexOf('|')
        if (i <= 0) return null
        return id.substring(0, i) to (id.substring(i + 1).toLongOrNull() ?: return null)
    }

    /** When a reminder rings: [leadMs] before the show, or straight away when that's already passed. */
    fun fireTime(startMs: Long, leadMs: Long, now: Long): Long = maxOf(startMs - leadMs, now + 1_000L)

    /** A reminder is still worth ringing until a little after the show has started. */
    fun isDue(startMs: Long, now: Long, graceMs: Long = 10 * MINUTE): Boolean = startMs + graceMs > now

    /** "Starting now", "Starting in a minute", "Starting in 5 minutes". */
    fun heading(startMs: Long, now: Long): String {
        val mins = ((startMs - now) / MINUTE).toInt()
        return when {
            mins <= 0 -> "Starting now"
            mins == 1 -> "Starting in a minute"
            else -> "Starting in $mins minutes"
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(prefs.userState.map { it.reminders }.distinctUntilChanged(), prefs.reminderLeadMinutes) { r, lead -> r to lead }
                .debounce(1_000)
                .collect { (reminders, lead) -> runCatching { rearm(reminders, lead * MINUTE) } }
        }
    }

    private fun rearm(reminders: List<Reminder>, leadMs: Long) {
        val now = LiveTvClock.nowMs()
        runCatching { prefs.pruneReminders(now) }
        val wanted = reminders.filter { isDue(it.startMs, now) }.associateBy { idOf(it) }
        val (gone, toSet) = kotlinx.atomicfu.locks.synchronized(lock) {
            val g = armed.keys.filter { it !in wanted }
            g.forEach { armed.remove(it) }
            val t = ArrayList<Pair<String, Long>>()
            wanted.forEach { (id, r) ->
                val at = fireTime(r.startMs, leadMs, now)
                if (armed[id] != at) { armed[id] = at; t += id to at }
            }
            g to t
        }
        gone.forEach { runCatching { RecordingPlatform.cancelAlarm(LiveTvAlarms.REMINDER + it) } }
        toSet.forEach { (id, at) -> runCatching { RecordingPlatform.scheduleAlarm(LiveTvAlarms.REMINDER + id, at) } }
    }

    /** The alarm for reminder [id] went off: a notification, and the reminder is done. */
    fun fire(id: String) {
        val (key, start) = parseId(id) ?: return
        kotlinx.atomicfu.locks.synchronized(lock) { armed.remove(id) }
        val r = prefs.currentUserState().reminders.firstOrNull { it.channelKey == key && it.startMs == start } ?: return
        val now = LiveTvClock.nowMs()
        if (isDue(r.startMs, now)) {
            RecordingPlatform.notifyReminder(
                LiveTvAlarms.REMINDER + id,
                heading(r.startMs, now),
                "${r.title} · ${r.channelName} · ${clockText(r.startMs)}",
                LiveTvDeepLinks.channelUrl(r.channelKey),
                important = true,
            )
        }
        prefs.removeReminder(key, start)
    }

    /** Sets or clears a reminder from the guide. */
    fun toggle(r: Reminder): Boolean {
        val has = prefs.currentUserState().reminders.any { it.channelKey == r.channelKey && it.startMs == r.startMs }
        if (has) prefs.removeReminder(r.channelKey, r.startMs) else prefs.addReminder(r)
        return !has
    }

    fun isSet(channelKey: String, startMs: Long): Boolean =
        prefs.currentUserState().reminders.any { it.channelKey == channelKey && it.startMs == startMs }

    private const val MINUTE = 60_000L
}
