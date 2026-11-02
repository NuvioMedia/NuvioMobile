package com.nuvio.app.features.livetv.record

import com.nuvio.app.features.livetv.data.optBoolean
import com.nuvio.app.features.livetv.data.optInt
import com.nuvio.app.features.livetv.data.optJSONArray
import com.nuvio.app.features.livetv.data.optJSONObject
import com.nuvio.app.features.livetv.data.optLong
import com.nuvio.app.features.livetv.data.optString
import com.nuvio.app.features.livetv.data.strings
import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a recording is up to. */
enum class RecordingStatus { RECORDING, DONE, FAILED }

/**
 * One recording: what it is (show, channel, times) and how it went. Saved as `info.json` in the
 * recording's own folder, next to its file, so a folder is complete on its own. The same fields
 * as the TV app's.
 */
data class LiveTvRecording(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val channelLogo: String?,
    val title: String,
    val episode: String? = null,
    val description: String? = null,
    /** The show's times in the guide (0 when recorded with no guide listing). */
    val programStartMs: Long = 0L,
    val programStopMs: Long = 0L,
    /** When recording began, and when it stops (or stopped). */
    val startedAtMs: Long,
    val stopAtMs: Long,
    val endedAtMs: Long? = null,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    /** Length recorded so far, and its size on disk. */
    val durationSec: Double = 0.0,
    val sizeBytes: Long = 0L,
    /** Why it failed or stopped early, in plain words. */
    val problem: String? = null,
    /** The folder it's in (absolute path). */
    val dir: String,
    /** Recorded by a series rule (its id): "keep the last N" applies. */
    val seriesId: String? = null,
    /** Downloaded from the provider's catch-up (rather than recorded as it aired). */
    val fromCatchup: Boolean = false,
    /** The file the video is in, inside [dir] ("recording.ts" or "recording.mp4"). */
    val fileName: String = "recording.ts",
) {
    val isRecording: Boolean get() = status == RecordingStatus.RECORDING

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("channelKey", channelKey); put("channelName", channelName)
        put("channelLogo", channelLogo ?: ""); put("title", title)
        put("episode", episode ?: ""); put("description", description ?: "")
        put("programStartMs", programStartMs); put("programStopMs", programStopMs)
        put("startedAtMs", startedAtMs); put("stopAtMs", stopAtMs)
        put("endedAtMs", endedAtMs ?: 0L); put("status", status.name)
        put("durationSec", durationSec); put("sizeBytes", sizeBytes)
        put("problem", problem ?: "")
        put("seriesId", seriesId ?: "")
        put("fromCatchup", fromCatchup)
        put("fileName", fileName)
    }

    companion object {
        fun fromJson(o: JsonObject, dir: String): LiveTvRecording? = runCatching {
            LiveTvRecording(
                id = o.optString("id").ifBlank { return null },
                channelKey = o.optString("channelKey"),
                channelName = o.optString("channelName"),
                channelLogo = o.optString("channelLogo").ifBlank { null },
                title = o.optString("title").ifBlank { "Recording" },
                episode = o.optString("episode").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                programStartMs = o.optLong("programStartMs"),
                programStopMs = o.optLong("programStopMs"),
                startedAtMs = o.optLong("startedAtMs"),
                stopAtMs = o.optLong("stopAtMs"),
                endedAtMs = o.optLong("endedAtMs").takeIf { it > 0 },
                status = RecordingStatus.entries.firstOrNull { it.name == o.optString("status") } ?: RecordingStatus.DONE,
                durationSec = (o["durationSec"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0,
                sizeBytes = o.optLong("sizeBytes"),
                problem = o.optString("problem").ifBlank { null },
                dir = dir,
                seriesId = o.optString("seriesId").ifBlank { null },
                fromCatchup = o.optBoolean("fromCatchup", false),
                fileName = o.optString("fileName").ifBlank { "recording.ts" },
            )
        }.getOrNull()
    }
}

/** The channel a recording is set for, saved with it so it can start without loading Live TV. */
data class ChannelSnapshot(
    val key: String,
    val sourceId: String,
    val name: String,
    val logo: String?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?,
) {
    fun toChannel(): LiveChannel = LiveChannel(
        key = key, sourceId = sourceId, sourceName = "", name = name, tvgId = null, tvgName = null,
        logo = logo, groupId = "", group = "", number = 0, url = url, headers = headers, catchup = catchup
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("key", key); put("sourceId", sourceId); put("name", name); put("logo", logo ?: "")
        put("url", url)
        put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
        catchup?.let { c ->
            put("catchup", buildJsonObject {
                put("type", c.type); put("source", c.source ?: ""); put("days", c.days); put("correction", c.correctionHours)
            })
        }
    }

    companion object {
        fun of(c: LiveChannel) = ChannelSnapshot(c.key, c.sourceId, c.name, c.logo, c.url, c.headers, c.catchup)

        fun fromJson(o: JsonObject): ChannelSnapshot {
            val headers = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys.forEach { k -> headers[k] = h.optString(k) } }
            val c = o.optJSONObject("catchup")?.let {
                CatchupInfo(
                    it.optString("type"), it.optString("source").ifBlank { null }, it.optInt("days"),
                    (it["correction"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                )
            }
            return ChannelSnapshot(
                o.optString("key"), o.optString("sourceId"), o.optString("name"), o.optString("logo").ifBlank { null },
                o.optString("url"), headers, c
            )
        }
    }
}

/** A show set to record. */
data class RecordingSchedule(
    val id: String,
    val channel: ChannelSnapshot,
    val title: String,
    val episode: String?,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
    /** Downloaded from the provider's catch-up after it ends (the reliable way), not recorded live. */
    val useCatchup: Boolean,
    /** Set by a series rule (its id), or null for a single show. */
    val seriesId: String? = null,
    val attempts: Int = 0,
    /** Next time to try (catch-up downloads retry a few times). */
    val nextTryMs: Long = 0L,
    /** Extra time before and after, from the settings when it was set (-1: the usual 2 and 5 min). */
    val preMs: Long = -1L,
    val postMs: Long = -1L,
) {
    val pre: Long get() = if (preMs >= 0) preMs else RecordingRules.PRE_MS
    val post: Long get() = if (postMs >= 0) postMs else RecordingRules.POST_MS

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("channel", channel.toJson()); put("title", title)
        put("episode", episode ?: ""); put("description", description ?: "")
        put("startMs", startMs); put("stopMs", stopMs); put("useCatchup", useCatchup)
        put("seriesId", seriesId ?: ""); put("attempts", attempts); put("nextTryMs", nextTryMs)
        put("preMs", preMs); put("postMs", postMs)
    }

    companion object {
        fun fromJson(o: JsonObject) = RecordingSchedule(
            id = o.optString("id"),
            channel = ChannelSnapshot.fromJson(o.optJSONObject("channel") ?: JsonObject(emptyMap())),
            title = o.optString("title"),
            episode = o.optString("episode").ifBlank { null },
            description = o.optString("description").ifBlank { null },
            startMs = o.optLong("startMs"),
            stopMs = o.optLong("stopMs"),
            useCatchup = o.optBoolean("useCatchup"),
            seriesId = o.optString("seriesId").ifBlank { null },
            attempts = o.optInt("attempts"),
            nextTryMs = o.optLong("nextTryMs"),
            preMs = o.optLong("preMs", -1L),
            postMs = o.optLong("postMs", -1L),
        )
    }
}

/** "Record series": every airing of [title] on this channel. */
data class SeriesRule(
    val id: String,
    val channel: ChannelSnapshot,
    val title: String,
    /** Keep only the newest this many episodes (0 = keep them all). */
    val keepLast: Int = 0,
    /**
     * Episodes this series has recorded (see [RecordingRules.episodeKey]), kept even after a
     * recording is deleted or dropped by "keep the last N", so a repeat is never recorded again.
     */
    val seen: List<String> = emptyList(),
) {
    val norm: String get() = RecordingRules.normalizeTitle(title)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("channel", channel.toJson()); put("title", title); put("keepLast", keepLast)
        put("seen", buildJsonArray { seen.forEach { add(it) } })
    }

    companion object {
        fun fromJson(o: JsonObject) = SeriesRule(
            o.optString("id"), ChannelSnapshot.fromJson(o.optJSONObject("channel") ?: JsonObject(emptyMap())),
            o.optString("title"), o.optInt("keepLast"),
            o.optJSONArray("seen")?.strings()?.filter { it.isNotBlank() }.orEmpty()
        )
    }
}

