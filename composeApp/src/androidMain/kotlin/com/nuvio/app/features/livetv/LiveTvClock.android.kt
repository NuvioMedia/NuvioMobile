package com.nuvio.app.features.livetv

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

actual object LiveTvClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun formatLocalTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    actual fun parseXmlTvTimestamp(value: String): Long? {
        val parts = value.trim().split(Regex("\\s+"), limit = 2)
        val digits = parts.firstOrNull().orEmpty()
        val normalizedDigits = when (digits.length) {
            12 -> "${digits}00"
            14 -> digits
            else -> return null
        }
        return runCatching {
            if (parts.size > 1) {
                OffsetDateTime.parse(
                    "$normalizedDigits ${parts[1]}",
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z"),
                ).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(
                    normalizedDigits,
                    DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }
}
