package com.nuvio.app.core.format

import android.text.format.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatCalendarDate(
    isoDate: String,
    localeTag: String,
    includeYear: Boolean,
    abbreviatedMonth: Boolean,
): String {
    val locale = Locale.forLanguageTag(localeTag)
    val pattern = DateFormat.getBestDateTimePattern(locale, calendarDateSkeleton(includeYear, abbreviatedMonth))
    return DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.parse(isoDate))
}
