package com.nuvio.app.features.autosync

/**
 * Applies an AutoSync timeline to a subtitle file's text by rewriting only its timestamps, for
 * players that load the subtitle file themselves (libmpv). Styling, positioning and markup stay
 * untouched, so ASS/SSA keeps rendering through libass.
 *
 * Handles SRT, WebVTT and ASS/SSA. Returns null for any other format, or when the text has no
 * timing to rewrite.
 */
internal object AutoSyncSubtitleTextRetimer {
    private const val TIMESTAMP = """(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?"""
    private val timingLineRegex = Regex("""^(\s*)($TIMESTAMP)(\s*-->\s*)($TIMESTAMP)(.*)$""")
    private val timestampRegex = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})(?:([.,])(\d+))?$""")
    private val ttmlRegex = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)
    private const val ASS_DEFAULT_FIELD_COUNT = 10
    private const val ASS_DEFAULT_START_INDEX = 1
    private const val ASS_DEFAULT_END_INDEX = 2
    private const val ASS_MIN_DURATION_MS = 10L

    fun retime(body: String, timeline: AutoSyncTimelineRetimeResult): String? {
        val text = body
            .replace("﻿", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (ttmlRegex.containsMatchIn(text.take(512))) return null
        val mapper = AutoSyncTimelineTimeMapper(timeline)
        val sample = text.take(4_096).lowercase()
        return if (sample.contains("[events]") && sample.contains("dialogue:")) {
            retimeAss(text, mapper)
        } else {
            retimeTimingLines(text, mapper)
        }
    }

    /** SRT and WebVTT: every `start --> end` line, keeping cue settings after the end time. */
    private fun retimeTimingLines(text: String, mapper: AutoSyncTimelineTimeMapper): String? {
        val lines = text.split('\n').toMutableList()
        val entries = ArrayList<TimingEntry>()
        lines.forEachIndexed { index, line ->
            val match = timingLineRegex.matchEntire(line) ?: return@forEachIndexed
            val start = parseTimestamp(match.groupValues[2]) ?: return@forEachIndexed
            val end = parseTimestamp(match.groupValues[4]) ?: return@forEachIndexed
            val originalEndMs = end.ms.coerceAtLeast(start.ms + 1L)
            val startMs = mapper.mapStartMs(start.ms)
            entries += TimingEntry(
                lineIndex = index,
                match = match,
                start = start,
                end = end,
                originalStartMs = start.ms,
                originalEndMs = originalEndMs,
                startMs = startMs,
                endMs = mapper.mapEndMs(originalEndMs).coerceAtLeast(startMs + 1L),
            )
        }
        if (entries.isEmpty()) return null

        clampIntroducedOverlaps(entries)

        entries.forEach { entry ->
            val groups = entry.match.groupValues
            lines[entry.lineIndex] = groups[1] +
                formatLike(entry.startMs, entry.start) +
                groups[3] +
                formatLike(entry.endMs, entry.end) +
                groups[5]
        }
        return lines.joinToString("\n")
    }

    /** A retimed cue that now runs into the next one is cut at its start, as long as the source did not overlap. */
    private fun clampIntroducedOverlaps(entries: List<TimingEntry>) {
        for (index in 0 until entries.lastIndex) {
            val current = entries[index]
            val next = entries[index + 1]
            if (current.originalEndMs > next.originalStartMs) continue
            if (current.endMs > next.startMs && next.startMs > current.startMs) {
                current.endMs = next.startMs
            }
        }
    }

    /** ASS/SSA: the Start and End fields of each Dialogue line in [Events]. */
    private fun retimeAss(text: String, mapper: AutoSyncTimelineTimeMapper): String? {
        val lines = text.split('\n').toMutableList()
        var inEvents = false
        var fieldCount = ASS_DEFAULT_FIELD_COUNT
        var startIndex = ASS_DEFAULT_START_INDEX
        var endIndex = ASS_DEFAULT_END_INDEX
        var rewritten = 0

        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") ->
                    inEvents = trimmed.equals("[Events]", ignoreCase = true)
                inEvents && trimmed.startsWith("Format:", ignoreCase = true) -> {
                    val fields = trimmed.substringAfter(':').split(',').map { it.trim() }
                    fieldCount = fields.size
                    startIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_START_INDEX
                    endIndex = fields.indexOfFirst { it.equals("End", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_END_INDEX
                }
                inEvents && trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val colon = line.indexOf(':')
                    val parts = line.substring(colon + 1).split(',', limit = fieldCount).toMutableList()
                    if (parts.size <= maxOf(startIndex, endIndex)) continue
                    val start = parseTimestamp(parts[startIndex].trim()) ?: continue
                    val end = parseTimestamp(parts[endIndex].trim()) ?: continue
                    val startMs = mapper.mapStartMs(start.ms)
                    val endMs = mapper.mapEndMs(end.ms.coerceAtLeast(start.ms + 1L))
                        .coerceAtLeast(startMs + ASS_MIN_DURATION_MS)
                    parts[startIndex] = parts[startIndex].leadingWhitespace() + formatAss(startMs)
                    parts[endIndex] = parts[endIndex].leadingWhitespace() + formatAss(endMs)
                    lines[index] = line.substring(0, colon + 1) + parts.joinToString(",")
                    rewritten++
                }
            }
        }
        return if (rewritten == 0) null else lines.joinToString("\n")
    }

    private fun parseTimestamp(raw: String): ParsedTimestamp? {
        val match = timestampRegex.matchEntire(raw) ?: return null
        val hoursText = match.groupValues[1]
        val fraction = match.groupValues[5]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        val ms = (
            (hoursText.toLongOrNull() ?: 0L) * 3_600L +
                match.groupValues[2].toLong() * 60L +
                match.groupValues[3].toLong()
            ) * 1_000L + millis
        return ParsedTimestamp(
            ms = ms,
            hasHours = hoursText.isNotEmpty(),
            separator = match.groupValues[4].firstOrNull() ?: ',',
        )
    }

    /** `HH:MM:SS,mmm` in the original's style: same decimal separator, hours only if it had them or needs them. */
    private fun formatLike(ms: Long, original: ParsedTimestamp): String {
        val hours = ms / 3_600_000L
        val minutes = (ms / 60_000L) % 60L
        val seconds = (ms / 1_000L) % 60L
        val millis = ms % 1_000L
        val clock = "${minutes.pad(2)}:${seconds.pad(2)}${original.separator}${millis.pad(3)}"
        return if (original.hasHours || hours > 0L) "${hours.pad(2)}:$clock" else clock
    }

    /** ASS time: `H:MM:SS.CC`, rounded to centiseconds. */
    private fun formatAss(ms: Long): String {
        val centis = (ms + 5L) / 10L
        return "${centis / 360_000L}:${((centis / 6_000L) % 60L).pad(2)}:" +
            "${((centis / 100L) % 60L).pad(2)}.${(centis % 100L).pad(2)}"
    }

    private fun Long.pad(width: Int): String = toString().padStart(width, '0')

    private fun String.leadingWhitespace(): String = takeWhile { it.isWhitespace() }

    private data class ParsedTimestamp(
        val ms: Long,
        val hasHours: Boolean,
        val separator: Char,
    )

    private class TimingEntry(
        val lineIndex: Int,
        val match: MatchResult,
        val start: ParsedTimestamp,
        val end: ParsedTimestamp,
        val originalStartMs: Long,
        val originalEndMs: Long,
        val startMs: Long,
        var endMs: Long,
    )
}
