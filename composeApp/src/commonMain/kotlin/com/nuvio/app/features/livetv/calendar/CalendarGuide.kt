package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.livetv.data.EpgStore
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.reminders.LiveTvReminders
import com.nuvio.app.features.livetv.model.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A channel in your Live TV guide showing a Calendar episode. */
internal data class CalendarGuideHit(
    val channelKey: String,
    val channelName: String,
    val channelLogo: String?,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val playlist: String? = null,
    val catchup: Boolean = false,
    val favorite: Boolean = false,
    val group: String? = null,
    /** The guide's own episode number says it's this very episode (not a rerun). */
    val confirmed: Boolean = false,
)

/**
 * Finds which of your Live TV channels are showing each upcoming Calendar episode, by searching
 * the guide around the time the episode airs. Best match first.
 */
internal object CalendarGuide {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private val _hits = MutableStateFlow<Map<String, List<CalendarGuideHit>>>(emptyMap())
    /** Entry key -> channels showing it. */
    val hits: StateFlow<Map<String, List<CalendarGuideHit>>> = _hits.asStateFlow()

    private val hd = Regex("(?i)\\b(fhd|hd|1080|4k|uhd)\\b")
    private val trailingYear = Regex("\\s*\\([^)]*\\)\\s*$")

    fun hitsOf(e: CalendarEntry): List<CalendarGuideHit> = _hits.value[e.key].orEmpty()

    /** The channel to use without asking: first match not already over. */
    fun bestOf(e: CalendarEntry): CalendarGuideHit? = hitsOf(e).firstOrNull()

    /** Looks the guide up for every TV episode airing from 3 hours ago to 8 days ahead. */
    suspend fun match(entries: List<CalendarEntry>) = withContext(Dispatchers.Default) {
        val channels = LiveTvRepository.channels.value
        if (channels.isEmpty()) { _hits.value = emptyMap(); return@withContext }
        val now = LiveTvClock.nowMs()
        val shift = LiveTvPreferences.currentSettings().epgOffsetMinutes * MIN
        val byKey = channels.associateBy { it.key }
        val favorites = runCatching { LiveTvPreferences.currentUserState().favorites.toSet() }.getOrDefault(emptySet())
        val found = HashMap<String, List<CalendarGuideHit>>()
        for (e in entries) {
            val air = e.airMs ?: continue
            if (e.kind != CalendarKind.EPISODE || e.streaming) continue
            if (air < now - 3 * HOUR || air > now + 8 * DAY) continue
            val word = searchWord(e.title).takeIf { it.length >= 3 } ?: continue
            val rows = runCatching {
                EpgStore.findByWords(
                    fromMs = air - 80 * MIN - shift, toMs = air + 205 * MIN - shift,
                    minStopMs = now - shift, words = listOf(word), inDescription = false, limit = 3_000,
                )
            }.getOrDefault(emptyList())
            val net = e.network?.let { LiveTvRepository.normalize(it) }.orEmpty()
            val lastEp = e.lastEpisode ?: e.episode
            var looked = 0
            val options = rows.mapNotNull { (key, p0) ->
                val ch = byKey[key] ?: return@mapNotNull null
                if (!sameShow(p0.title, e.title)) return@mapNotNull null
                val p = if (shift == 0L) p0 else p0.copy(startMs = p0.startMs + shift, stopMs = p0.stopMs + shift)
                val off = p.startMs - air
                if (off < -75 * MIN || off > 200 * MIN) return@mapNotNull null
                val guideEp = if (looked++ < 60) runCatching { EpgStore.program(key, p0.startMs)?.episode }.getOrNull() else null
                val sameEp = sameEpisode(guideEp, e.season, e.episode, lastEp)
                if (sameEp == false) return@mapNotNull null
                val chName = LiveTvRepository.normalize(ch.name)
                var score = 0
                if (sameEp == true) score += 200
                if (net.isNotEmpty() && (chName == net || chName.startsWith(net) || chName.contains(net))) score += 100
                if (key in favorites) score += 40
                if (hd.containsMatchIn(ch.name)) score += 5
                score -= (kotlin.math.abs(off) / MIN).toInt().coerceAtMost(75)
                score to CalendarGuideHit(
                    ch.key, ch.name, ch.logo, p.startMs, p.stopMs, p.title,
                    playlist = ch.sourceName, catchup = ch.catchup != null, favorite = key in favorites,
                    group = ch.group.ifBlank { null }, confirmed = sameEp == true,
                )
            }.distinctBy { it.second.channelKey }.sortedByDescending { it.first }.map { it.second }
            if (options.isNotEmpty()) found[e.key] = options
        }
        _hits.value = found
    }