/** The timing rules recordings follow (the TV app's), kept apart from the files and alarms so they can be tested. */
object RecordingRules {
    /** Live recordings start this early and run this late, unless the settings say otherwise. */
    const val PRE_MS = 2 * 60_000L
    const val POST_MS = 5 * 60_000L
    /** Catch-up downloads start this long after the show ends (providers need a moment). */
    const val CATCHUP_DELAY_MS = 10 * 60_000L
    const val RETRY_MS = 30 * 60_000L
    const val MAX_TRIES = 3

    /** When a live recording of a show runs: [preMs] before its start to [postMs] after its end. */
    fun window(startMs: Long, stopMs: Long, preMs: Long, postMs: Long): Pair<Long, Long> =
        (startMs - preMs.coerceAtLeast(0)) to (stopMs + postMs.coerceAtLeast(0))

    /** When a schedule fires: its retry time, else after the show (catch-up) or before it (live). */
    fun fireTime(s: RecordingSchedule): Long = when {
        s.nextTryMs > 0 -> s.nextTryMs
        s.useCatchup -> s.stopMs + CATCHUP_DELAY_MS
        else -> s.startMs - s.pre
    }

    /** A show on now (no catch-up) is recorded from now until its end plus the padding. */
    fun recordNowStop(stopMs: Long, postMs: Long): Long = stopMs + postMs.coerceAtLeast(0)

