package com.nuvio.app.features.livetv.record

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.XtreamClient
import com.nuvio.app.features.livetv.data.parseObject
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.model.LiveTvSettings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records Live TV channels to the device, and keeps the list of recordings. Each recording is a
 * folder with one video file and an `info.json`:
 *  - TS channels are copied straight to a .ts file; a dropped connection is retried until the
 *    stop time and the stream appended, so playback carries on across the gap;
 *  - HLS channels' pieces are saved as they appear and appended to one file (.ts, or .mp4 when
 *    the stream is fragmented MP4 with an init part).
 * A catch-up "save" is a finite download of a whole show from the provider's archive.
 * While anything is recording, the platform keeps the app running in the background.
 */
internal object LiveTvRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val jobs = HashMap<String, Job>()
    private val stopRequested = HashSet<String>()

    private val _recordings = MutableStateFlow<List<LiveTvRecording>>(emptyList())
    /** Every recording, newest first. */
    val recordings: StateFlow<List<LiveTvRecording>> = _recordings.asStateFlow()

    private var loaded = false

    /** Reads the recordings on the device once (and keeps the space rule up to date). */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch { runCatching { load() } }
    }

    private fun settings(): LiveTvSettings = LiveTvPreferences.currentSettings()

    /** The recording running on [channelKey], if any. */
    fun activeFor(channelKey: String): LiveTvRecording? =
        _recordings.value.firstOrNull { it.channelKey == channelKey && it.isRecording }

    fun anyRecording(): Boolean = _recordings.value.any { it.isRecording }

    fun filePath(rec: LiveTvRecording): String = rec.dir + "/" + rec.fileName

    /** A link the player can open. */
    fun playUrl(rec: LiveTvRecording): String = RecordingPlatform.playUrl(filePath(rec))

    /** Roughly how much room there is where recordings go. */
    fun freeBytes(): Long = RecordingPlatform.usableSpace()

    /**
     * Starts recording [channel] from [url] until [stopAtMs]. Returns the new recording, or the
     * one already running on that channel.
     */
    fun start(
        channel: LiveChannel,
        url: String,
        title: String,
        episode: String?,
        description: String?,
        programStartMs: Long,
        programStopMs: Long,
        stopAtMs: Long,
        /** A catch-up download: it ends by itself (end of file / end of playlist); no reconnecting. */
        finite: Boolean = false,
        /** Other links to try if the first doesn't work (catch-up variants). */
        fallbacks: List<String> = emptyList(),
        /** Called once it's finished (done or failed). */
        onFinished: (LiveTvRecording) -> Unit = {},
        seriesId: String? = null,
        /**
         * Catch-up only: the link for the rest of the show, from this far in (ms). A download that
         * gets cut off carries on from there instead of stopping with part of the show.
         */
        resumeFrom: ((doneMs: Long) -> String?)? = null,
    ): LiveTvRecording {
        ensureLoaded()
        if (!finite) activeFor(channel.key)?.let { return it }
        val now = LiveTvClock.nowMs()
        val id = "rec$now"
        val root = RecordingPlatform.recordingsRoot()
        val dir = "$root/$id"
        val rec = LiveTvRecording(
            id = id,
            channelKey = channel.key,
            channelName = channel.name,
            channelLogo = channel.logo,
            title = title,
            episode = episode,
            description = description,
            programStartMs = programStartMs,
            programStopMs = programStopMs,
            startedAtMs = now,
            stopAtMs = stopAtMs,
            dir = dir,
            seriesId = seriesId,
            fromCatchup = finite,
        )
        if (!RecordingPlatform.available || root.isBlank()) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Recording isn't available on this device")
            update(failed)
            runCatching { onFinished(failed) }
            return failed
        }
        // Not enough room to start: make room (if allowed) or say so.
        if (!ensureSpace(protect = id)) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Not enough storage space to record")
            save(failed); update(failed)
            RecordingNotices.show("Not enough storage space to record \"$title\"")
            runCatching { onFinished(failed) }
            return failed
        }
        save(rec)
        update(rec)
        RecordingPlatform.keepAlive(true)
        val job = scope.launch { record(rec, url, channel.headers, finite, fallbacks, onFinished, resumeFrom) }
        synchronized(lock) { jobs[id] = job }
        return rec
    }

    /** Stops a recording now (what's recorded is kept). */
    fun stop(id: String) {
        val job = synchronized(lock) { stopRequested += id; jobs[id] }
        job?.cancel()
        // A recording stuck waiting on a dead connection can ignore the stop: if it is still
        // "recording" a few seconds later, end it here.
        scope.launch {
            delay(4_000)
            if (_recordings.value.firstOrNull { it.id == id }?.isRecording == true) forceFinish(id)
        }
    }

    private fun forceFinish(id: String) {
        val latest = _recordings.value.firstOrNull { it.id == id } ?: return
        val hasVideo = RecordingPlatform.fileSize(filePath(latest)) > 0
        val done = latest.copy(
            status = if (hasVideo) RecordingStatus.DONE else RecordingStatus.FAILED,
            endedAtMs = LiveTvClock.nowMs(),
            sizeBytes = RecordingPlatform.dirSize(latest.dir),
            problem = if (hasVideo) null else "Stopped before anything was recorded"
        )
        save(done); update(done)
        synchronized(lock) { jobs.remove(id)?.cancel(); stopRequested.remove(id) }
        stopServiceIfIdle()
    }

    /** Deletes a recording (stopping it first if it's still going). */
    fun delete(id: String) {
        val rec = _recordings.value.firstOrNull { it.id == id }
        if (rec?.isRecording == true) stop(id)
        _recordings.value = _recordings.value.filterNot { it.id == id }
        rec?.let { r -> scope.launch { runCatching { RecordingPlatform.deleteDir(r.dir) } } }
        stopServiceIfIdle()
    }

    /** Changes when a running recording stops. */
    fun extend(id: String, stopAtMs: Long) {
        val rec = _recordings.value.firstOrNull { it.id == id } ?: return
        val r = rec.copy(stopAtMs = stopAtMs)
        save(r); update(r)
    }

    // ------------------------------------------------------------------ recording

    private fun stopping(id: String): Boolean = synchronized(lock) { id in stopRequested }

    private suspend fun record(
        start: LiveTvRecording,
        firstUrl: String,
        headers: Map<String, String>,
        finite: Boolean,
        fallbacks: List<String>,
        onFinished: (LiveTvRecording) -> Unit,
        resumeFrom: ((doneMs: Long) -> String?)?,
    ) {
        val links = ArrayDeque((listOf(firstUrl) + fallbacks).distinct())
        var url = links.removeFirst()
        var rec = start
        var problem: String? = null
        val path = filePath(rec)
        val hdrs = withUserAgent(headers)
        try {
            val isHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            if (isHls) {
                problem = recordHls(rec, url, hdrs, finite) { secs, size -> rec = progress(rec, secs, size) }
            } else {
                var first = true
                var tries = 0
                var recordedMs = 0L
                while (scope.isActive && !stopping(rec.id) && LiveTvClock.nowMs() < currentStop(rec.id)) {
                    if (!first) {
                        if (finite) {
                            // A catch-up download that got cut off carries on from where it got to
                            // (a new link starting there); one that never started gets a few tries.
                            if (++tries > (if (recordedMs > 0) 10 else 3)) break
                            if (recordedMs > 0) url = resumeFrom?.invoke(recordedMs) ?: break
                            delay(3_000L * tries.coerceAtMost(5))
                        } else {
                            // A live recording reconnects until its end.
                            delay(3_000)
                        }
                    }
                    first = false
                    val segmentStart = LiveTvClock.nowMs()
                    try {
                        val append = RecordingPlatform.fileSize(path) > 0
                        var lastSave = 0L
                        val before = if (append) RecordingPlatform.fileSize(path) else 0L
                        var got = 0L
                        RecordingPlatform.download(url, hdrs, path, append, shouldStop = { stopping(rec.id) || LiveTvClock.nowMs() >= currentStop(rec.id) }) { total ->
                            got = total - before
                            val now = LiveTvClock.nowMs()
                            if (now - lastSave > 5_000) {
                                lastSave = now
                                val secs = (recordedMs + (now - segmentStart)) / 1000.0
                                rec = progress(rec, secs, RecordingPlatform.dirSize(rec.dir))
                                if (!ensureSpace(protect = rec.id)) {
                                    problem = "Storage ran out, so it stopped early"
                                    synchronized(lock) { stopRequested += rec.id }
                                }
                            }
                        }
                        if (got > 0L) {
                            recordedMs += LiveTvClock.nowMs() - segmentStart
                            problem = null
                        } else if (!stopping(rec.id)) {
                            // Connected without an error but no video came: say so instead of staying silent.
                            problem = NO_VIDEO
                            rec = noteProblem(rec, problem)
                        }
                        if (finite) break // the whole show has come in
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RecordingHttpError) {
                        problem = "The provider answered HTTP ${e.code}"
                        rec = noteProblem(rec, problem)
                        // Catch-up: try the other link forms.
                        if (finite && links.isNotEmpty()) url = links.removeFirst()
                    } catch (e: Exception) {
                        recordedMs += LiveTvClock.nowMs() - segmentStart
                        problem = "The connection dropped (${e.message ?: "unknown error"}); it reconnected where it could"
                        rec = noteProblem(rec, problem)
                    }
                }
                rec = progress(rec, recordedMs / 1000.0, RecordingPlatform.dirSize(rec.dir))
            }
        } catch (e: CancellationException) {
            // Stopped by hand: finish below.
        } catch (e: Exception) {
            problem = e.message ?: "unknown error"
        } finally {
            val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
            val hasVideo = RecordingPlatform.fileSize(path) > 0
            val expectedSec = (latest.stopAtMs - latest.startedAtMs) / 1000.0
            val done = latest.copy(
                status = if (hasVideo) RecordingStatus.DONE else RecordingStatus.FAILED,
                endedAtMs = LiveTvClock.nowMs(),
                sizeBytes = RecordingPlatform.dirSize(latest.dir),
                problem = if (hasVideo) problem?.takeIf { latest.durationSec < expectedSec * 0.9 }
                else (problem ?: latest.problem ?: "Nothing could be recorded from this channel ($NO_VIDEO_SHORT)")
            )
            if (_recordings.value.any { it.id == done.id }) { save(done); update(done) }
            synchronized(lock) { jobs.remove(rec.id); stopRequested.remove(rec.id) }
            stopServiceIfIdle()
            runCatching { onFinished(done) }
        }
    }

    private fun withUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers else headers + ("User-Agent" to XtreamClient.DEFAULT_UA)

    private fun currentStop(id: String): Long = _recordings.value.firstOrNull { it.id == id }?.stopAtMs ?: 0L

    /** Shows what's going wrong while it's still recording (Recordings shows it under the title). */
    private fun noteProblem(rec: LiveTvRecording, problem: String?): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(problem = problem)
        update(r)
        return r
    }

    private fun progress(rec: LiveTvRecording, durationSec: Double, size: Long): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(durationSec = durationSec, sizeBytes = size, problem = if (durationSec > latest.durationSec) null else latest.problem)
        save(r); update(r)
        return r
    }

    private val STREAM_INF = Regex("""#EXT-X-STREAM-INF""")
    private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val MEDIA_SEQUENCE = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
    private val TARGET_DURATION = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
    private val MAP_URI = Regex("""#EXT-X-MAP:URI="([^"]+)"""")

    /** HLS channel: saves each piece as it appears, appended to one file. Returns a problem, or null. */
    private suspend fun recordHls(
        rec: LiveTvRecording,
        url: String,
        headers: Map<String, String>,
        finite: Boolean,
        onProgress: (Double, Long) -> Unit,
    ): String? {
        var mediaUrl = url
        var text = RecordingPlatform.fetchText(mediaUrl, headers)
        if (STREAM_INF.containsMatchIn(text)) {
            val lines = text.lines()
            var best = -1L
            var bestUri: String? = null
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = BANDWIDTH.find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (uri != null && bw >= best) { best = bw; bestUri = uri }
                }
            }
            mediaUrl = resolve(mediaUrl, bestUri ?: return "This channel's stream couldn't be read")
            text = RecordingPlatform.fetchText(mediaUrl, headers)
        }
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) return "This channel is encrypted and can't be recorded"
        var path = filePath(rec)
        var lastSeq = -1L
        var target = 6
        var total = 0.0
        var problem: String? = null
        var mapDone = false
        while (scope.isActive && !stopping(rec.id) && LiveTvClock.nowMs() < currentStop(rec.id)) {
            val lines = text.lines()
            var seq = MEDIA_SEQUENCE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            target = TARGET_DURATION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: target
            if (!mapDone) {
                MAP_URI.find(text)?.groupValues?.get(1)?.let { m ->
                    // Fragmented MP4: the init part first, then the pieces, all in one .mp4.
                    path = rec.dir + "/recording.mp4"
                    val withMp4 = (_recordings.value.firstOrNull { it.id == rec.id } ?: rec).copy(fileName = "recording.mp4")
                    save(withMp4); update(withMp4)
                    runCatching { RecordingPlatform.download(resolve(mediaUrl, m), headers, path, append = false, shouldStop = { false }) {} }
                }
                mapDone = true
            }
            var dur = 0.0
            for (l in lines) {
                when {
                    l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: target.toDouble()
                    l.isNotBlank() && !l.startsWith("#") -> {
                        if (seq > lastSeq) {
                            val failure = try {
                                RecordingPlatform.download(resolve(mediaUrl, l), headers, path, append = RecordingPlatform.fileSize(path) > 0, shouldStop = { stopping(rec.id) }) {}
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: RecordingHttpError) {
                                "The provider answered HTTP ${e.code} for the video pieces"
                            } catch (e: Exception) {
                                e.message ?: "unknown error"
                            }
                            if (failure == null) {
                                total += dur
                                lastSeq = seq
                            } else if (total <= 0.0) {
                                problem = failure
                                noteProblem(rec, failure) // kept even if a stop cancels this loop
                            }
                        }
                        seq++
                    }
                }
            }
            onProgress(total, RecordingPlatform.dirSize(rec.dir))
            if (!ensureSpace(protect = rec.id)) return "Storage ran out, so it stopped early"
            // A finished playlist (a catch-up show): everything's saved.
            if (finite && text.contains("#EXT-X-ENDLIST")) break
            delay((target * 1000L / 2).coerceIn(1_000L, 6_000L))
            text = try {
                RecordingPlatform.fetchText(mediaUrl, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "The connection dropped (${e.message ?: "unknown error"}); it reconnected where it could"
                // A live channel's list moves on while it's away, so it starts again at the newest
                // pieces. A catch-up show's list is the same one: carry on from the last piece.
                if (!finite) lastSeq = -1L
                delay(3_000)
                continue
            }
        }
        return problem
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        val noQuery = base.substringBefore('?')
        if (ref.startsWith("/")) {
            val schemeEnd = noQuery.indexOf("://") + 3
            val hostEnd = noQuery.indexOf('/', schemeEnd).let { if (it < 0) noQuery.length else it }
            return noQuery.substring(0, hostEnd) + ref
        }
        return noQuery.substringBeforeLast('/') + "/" + ref
    }

    // ------------------------------------------------------------------ list and files

    private fun save(rec: LiveTvRecording) {
        runCatching { RecordingPlatform.writeText(rec.dir + "/info.json", rec.toJson().toString()) }
    }

    private fun update(rec: LiveTvRecording) {
        synchronized(lock) {
            val list = _recordings.value.filterNot { it.id == rec.id } + rec
            _recordings.value = list.sortedByDescending { it.startedAtMs }
        }
    }

    /**
     * Makes sure there's room to keep recording: below 500 MB free, either deletes the oldest
     * finished recordings (if set to) or says no. [protect]: never deleted.
     */
    private fun ensureSpace(protect: String): Boolean {
        fun free() = runCatching { RecordingPlatform.usableSpace() }.getOrDefault(Long.MAX_VALUE)
        if (free() >= MIN_FREE) return true
        if (settings().recordingSpaceRule != "DELETE_OLDEST") return false
        val oldest = _recordings.value.filter { !it.isRecording && it.id != protect }.sortedBy { it.startedAtMs }
        for (r in oldest) {
            if (free() >= MIN_FREE) break
            RecordingNotices.show("Storage is low: deleted the oldest recording, \"${r.title}\"")
            _recordings.value = _recordings.value.filterNot { it.id == r.id }
            runCatching { RecordingPlatform.deleteDir(r.dir) }
        }
        return free() >= MIN_FREE
    }

    /** Reads the recording folders. Ones that were still recording when the app closed are finished off. */
    private suspend fun load() = withContext(Dispatchers.Default) {
        if (!RecordingPlatform.available) return@withContext
        val out = ArrayList<LiveTvRecording>()
        RecordingPlatform.listRecordingDirs().forEach { d ->
            val info = RecordingPlatform.readText("$d/info.json") ?: return@forEach
            val rec = runCatching { LiveTvRecording.fromJson(parseObject(info), d) }.getOrNull() ?: return@forEach
            val running = synchronized(lock) { jobs.containsKey(rec.id) }
            if (rec.isRecording && !running) {
                val hasVideo = RecordingPlatform.fileSize("$d/${rec.fileName}") > 0
                val fixed = if (hasVideo) rec.copy(status = RecordingStatus.DONE, endedAtMs = LiveTvClock.nowMs(), sizeBytes = RecordingPlatform.dirSize(d),
                    problem = "The app closed before it finished, so it stops early")
                else rec.copy(status = RecordingStatus.FAILED, problem = "The app closed before anything was recorded")
                save(fixed)
                out += fixed
            } else out += rec
        }
        synchronized(lock) {
            val running = _recordings.value.filter { it.isRecording }
            _recordings.value = (out.filterNot { o -> running.any { it.id == o.id } } + running).sortedByDescending { it.startedAtMs }
        }
    }

    fun stopServiceIfIdle() {
        val idle = synchronized(lock) { jobs.isEmpty() } || _recordings.value.none { it.isRecording }
        if (idle) RecordingPlatform.keepAlive(false)
    }

    private const val NO_VIDEO = "The provider answered but sent no video. If you're watching this channel, your provider may only allow one connection at a time"
    private const val NO_VIDEO_SHORT = "no video came back"

    /** Below this much free space, recording stops (or the oldest recordings make room). */
    private const val MIN_FREE = 500L * 1024 * 1024
}

/** Short messages about recordings: in the app while it's open, and as a notification. */
internal object RecordingNotices {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun show(text: String) {
        _current.value = text
        runCatching { RecordingPlatform.notify("notice", "Recordings", text) }
    }

    fun clear() { _current.value = null }
}
