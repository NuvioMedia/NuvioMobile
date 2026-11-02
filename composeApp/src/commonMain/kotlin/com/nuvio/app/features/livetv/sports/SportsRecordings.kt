package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.record.RecordingSchedule
import kotlinx.coroutines.flow.StateFlow

/**
 * Recording a game from Sports: the channel you pick, from a few minutes before the start, for
 * as long as you choose (overtime, extra time), using Live TV's own recordings (phase 3's scheduler).
 */
internal object SportsRecordings {
    /** Sports start a little early: 5 minutes before kick-off at least. */
    const val EARLY_MS = 5 * 60_000L
    val LENGTHS = (2..12).map { it * 30 } // 1:00 to 6:00

    val scheduled: StateFlow<List<RecordingSchedule>> get() = LiveTvRecordingScheduler.schedules

    private val LIVE_MARK = Regex("""^\s*live\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
    private val NEW_MARK = Regex("""^\s*new\s*[:\-–|]\s*|\s*[(\[]\s*new\s*[)\]]\s*""", RegexOption.IGNORE_CASE)

    /** How recordings name a show (the same clean-up Live TV's recordings use: no "LIVE:" or "(NEW)"). */
    fun titleKey(title: String): String {
        var t = SportsCatalog.withoutSmallMarks(title)
        t = t.replace(LIVE_MARK, "").replace(NEW_MARK, " ").trim()
        return t.ifBlank { title }.trim()
    }

    /** The recording set for [ev] (by its title, starting up to the early padding before it). */
    fun forEvent(ev: SportsEvent, list: List<RecordingSchedule> = scheduled.value): RecordingSchedule? {
        val key = titleKey(ev.title)
        return list.firstOrNull { (it.title == key || it.title == ev.title.trim()) && it.startMs in (ev.startMs - 15 * 60_000L)..(ev.startMs + 60_000L) }
    }

    /** The usual length for the sport, rounded up to the half hour (football 2:00, NFL 3:30). */
    fun defaultLength(ev: SportsEvent): Int {
        val m = SportsCatalog.league(ev.leagueId)?.minutes ?: 180
        val rounded = ((m + 29) / 30) * 30
        return rounded.coerceIn(LENGTHS.first(), LENGTHS.last())
    }

    fun label(minutes: Int): String = hoursMinutes(minutes)

    /**
     * Records [ev] on [m]'s channel: from 5 minutes before the start (or Live TV's own "record
     * early", if that's more) for [minutes] from the start, plus Live TV's "record late".
     * Returns the message to show.
     */
    fun record(ev: SportsEvent, m: ChannelMatch, minutes: Int): String {
        val pre = LiveTvRecordingScheduler.padding().first
        val extraEarly = (EARLY_MS - pre).coerceAtLeast(0L)
        val league = SportsCatalog.league(ev.leagueId)
        val program = EpgProgram(
            startMs = ev.startMs - extraEarly,
            stopMs = ev.startMs + minutes * 60_000L,
            title = ev.title,
            description = listOfNotNull(league?.name, ev.subtitle).joinToString(" · ").ifBlank { null }
        )
        return LiveTvRecordingScheduler.add(m.channel, program)
    }

    fun cancel(ev: SportsEvent) {
        forEvent(ev)?.let { LiveTvRecordingScheduler.cancel(it.id) }
    }
}