    // ---- recording / reminders (they reuse what the guide screen uses)

    private fun channelOf(hit: CalendarGuideHit): LiveChannel? =
        LiveTvRepository.channels.value.firstOrNull { it.key == hit.channelKey }

    private fun programOf(e: CalendarEntry, hit: CalendarGuideHit) =
        EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title.ifBlank { e.title })

    fun isRecording(hit: CalendarGuideHit): Boolean {
        val ch = channelOf(hit) ?: return false
        return LiveTvRecordingScheduler.isScheduled(ch.key, EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title))
    }

    fun isReminder(hit: CalendarGuideHit): Boolean = LiveTvReminders.isSet(hit.channelKey, hit.startMs)

    /** Sets (or cancels) recording this airing. Returns a short message. */
    fun record(e: CalendarEntry, hit: CalendarGuideHit): String {
        val ch = channelOf(hit) ?: return "That channel is no longer in your playlist"
        if (isRecording(hit)) {
            LiveTvRecordingScheduler.cancel(hit.channelKey, hit.startMs)
            return "Recording cancelled"
        }
        // Moving to another channel: the same episode isn't recorded twice.
        hitsOf(e).filter { it.channelKey != hit.channelKey && isRecording(it) }
            .forEach { LiveTvRecordingScheduler.cancel(it.channelKey, it.startMs) }
        return LiveTvRecordingScheduler.add(ch, programOf(e, hit))
    }

    /** Sets (or cancels) a reminder. Returns a short message. */
    fun remind(e: CalendarEntry, hit: CalendarGuideHit): String {
        val on = LiveTvReminders.toggle(Reminder(hit.channelKey, hit.channelName, e.title, hit.startMs))
        return if (on) "Reminder set" else "Reminder cancelled"
    }

    // ---- matching helpers

    private fun searchWord(title: String): String = title.substringBefore(": ").replace(trailingYear, "").trim()

    private fun clean(t: String): String = LiveTvRepository.normalize(t.replace(trailingYear, "").replace(Regex("(?i)\\b(new|live)\\b"), ""))

    private fun sameShow(guideTitle: String, showTitle: String): Boolean {
        val g = clean(guideTitle.substringBefore(": "))
        val s = clean(showTitle)
        if (g.isEmpty() || s.isEmpty()) return false
        return g == s || (s.length >= 6 && g.startsWith(s))
    }

    /** true: the guide says it's this episode; false: a different one (a rerun); null: can't tell. */
    private fun sameEpisode(guideEp: String?, season: Int?, episode: Int?, last: Int?): Boolean? {
        if (guideEp.isNullOrBlank() || season == null || episode == null) return null
        val m = Regex("(?i)s(\\d+)\\s*e(\\d+)").find(guideEp)
            ?: Regex("(?i)(\\d+)x(\\d+)").find(guideEp) ?: return null
        val gs = m.groupValues[1].toIntOrNull()
        val ge = m.groupValues[2].toIntOrNull()
        if (gs == null || ge == null) return null
        return when {
            gs == season -> ge in episode..(last ?: episode)
            gs < season -> false
            else -> null
        }
    }
}
