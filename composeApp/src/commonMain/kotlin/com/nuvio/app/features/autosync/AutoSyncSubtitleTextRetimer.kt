package com.nuvio.app.features.autosync

/**
 * Applies an AutoSync timeline to a subtitle file's text by rewriting only its timestamps, for
 * players that load the subtitle file themselves (mpv). Styling, positioning and markup stay
 * untouched, so ASS/SSA keeps rendering through libass.
 *
 * Handles SRT, WebVTT and ASS/SSA. Returns null for any other format, or when the text has no
 * timing to rewrite.
 */
internal object AutoSyncSubtitleTextRetimer {
    private val ttmlRegex = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)
    private const val ASS_DEFAULT_FIELD_COUNT = 10
    private const val ASS_DEFAULT_START_INDEX = 1
    private const val ASS_DEFAULT_END_INDEX = 2
    private const val ASS_MIN_DURATION_MS = 10L

    fun retime(body: String, timeline: AutoSyncTimelineRetimeResult): String? =
        retime(body, AutoSyncTimelineTimeMapper(timeline))

    fun retime(body: String, mapper: SubtitleTimeMapping): String? {
        val text = AutoSyncSubtitleCueParser.cleanText(body)
        if (ttmlRegex.containsMatchIn(text.substring(0, minOf(512, text.length)))) return null
        // Not a prefix sample: embedded [Fonts] or [Graphics] can push [Events] far into the file.
        return if (AutoSyncSubtitleCueParser.hasAssEventsHeader(text)) {
            retimeAss(text, mapper)
        } else {
            retimeTimingLines(text, mapper)
        }
    }

    /** SRT and WebVTT: every `start --> end` line, keeping cue settings after the end time. */
    private fun retimeTimingLines(text: String, mapper: SubtitleTimeMapping): String? {
        val len = text.length
        val entries = ArrayList<TimingEntry>(maxOf(16, len / 80))
        var pos = 0

        while (pos <= len) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }
            val arrow = AutoSyncSubtitleCueParser.indexOfArrow(text, pos, lineEnd)
            if (arrow >= 0) {
                parseTimingLine(text, pos, lineEnd, arrow, mapper)?.let { entries += it }
            }
            if (lineEnd >= len) break
            pos = lineEnd + 1
        }
        if (entries.isEmpty()) return null

        clampIntroducedOverlaps(entries)

        val out = StringBuilder(len + 64)
        var cursor = 0
        for (i in entries.indices) {
            val entry = entries[i]
            out.append(text, cursor, entry.startTsFrom)
            appendFormattedLike(out, entry.startMs, entry.startHasHours, entry.startSeparator)
            out.append(text, entry.startTsTo, entry.endTsFrom)
            appendFormattedLike(out, entry.endMs, entry.endHasHours, entry.endSeparator)
            cursor = entry.endTsTo
        }
        if (cursor < len) out.append(text, cursor, len)
        return out.toString()
    }

    private fun parseTimingLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        arrow: Int,
        mapper: SubtitleTimeMapping,
    ): TimingEntry? {
        val startTsFrom = AutoSyncSubtitleCueParser.trimStartIndex(text, lineStart, arrow)
        val startTsTo = AutoSyncSubtitleCueParser.trimEndIndex(text, startTsFrom, arrow)
        val startMsOrig = AutoSyncSubtitleCueParser.parseTimestampMsRange(text, startTsFrom, startTsTo)
            ?: return null

        val endTsFrom = AutoSyncSubtitleCueParser.trimStartIndex(text, arrow + 3, lineEnd)
        var endTsTo = endTsFrom
        while (endTsTo < lineEnd) {
            val ch = text[endTsTo]
            if (ch in '0'..'9' || ch == ':' || ch == '.' || ch == ',') endTsTo++ else break
        }
        val endMsOrig = AutoSyncSubtitleCueParser.parseTimestampMsRange(text, endTsFrom, endTsTo)
            ?: return null

        val originalEndMs = endMsOrig.coerceAtLeast(startMsOrig + 1L)
        val startMs = mapper.mapStartMs(startMsOrig)
        return TimingEntry(
            startTsFrom = startTsFrom,
            startTsTo = startTsTo,
            endTsFrom = endTsFrom,
            endTsTo = endTsTo,
            startHasHours = hasHoursInRange(text, startTsFrom, startTsTo),
            startSeparator = separatorInRange(text, startTsFrom, startTsTo),
            endHasHours = hasHoursInRange(text, endTsFrom, endTsTo),
            endSeparator = separatorInRange(text, endTsFrom, endTsTo),
            originalStartMs = startMsOrig,
            originalEndMs = originalEndMs,
            startMs = startMs,
            endMs = mapper.mapEndMs(originalEndMs).coerceAtLeast(startMs + 1L),
        )
    }

    private fun hasHoursInRange(text: String, start: Int, end: Int): Boolean {
        var colons = 0
        for (i in start until end) if (text[i] == ':') colons++
        return colons >= 2
    }

    private fun separatorInRange(text: String, start: Int, end: Int): Char {
        for (i in start until end) if (text[i] == '.') return '.'
        return ','
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
    private fun retimeAss(text: String, mapper: SubtitleTimeMapping): String? {
        val len = text.length
        val out = StringBuilder(len + 64)
        var inEvents = false
        var fieldCount = ASS_DEFAULT_FIELD_COUNT
        var startIndex = ASS_DEFAULT_START_INDEX
        var endIndex = ASS_DEFAULT_END_INDEX
        var rewritten = 0

        val partStarts = IntArray(32)
        val partEnds = IntArray(32)
        var pos = 0

        while (pos <= len) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }
            val tStart = AutoSyncSubtitleCueParser.trimStartIndex(text, pos, lineEnd)
            val tEnd = AutoSyncSubtitleCueParser.trimEndIndex(text, tStart, lineEnd)

            var handled = false
            if (tStart < tEnd) {
                if (text[tStart] == '[' && text[tEnd - 1] == ']') {
                    inEvents = tEnd - tStart == 8 &&
                        text.regionMatches(tStart, "[Events]", 0, 8, ignoreCase = true)
                    // Bulk-copy multi-MB [Fonts] / [Graphics] sections verbatim instead of scanning line-by-line.
                    val isBulkSection = !inEvents && (
                        (tEnd - tStart == 7 && text.regionMatches(tStart, "[Fonts]", 0, 7, ignoreCase = true)) ||
                            (tEnd - tStart == 10 && text.regionMatches(tStart, "[Graphics]", 0, 10, ignoreCase = true))
                        )
                    if (isBulkSection) {
                        val nextSection = AutoSyncSubtitleCueParser.findNextAssSectionAfterBulk(
                            text = text,
                            startPos = if (lineEnd < len) lineEnd + 1 else len,
                        )
                        out.append(text, pos, nextSection)
                        if (nextSection >= len) break
                        pos = nextSection
                        continue
                    }
                } else if (inEvents && tEnd - tStart >= 7 &&
                    text.regionMatches(tStart, "Format:", 0, 7, ignoreCase = true)
                ) {
                    val fields = text.substring(tStart + 7, tEnd).split(',').map { it.trim() }
                    fieldCount = fields.size
                    startIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_START_INDEX
                    endIndex = fields.indexOfFirst { it.equals("End", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_END_INDEX
                } else if (inEvents && tEnd - tStart >= 9 &&
                    text.regionMatches(tStart, "Dialogue:", 0, 9, ignoreCase = true)
                ) {
                    val colon = text.indexOf(':', pos)
                    if (colon in pos until lineEnd) {
                        val partCount = AutoSyncSubtitleCueParser.splitAssCommaRanges(
                            text = text,
                            start = colon + 1,
                            end = lineEnd,
                            maxParts = fieldCount,
                            partStarts = partStarts,
                            partEnds = partEnds,
                            trim = false,
                        )
                        if (startIndex != endIndex && partCount > maxOf(startIndex, endIndex)) {
                            val sRawStart = partStarts[startIndex]
                            val sRawEnd = partEnds[startIndex]
                            val sTsStart = AutoSyncSubtitleCueParser.trimStartIndex(text, sRawStart, sRawEnd)
                            val sTsEnd = AutoSyncSubtitleCueParser.trimEndIndex(text, sTsStart, sRawEnd)
                            val eRawStart = partStarts[endIndex]
                            val eRawEnd = partEnds[endIndex]
                            val eTsStart = AutoSyncSubtitleCueParser.trimStartIndex(text, eRawStart, eRawEnd)
                            val eTsEnd = AutoSyncSubtitleCueParser.trimEndIndex(text, eTsStart, eRawEnd)

                            val startOrig = AutoSyncSubtitleCueParser.parseTimestampMsRange(text, sTsStart, sTsEnd)
                            val endOrig = AutoSyncSubtitleCueParser.parseTimestampMsRange(text, eTsStart, eTsEnd)
                            if (startOrig != null && endOrig != null) {
                                val startMs = mapper.mapStartMs(startOrig)
                                val endMs = mapper.mapEndMs(endOrig.coerceAtLeast(startOrig + 1L))
                                    .coerceAtLeast(startMs + ASS_MIN_DURATION_MS)
                                if (startIndex < endIndex) {
                                    out.append(text, pos, sTsStart)
                                    appendAssTimestamp(out, startMs)
                                    out.append(text, sRawEnd, eTsStart)
                                    appendAssTimestamp(out, endMs)
                                    out.append(text, eRawEnd, lineEnd)
                                } else {
                                    out.append(text, pos, eTsStart)
                                    appendAssTimestamp(out, endMs)
                                    out.append(text, eRawEnd, sTsStart)
                                    appendAssTimestamp(out, startMs)
                                    out.append(text, sRawEnd, lineEnd)
                                }
                                rewritten++
                                handled = true
                            }
                        }
                    }
                }
            }

            if (!handled) out.append(text, pos, lineEnd)
            if (lineEnd >= len) break
            out.append('\n')
            pos = lineEnd + 1
        }
        return if (rewritten == 0) null else out.toString()
    }

    /** `HH:MM:SS,mmm` in the original's style: same decimal separator, hours only if it had them or needs them. */
    private fun appendFormattedLike(
        out: StringBuilder,
        ms: Long,
        hasHours: Boolean,
        separator: Char,
    ) {
        val hours = ms / 3_600_000L
        if (hasHours || hours > 0L) {
            if (hours < 10L) out.append('0')
            out.append(hours).append(':')
        }
        appendTwoDigits(out, ((ms / 60_000L) % 60L).toInt())
        out.append(':')
        appendTwoDigits(out, ((ms / 1_000L) % 60L).toInt())
        out.append(separator)
        val millis = (ms % 1_000L).toInt()
        out.append(('0'.code + millis / 100).toChar())
        appendTwoDigits(out, millis % 100)
    }

    /** ASS time: `H:MM:SS.CC`, rounded to centiseconds. */
    private fun appendAssTimestamp(out: StringBuilder, ms: Long) {
        val centis = (ms + 5L) / 10L
        out.append(centis / 360_000L).append(':')
        appendTwoDigits(out, ((centis / 6_000L) % 60L).toInt())
        out.append(':')
        appendTwoDigits(out, ((centis / 100L) % 60L).toInt())
        out.append('.')
        appendTwoDigits(out, (centis % 100L).toInt())
    }

    private fun appendTwoDigits(out: StringBuilder, value: Int) {
        out.append(('0'.code + value / 10).toChar())
        out.append(('0'.code + value % 10).toChar())
    }

    private class TimingEntry(
        val startTsFrom: Int,
        val startTsTo: Int,
        val endTsFrom: Int,
        val endTsTo: Int,
        val startHasHours: Boolean,
        val startSeparator: Char,
        val endHasHours: Boolean,
        val endSeparator: Char,
        val originalStartMs: Long,
        val originalEndMs: Long,
        val startMs: Long,
        var endMs: Long,
    )
}
