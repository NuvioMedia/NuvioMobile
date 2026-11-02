package com.nuvio.app.features.livetv.data

import java.util.TimeZone

internal actual object LiveTvTimeZones {
    actual val SYSTEM: String = "\u0000system"
    actual val UTC: String = "UTC"

    actual fun isKnown(zoneId: String): Boolean =
        zoneId == SYSTEM || zoneId.equals("UTC", ignoreCase = true) || TimeZone.getAvailableIDs().contains(zoneId)

    actual fun offsetMs(zone: String, epochMs: Long): Long {
        val tz = when {
            zone == SYSTEM -> TimeZone.getDefault()
            isKnown(zone) -> TimeZone.getTimeZone(zone)
            else -> return 0L
        }
        return tz.getOffset(epochMs).toLong()
    }
}
