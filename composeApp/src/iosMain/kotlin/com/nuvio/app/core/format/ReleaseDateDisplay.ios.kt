package com.nuvio.app.core.format

import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String {
    val epochMs = parseEpisodeReleaseEpochMs(isoDate) ?: return isoDate
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0)
    return NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = localeTag)
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        setLocalizedDateFormatFromTemplate(if (includeYear) "dMMMMy" else "dMMMM")
    }.stringFromDate(date)
}
