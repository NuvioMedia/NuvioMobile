package com.nuvio.app.core.format

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate

fun formatReleaseDateForDisplay(raw: String): String = formatReleaseDate(raw, includeYear = true)

fun formatReleaseDateWithoutYear(raw: String): String = formatReleaseDate(raw, includeYear = false)

fun formatCompactReleaseDate(raw: String): String =
    formatCompactReleaseDate(raw, Locale.current.toLanguageTag())

internal fun formatCompactReleaseDate(raw: String, localeTag: String): String {
    val date = parseEpisodeReleaseLocalDate(raw) ?: return raw
    return formatCalendarDate(date, localeTag, includeYear = true, abbreviatedMonth = true)
}

fun formatShortReleaseDate(raw: String, todayIsoDate: String): String =
    formatShortReleaseDate(raw, todayIsoDate, Locale.current.toLanguageTag())

internal fun formatShortReleaseDate(raw: String, todayIsoDate: String, localeTag: String): String {
    val date = parseEpisodeReleaseLocalDate(raw) ?: return raw
    val includeYear = date.take(4) != todayIsoDate.take(4)
    return formatCalendarDate(date, localeTag, includeYear, abbreviatedMonth = true)
}

internal fun formatReleaseDate(
    raw: String,
    includeYear: Boolean,
    localeTag: String = Locale.current.toLanguageTag(),
): String {
    val date = parseEpisodeReleaseLocalDate(raw) ?: return raw
    return formatCalendarDate(date, localeTag, includeYear)
}

internal expect fun formatCalendarDate(
    isoDate: String,
    localeTag: String,
    includeYear: Boolean,
    abbreviatedMonth: Boolean = false,
): String

internal fun calendarDateSkeleton(includeYear: Boolean, abbreviatedMonth: Boolean): String =
    (if (abbreviatedMonth) "dMMM" else "dMMMM") + if (includeYear) "y" else ""

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
