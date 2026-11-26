package com.nuvio.app.features.audiosync

import com.nuvio.app.features.player.SubtitleSdhFilter

/** Dialogue intervals of a subtitle file, in subtitle time (milliseconds). */
internal class SubtitleSpeechTrack private constructor(
    val startsMs: LongArray,
    val endsMs: LongArray,
) {
    val size: Int get() = startsMs.size

    /**
     * Where lines start, as short intervals around each start: matched against where detected speech
     * rises (see [SubtitleAudioAligner.speechStarts]). Overlap with speech alone is ambiguous when
     * dialogue is dense; the starts of lines are much sharper.
     */
    val starts: SubtitleSpeechTrack by lazy {
        SubtitleSpeechTrack(
            startsMs = LongArray(size) { startsMs[it] - START_HALF_WIDTH_MS },
            endsMs = LongArray(size) { startsMs[it] + START_HALF_WIDTH_MS },
        )
    }

    /**
     * Fractional on-screen coverage per 32 ms frame for frames [fromFrame, toFrame) after mapping
     * subtitle time t to media time t * [scale].
     */
    fun render(fromFrame: Int, toFrame: Int, scale: Double): DoubleArray =
        renderInto(DoubleArray((toFrame - fromFrame).coerceAtLeast(0)), fromFrame, scale)

    fun renderInto(out: DoubleArray, fromFrame: Int, scale: Double): DoubleArray {
        out.fill(0.0)
        if (out.isEmpty()) return out
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        for (i in startsMs.indices) {
            val a = startsMs[i] * scale / frameMs - fromFrame
            val b = endsMs[i] * scale / frameMs - fromFrame
            if (b <= 0.0 || a >= out.size) continue
            val start = a.coerceAtLeast(0.0)
            val end = b.coerceAtMost(out.size.toDouble())
            val first = start.toInt()
            val last = end.toInt()
            if (first == last) {
                out[first] += end - start
            } else {
                out[first] += first + 1 - start
                for (f in first + 1 until last) out[f] += 1.0
                if (last < out.size) out[last] += end - last
            }
        }
        for (i in out.indices) if (out[i] > 1.0) out[i] = 1.0
        return out
    }

    /** Number of cues whose mapped interval overlaps media time [fromMs, toMs). */
    fun countCuesIn(fromMs: Double, toMs: Double, scale: Double, shiftMs: Double): Int {
        var count = 0
        for (i in startsMs.indices) {
            val a = startsMs[i] * scale + shiftMs
            val b = endsMs[i] * scale + shiftMs
            if (b > fromMs && a < toMs) count++
        }
        return count
    }

    companion object {
        private val htmlTag = Regex("</?[a-zA-Z][^>]*>")
        private val musicOnly = Regex("^[\\s♪♫#*~-]*$")
        private val musicLine = Regex("^\\s*[♪♫#].*|.*[♪♫]\\s*$")

        /** Builds the track from (startMs, endMs, text) cues, dropping sound-effect and lyric-only cues. */
        fun fromCues(cues: List<Triple<Long, Long, String>>): SubtitleSpeechTrack {
            val kept = cues
                .asSequence()
                .filter { (start, end, _) -> start >= 0L && end > start && end - start < MAX_CUE_DURATION_MS }
                .filter { (_, _, text) -> isDialogue(text) }
                .sortedBy { it.first }
                .toList()
            return SubtitleSpeechTrack(
                startsMs = LongArray(kept.size) { kept[it].first },
                endsMs = LongArray(kept.size) { kept[it].second },
            )
        }

        internal fun isDialogue(text: String): Boolean {
            if (text.isBlank()) return true // Bitmap or styled cues without text still mark dialogue.
            val plain = htmlTag.replace(text, "")
            val filtered = SubtitleSdhFilter.filter(plain) ?: return false
            val nonBlankLines = filtered.lines().filter { it.isNotBlank() }
            if (nonBlankLines.isEmpty()) return false
            val first = nonBlankLines.first().trim()
            val last = nonBlankLines.last().trim()
            if ((first.startsWith('♪') || first.startsWith('♫')) &&
                (last.endsWith('♪') || last.endsWith('♫'))
            ) {
                return false
            }
            val lines = nonBlankLines.filterNot { musicOnly.matches(it) || musicLine.matches(it) }
            return lines.any { line -> line.any(Char::isLetterOrDigit) }
        }

        private const val MAX_CUE_DURATION_MS = 20_000L

        /** Each line start counts within about 2.5 speech frames either side. */
        private const val START_HALF_WIDTH_MS = 80L
    }
}