    /**
     * Another live recording on the same provider at the same time (padding included), if any.
     * Catch-up saves never clash: they download after the show.
     */
    fun clashWith(s: RecordingSchedule, all: List<RecordingSchedule>): RecordingSchedule? {
        if (s.useCatchup) return null
        val (start, stop) = window(s.startMs, s.stopMs, s.pre, s.post)
        return all.firstOrNull { o ->
            if (o.id == s.id || o.useCatchup || o.channel.sourceId != s.channel.sourceId) return@firstOrNull false
            val (oStart, oStop) = window(o.startMs, o.stopMs, o.pre, o.post)
            oStart < stop && start < oStop
        }
    }

    /** Whether [program] on [channelKey] is among [schedules]. */
    fun isScheduled(schedules: List<RecordingSchedule>, channelKey: String, program: EpgProgram): Boolean =
        schedules.any { it.channel.key == channelKey && it.startMs == program.startMs }

    /** A show title as compared between airings: lower case letters and digits. */
    fun normalizeTitle(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private val SE = Regex("""\bS(?:eason)?\s*0*(\d{1,3})\s*[\-.:,x]?\s*E(?:p(?:isode)?)?\s*0*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val SXE = Regex("""\b0*(\d{1,2})\s*x\s*0*(\d{1,4})\b""")
    private val XMLTV_NS = Regex("""^\s*(\d{1,3})\s*(?:/\s*\d+)?\s*\.\s*(\d{1,4})""")
    private val EP_ONLY = Regex("""^\s*(?:e|ep|episode)\.?\s*0*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)

    /**
     * One key per episode, whatever way the guide writes its number: "S02E05", "S2 E5",
     * "Season 2 Episode 5", "2x05" and XMLTV's "1.4." (counted from 0) all give "s2e5". Other
     * episode codes (a guide's own ids) are kept as they are. Null: no episode number.
     */
    fun episodeKey(episode: String?): String? {
        val e = episode?.trim().orEmpty()
        if (e.isEmpty()) return null
        SE.find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
        XMLTV_NS.find(e)?.let { return "s${it.groupValues[1].toInt() + 1}e${it.groupValues[2].toInt() + 1}" }
        SXE.find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
        EP_ONLY.find(e)?.let { return "e${it.groupValues[1].toInt()}" }
        return "x:" + e.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { return null }
    }

    /** "1.2 GB", "340 MB", "12 KB". */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${(bytes * 10 / (1024L * 1024 * 1024)) / 10.0} GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(0)} KB"
    }

    /** "1 h 23 min", "45 min". */
    fun durationLabel(seconds: Double): String {
        val m = (seconds / 60).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
