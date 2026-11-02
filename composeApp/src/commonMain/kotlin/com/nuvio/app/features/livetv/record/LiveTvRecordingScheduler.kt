package com.nuvio.app.features.livetv.record

import com.nuvio.app.features.livetv.data.CatchupUrlBuilder
import com.nuvio.app.features.livetv.data.LiveTvDirs
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.optJSONArray
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.player.LiveTvCatchup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scheduled recordings: shows set to record from the guide, recordings set by hand, and series
 * rules. Each one gets an alarm that wakes the app at the right time:
 *  - Channels with catch-up: after the show ends, the whole show is downloaded from the
 *    provider's catch-up (the phone doesn't have to be on at air time, and nothing's lost to a
 *    dropped connection). Tried again later if the provider hasn't got it yet.
 *  - Other channels: recorded live, from a little before the show until a little after.
 * Series rules add each new airing they find in the guide.
 */
@OptIn(FlowPreview::class)
internal object LiveTvRecordingScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private const val FILE = "recording_schedules.json"

    private val _schedules = MutableStateFlow<List<RecordingSchedule>>(emptyList())
    val schedules: StateFlow<List<RecordingSchedule>> = _schedules.asStateFlow()
    private val _series = MutableStateFlow<List<SeriesRule>>(emptyList())
    val series: StateFlow<List<SeriesRule>> = _series.asStateFlow()

    private var started = false
    private var loadedOnce = false

    /** Loads the schedules and sets their alarms (called when the app starts, or by an alarm). */
    fun start() {
        if (started) return
        started = true
        LiveTvRecorder.ensureLoaded()
        scope.launch {
            load()
            armAll()
            // New guide listings: look for new airings of series.
            LiveTvRepository.guideGeneration.drop(1).debounce(10_000).collect { if (_series.value.isNotEmpty()) expandSeries() }
        }
    }

    /** Anything set to record, or already recorded: the Recordings page is worth showing. */
    fun hasAnything(): Boolean = _schedules.value.isNotEmpty() || _series.value.isNotEmpty() || LiveTvRecorder.recordings.value.isNotEmpty()

    // ------------------------------------------------------------------ setting recordings

    /** Extra time before and after recordings (Settings → Live TV → Recordings), in ms. */
    fun padding(): Pair<Long, Long> {
        val s = LiveTvPreferences.currentSettings()
        return s.recordPreMinutes.coerceAtLeast(0) * 60_000L to s.recordPostMinutes.coerceAtLeast(0) * 60_000L
    }

    fun isScheduled(channelKey: String, program: EpgProgram): Boolean = RecordingRules.isScheduled(_schedules.value, channelKey, program)

    fun seriesFor(channelKey: String, title: String): SeriesRule? {
        val n = RecordingRules.normalizeTitle(title)
        return _series.value.firstOrNull { it.channel.key == channelKey && it.norm == n }
    }

    /** A recording set by hand: [title] from [startMs] to [stopMs] on [channel]. Recorded like a show from the guide. */
    fun addManual(channel: LiveChannel, title: String, startMs: Long, stopMs: Long): String =
        add(channel, EpgProgram(startMs = startMs, stopMs = stopMs, title = title.ifBlank { channel.name }))

    /**
     * Sets [program] on [channel] to record. A show on now on a channel without catch-up starts
     * recording straight away. Returns a message to show.
     */
    fun add(channel: LiveChannel, program: EpgProgram, seriesId: String? = null): String {
        val now = LiveTvClock.nowMs()
        val (preMs, postMs) = padding()
        val useCatchup = channel.catchup != null && channel.catchup.days > 0
        val title = program.title.trim()
        if (!useCatchup && now >= program.startMs && now < program.stopMs) {
            if (LiveTvRecorder.activeFor(channel.key) == null) {
                LiveTvRecorder.start(channel, channel.url, title, program.episode, program.description,
                    program.startMs, program.stopMs, RecordingRules.recordNowStop(program.stopMs, postMs), seriesId = seriesId)
            }
            return "Recording \"$title\" now"
        }
        if (program.stopMs <= now && !useCatchup) return "That show has already been on"
        val s = RecordingSchedule(
            id = "s${now}_${program.startMs.hashCode()}",
            channel = ChannelSnapshot.of(channel),
            title = title,
            episode = program.episode,
            description = program.description,
            startMs = program.startMs,
            stopMs = program.stopMs,
            useCatchup = useCatchup,
            seriesId = seriesId,
            preMs = preMs,
            postMs = postMs,
        )
        val clash = RecordingRules.clashWith(s, _schedules.value)
        scope.launch {
            mutex.withLock {
                if (_schedules.value.none { it.channel.key == channel.key && it.startMs == program.startMs }) {
                    _schedules.value = (_schedules.value + s).sortedBy { it.startMs }
                    save()
                }
            }
            arm(s)
        }
        val whenText = com.nuvio.app.core.format.formatLocalDateTime(program.startMs)
        return when {
            clash != null -> "Set to record \"$title\" ($whenText). It overlaps \"${clash.title}\"; your account may only allow one at a time."
            useCatchup && program.stopMs <= now -> "Saving \"$title\" from catch-up"
            useCatchup -> "Set to record \"$title\" ($whenText). It's saved from catch-up after it ends."
            else -> "Set to record \"$title\" ($whenText)"
        }
    }

    /** Cancels a scheduled show. */
    fun cancel(channelKey: String, startMs: Long) {
        scope.launch {
            mutex.withLock {
                val gone = _schedules.value.filter { it.channel.key == channelKey && it.startMs == startMs }
                gone.forEach { RecordingPlatform.cancelAlarm(it.id) }
                _schedules.value = _schedules.value - gone.toSet()
                save()
            }
        }
    }

    fun cancel(id: String) {
        scope.launch {
            mutex.withLock {
                _schedules.value.firstOrNull { it.id == id }?.let { RecordingPlatform.cancelAlarm(it.id) }
                _schedules.value = _schedules.value.filterNot { it.id == id }
                save()
            }
        }
    }

    /** Record series: every airing of this show's title on this channel. */
    fun addSeries(channel: LiveChannel, program: EpgProgram): String {
        val title = program.title.trim()
        seriesFor(channel.key, title)?.let { return "\"$title\" is already set to record" }
        val keep = LiveTvPreferences.currentSettings().recordSeriesKeep.coerceAtLeast(0)
        val rule = SeriesRule("r${LiveTvClock.nowMs()}", ChannelSnapshot.of(channel), title, keepLast = keep)
        scope.launch {
            mutex.withLock {
                _series.value = _series.value + rule
                save()
            }
            expandSeries()
        }
        return "Recording every \"$title\" on ${channel.name}"
    }

    /** Stops a series rule (and cancels the airings it set that haven't started). */
    fun removeSeries(id: String) {
        scope.launch {
            mutex.withLock {
                _series.value = _series.value.filterNot { it.id == id }
                val gone = _schedules.value.filter { it.seriesId == id }
                gone.forEach { RecordingPlatform.cancelAlarm(it.id) }
                _schedules.value = _schedules.value - gone.toSet()
                save()
            }
        }
    }

    /** "Keep the last N" for a series. */
    fun setKeep(seriesId: String, keepLast: Int) {
        scope.launch {
            mutex.withLock {
                _series.value = _series.value.map { if (it.id == seriesId) it.copy(keepLast = keepLast) else it }
                save()
            }
            keepOnlyNewest(seriesId)
        }
    }

    /** Deletes a series' older recordings past its "keep the last N". */
    private fun keepOnlyNewest(seriesId: String) {
        val keep = _series.value.firstOrNull { it.id == seriesId }?.keepLast ?: return
        if (keep <= 0) return
        LiveTvRecorder.recordings.value
            .filter { it.seriesId == seriesId && !it.isRecording }
            .sortedByDescending { it.programStartMs.takeIf { s -> s > 0 } ?: it.startedAtMs }
            .drop(keep)
            .forEach { LiveTvRecorder.delete(it.id) }
    }

    // ------------------------------------------------------------------ series

    private suspend fun expandSeries() {
        val rules = _series.value
        if (rules.isEmpty()) return
        val now = LiveTvClock.nowMs()
        for (rule in rules) {
            val listings = runCatching { LiveTvRepository.channelSchedule(rule.channel.key, now, now + 7L * 24 * 3_600_000L) }.getOrDefault(emptyList())
            val channel = freshChannel(rule.channel)
            listings.filter { RecordingRules.normalizeTitle(it.title) == rule.norm && it.stopMs > now }.forEach { p ->
                // The episode by its number from the guide: a repeat of one recorded before (even
                // one deleted since) or already set is skipped. Airings without a number are all recorded.
                val ep = RecordingRules.episodeKey(p.episode)
                val current = _series.value.firstOrNull { it.id == rule.id } ?: rule
                val recordings = LiveTvRecorder.recordings.value
                val already = _schedules.value.any { it.channel.key == rule.channel.key && it.startMs == p.startMs } ||
                    recordings.any { it.channelKey == rule.channel.key && it.programStartMs == p.startMs } ||
                    (ep != null && (ep in current.seen ||
                        _schedules.value.any { it.seriesId == rule.id && RecordingRules.episodeKey(it.episode) == ep } ||
                        recordings.any {
                            (it.seriesId == rule.id || it.channelKey == rule.channel.key) && RecordingRules.episodeKey(it.episode) == ep &&
                                RecordingRules.normalizeTitle(it.title) == rule.norm
                        }))
                if (!already && p.startMs > now) add(channel, p, seriesId = rule.id)
            }
        }
    }

    // ------------------------------------------------------------------ alarms

    /** When a schedule will start: recording, or saving from catch-up (for the Recordings screen). */
    fun startsAt(s: RecordingSchedule): Long = RecordingRules.fireTime(s)

    private fun armAll() {
        val now = LiveTvClock.nowMs()
        _schedules.value.forEach { s ->
            if (!s.useCatchup && s.stopMs < now) {
                // Missed while the phone was off.
                scope.launch { drop(s, "\"${s.title}\" was missed (the phone was off or asleep)") }
            } else arm(s)
        }
    }

    /**
     * Anything whose time has already passed but is still waiting (its alarm came late or not at
     * all) starts now. Called when the Recordings screen opens.
     */
    fun fireOverdue() {
        val now = LiveTvClock.nowMs()
        _schedules.value.filter { RecordingRules.fireTime(it) < now - 60_000L }.forEach { fire(it.id) }
    }

    private fun arm(s: RecordingSchedule) {
        val at = RecordingRules.fireTime(s)
        if (at <= LiveTvClock.nowMs()) { fire(s.id); return }
        runCatching { RecordingPlatform.scheduleAlarm(s.id, at) }
    }

    /** An alarm went off (or a time already passed): start the recording or the download. */
    fun fire(id: String) {
        scope.launch {
            if (!loadedOnce) load()
            val s = _schedules.value.firstOrNull { it.id == id } ?: run { LiveTvRecorder.stopServiceIfIdle(); return@launch }
            val now = LiveTvClock.nowMs()
            if (!s.useCatchup) {
                if (now >= s.stopMs) { drop(s, "\"${s.title}\" was missed"); return@launch }
                // Too early (the alarm came early): set it again.
                if (now < s.startMs - s.pre - 60_000L) { arm(s); LiveTvRecorder.stopServiceIfIdle(); return@launch }
                val channel = freshChannel(s.channel)
                markSeen(s)
                LiveTvRecorder.start(channel, channel.url, s.title, s.episode, s.description, s.startMs, s.stopMs, s.stopMs + s.post,
                    onFinished = { rec ->
                        s.seriesId?.let { keepOnlyNewest(it) }
                        RecordingNotices.show(if (rec.status == RecordingStatus.FAILED) "\"${s.title}\" couldn't be recorded: ${rec.problem ?: "unknown problem"}" else "\"${s.title}\" was recorded")
                    }, seriesId = s.seriesId)
                remove(s)
                RecordingNotices.show("Recording \"${s.title}\" on ${channel.name}")
                return@launch
            }
            // Catch-up: download the whole show.
            val channel = freshChannel(s.channel)
            val catchup = channel.catchup ?: s.channel.catchup
            val settings = LiveTvPreferences.currentSettings()
            val zone = LiveTvPreferences.currentPlaylists().firstOrNull { it.id == channel.sourceId }?.serverTimezone?.takeIf { it.isNotBlank() }
            // As when watching catch-up: the guide's shift off, the catch-up shift on.
            val shift = LiveTvCatchup.shiftMs(settings)
            val url = catchup?.let {
                CatchupUrlBuilder.build(channel.url, it, s.startMs - s.pre - shift, s.stopMs + s.post - shift, now, preferHls = false, serverTimezone = zone)
            }
            if (url == null) {
                drop(s, "\"${s.title}\" couldn't be saved: this channel's catch-up isn't available")
                return@launch
            }
            remove(s)
            markSeen(s)
            LiveTvRecorder.start(
                channel, url, s.title, s.episode, s.description, s.startMs, s.stopMs,
                stopAtMs = now + (s.stopMs - s.startMs) * 3 + 30 * 60_000L,
                finite = true,
                fallbacks = CatchupUrlBuilder.alternatives(url, zone),
                seriesId = s.seriesId,
                // Cut off part way: the rest of the show, from a little before where it got to
                // (catch-up links often go by the minute, so a few seconds may repeat).
                resumeFrom = { doneMs ->
                    catchup?.let {
                        val from = s.startMs - s.pre - shift + doneMs - 30_000L
                        CatchupUrlBuilder.build(channel.url, it, from, s.stopMs + s.post - shift, LiveTvClock.nowMs(), preferHls = false, serverTimezone = zone)
                    }
                },
                onFinished = { rec ->
                    if (rec.status != RecordingStatus.FAILED) {
                        s.seriesId?.let { keepOnlyNewest(it) }
                        RecordingNotices.show("\"${s.title}\" was saved from catch-up")
                        return@start
                    }
                    // No connection: that's not the show's fault, so it doesn't use up a try. It's
                    // tried again while the provider still keeps the show.
                    val offline = !RecordingPlatform.online() || looksOffline(rec.problem)
                    val keptUntil = s.stopMs + (catchup?.days ?: 1).coerceAtLeast(1) * 24 * 60 * 60_000L - 60 * 60_000L
                    val retryable = offline || s.attempts < RecordingRules.MAX_TRIES
                    if (retryable && LiveTvClock.nowMs() < keptUntil) {
                        LiveTvRecorder.delete(rec.id)
                        val retry = s.copy(attempts = if (offline) s.attempts else s.attempts + 1, nextTryMs = LiveTvClock.nowMs() + RecordingRules.RETRY_MS)
                        scope.launch {
                            mutex.withLock { _schedules.value = (_schedules.value + retry).sortedBy { it.startMs }; save() }
                            arm(retry)
                        }
                    } else {
                        RecordingNotices.show("\"${s.title}\" couldn't be saved from catch-up: ${rec.problem ?: "unknown problem"}")
                    }
                }
            )
        }
    }

    /** Failures that mean "no connection at all", not "the provider said no". */
    private fun looksOffline(problem: String?): Boolean {
        val p = problem?.lowercase() ?: return false
        return listOf("unable to resolve host", "unknownhost", "failed to connect", "network is unreachable",
            "connectexception", "no address associated", "software caused connection abort", "couldn't find the provider", "couldn't connect").any { it in p }
    }

    /** The channel as it is now (its link may have changed), or as saved. */
    private fun freshChannel(saved: ChannelSnapshot): LiveChannel =
        LiveTvRepository.channels.value.firstOrNull { it.key == saved.key } ?: saved.toChannel()

    /** A series episode is being recorded: remember it, so its repeats are skipped from now on. */
    private suspend fun markSeen(s: RecordingSchedule) {
        val id = s.seriesId ?: return
        val ep = RecordingRules.episodeKey(s.episode) ?: return
        mutex.withLock {
            _series.value = _series.value.map { r -> if (r.id == id && ep !in r.seen) r.copy(seen = (r.seen + ep).takeLast(500)) else r }
            save()
        }
    }

    private suspend fun remove(s: RecordingSchedule) {
        mutex.withLock {
            _schedules.value = _schedules.value.filterNot { it.id == s.id }
            save()
        }
    }

    private suspend fun drop(s: RecordingSchedule, message: String) {
        remove(s)
        LiveTvRecorder.stopServiceIfIdle()
        RecordingNotices.show(message)
    }

    // ------------------------------------------------------------------ saving

    private fun save() {
        runCatching {
            val o = buildJsonObject {
                put("schedules", buildJsonArray { _schedules.value.forEach { add(it.toJson()) } })
                put("series", buildJsonArray { _series.value.forEach { add(it.toJson()) } })
            }
            LiveTvFiles.writeSmall(LiveTvDirs.STATE, FILE, o.toString())
        }
    }

    private suspend fun load() = mutex.withLock {
        loadedOnce = true
        val text = LiveTvFiles.readSmall(LiveTvDirs.STATE, FILE) ?: return@withLock
        val o = runCatching { parseObject(text) }.getOrNull() ?: return@withLock
        _schedules.value = o.optJSONArray("schedules")?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { RecordingSchedule.fromJson(it) }.getOrNull() } }
            .orEmpty().sortedBy { it.startMs }
        _series.value = o.optJSONArray("series")?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { SeriesRule.fromJson(it) }.getOrNull() } }.orEmpty()
    }
}
