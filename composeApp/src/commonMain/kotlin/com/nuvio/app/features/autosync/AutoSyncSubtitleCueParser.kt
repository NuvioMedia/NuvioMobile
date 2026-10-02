package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.max

/**
 * AutoSync's subtitle cue parser (SRT, WebVTT, ASS/SSA and TTML), with cooperative cancellation
 * for long files. Nuvio's own [com.nuvio.app.features.player.PlayerSubtitleCueParser] only
 * reads SRT and WebVTT, so AutoSync keeps this one to itself and leaves Nuvio's untouched.
 */
internal object AutoSyncSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d+))?""")
    private const val CANCELLATION_CHECK_INTERVAL = 64

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

    private fun cleanText(rawText: String): String =
        rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

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
        val sample = text.take(4_096).lowercase()

        return when {
            sourcePath.endsWith(".vtt") || sourcePath.endsWith(".webvtt") || text.trimStart().startsWith("WEBVTT") ->
                SubtitleFormatHint.WebVtt
            sourcePath.endsWith(".ass") || sourcePath.endsWith(".ssa") ||
                (sample.contains("[events]") && sample.contains("dialogue:")) ->
                SubtitleFormatHint.Ass
            sourcePath.endsWith(".ttml") || sourcePath.endsWith(".dfxp") || sourcePath.endsWith(".xml") ||
                Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(text.take(512)) ->
                SubtitleFormatHint.Ttml
            else -> SubtitleFormatHint.Srt
        }
    }

    private fun parseSrt(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val blocks = text.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<SubtitleSyncCue>()
        var cancellationCounter = 0
        for (block in blocks) {
            checkCancellation(cancellationCounter++, cancellationCheck)
            val lines = block
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var index = 0
            if (lines[index].all { it.isDigit() } && index + 1 < lines.size) {
                index++
            }
            val timing = lines.getOrNull(index) ?: continue
            if (!timing.contains("-->")) continue
            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timing) ?: continue
            if (endTimeMs - startTimeMs <= 0) continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString("\n"))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
        }
        return cues
    }

    private fun parseVtt(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val lines = text
            .lines()
            .map { it.trimEnd() }

        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0
        var cancellationCounter = 0

        while (cursor < lines.size) {
            checkCancellation(cancellationCounter++, cancellationCheck)
            val line = lines[cursor].trim()
            if (line.isBlank()) {
                cursor++
                continue
            }
            if (line.startsWith("WEBVTT")) {
                cursor++
                continue
            }
            if (isWebVttMetadataBlockHeader(line)) {
                val nextLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                if (nextLine.isEmpty() || !nextLine.contains("-->")) {
                    cursor = skipWebVttBlock(lines, cursor + 1)
                    continue
                }
            }

            var timingLine = line
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) {
                cursor++
                continue
            }

            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timingLine) ?: run {
                cursor++
                continue
            }
            if (endTimeMs - startTimeMs <= 0) {
                cursor++
                continue
            }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString("\n"))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private val defaultAssFormatFields = listOf(
        "Layer",
        "Start",
        "End",
        "Style",
        "Name",
        "MarginL",
        "MarginR",
        "MarginV",
        "Effect",
        "Text",
    )

    private fun parseAss(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        var inEventsSection = false
        var formatFields: List<String>? = null
        var cancellationCounter = 0

        val cues = text.lines()
            .mapNotNull { rawLine ->
                checkCancellation(cancellationCounter++, cancellationCheck)
                val line = rawLine.trim()
                when {
                    line.equals("[Events]", ignoreCase = true) -> {
                        inEventsSection = true
                        null
                    }
                    line.startsWith("[") && line.endsWith("]") -> {
                        inEventsSection = false
                        null
                    }
                    inEventsSection && line.startsWith("Format:", ignoreCase = true) -> {
                        formatFields = line.substringAfter(':')
                            .split(',')
                            .map { it.trim() }
                        null
                    }
                    inEventsSection && line.startsWith("Dialogue:", ignoreCase = true) ->
                        parseAssDialogue(line.substringAfter(':'), formatFields)
                    else -> null
                }
            }
        cancellationCheck?.invoke()
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseAssDialogue(payload: String, formatFields: List<String>?): SubtitleSyncCue? {
        val fields = formatFields.orEmpty()
        val parts = payload
            .split(',', limit = fields.ifEmpty { defaultAssFormatFields }.size)
            .map { it.trim() }
        val startIndex = fields.indexOfField("Start").takeIf { it >= 0 } ?: 1
        val endIndex = fields.indexOfField("End").takeIf { it >= 0 } ?: 2
        val textIndex = fields.indexOfField("Text").takeIf { it >= 0 } ?: 9

        if (parts.size <= startIndex || parts.size <= textIndex) return null
        val start = parseTimestampMs(parts[startIndex]) ?: return null
        val end = parts.getOrNull(endIndex)?.let { parseTimestampMs(it) } ?: (start + 3000L)
        if (end - start <= 0) return null
        val body = normalizeCueText(
            parts[textIndex]
                .replace(Regex("""\{[^}]*}"""), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
        )
        return if (body.isBlank()) null else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
    }

    private fun parseTtml(
        text: String,
        cancellationCheck: (() -> Unit)? = null,
    ): List<SubtitleSyncCue> {
        val frameRate = parseTtmlFrameRate(text)
        var cancellationCounter = 0
        val cues = Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
            .findAll(text)
            .mapNotNull { match ->
                checkCancellation(cancellationCounter++, cancellationCheck)
                val attrs = match.groupValues[1]
                val startRaw = attrs.attributeValue("begin")
                    ?: attrs.attributeValue("start")
                    ?: return@mapNotNull null
                val endRaw = attrs.attributeValue("end")
                val start = parseTtmlTimestamp(startRaw, frameRate) ?: return@mapNotNull null
                val end = endRaw?.let { parseTtmlTimestamp(it, frameRate) } ?: (start + 3000L)
                if (end - start <= 0) return@mapNotNull null
                val body = normalizeCueText(
                    match.groupValues[2]
                        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                )
                if (body.isBlank()) null else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
            }
            .toList()
        cancellationCheck?.invoke()
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseTtmlFrameRate(text: String): Double {
        val rootAttributes = Regex("""(?is)<tt\b([^>]*)>""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val baseFrameRate =
            rootAttributes.attributeValue("ttp:frameRate")?.toDoubleOrNull()
                ?: rootAttributes.attributeValue("frameRate")?.toDoubleOrNull()
                ?: 30.0
        val multiplierRaw =
            rootAttributes.attributeValue("ttp:frameRateMultiplier")
                ?: rootAttributes.attributeValue("frameRateMultiplier")
        val multiplier = multiplierRaw
            ?.trim()
            ?.split(Regex("""\s+"""))
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

    private fun parseTtmlTimestamp(raw: String, frameRate: Double): Long? {
        val cleaned = raw.trim().substringBefore(' ')
        if (cleaned.isBlank()) return null

        parseClockTimeWithFrames(cleaned, frameRate)?.let { return it }
        parseTimestampMs(cleaned)?.let { return it }

        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)(ms|h|m|s)$""", RegexOption.IGNORE_CASE)
            .matchEntire(cleaned)
            ?: return null
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

    private fun isWebVttMetadataBlockHeader(line: String): Boolean {
        return line == "STYLE" ||
            line == "REGION" ||
            line == "NOTE" ||
            line.startsWith("NOTE ") ||
            line.startsWith("NOTE\t")
    }

    private fun skipWebVttBlock(lines: List<String>, start: Int): Int {
        var cursor = start
        while (cursor < lines.size && lines[cursor].isNotBlank()) {
            cursor++
        }
        return if (cursor < lines.size) cursor + 1 else cursor
    }

    private fun parseStartEndTimeMs(timingLine: String): Pair<Long, Long>? {
        val parts = timingLine.split("-->")
        if (parts.size != 2) return null
        val startTimeMs = parseTimestampMs(parts[0].trim().substringBefore(' ')) ?: return null
        val endTimeMs = parseTimestampMs(parts[1].trim().substringBefore(' ')) ?: return null
        return startTimeMs to endTimeMs
    }

    private fun parseTimestampMs(rawTimestamp: String): Long? {
        val match = timestampRegex.matchEntire(rawTimestamp.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        val millisRaw = match.groupValues[5]
        val millis = when (millisRaw.length) {
            0 -> 0L
            1 -> "${millisRaw}00".toLong()
            2 -> "${millisRaw}0".toLong()
            else -> millisRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun checkCancellation(
        index: Int,
        cancellationCheck: (() -> Unit)?,
    ) {
        if (
            cancellationCheck != null &&
            index % CANCELLATION_CHECK_INTERVAL == 0
        ) {
            cancellationCheck()
        }
    }

    private fun List<String>.indexOfField(name: String): Int =
        indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun String.attributeValue(name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun normalizeCueText(text: String): String {
        return text
            .replace(Regex("""<(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?>"""), "")
            .replace(Regex("""</?[a-zA-Z0-9._-]+(?: [^>]*)?>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines()
            .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
