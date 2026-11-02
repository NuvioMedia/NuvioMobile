package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.LiveTvClock

/**
 * Builds archive (catch-up) URLs for the common provider formats:
 * default / append (template in catchup-source), shift, flussonic and xtream codes.
 */
object CatchupUrlBuilder {

    fun isAvailable(catchup: CatchupInfo?, programStartMs: Long, nowMs: Long): Boolean {
        if (catchup == null) return false
        if (programStartMs >= nowMs) return false
        val maxAgeMs = catchup.days.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        return nowMs - programStartMs <= maxAgeMs
    }

    fun build(
        liveUrl: String,
        catchup: CatchupInfo,
        startMs: Long,
        stopMs: Long,
        nowMs: Long = LiveTvClock.nowMs(),
        /** Xtream: ask for HLS (.m3u8) instead of TS. */
        preferHls: Boolean = false,
        /** Xtream: the server's time zone (catch-up times are in server time). */
        serverTimezone: String? = null
    ): String? {
        // "catchup-correction": the playlist's own time fix for this channel, in hours.
        val correctionMs = (catchup.correctionHours * 3_600_000).toLong()
        val startMsC = startMs + correctionMs
        val start = startMsC / 1000
        val end = (stopMs + correctionMs) / 1000
        val now = nowMs / 1000
        val duration = (end - start).coerceAtLeast(60)
        // Xtream-style links (server/user/pass/id) whose playlist doesn't say which kind of
        // catch-up: they use Xtream's /timeshift/ links, like TiviMate assumes.
        val xtreamGuess = { xtream(liveUrl, startMsC, duration, preferHls, serverTimezone) }
        return when (catchup.type) {
            "append" -> catchup.source?.let { liveUrl + fill(it, start, end, now, duration) }
            "default" -> {
                val src = catchup.source
                when {
                    src.isNullOrBlank() -> xtreamGuess() ?: shift(liveUrl, start, now)
                    src.startsWith("http", ignoreCase = true) -> fill(src, start, end, now, duration)
                    else -> liveUrl + fill(src, start, end, now, duration)
                }
            }
            "shift", "timeshift" -> if (catchup.source.isNullOrBlank()) xtreamGuess() ?: shift(liveUrl, start, now) else shift(liveUrl, start, now)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, start, duration)
            "xc", "xtream" -> xtream(liveUrl, startMsC, duration, preferHls, serverTimezone)
            else -> catchup.source?.let { fill(it, start, end, now, duration) }
        }
    }

    private fun shift(url: String, start: Long, now: Long): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}utc=$start&lutc=$now"
    }

    private val flussonicLink = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""")

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val m = flussonicLink.find(url) ?: return null
        val host = m.groupValues[1]
        val stream = m.groupValues[2]
        val file = m.groupValues[3]
        val query = m.groupValues[4]
        return if (file.endsWith(".m3u8") || file.isEmpty()) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    /** http://host:port/(live/)?user/pass/id(.ext) */
    private val xtreamLink = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun xtream(url: String, startMs: Long, duration: Long, preferHls: Boolean, serverTimezone: String?): String? {
        val m = xtreamLink.find(url) ?: return null
        val query = m.groupValues[6]
        val (host, user, pass, id) = m.destructured
        // HLS (.m3u8) replays come with a length, so the seek bar and skipping work properly.
        val ext = if (preferHls) ".m3u8" else m.groupValues[5].ifEmpty { ".ts" }
        val minutes = (duration / 60).coerceAtLeast(1)
        return "$host/timeshift/$user/$pass/$minutes/${stamp(startMs, zone(serverTimezone))}/$id$ext$query"
    }

    /**
     * Other ways to ask for the same catch-up, tried in turn if [url] fails, because Xtream
     * panels differ: TS instead of HLS, the older timeshift.php form, the link in this device's
     * time instead of the server's (or the other way round), and with/without "/live".
     */
    fun alternatives(
        url: String,
        serverTimezone: String?,
        /** The show's own length in minutes: some panels refuse a window longer than that. */
        showMinutes: Long? = null,
        /**
         * A show still on air (watch from the beginning): how many minutes of it have aired.
         * Some panels refuse a window that reaches into the future.
         */
        airedMinutes: Long? = null
    ): List<String> {
        val path = url.substringBefore('?')
        val query = url.substring(path.length)
        val m = Regex("""^(https?://[^/]+)/timeshift/([^/]+)/([^/]+)/(\d+)/([0-9-]+:[0-9-]+)/(\d+)(\.[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)
            .find(path) ?: return listOfNotNull(tsFallback(url))
        val (host, user, pass, minutes, stamp, id) = m.destructured
        val ext = m.groupValues[7]
        val out = LinkedHashSet<String>()
        if (ext.equals(".m3u8", true)) out += "$host/timeshift/$user/$pass/$minutes/$stamp/$id.ts$query"
        // TS answered with something that isn't video: many panels only replay as HLS.
        else out += "$host/timeshift/$user/$pass/$minutes/$stamp/$id.m3u8$query"
        // A show still on air: only the part that has aired so far (in both forms). Tried before
        // the other forms below, so a refusal doesn't end with the "other clock" link, which
        // plays an earlier show when the server's time zone was right all along.
        val aired = airedMinutes?.coerceAtLeast(1)?.takeIf { it < (minutes.toLongOrNull() ?: 0) }
        if (aired != null) {
            out += "$host/timeshift/$user/$pass/$aired/$stamp/$id${ext.ifEmpty { ".ts" }}$query"
            out += "$host/timeshift/$user/$pass/$aired/$stamp/$id${if (ext.equals(".m3u8", true)) ".ts" else ".m3u8"}$query"
        }
        // Just the show, without the extra time after it (in both forms).
        val short = showMinutes?.coerceAtLeast(1)?.takeIf { it < (minutes.toLongOrNull() ?: 0) }
        if (short != null) {
            out += "$host/timeshift/$user/$pass/$short/$stamp/$id${ext.ifEmpty { ".ts" }}$query"
            out += "$host/timeshift/$user/$pass/$short/$stamp/$id${if (ext.equals(".m3u8", true)) ".ts" else ".m3u8"}$query"
        }
        // timeshift.php form (some panels only answer this).
        out += "$host/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$stamp&duration=$minutes"
        // Same request with the time written in the other clock (server vs device).
        val serverZone = serverTimezone?.takeIf { it.isNotBlank() && LiveTvTimeZones.isKnown(it) }
        val startMs = parseStamp(stamp, serverZone ?: LiveTvTimeZones.SYSTEM)
        if (startMs != null) {
            val other = stamp(startMs, if (serverZone != null) LiveTvTimeZones.SYSTEM else LiveTvTimeZones.UTC)
            if (other != stamp) out += "$host/timeshift/$user/$pass/$minutes/$other/$id${ext.ifEmpty { ".ts" }}$query"
        }
        out.remove(url)
        return out.toList()
    }

    /** The TS version of an Xtream HLS catch-up link, for panels that don't offer HLS. */
    fun tsFallback(url: String): String? {
        val path = url.substringBefore('?')
        if (!path.contains("/timeshift/") || !path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(".m3u8".length) + ".ts" + url.substring(path.length)
    }

    private val formattedToken = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}""")
    private val durationToken = Regex("""\$?\{duration:(\d+)\}""")
    private val offsetToken = Regex("""\$?\{offset:(\d+)\}""")

    internal fun fill(template: String, start: Long, end: Long, now: Long, duration: Long): String {
        var out = template
        // {utc:YmdHMS} / ${start:Y-m-d} style formatted dates.
        out = formattedToken.replace(out) { m ->
            val epoch = when (m.groupValues[1]) {
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                else -> start
            }
            formatPattern(m.groupValues[2], epoch)
        }
        out = durationToken.replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            (duration / divisor).toString()
        }
        out = offsetToken.replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            ((now - start) / divisor).toString()
        }
        val simple = mapOf(
            "utc" to start, "start" to start, "timestamp" to now,
            "utcend" to end, "end" to end,
            "lutc" to now, "now" to now,
            "duration" to duration, "offset" to (now - start)
        )
        simple.forEach { (k, v) ->
            out = out.replace("\${$k}", v.toString()).replace("{$k}", v.toString())
        }
        // Bare {Y}{m}{d}{H}{M}{S} refer to the program start in local time.
        val t = CivilTime.at(start * 1000, LiveTvTimeZones.SYSTEM)
        out = out
            .replace("{Y}", pad(t.year, 4))
            .replace("{m}", pad(t.month, 2))
            .replace("{d}", pad(t.day, 2))
            .replace("{H}", pad(t.hour, 2))
            .replace("{M}", pad(t.minute, 2))
            .replace("{S}", pad(t.second, 2))
        return out
    }

    /**
     * A strftime-style pattern (Y, m, d, H, M, S; anything else copied), for the program's time
     * in UTC. Like the TV app's SimpleDateFormat version, other letters come through as they are.
     */
    private fun formatPattern(pattern: String, epochSec: Long): String {
        val t = CivilTime.at(epochSec * 1000, LiveTvTimeZones.UTC)
        return buildString {
            pattern.forEach { c ->
                append(
                    when (c) {
                        'Y' -> pad(t.year, 4)
                        'm' -> pad(t.month, 2)
                        'd' -> pad(t.day, 2)
                        'H' -> pad(t.hour, 2)
                        'M' -> pad(t.minute, 2)
                        'S' -> pad(t.second, 2)
                        else -> c.toString()
                    }
                )
            }
        }
    }

    private fun zone(name: String?): String =
        name?.takeIf { it.isNotBlank() && LiveTvTimeZones.isKnown(it) } ?: LiveTvTimeZones.SYSTEM

    /** Xtream's "yyyy-MM-dd:HH-mm" for a time, in [zone]. */
    private fun stamp(epochMs: Long, zone: String): String {
        val t = CivilTime.at(epochMs, zone)
        return "${pad(t.year, 4)}-${pad(t.month, 2)}-${pad(t.day, 2)}:${pad(t.hour, 2)}-${pad(t.minute, 2)}"
    }

    private val stampPattern = Regex("""^(\d{4})-(\d{2})-(\d{2}):(\d{2})-(\d{2})$""")

    /** The reverse of [stamp]; null for anything that isn't one. */
    private fun parseStamp(stamp: String, zone: String): Long? {
        val m = stampPattern.find(stamp) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return CivilTime.toEpochMs(y.toInt(), month, day, h.toInt(), mi.toInt(), 0, zone)
    }

    private fun pad(n: Int, width: Int): String = n.toString().padStart(width, '0')
}

/** A calendar date and time of day, in some time zone. */
internal data class CivilTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int) {
    companion object {
        /** The civil time of [epochMs] in [zone] (a zone id, [LiveTvTimeZones.SYSTEM] or [LiveTvTimeZones.UTC]). */
        fun at(epochMs: Long, zone: String): CivilTime = atUtc(epochMs + LiveTvTimeZones.offsetMs(zone, epochMs))

        fun atUtc(epochMs: Long): CivilTime {
            val days = epochMs.floorDiv(86_400_000L)
            var rem = epochMs - days * 86_400_000L
            val hour = (rem / 3_600_000L).toInt(); rem -= hour * 3_600_000L
            val minute = (rem / 60_000L).toInt(); rem -= minute * 60_000L
            val second = (rem / 1_000L).toInt()
            // Civil date from days since the epoch (Howard Hinnant's algorithm).
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return CivilTime(y, m, d, hour, minute, second)
        }

        /** Days since 1970-01-01 for a calendar date. */
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        /** The instant at a civil time in [zone]. */
        fun toEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: String): Long {
            val asUtc = (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
            // The offset depends on the instant (summer time): take it at the UTC guess, then
            // once more at the result, which settles it except right at a clock change.
            val guess = asUtc - LiveTvTimeZones.offsetMs(zone, asUtc)
            return asUtc - LiveTvTimeZones.offsetMs(zone, guess)
        }
    }
}

/** The platform's time zone database, for the few places catch-up needs server-local time. */
internal expect object LiveTvTimeZones {
    /** Stands for the device's own zone. */
    val SYSTEM: String
    /** Stands for UTC. */
    val UTC: String
    /** Whether [zoneId] names a zone the platform knows. */
    fun isKnown(zoneId: String): Boolean
    /** [zone]'s offset from UTC at [epochMs], in milliseconds (0 for an unknown zone). */
    fun offsetMs(zone: String, epochMs: Long): Long
}
