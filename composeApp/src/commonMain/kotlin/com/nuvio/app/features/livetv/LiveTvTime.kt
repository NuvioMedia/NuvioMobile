package com.nuvio.app.features.livetv

import kotlin.math.floor

internal object LiveTvTime {
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS

    fun nowEpochMs(): Long = currentEpochMs()

    fun alignToHour(ms: Long): Long = floor(ms.toDouble() / HOUR_MS).toLong() * HOUR_MS

    fun defaultGuideWindow(nowMs: Long = nowEpochMs()): Pair<Long, Long> {
        val start = alignToHour(nowMs) - HOUR_MS
        val end = start + (4 * HOUR_MS)
        return start to end
    }

    fun formatClock(ms: Long): String {
        val totalMinutes = ((ms % DAY_MS) / 60_000L).toInt()
        val hour = ((totalMinutes / 60) + 24) % 24
        val minute = totalMinutes % 60
        val suffix = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return buildString {
            append(displayHour)
            append(':')
            append(minute.toString().padStart(2, '0'))
            append(' ')
            append(suffix)
        }
    }

    fun formatHourLabel(ms: Long): String {
        val totalMinutes = ((ms % DAY_MS) / 60_000L).toInt()
        val hour = ((totalMinutes / 60) + 24) % 24
        val suffix = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$displayHour $suffix"
    }

    fun epochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long {
        var days = daysSinceEpoch(year, month, day)
        days += hour / 24
        val hourOfDay = hour % 24
        return days * DAY_MS + hourOfDay * HOUR_MS + minute * 60_000L + second * 1_000L
    }

    private fun daysSinceEpoch(year: Int, month: Int, day: Int): Long {
        var y = year.toLong()
        var m = month.toLong()
        y -= if (m <= 2) 1 else 0
        val era = if (y >= 0) y else y - 399
        val yearDays = 365 * era + era / 4 - era / 100 + era / 400
        val monthDays = (m + if (m > 2) -3 else 9) * 153 / 5 + day - 1
        return yearDays + monthDays - 719468
    }
}

internal expect fun currentEpochMs(): Long
