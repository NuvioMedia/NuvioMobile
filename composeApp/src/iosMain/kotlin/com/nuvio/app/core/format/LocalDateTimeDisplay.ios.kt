package com.nuvio.app.core.format

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatLocalDateTime(epochMs: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterShortStyle
    timeStyle = NSDateFormatterShortStyle
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))

actual fun formatLocalTime(epochMs: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterNoStyle
    timeStyle = NSDateFormatterShortStyle
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0))
