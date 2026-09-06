package com.nuvio.app.core.format

import com.nuvio.app.core.i18n.localizedMonthName
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate

private fun isEpisodicType(type: String?): Boolean {
    if (type.isNullOrBlank()) return false
    val t = type.trim().lowercase()
    return t in setOf("series", "show", "tv", "tvshow", "anime")
}

/**
 * Formats release dates for UI display:
 * - Movies: Absolute date format starting with day and ending with year ("5 June 2014")
 * - Series, Anime & Shows:
 *   - Standalone / 1 season completed: "yyyy" (e.g. "2018")
 *   - Multiple seasons continuing: "yyyy – " (e.g. "2000 – ")
 *   - Multiple seasons completed: "yyyy – yyyy" (e.g. "2000 – 2010")
 */
fun formatReleaseDateForDisplay(raw: String, type: String? = null): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw

    val isEpisodic = isEpisodicType(type)

    if (isEpisodic) {
        // 1. Check for multi-year range: e.g. "2000-2010", "2017–2022", "2024-2025"
        val fullRangeMatch = Regex("""^(\d{4})\s*[-–—/]\s*(\d{4})$""").find(trimmed)
        if (fullRangeMatch != null) {
            val (startYear, endYear) = fullRangeMatch.destructured
            return if (startYear == endYear) startYear else "$startYear – $endYear"
        }

        // 2. Check for explicit continuing/ongoing range: e.g. "2000-", "2000–", "1999-"
        val ongoingRangeMatch = Regex("""^(\d{4})\s*[-–—/]\s*$""").find(trimmed)
        if (ongoingRangeMatch != null) {
            val (startYear) = ongoingRangeMatch.destructured
            return "$startYear – "
        }

        // 3. Check if raw explicitly contains trailing ongoing indicator
        if (trimmed.endsWith("-") || trimmed.endsWith("–") || trimmed.contains(" – ")) {
            val year = extractReleaseYearForDisplay(trimmed)
            if (year != null) return "$year – "
        }

        // 4. Standalone / 1-season completed show: return year only (e.g. "2018")
        val year = extractReleaseYearForDisplay(trimmed)
        if (year != null) {
            return "$year"
        }

        return trimmed
    }

    // Movies & default types: Absolute date format starting with day and ending with year ("15 July 2026")
    val datePart = parseEpisodeReleaseLocalDate(trimmed)
    if (datePart != null) {
        val parts = datePart.split('-')
        if (parts.size == 3) {
            val year = parts[0].toIntOrNull()
            val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 }
            val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 }
            if (year != null && month != null && day != null) {
                return "$day ${localizedMonthName(month)} $year"
            }
        }
    }

    val yearMonthDayMatch = Regex("""^(\d{4})\s+([A-Za-z]+)\s+(\d{1,2})$""").find(trimmed)
    if (yearMonthDayMatch != null) {
        val (y, mStr, d) = yearMonthDayMatch.destructured
        return "$d $mStr $y"
    }

    val monthDayYearMatch = Regex("""^([A-Za-z]+)\s+(\d{1,2}),?\s+(\d{4})$""").find(trimmed)
    if (monthDayYearMatch != null) {
        val (mStr, d, y) = monthDayYearMatch.destructured
        return "$d $mStr $y"
    }

    return trimmed
}

fun formatReleaseDateWithoutYear(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    val datePart = parseEpisodeReleaseLocalDate(trimmed) ?: return raw
    val parts = datePart.split('-')
    if (parts.size != 3) return raw
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return raw
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return raw
    return "${localizedMonthName(month)} $day"
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
