package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.max

/**
 * AutoSync's subtitle cue parser (SRT, WebVTT, ASS/SSA and TTML), with cooperative cancellation
 * for long files. Nuvio TV's own [com.nuvio.tv.ui.screens.player.PlayerSubtitleCueParser] only
 * reads SRT and WebVTT, so AutoSync keeps this one to itself and leaves Nuvio's untouched.
 */
internal object AutoSyncSubtitleCueParser {
    private const val CANCELLATION_CHECK_INTERVAL = 64
    private val ttmlDetectRegex = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)
    private val ttmlParagraphRegex = Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
    private val ttmlRootRegex = Regex("""(?is)<tt\b([^>]*)>""")
    private val ttmlBrRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ttmlOffsetTimeRegex = Regex("""^([0-9]+(?:\.[0-9]+)?)(ms|h|m|s)$""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("""\s+""")
    private val ttmlBeginAttrRegex = Regex("""\bbegin\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlStartAttrRegex = Regex("""\bstart\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlEndAttrRegex = Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlTtpFrameRateAttrRegex = Regex("""\bttp:frameRate\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlFrameRateAttrRegex = Regex("""\bframeRate\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlTtpMultiplierAttrRegex = Regex("""\bttp:frameRateMultiplier\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ttmlMultiplierAttrRegex = Regex("""\bframeRateMultiplier\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parse(text: String, sourceUrl: String? = null): List<SubtitleSyncCue> =
        parse(
            text = text,
            sourceUrl = sourceUrl,
            cancellationCheck = null,
        )

    internal fun parse(
        text: String,
        sourceUrl: String?,
        cancellationCheck: (() -> Unit)?,
    ): List<SubtitleSyncCue> {
        cancellationCheck?.invoke()
        val cleanedText = cleanText(text)
        cancellationCheck?.invoke()
        return when (detectSubtitleFormat(sourceUrl, cleanedText)) {
            SubtitleFormatHint.WebVtt -> parseVtt(cleanedText, cancellationCheck)
            SubtitleFormatHint.Ass -> parseAss(cleanedText, cancellationCheck)
            SubtitleFormatHint.Ttml -> parseTtml(cleanedText, cancellationCheck)
            SubtitleFormatHint.Srt -> parseSrt(cleanedText, cancellationCheck)
        }
    }

    internal fun cleanText(rawText: String): String {
        if (rawText.indexOf('\uFEFF') < 0 && rawText.indexOf('\r') < 0) return rawText
        val len = rawText.length
        val out = StringBuilder(len)
        var i = 0
        while (i < len) {
            val ch = rawText[i]
            when {
                ch == '\uFEFF' -> i++
                ch == '\r' -> {
                    out.append('\n')
                    i += if (i + 1 < len && rawText[i + 1] == '\n') 2 else 1
                }
                else -> { out.append(ch); i++ }
            }
        }
        return out.toString()
    }

    private enum class SubtitleFormatHint {
        Srt,
        WebVtt,
        Ass,
        Ttml,
    }

    private fun detectSubtitleFormat(sourceUrl: String?, text: String): SubtitleFormatHint {
        val sourcePath = sourceUrl
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.lowercase()
            .orEmpty()

        return when {
            sourcePath.endsWith(".vtt") || sourcePath.endsWith(".webvtt") || startsWithTrimmed(text, "WEBVTT") ->
                SubtitleFormatHint.WebVtt
            sourcePath.endsWith(".ass") || sourcePath.endsWith(".ssa") ||
                hasAssEventsHeader(text) ->
                SubtitleFormatHint.Ass
            sourcePath.endsWith(".ttml") || sourcePath.endsWith(".dfxp") || sourcePath.endsWith(".xml") ||
                ttmlDetectRegex.containsMatchIn(text.substring(0, minOf(512, text.length))) ->
                SubtitleFormatHint.Ttml
            else -> SubtitleFormatHint.Srt
        }
    }

    /** Exact zero-regex equivalent of `Regex("""^[ \t]*\[Events\][ \t]*$""", IGNORE_CASE + MULTILINE)`. */
    internal fun hasAssEventsHeader(text: String): Boolean {
        val len = text.length
        var idx = text.indexOf('[')
        while (idx >= 0 && idx + 8 <= len) {
            if (text.regionMatches(idx, "[Events]", 0, 8, ignoreCase = true)) {
                var left = idx - 1
                while (left >= 0 && (text[left] == ' ' || text[left] == '\t')) left--
                val lineStartOk = left < 0 || text[left] == '\n' || text[left] == '\r'
                if (lineStartOk) {
                    var right = idx + 8
                    while (right < len && (text[right] == ' ' || text[right] == '\t')) right++
                    if (right >= len || text[right] == '\n' || text[right] == '\r') return true
                }
            }
            idx = text.indexOf('[', idx + 1)
        }
        return false
    }

    private fun startsWithTrimmed(text: String, prefix: String): Boolean {
        var i = 0
        val len = text.length
        while (i < len && text[i].isWhitespace()) i++
        return text.regionMatches(i, prefix, 0, prefix.length)
    }

    private fun lineEndAt(text: String, pos: Int, len: Int): Int =
        text.indexOf('\n', pos).let { if (it < 0) len else it }

    private fun parseSrt(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val len = text.length
        val cues = ArrayList<SubtitleSyncCue>(max(16, len / 80))
        val textBuf = StringBuilder(128)
        val normBuf = StringBuilder(128)
        var pos = 0
        var cancellationCounter = 0

        while (pos < len) {
            var lineEnd = lineEndAt(text, pos, len)
            var tStart = trimStartIndex(text, pos, lineEnd)
            var tEnd = trimEndIndex(text, tStart, lineEnd)
            while (tStart >= tEnd) {
                pos = lineEnd + 1
                if (pos >= len) return cues
                lineEnd = lineEndAt(text, pos, len)
                tStart = trimStartIndex(text, pos, lineEnd)
                tEnd = trimEndIndex(text, tStart, lineEnd)
            }

            checkCancellation(cancellationCounter++, cancellationCheck)
            var timingStart = tStart
            var timingEnd = tEnd
            pos = lineEnd + 1

            if (isAllDigits(text, tStart, tEnd) && pos < len) {
                val nextEnd = lineEndAt(text, pos, len)
                val nextStart = trimStartIndex(text, pos, nextEnd)
                val nextTrimEnd = trimEndIndex(text, nextStart, nextEnd)
                pos = nextEnd + 1
                if (nextStart >= nextTrimEnd) continue
                timingStart = nextStart
                timingEnd = nextTrimEnd
            }

            val timingRange = parseStartEndTimeMsRange(text, timingStart, timingEnd)
            if (timingRange == null || timingRange.second <= timingRange.first) {
                pos = skipToBlankLine(text, pos, len)
                continue
            }

            // Collect cue lines, stopping early if a malformed SRT omits the blank line before the next cue.
            textBuf.setLength(0)
            while (pos < len) {
                val nEnd = lineEndAt(text, pos, len)
                val ntStart = trimStartIndex(text, pos, nEnd)
                val ntEnd = trimEndIndex(text, ntStart, nEnd)
                if (ntStart >= ntEnd) { pos = nEnd + 1; break }
                if (textBuf.isNotEmpty()) {
                    if (parseStartEndTimeMsRange(text, ntStart, ntEnd) != null) break
                    if (isAllDigits(text, ntStart, ntEnd) && nEnd + 1 < len) {
                        val peekEnd = lineEndAt(text, nEnd + 1, len)
                        val pStart = trimStartIndex(text, nEnd + 1, peekEnd)
                        val pEnd = trimEndIndex(text, pStart, peekEnd)
                        if (pStart < pEnd && parseStartEndTimeMsRange(text, pStart, pEnd) != null) break
                    }
                }
                pos = nEnd + 1
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(text, ntStart, ntEnd)
            }
            if (textBuf.isEmpty()) continue
            val cueText = normalizeCueTextFast(textBuf, normBuf)
            if (cueText.isNotEmpty()) {
                cues += SubtitleSyncCue(timingRange.first, timingRange.second, cueText)
            }
        }
        return cues
    }

    private fun skipToBlankLine(text: String, startPos: Int, len: Int): Int {
        var pos = startPos
        while (pos < len) {
            val nEnd = lineEndAt(text, pos, len)
            val ntStart = trimStartIndex(text, pos, nEnd)
            pos = nEnd + 1
            if (ntStart >= nEnd) break
        }
        return pos
    }

    private fun parseVtt(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val len = text.length
        val cues = ArrayList<SubtitleSyncCue>(max(16, len / 80))
        val textBuf = StringBuilder(128)
        val normBuf = StringBuilder(128)
        var pos = 0
        var cancellationCounter = 0

        while (pos < len) {
            checkCancellation(cancellationCounter++, cancellationCheck)
            val lineEnd = lineEndAt(text, pos, len)
            val tStart = trimStartIndex(text, pos, lineEnd)
            val tEnd = trimEndIndex(text, tStart, lineEnd)
            val nextPos = lineEnd + 1

            if (tStart >= tEnd || text.regionMatches(tStart, "WEBVTT", 0, 6)) {
                pos = nextPos
                continue
            }
            if (isWebVttMetadataBlockHeaderRange(text, tStart, tEnd)) {
                val peekEnd = if (nextPos < len) lineEndAt(text, nextPos, len) else len
                val peekStart = if (nextPos < len) trimStartIndex(text, nextPos, peekEnd) else len
                val peekTrimEnd = if (nextPos < len) trimEndIndex(text, peekStart, peekEnd) else len
                if (peekStart >= peekTrimEnd || indexOfArrow(text, peekStart, peekTrimEnd) < 0) {
                    pos = skipToBlankLine(text, nextPos, len)
                    continue
                }
            }

            var timingStart = tStart
            var timingEnd = tEnd
            var textPos = nextPos
            if (indexOfArrow(text, timingStart, timingEnd) < 0) {
                if (nextPos >= len) { pos = nextPos; continue }
                val nextLineEnd = lineEndAt(text, nextPos, len)
                val nextTStart = trimStartIndex(text, nextPos, nextLineEnd)
                val nextTEnd = trimEndIndex(text, nextTStart, nextLineEnd)
                if (indexOfArrow(text, nextTStart, nextTEnd) < 0) { pos = nextPos; continue }
                timingStart = nextTStart
                timingEnd = nextTEnd
                textPos = nextLineEnd + 1
            }

            val timingRange = parseStartEndTimeMsRange(text, timingStart, timingEnd)
            if (timingRange == null || timingRange.second <= timingRange.first) {
                pos = nextPos
                continue
            }

            textBuf.setLength(0)
            var scanPos = textPos
            while (scanPos < len) {
                val nEnd = lineEndAt(text, scanPos, len)
                val ntStart = trimStartIndex(text, scanPos, nEnd)
                val ntEnd = trimEndIndex(text, ntStart, nEnd)
                scanPos = nEnd + 1
                if (ntStart >= ntEnd) break
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(text, ntStart, ntEnd)
            }
            if (textBuf.isNotEmpty()) {
                val cueText = normalizeCueTextFast(textBuf, normBuf)
                if (cueText.isNotEmpty()) {
                    cues += SubtitleSyncCue(timingRange.first, timingRange.second, cueText)
                }
            }
            pos = scanPos
        }
        return cues
    }

    private class AssFieldLayout(
        val fieldCount: Int,
        val startIndex: Int,
        val endIndex: Int,
        val styleIndex: Int,
        val nameIndex: Int,
        val effectIndex: Int,
        val textIndex: Int,
    )

    private val defaultAssLayout = AssFieldLayout(
        fieldCount = 10,
        startIndex = 1,
        endIndex = 2,
        styleIndex = 3,
        nameIndex = 4,
        effectIndex = 8,
        textIndex = 9,
    )

    /** Dialogue only ([AutoSyncAssDialogueFilter]): signs, karaoke and titles differ between releases. */
    private fun parseAss(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        var section = ""
        var layout = defaultAssLayout
        var styleFormatFields: List<String>? = null
        var cancellationCounter = 0
        val styles = ArrayList<AutoSyncAssDialogueFilter.Style>()
        val events = ArrayList<AutoSyncAssDialogueFilter.Event>(max(16, text.length / 100))

        val len = text.length
        var pos = 0
        val partStarts = IntArray(32)
        val partEnds = IntArray(32)

        while (pos < len) {
            checkCancellation(cancellationCounter++, cancellationCheck)
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }
            val tStart = trimStartIndex(text, pos, lineEnd)
            val tEnd = trimEndIndex(text, tStart, lineEnd)
            pos = lineEnd + 1
            if (tStart >= tEnd) continue

            val firstChar = text[tStart]
            if (firstChar == '[' && text[tEnd - 1] == ']') {
                val header = text.substring(tStart, tEnd).lowercase()
                section = header
                // Skip multi-megabyte embedded [Fonts] / [Graphics] UU-encoded payloads in one jump.
                if (header == "[fonts]" || header == "[graphics]") {
                    pos = findNextAssSectionAfterBulk(text, pos)
                }
            } else if (section == "[events]") {
                if (startsWithIgnoreCase(text, tStart, tEnd, "Dialogue:")) {
                    parseAssDialogueRange(text, tStart + 9, tEnd, layout, partStarts, partEnds)
                        ?.let { events += it }
                } else if (startsWithIgnoreCase(text, tStart, tEnd, "Format:")) {
                    val fields = parseAssFormat(text.substring(tStart + 7, tEnd))
                    if (fields.isNotEmpty()) {
                        layout = AssFieldLayout(
                            fieldCount = fields.size,
                            startIndex = fields.indexOfField("Start").takeIf { it >= 0 } ?: 1,
                            endIndex = fields.indexOfField("End").takeIf { it >= 0 } ?: 2,
                            styleIndex = fields.indexOfField("Style").takeIf { it >= 0 } ?: 3,
                            nameIndex = fields.indexOfField("Name").takeIf { it >= 0 } ?: 4,
                            effectIndex = fields.indexOfField("Effect").takeIf { it >= 0 } ?: 8,
                            textIndex = fields.indexOfField("Text").takeIf { it >= 0 } ?: 9,
                        )
                    }
                }
            } else if (section.contains("styles")) {
                if (startsWithIgnoreCase(text, tStart, tEnd, "Format:")) {
                    styleFormatFields = parseAssFormat(text.substring(tStart + 7, tEnd))
                } else if (startsWithIgnoreCase(text, tStart, tEnd, "Style:")) {
                    parseAssStyle(
                        payload = text.substring(tStart + 6, tEnd),
                        formatFields = styleFormatFields,
                        legacySsa = section == "[v4 styles]",
                    )?.let { styles += it }
                }
            }
        }
        cancellationCheck?.invoke()

        val filtered = AutoSyncAssDialogueFilter.dialogueEvents(events, styles)
        val cues = ArrayList<SubtitleSyncCue>(filtered.size)
        val stripBuf = StringBuilder(128)
        val normBuf = StringBuilder(128)
        var sorted = true
        var prevStart = Long.MIN_VALUE

        for (i in filtered.indices) {
            val event = filtered[i]
            stripAssOverridesAndNewlines(event.rawText, stripBuf)
            val body = normalizeCueTextFast(stripBuf, normBuf)
            if (body.isNotEmpty()) {
                cues += SubtitleSyncCue(
                    startTimeMs = event.startTimeMs,
                    endTimeMs = event.endTimeMs,
                    text = body,
                )
                if (event.startTimeMs < prevStart) sorted = false
                prevStart = event.startTimeMs
            }
        }
        if (!sorted) cues.sortBy { it.startTimeMs }
        return cues
    }

    private fun stripAssOverridesAndNewlines(raw: String, out: StringBuilder) {
        out.setLength(0)
        val len = raw.length
        var i = 0
        while (i < len) {
            val ch = raw[i]
            if (ch == '{') {
                val close = raw.indexOf('}', i + 1)
                if (close >= 0) {
                    i = close + 1
                    continue
                }
            } else if (ch == '\\' && i + 1 < len) {
                val next = raw[i + 1]
                if (next == 'N' || next == 'n') {
                    out.append('\n')
                    i += 2
                    continue
                } else if (next == 'h') {
                    out.append(' ')
                    i += 2
                    continue
                }
            }
            out.append(ch)
            i++
        }
    }

    internal fun findNextAssSectionAfterBulk(text: String, startPos: Int): Int {
        val len = text.length
        var scan = startPos
        while (scan < len) {
            val bracket = text.indexOf('[', scan)
            if (bracket < 0) return len
            var lineStart = bracket
            while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') {
                val prev = text[lineStart - 1]
                if (prev != ' ' && prev != '\t') {
                    lineStart = -1
                    break
                }
                lineStart--
            }
            if (lineStart >= startPos) {
                var hLineEnd = bracket
                while (hLineEnd < len && text[hLineEnd] != '\n' && text[hLineEnd] != '\r') hLineEnd++
                val hTrimEnd = trimEndIndex(text, bracket, hLineEnd)
                if (hTrimEnd > bracket + 2 && text[hTrimEnd - 1] == ']' && isKnownAssSectionHeader(text, bracket, hTrimEnd)) {
                    return lineStart
                }
                scan = hLineEnd + 1
            } else {
                scan = bracket + 1
            }
        }
        return len
    }

    internal fun splitAssCommaRanges(
        text: String,
        start: Int,
        end: Int,
        maxParts: Int,
        partStarts: IntArray,
        partEnds: IntArray,
        trim: Boolean,
    ): Int {
        val limit = minOf(maxParts, partStarts.size)
        var partCount = 0
        var cursor = start
        while (partCount < limit - 1) {
            val comma = text.indexOf(',', cursor)
            if (comma < 0 || comma >= end) break
            partStarts[partCount] = if (trim) trimStartIndex(text, cursor, comma) else cursor
            partEnds[partCount] = if (trim) trimEndIndex(text, partStarts[partCount], comma) else comma
            partCount++
            cursor = comma + 1
        }
        partStarts[partCount] = if (trim) trimStartIndex(text, cursor, end) else cursor
        partEnds[partCount] = if (trim) trimEndIndex(text, partStarts[partCount], end) else end
        return partCount + 1
    }

    private fun isKnownAssSectionHeader(text: String, start: Int, end: Int): Boolean {
        val len = end - start
        return (len == 8 && text.regionMatches(start, "[Events]", 0, 8, ignoreCase = true)) ||
            (len == 13 && text.regionMatches(start, "[Script Info]", 0, 13, ignoreCase = true)) ||
            (len == 11 && text.regionMatches(start, "[V4 Styles]", 0, 11, ignoreCase = true)) ||
            (len == 12 && text.regionMatches(start, "[V4+ Styles]", 0, 12, ignoreCase = true)) ||
            (len == 7 && text.regionMatches(start, "[Fonts]", 0, 7, ignoreCase = true)) ||
            (len == 10 && text.regionMatches(start, "[Graphics]", 0, 10, ignoreCase = true)) ||
            (len >= 10 && text.regionMatches(start, "[Aegisub ", 0, 9, ignoreCase = true))
    }

    private fun parseAssFormat(payload: String): List<String> =
        payload.split(',').map { it.trim() }

    private fun parseAssStyle(
        payload: String,
        formatFields: List<String>?,
        legacySsa: Boolean,
    ): AutoSyncAssDialogueFilter.Style? {
        val fields = formatFields.orEmpty()
        val parts = payload.split(',').map { it.trim() }
        val nameIndex = fields.indexOfField("Name").takeIf { it >= 0 } ?: 0
        val alignmentIndex = fields.indexOfField("Alignment")
        val name = parts.getOrNull(nameIndex)?.takeIf { it.isNotEmpty() } ?: return null
        val alignment = parts.getOrNull(alignmentIndex)?.toIntOrNull()
        // SSA v4 numbers alignment 1-3 bottom, 5-7 top, 9-11 middle; ASS uses the numpad.
        val numpadAlignment = when {
            alignment == null || !legacySsa -> alignment
            alignment in 5..7 -> alignment + 2
            alignment in 9..11 -> alignment - 5
            else -> alignment
        }
        return AutoSyncAssDialogueFilter.Style(name = name, alignment = numpadAlignment)
    }

    private fun parseAssDialogueRange(
        text: String,
        payloadStart: Int,
        payloadEnd: Int,
        layout: AssFieldLayout,
        partStarts: IntArray,
        partEnds: IntArray,
    ): AutoSyncAssDialogueFilter.Event? {
        val partCount = splitAssCommaRanges(
            text = text,
            start = payloadStart,
            end = payloadEnd,
            maxParts = layout.fieldCount,
            partStarts = partStarts,
            partEnds = partEnds,
            trim = true,
        )
        if (partCount <= layout.startIndex || partCount <= layout.textIndex) return null
        val start = parseTimestampMsRange(text, partStarts[layout.startIndex], partEnds[layout.startIndex])
            ?: return null
        val end = if (layout.endIndex < partCount) {
            parseTimestampMsRange(text, partStarts[layout.endIndex], partEnds[layout.endIndex]) ?: (start + 3000L)
        } else {
            start + 3000L
        }
        if (end <= start) return null

        fun field(idx: Int): String =
            if (idx < partCount && partStarts[idx] < partEnds[idx]) text.substring(partStarts[idx], partEnds[idx]) else ""
        return AutoSyncAssDialogueFilter.Event(
            startTimeMs = start,
            endTimeMs = end,
            style = field(layout.styleIndex),
            name = field(layout.nameIndex),
            effect = field(layout.effectIndex),
            rawText = text.substring(partStarts[layout.textIndex], partEnds[layout.textIndex]),
        )
    }

    private fun parseTtml(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val frameRate = parseTtmlFrameRate(text)
        var cancellationCounter = 0
        val normBuf = StringBuilder(128)
        val cues = ttmlParagraphRegex
            .findAll(text)
            .mapNotNull { match ->
                checkCancellation(cancellationCounter++, cancellationCheck)
                val attrs = match.groupValues[1]
                val startRaw = findAttr(attrs, ttmlBeginAttrRegex)
                    ?: findAttr(attrs, ttmlStartAttrRegex)
                    ?: return@mapNotNull null
                val endRaw = findAttr(attrs, ttmlEndAttrRegex)
                val start = parseTtmlTimestamp(startRaw, frameRate) ?: return@mapNotNull null
                val end = endRaw?.let { parseTtmlTimestamp(it, frameRate) } ?: (start + 3000L)
                if (end - start <= 0) return@mapNotNull null
                val rawBody = match.groupValues[2]
                val withBreaks = if (rawBody.indexOf('<') >= 0) {
                    ttmlBrRegex.replace(rawBody, "\n")
                } else {
                    rawBody
                }
                val body = normalizeCueTextFast(withBreaks, normBuf)
                if (body.isEmpty()) null else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
            }
            .toMutableList()
        cancellationCheck?.invoke()
        cues.sortBy { it.startTimeMs }
        return cues
    }

    private fun parseTtmlFrameRate(text: String): Double {
        val rootAttributes = ttmlRootRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val baseFrameRate =
            findAttr(rootAttributes, ttmlTtpFrameRateAttrRegex)?.toDoubleOrNull()
                ?: findAttr(rootAttributes, ttmlFrameRateAttrRegex)?.toDoubleOrNull()
                ?: 30.0
        val multiplierRaw =
            findAttr(rootAttributes, ttmlTtpMultiplierAttrRegex)
                ?: findAttr(rootAttributes, ttmlMultiplierAttrRegex)
        val multiplier = multiplierRaw
            ?.trim()
            ?.split(whitespaceRegex)
            ?.takeIf { it.size == 2 }
            ?.let { parts ->
                val numerator = parts[0].toDoubleOrNull()
                val denominator = parts[1].toDoubleOrNull()
                if (numerator != null && denominator != null && denominator > 0.0) {
                    numerator / denominator
                } else {
                    null
                }
            }
            ?: 1.0
        val effective = baseFrameRate * multiplier
        return effective.takeIf { it.isFinite() && it > 0.0 } ?: 30.0
    }

    private fun findAttr(attrs: String, regex: Regex): String? =
        regex.find(attrs)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun parseTtmlTimestamp(raw: String, frameRate: Double): Long? {
        val cleaned = raw.trim().substringBefore(' ')
        if (cleaned.isBlank()) return null

        parseClockTimeWithFrames(cleaned, frameRate)?.let { return it }
        parseTimestampMsRange(cleaned, 0, cleaned.length)?.let { return it }

        val match = ttmlOffsetTimeRegex.matchEntire(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "h" -> 3_600_000.0
            "m" -> 60_000.0
            "s" -> 1_000.0
            "ms" -> 1.0
            else -> return null
        }
        return max(0L, (value * multiplier).toLong())
    }

    private fun parseClockTimeWithFrames(raw: String, frameRate: Double): Long? {
        if (!frameRate.isFinite() || frameRate <= 0.0) return null
        val parts = raw.split(':')
        if (parts.size != 4) return null

        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val frames = parts[3].toDoubleOrNull() ?: return null
        val frameMs = frames * 1_000.0 / frameRate
        return max(
            0L,
            (hours * 3_600_000.0 + minutes * 60_000.0 + seconds * 1_000.0 + frameMs).toLong(),
        )
    }

    private fun isWebVttMetadataBlockHeaderRange(text: String, start: Int, end: Int): Boolean {
        val len = end - start
        if (len == 5 && text.regionMatches(start, "STYLE", 0, 5)) return true
        if (len == 6 && text.regionMatches(start, "REGION", 0, 6)) return true
        if (len == 4 && text.regionMatches(start, "NOTE", 0, 4)) return true
        if (len >= 5 && text.regionMatches(start, "NOTE", 0, 4)) {
            val ch = text[start + 4]
            if (ch == ' ' || ch == '\t') return true
        }
        return false
    }

    internal fun indexOfArrow(text: String, start: Int, end: Int): Int {
        val limit = end - 3
        var i = start
        while (i <= limit) {
            if (text[i] == '-' && text[i + 1] == '-' && text[i + 2] == '>') return i
            i++
        }
        return -1
    }

    private fun parseStartEndTimeMsRange(text: String, start: Int, end: Int): Pair<Long, Long>? {
        val arrow = indexOfArrow(text, start, end)
        if (arrow < 0) return null
        // Preserve `split("-->").size == 2`: reject lines with multiple arrows.
        if (indexOfArrow(text, arrow + 3, end) >= 0) return null

        val leftStart = trimStartIndex(text, start, arrow)
        var leftEnd = trimEndIndex(text, leftStart, arrow)
        val leftSpace = indexOfHorizontalWhitespace(text, leftStart, leftEnd)
        if (leftSpace >= 0) leftEnd = leftSpace

        val rightStart = trimStartIndex(text, arrow + 3, end)
        var rightEnd = trimEndIndex(text, rightStart, end)
        val rightSpace = indexOfHorizontalWhitespace(text, rightStart, rightEnd)
        if (rightSpace >= 0) rightEnd = rightSpace

        val startTimeMs = parseTimestampMsRange(text, leftStart, leftEnd) ?: return null
        val endTimeMs = parseTimestampMsRange(text, rightStart, rightEnd) ?: return null
        return startTimeMs to endTimeMs
    }

    /**
     * Exact zero-allocation equivalent of `Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d+))?""")`.
     */
    internal fun parseTimestampMsRange(text: CharSequence, rawStart: Int, rawEnd: Int): Long? {
        var i = rawStart
        var end = rawEnd
        while (i < end && text[i].isWhitespace()) i++
        while (end > i && text[end - 1].isWhitespace()) end--
        if (i >= end) return null

        // First numeric segment
        var p1 = 0L
        var d1 = 0
        while (i < end) {
            val d = text[i] - '0'
            if (d !in 0..9) break
            p1 = p1 * 10L + d
            d1++
            i++
        }
        if (d1 == 0 || i >= end || text[i] != ':') return null
        i++ // consume ':'

        // Second numeric segment
        var p2 = 0L
        var d2 = 0
        while (i < end) {
            val d = text[i] - '0'
            if (d !in 0..9) break
            p2 = p2 * 10L + d
            d2++
            i++
        }
        if (d2 == 0) return null

        val hours: Long
        val minutes: Long
        val seconds: Long
        if (i < end && text[i] == ':') {
            i++ // consume second ':'
            if (d2 > 2) return null
            var p3 = 0L
            var d3 = 0
            while (i < end) {
                val d = text[i] - '0'
                if (d !in 0..9) break
                p3 = p3 * 10L + d
                d3++
                i++
            }
            if (d3 != 2) return null
            hours = p1
            minutes = p2
            seconds = p3
        } else {
            if (d1 > 2 || d2 != 2) return null
            hours = 0L
            minutes = p1
            seconds = p2
        }

        var millis = 0L
        if (i < end) {
            val sep = text[i]
            if (sep != '.' && sep != ',') return null
            i++
            var fracDigits = 0
            while (i < end) {
                val d = text[i] - '0'
                if (d !in 0..9) return null
                if (fracDigits < 3) {
                    millis = millis * 10L + d
                }
                fracDigits++
                i++
            }
            if (fracDigits == 0) return null
            if (fracDigits == 1) millis *= 100L
            else if (fracDigits == 2) millis *= 10L
        }

        if (i != end) return null
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun checkCancellation(
        index: Int,
        cancellationCheck: (() -> Unit)?,
    ) {
        if (cancellationCheck != null && (index and (CANCELLATION_CHECK_INTERVAL - 1)) == 0) {
            cancellationCheck()
        }
    }

    private fun List<String>.indexOfField(name: String): Int =
        indexOfFirst { it.equals(name, ignoreCase = true) }

    internal fun trimStartIndex(text: CharSequence, start: Int, end: Int): Int {
        var i = start
        while (i < end && text[i].isWhitespace()) i++
        return i
    }

    internal fun trimEndIndex(text: CharSequence, start: Int, end: Int): Int {
        var i = end
        while (i > start && text[i - 1].isWhitespace()) i--
        return i
    }

    private fun indexOfHorizontalWhitespace(text: String, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t') return i
            i++
        }
        return -1
    }

    private fun isAllDigits(text: String, start: Int, end: Int): Boolean {
        if (start >= end) return false
        for (i in start until end) {
            if (!text[i].isDigit()) return false
        }
        return true
    }

    private fun startsWithIgnoreCase(text: String, start: Int, end: Int, prefix: String): Boolean {
        if (end - start < prefix.length) return false
        return text.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)
    }

    private fun normalizeCueTextFast(input: CharSequence, out: StringBuilder): String {
        // Single-pass zero-regex normalization:
        // 1. Strip VTT timestamp tags (<00:01.000>) and HTML tags (</?tag ...>)
        // 2. Decode &nbsp;, &amp;, &lt;, &gt;, &quot;
        // 3. Collapse [ \t]+ to a single space, trim each line, drop blank lines.
        out.setLength(0)
        val len = input.length
        var lineHasContent = false
        var pendingSpace = false
        var i = 0

        fun emitSpace() {
            if (lineHasContent) pendingSpace = true
        }

        fun emitChar(ch: Char) {
            if (ch == ' ' || ch == '\t') {
                emitSpace()
            } else if (ch == '\n') {
                lineHasContent = false
                pendingSpace = false
            } else if (ch.isWhitespace()) {
                // Non-space/tab whitespace (e.g. \u000B, \u000C, \u00A0): keep original
                // .trim() semantics on line boundaries by buffering only if inside line,
                // or delegate rare characters. In practice ASCII spaces/tabs dominate.
                if (lineHasContent) {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.append(ch)
                }
            } else {
                if (!lineHasContent && out.isNotEmpty()) {
                    out.append('\n')
                } else if (pendingSpace) {
                    out.append(' ')
                }
                pendingSpace = false
                lineHasContent = true
                out.append(ch)
            }
        }

        while (i < len) {
            val ch = input[i]
            if (ch == '<') {
                val close = indexOfCharSeq(input, '>', i + 1, len)
                if (close > i + 1 && isStrippableTag(input, i + 1, close)) {
                    i = close + 1
                    continue
                }
                emitChar('<')
                i++
            } else if (ch == '&') {
                if (matchesLiteral(input, i, len, "&nbsp;")) {
                    emitSpace()
                    i += 6
                } else if (matchesLiteral(input, i, len, "&amp;")) {
                    emitChar('&')
                    i += 5
                } else if (matchesLiteral(input, i, len, "&lt;")) {
                    emitChar('<')
                    i += 4
                } else if (matchesLiteral(input, i, len, "&gt;")) {
                    emitChar('>')
                    i += 4
                } else if (matchesLiteral(input, i, len, "&quot;")) {
                    emitChar('"')
                    i += 6
                } else {
                    emitChar('&')
                    i++
                }
            } else {
                emitChar(ch)
                i++
            }
        }

        // Trim any rare trailing non-ASCII whitespace on the last line if present.
        var outEnd = out.length
        while (outEnd > 0 && out[outEnd - 1].isWhitespace() && out[outEnd - 1] != '\n') {
            outEnd--
        }
        if (outEnd != out.length) out.setLength(outEnd)
        return out.toString()
    }

    private fun indexOfCharSeq(text: CharSequence, target: Char, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            if (text[i] == target) return i
            i++
        }
        return -1
    }

    private fun matchesLiteral(text: CharSequence, offset: Int, len: Int, literal: String): Boolean {
        val litLen = literal.length
        if (offset + litLen > len) return false
        for (k in 1 until litLen) {
            if (text[offset + k] != literal[k]) return false
        }
        return true
    }

    /**
     * Exact match for either:
     * - VTT timestamp tag: `(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?`
     * - HTML/cue tag: `/?[a-zA-Z0-9._-]+(?: [^>]*)?`
     * where [start, end) is the content strictly between `<` and the first `>`.
     */
    private fun isStrippableTag(text: CharSequence, start: Int, end: Int): Boolean {
        // Check HTML/cue tag first (most common: <i>, </i>, <font ...>, <c.color>)
        var i = start
        if (text[i] == '/') i++
        val nameStart = i
        while (i < end) {
            val c = text[i]
            val isNameChar =
                (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c == '.' || c == '_' || c == '-'
            if (!isNameChar) break
            i++
        }
        if (i > nameStart && (i == end || text[i] == ' ')) {
            return true
        }
        // Check VTT inline timestamp tag (contains ':')
        return parseTimestampMsRange(text, start, end) != null
    }
}
