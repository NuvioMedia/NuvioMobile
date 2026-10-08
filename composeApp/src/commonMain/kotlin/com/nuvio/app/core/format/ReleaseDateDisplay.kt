package com.nuvio.app.core.format

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.core.i18n.localizedMonthName
import com.nuvio.app.core.i18n.localizedShortMonthName
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate

fun formatReleaseDateForDisplay(raw: String): String = formatReleaseDate(raw, includeYear = true)

fun formatReleaseDateWithoutYear(raw: String): String = formatReleaseDate(raw, includeYear = false)

internal fun formatReleaseDate(
    raw: String,
    includeYear: Boolean,
    localeTag: String = Locale.current.toLanguageTag(),
): String {
    val date = parseEpisodeReleaseLocalDate(raw) ?: return raw
    return formatCalendarDate(date, localeTag, includeYear)
}

internal expect fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String

/**
 * Formats ISO calendar dates (yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss…) with day-first ordering:
 * e.g., "5 Sep 2026" (or "5 Sep" if without year).
 * Returns null if the raw string is blank or cannot be parsed as an ISO date.
 */
fun formatDayFirstReleaseDate(
    raw: String?,
    includeYear: Boolean = true,
): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val datePart = parseEpisodeReleaseLocalDate(trimmed) ?: return null
    val parts = datePart.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null

    val monthName = localizedShortMonthName(month)
    return if (includeYear) {
        "$day $monthName $year"
    } else {
        "$day $monthName"
    }
}

/**
 * Formats a release date string with day-first ordering, falling back to the raw string if unparseable.
 */
fun formatReleaseDateDayFirst(
    raw: String,
    includeYear: Boolean = true,
): String {
    return formatDayFirstReleaseDate(raw, includeYear = includeYear) ?: raw
}

/**
 * Parses a release/air string (ISO date, year-only, or timestamp prefix) for compact UI (e.g. year chips).
 */
fun extractReleaseYearForDisplay(raw: String): Int? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t.length == 4 && t.all { it.isDigit() }) {
        return t.toIntOrNull()?.takeIf { it in 1000..9999 }
    }
    val datePart = parseEpisodeReleaseLocalDate(t) ?: return null
    val yearStr = datePart.split('-').firstOrNull() ?: return null
    return yearStr.toIntOrNull()?.takeIf { it in 1000..9999 }
}
