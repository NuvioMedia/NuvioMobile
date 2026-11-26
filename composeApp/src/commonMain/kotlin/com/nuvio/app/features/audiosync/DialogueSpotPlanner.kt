package com.nuvio.app.features.audiosync

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Chooses where to sample a film's audio ahead of playback. Subtitle files show where people talk
 * even when their timing is off, so each spot goes where the available subtitles agree on dense,
 * irregular dialogue (varied line lengths and pauses make the most distinctive timing pattern);
 * spots are spread over the film so frame-rate drift and edited cuts show up at once. On a
 * high-bitrate stream only a spot's opening seconds may be read, so spots also start inside long
 * stretches of dialogue, where the opening finds speech even if the subtitle's timing is off.
 */
internal object DialogueSpotPlanner {
    /** Slack around a spot for subtitles that are off by this much. */
    private const val TIMING_SLACK_MS = 10_000L
    private const val STEP_MS = 5_000L

    /** A spot's opening that is read on any stream, and the timing error it should survive. */
    private const val OPENING_MS = 8_000L
    private const val OPENING_SLACK_MS = 20_000L

    /** Opening and closing credits rarely have dialogue. */
    private const val SKIP_START_MS = 3 * 60_000L
    private const val SKIP_END_MS = 6 * 60_000L

    /**
     * Returns up to [count] spot start times (media ms) of [spotMs] each within [durationMs], after
     * [notBeforeMs] (audio already covered), in the order they should be fetched: the most widely
     * spread first, so an early stop still leaves spread-out evidence.
     */
    fun plan(
        tracks: List<SubtitleSpeechTrack>,
        durationMs: Long,
        count: Int,
        spotMs: Long,
        notBeforeMs: Long = 0L,
    ): List<Long> {
        val from = maxOf(SKIP_START_MS, notBeforeMs)
        val to = durationMs - SKIP_END_MS - spotMs
        if (count <= 0 || to <= from) return emptyList()
        val sections = minOf(count, ((to - from) / (2 * spotMs)).toInt()).coerceAtLeast(1)
        val sectionMs = (to - from) / sections
        val usable = tracks.filter { it.size > 0 }
        val spots = List(sections) { index ->
            val sectionStart = from + index * sectionMs
            val sectionEnd = sectionStart + sectionMs
            if (usable.isEmpty()) {
                sectionStart + sectionMs / 2
            } else {
                var best = sectionStart + sectionMs / 2
                var bestScore = 0.0
                var start = sectionStart
                while (start <= sectionEnd) {
                    val score = usable.sumOf { score(it, start, start + spotMs) * (0.5 + openingShare(it, start)) } / usable.size
                    if (score > bestScore) {
                        bestScore = score
                        best = start
                    }
                    start += STEP_MS
                }
                best
            }
        }
        return spreadOrder(spots)
    }

    /**
     * Dialogue covering [fromMs, toMs), plus half of what covers [TIMING_SLACK_MS] on either side
     * (for subtitles whose timing is off), weighted up by how irregular the lines' rhythm is.
     */
    private fun score(track: SubtitleSpeechTrack, fromMs: Long, toMs: Long): Double {
        val starts = track.startsMs
        val ends = track.endsMs
        val outerFrom = fromMs - TIMING_SLACK_MS
        val outerTo = toMs + TIMING_SLACK_MS
        var index = starts.binarySearchCompat(outerFrom).let { if (it < 0) -it - 1 else it }
        while (index > 0 && ends[index - 1] > outerFrom) index--
        var covered = 0.0
        var lines = 0
        var sum = 0.0
        var squares = 0.0
        var previousStart = -1L
        while (index < starts.size && starts[index] < outerTo) {
            val a = starts[index]
            val b = ends[index]
            val inner = overlap(a, b, fromMs, toMs)
            covered += inner + 0.5 * (overlap(a, b, outerFrom, outerTo) - inner)
            if (previousStart >= 0) {
                val gap = (a - previousStart).toDouble()
                sum += gap
                squares += gap * gap
            }
            previousStart = a
            lines++
            index++
        }
        if (lines < 3) return covered
        val mean = sum / (lines - 1)
        val variation = if (mean > 0) sqrt((squares / (lines - 1) - mean * mean).coerceAtLeast(0.0)) / mean else 0.0
        return covered * (1.0 + variation.coerceAtMost(1.0))
    }

    /** Share of the stretch around a spot's opening (with [OPENING_SLACK_MS] either side) that is dialogue. */
    private fun openingShare(track: SubtitleSpeechTrack, startMs: Long): Double {
        val fromMs = startMs - OPENING_SLACK_MS
        val toMs = startMs + OPENING_MS + OPENING_SLACK_MS
        val starts = track.startsMs
        val ends = track.endsMs
        var index = starts.binarySearchCompat(fromMs).let { if (it < 0) -it - 1 else it }
        while (index > 0 && ends[index - 1] > fromMs) index--
        var covered = 0.0
        while (index < starts.size && starts[index] < toMs) {
            covered += overlap(starts[index], ends[index], fromMs, toMs)
            index++
        }
        return (covered / (toMs - fromMs)).coerceAtMost(1.0)
    }

    private fun overlap(a: Long, b: Long, from: Long, to: Long): Double =
        (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L).toDouble()

    /** Middle first, then always the spot farthest from those already taken. */
    private fun spreadOrder(spots: List<Long>): List<Long> {
        val remaining = spots.toMutableList()
        val ordered = ArrayList<Long>(spots.size)
        ordered += remaining.removeAt(remaining.size / 2)
        while (remaining.isNotEmpty()) {
            val next = remaining.maxBy { spot -> ordered.minOf { abs(it - spot) } }
            remaining.remove(next)
            ordered += next
        }
        return ordered
    }
}
