package com.nuvio.app.features.livetv

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.localeWithLocaleIdentifier
import platform.Foundation.timeIntervalSince1970

private const val COCOA_REFERENCE_DATE_EPOCH_SECONDS = 978307200.0

actual object LiveTvClock {
    actual fun nowEpochMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    actual fun formatLocalTime(epochMs: Long): String =
        NSDateFormatter().apply {
            locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
            dateFormat = "HH:mm"
        }.stringFromDate(
            NSDate(
                timeIntervalSinceReferenceDate =
                    (epochMs / 1000.0) - COCOA_REFERENCE_DATE_EPOCH_SECONDS,
            ),
        )

    actual fun parseXmlTvTimestamp(value: String): Long? {
        val parts = value.trim().split(Regex("\\s+"), limit = 2)
        val digits = parts.firstOrNull().orEmpty()
        val normalizedDigits = when (digits.length) {
            12 -> "${digits}00"
            14 -> digits
            else -> return null
        }
        val formatter = NSDateFormatter().apply {
            locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
            dateFormat = if (parts.size > 1) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss"
        }
        val input = if (parts.size > 1) "$normalizedDigits ${parts[1]}" else normalizedDigits
        return formatter.dateFromString(input)?.timeIntervalSince1970?.times(1000.0)?.toLong()
    }
}
