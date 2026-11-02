package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.data.LiveTvTimeZones
import com.nuvio.app.features.livetv.model.LiveTvClock

/**
 * The few date jobs the Sports feeds need, in plain Kotlin (no java.time or SimpleDateFormat
 * in shared code): ESPN's US Eastern day keys, ISO 8601 parsing, the device's day boundaries
 * and short English labels for cards.
 */
internal object SportsDates {
    const val EASTERN = "America/New_York"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun two(n: Int) = n.toString().padStart(2, '0')

    /** "yyyyMMdd" for [ms] in US Eastern time (how ESPN groups a day's games). */
    fun dayKey(ms: Long): String {
        val t = CivilTime.at(ms, EASTERN)
        return "${t.year}${two(t.month)}${two(t.day)}"
    }

    /** "yyyyMM" in US Eastern time. */
    fun monthKey(ms: Long): String {
        val t = CivilTime.at(ms, EASTERN)
        return "${t.year}${two(t.month)}"
    }

    /** The start (midnight Eastern) of the day a "yyyyMMdd" key names, or null. */
    fun dayKeyStart(key: String): Long? {
        if (key.length != 8) return null
        val y = key.substring(0, 4).toIntOrNull() ?: return null
        val m = key.substring(4, 6).toIntOrNull() ?: return null
        val d = key.substring(6, 8).toIntOrNull() ?: return null
        return CivilTime.toEpochMs(y, m, d, 0, 0, 0, EASTERN)
    }

    /** "yyyy-MM-dd'T'HH:mm:ss'Z'" in UTC. */
    fun isoOut(ms: Long): String {
        val t = CivilTime.atUtc(ms)
        return "${t.year}-${two(t.month)}-${two(t.day)}T${two(t.hour)}:${two(t.minute)}:${two(t.second)}Z"
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    /**
     * An ISO 8601 instant ("2026-10-20T19:30Z", "2026-10-20T19:30:00.000Z", "2026-10-20T15:30:00-04:00",
     * "2026-10-20 19:30:00"), in ms. A date with no zone is read as UTC. Null when it doesn't parse.
     */
    fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = ISO.find(s.trim()) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        val h = g[4].toIntOrNull() ?: 0
        val mi = g[5].toIntOrNull() ?: 0
        val sec = g[6].toIntOrNull() ?: 0
        val frac = g[7].takeIf { it.isNotEmpty() }?.let { (it + "000").take(3).toLong() } ?: 0L
        var ms = (CivilTime.daysFromCivil(y, mo, d) * 86_400L + h * 3_600L + mi * 60L + sec) * 1_000L + frac
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val oh = digits.take(2).toIntOrNull() ?: 0
            val om = digits.drop(2).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600L + om * 60L) * 1_000L
        }
        return ms
    }

    /** Midnight on the device, [offset] days from today. */
    fun dayStart(offset: Int, now: Long = LiveTvClock.nowMs()): Long {
        val t = CivilTime.at(now, LiveTvTimeZones.SYSTEM)
        // Step by calendar days (not 24 hours: clock changes).
        val d = CivilTime.atUtc((CivilTime.daysFromCivil(t.year, t.month, t.day) + offset) * DAY)
        return CivilTime.toEpochMs(d.year, d.month, d.day, 0, 0, 0, LiveTvTimeZones.SYSTEM)
    }

    /** Midnight (device time) of the day [ms] falls on. */
    fun dayOf(ms: Long): Long {
        val t = CivilTime.at(ms, LiveTvTimeZones.SYSTEM)
        return CivilTime.toEpochMs(t.year, t.month, t.day, 0, 0, 0, LiveTvTimeZones.SYSTEM)
    }

    /** A local calendar date at midnight on the device (events that come with a date only). */
    fun localDate(year: Int, month: Int, day: Int): Long = CivilTime.toEpochMs(year, month, day, 0, 0, 0, LiveTvTimeZones.SYSTEM)

    /** The date part of an ISO string ("2026-10-20") as midnight on the device, or null. */
    fun localDateOf(iso: String?): Long? {
        val p = iso?.take(10)?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        if (p.size != 3) return null
        return localDate(p[0], p[1], p[2])
    }

    /** 0 = Monday … 6 = Sunday, for [ms] on the device. */
    private fun weekday(ms: Long): Int {
        val days = (ms + LiveTvTimeZones.offsetMs(LiveTvTimeZones.SYSTEM, ms)).floorDiv(DAY)
        // 1970-01-01 was a Thursday (index 3).
        return ((days + 3) % 7 + 7).toInt() % 7
    }

    /** "Sun 2 Nov". */
    fun dayLabel(ms: Long): String {
        val t = CivilTime.at(ms, LiveTvTimeZones.SYSTEM)
        return "${DAYS[weekday(ms)]} ${t.day} ${MONTHS[t.month - 1]}"
    }

    /** "Sun 2". */
    fun shortDayLabel(ms: Long): String {
        val t = CivilTime.at(ms, LiveTvTimeZones.SYSTEM)
        return "${DAYS[weekday(ms)]} ${t.day}"
    }

    /** "Sun". */
    fun weekdayLabel(ms: Long): String = DAYS[weekday(ms)]

    /** "Nov". */
    fun monthLabel(ms: Long): String = MONTHS[CivilTime.at(ms, LiveTvTimeZones.SYSTEM).month - 1]

    /** "2 Nov 2026". */
    fun longDateLabel(ms: Long): String {
        val t = CivilTime.at(ms, LiveTvTimeZones.SYSTEM)
        return "${t.day} ${MONTHS[t.month - 1]} ${t.year}"
    }

    /** Today's date on the device as "yyyy-MM-dd". */
    fun localIsoDate(now: Long = LiveTvClock.nowMs()): String {
        val t = CivilTime.at(now, LiveTvTimeZones.SYSTEM)
        return "${t.year}-${two(t.month)}-${two(t.day)}"
    }

    /** The device's year and month now. */
    fun localYearMonth(now: Long = LiveTvClock.nowMs()): Pair<Int, Int> {
        val t = CivilTime.at(now, LiveTvTimeZones.SYSTEM)
        return t.year to t.month
    }
}

/**
 * Accent folding for platforms without a Unicode normalizer: the Latin letters feeds and guides
 * use ("Gaúcho" -> "Gaucho", "Süper" -> "Super", "Taça" -> "Taca").
 */
internal object SportsAccents {
    private val FROM = "ÀÁÂÃÄÅàáâãäåÈÉÊËèéêëÌÍÎÏìíîïÒÓÔÕÖØòóôõöøÙÚÛÜùúûüÝýÿÑñÇçŠšŽžĀāĂăĄąĆćĈĉČčĎďĐđĒēĖėĘęĚěĞğĢģĪīĮįİıĶķĹĺĻļĽľŁłŃńŅņŇňŌōŐőŔŕŘřŚśŞşŢţŤťŪūŮůŰűŲųŹźŻżẞß"
    private val TO = "AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOOooooooUUUUuuuuYyyNnCcSsZzAaAaAaCcCcCcDdDdEeEeEeEeGgGgIiIiIiKkLlLlLlLlNnNnNnOoOoRrRrSsSsTtTtUuUuUuUuZzZzSs"

    fun fold(text: String): String {
        if (text.all { it.code < 128 }) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val i = FROM.indexOf(ch)
            when {
                i >= 0 -> sb.append(TO[i])
                ch == 'Æ' -> sb.append("AE")
                ch == 'æ' -> sb.append("ae")
                ch == 'Œ' -> sb.append("OE")
                ch == 'œ' -> sb.append("oe")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
