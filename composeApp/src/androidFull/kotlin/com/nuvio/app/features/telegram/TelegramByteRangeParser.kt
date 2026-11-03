package com.nuvio.app.features.telegram

internal data class TelegramByteRange(
    val start: Long,
    val end: Long,
) {
    val length: Long get() = end - start + 1L
}

/**
 * Parses a single HTTP byte range. Multiple ranges are intentionally rejected because
 * the Telegram proxy streams one bounded interval per player request.
 */
internal object TelegramByteRangeParser {
    fun parse(header: String?, totalSize: Long): TelegramByteRange? {
        if (totalSize <= 0L) return null
        if (header.isNullOrBlank()) return TelegramByteRange(0L, totalSize - 1L)
        if (!header.startsWith("bytes=", ignoreCase = true)) return null

        val value = header.substringAfter("bytes=", "").trim()
        if (value.isBlank() || value.contains(',')) return null

        val separator = value.indexOf('-')
        if (separator < 0) return null
        val left = value.substring(0, separator).trim()
        val right = value.substring(separator + 1).trim()

        return when {
            left.isBlank() -> {
                val suffixLength = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
                val length = minOf(suffixLength, totalSize)
                TelegramByteRange(totalSize - length, totalSize - 1L)
            }
            else -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L && it < totalSize } ?: return null
                val requestedEnd = if (right.isBlank()) {
                    totalSize - 1L
                } else {
                    right.toLongOrNull()?.takeIf { it >= start } ?: return null
                }
                TelegramByteRange(start, minOf(requestedEnd, totalSize - 1L))
            }
        }
    }
}
