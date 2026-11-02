package com.nuvio.app.features.livetv.data

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.knownTimeZoneNames
import platform.Foundation.secondsFromGMTForDate
import platform.Foundation.systemTimeZone
import platform.Foundation.timeZoneWithName

internal actual object LiveTvTimeZones {
    actual val SYSTEM: String = "\u0000system"
    actual val UTC: String = "UTC"

    actual fun isKnown(zoneId: String): Boolean =
        zoneId == SYSTEM || zoneId.equals("UTC", ignoreCase = true) ||
            NSTimeZone.knownTimeZoneNames.any { it == zoneId } || NSTimeZone.timeZoneWithName(zoneId) != null

    actual fun offsetMs(zone: String, epochMs: Long): Long {
        val tz = when {
            zone == SYSTEM -> NSTimeZone.systemTimeZone
            else -> NSTimeZone.timeZoneWithName(zone) ?: return 0L
        }
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        return tz.secondsFromGMTForDate(date) * 1000L
    }
}
