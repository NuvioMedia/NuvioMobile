package com.nuvio.app.features.audiosync.asr

import kotlin.math.abs

/** A recognised word at media time [timeSec]; [segment] groups words from one speech segment. */
internal data class HeardWord(val timeSec: Double, val text: String, val segment: Int) {
    /** [text] as the matcher compares it, normalised once: every word is matched again after each segment. */
    val key: String = WordAnchorMatcher.normalize(text)
}

/** Media = subtitle time * [scale] + [shiftSec], plus how strongly the heard words support it. */
internal data class AnchorFit(
    val scale: Double,
    val shiftSec: Double,
    val score: Double,
    val segments: Int,
    val runnerUp: Double,
    val spanSec: Double,
    /** Media time (median) of the agreeing words: where [shiftSec] holds exactly. */
    val anchorSec: Double = 0.0,
    /** Media time of the first and last agreeing word. */
    val firstSec: Double = anchorSec,
    val lastSec: Double = anchorSec,
) {
    val isConfident: Boolean
        get() = segments >= MIN_SEGMENTS && score >= MIN_SCORE && score >= RATIO * maxOf(runnerUp, 0.5)

    companion object {
        private const val MIN_SEGMENTS = 3
        private const val MIN_SCORE = 3.0
        private const val RATIO = 2.5
    }
}

/**
 * Matches recognised words against the words of one same-language subtitle file and finds the
 * mapping most of them agree on. Every recognised word that also occurs in the subtitle (a few
 * times at most) votes for "media time − subtitle time"; neighbouring words that match in order
 * vote much more strongly. A tight cluster of votes from several separate speech segments is an
 * unambiguous anchor, usually after the first handful of lines.
 */
internal class WordAnchorMatcher(cues: List<Triple<Long, Long, String>>) {
    private class SubWord(val timeSec: Double, val word: String, val firstInCue: Boolean)

    private val words = ArrayList<SubWord>()
    private val index = HashMap<String, MutableList<Int>>()

    init {
        for ((startMs, endMs, text) in cues.sortedBy { it.first }) {
            val tokens = tokenize(text)
            if (tokens.isEmpty()) continue
            val durationMs = (endMs - startMs).coerceAtLeast(1L)
            tokens.forEachIndexed { k, word ->
                // Word times inside a line are interpolated; the first word is the cue start exactly.
                val timeSec = (startMs + durationMs * k.toDouble() / tokens.size) / 1_000.0
                index.getOrPut(word) { ArrayList(2) }.add(words.size)
                words += SubWord(timeSec, word, k == 0)
            }
        }
    }

    val isEmpty: Boolean get() = words.isEmpty()

    /**
     * The votes of a list of heard words against this subtitle. [fit], [localFits] and
     * [fitByPlace] all start from them, so a caller asking several of these for the same words
     * builds them once with [votes].
     *
     * A vote's weight is kept in [WEIGHT_UNITS]ths: every weight is 1/occurrences plus whole
     * neighbour bonuses, so sums over a window are exact and do not depend on summation order.
     */
    inner class Votes internal constructor(heard: List<HeardWord>) {
        internal val owner: WordAnchorMatcher get() = this@WordAnchorMatcher
        internal var size = 0
            private set
        internal var mediaSec = DoubleArray(INITIAL_CAPACITY)
            private set
        internal var subSec = DoubleArray(INITIAL_CAPACITY)
            private set
        internal var units = IntArray(INITIAL_CAPACITY)
            private set

        /** Dense index of the vote's speech segment, and of its [LOCAL_WINDOW_SEC] place in the film. */
        internal var segment = IntArray(INITIAL_CAPACITY)
            private set
        internal var place = IntArray(INITIAL_CAPACITY)
            private set
        internal var first = BooleanArray(INITIAL_CAPACITY)
            private set
        internal val segmentCount: Int
        internal val placeCount: Int

        init {
            val segments = HashMap<Int, Int>()
            val places = HashMap<Int, Int>()
            for (i in heard.indices) {
                val h = heard[i]
                if (h.key.length < 3 || h.key in STOP_WORDS) continue
                val occurrences = index[h.key] ?: continue
                if (occurrences.size > MAX_OCCURRENCES) continue
                val next = heard.getOrNull(i + 1)
                val previous = heard.getOrNull(i - 1)
                val segmentIndex = segments.getOrPut(h.segment) { segments.size }
                val placeIndex = places.getOrPut((h.timeSec / LOCAL_WINDOW_SEC).toInt()) { places.size }
                for (j in occurrences) {
                    var weight = WEIGHT_UNITS / occurrences.size
                    if (next != null && next.segment == h.segment && words.getOrNull(j + 1)?.word == next.key) {
                        weight += WEIGHT_UNITS
                    }
                    if (previous != null && previous.segment == h.segment && words.getOrNull(j - 1)?.word == previous.key) {
                        weight += WEIGHT_UNITS
                    }
                    add(h.timeSec, words[j].timeSec, weight, segmentIndex, placeIndex, words[j].firstInCue)
                }
            }
            segmentCount = segments.size
            placeCount = places.size
        }

        internal val all: IntArray = IntArray(size) { it }

        private fun add(media: Double, sub: Double, weight: Int, segmentIndex: Int, placeIndex: Int, isFirst: Boolean) {
            if (size == mediaSec.size) {
                val capacity = size * 2
                mediaSec = mediaSec.copyOf(capacity)
                subSec = subSec.copyOf(capacity)
                units = units.copyOf(capacity)
                segment = segment.copyOf(capacity)
                place = place.copyOf(capacity)
                first = first.copyOf(capacity)
            }
            mediaSec[size] = media
            subSec[size] = sub
            units[size] = weight
            segment[size] = segmentIndex
            place[size] = placeIndex
            first[size] = isFirst
            size++
        }
    }

    fun votes(heard: List<HeardWord>): Votes = Votes(heard)

    fun fit(heard: List<HeardWord>, maxShiftSec: Double = MAX_SHIFT_SEC): AnchorFit? = fit(votes(heard), maxShiftSec)

    fun fit(votes: Votes, maxShiftSec: Double = MAX_SHIFT_SEC): AnchorFit? {
        require(votes.owner === this)
        if (votes.size == 0) return null
        val anchors = votes.all
        val unit = cluster(votes, anchors, 1.0, maxShiftSec)
        var minMedia = Double.POSITIVE_INFINITY
        var maxMedia = Double.NEGATIVE_INFINITY
        for (a in anchors) {
            minMedia = minOf(minMedia, votes.mediaSec[a])
            maxMedia = maxOf(maxMedia, votes.mediaSec[a])
        }
        val span = maxMedia - minMedia
        // Over a short stretch every frame rate fits equally well; judge the rate only on a long span.
        if (span < MIN_RATE_SPAN_SEC) return unit
        // Other rates are tried even when no word fits at 1x: late in a film a frame-rate mismatch
        // can put every word further than [maxShiftSec] off at 1x, exactly when a rate is needed.
        val unitScore = unit?.score ?: 0.0
        var best = unit
        for (scale in RATE_CANDIDATES) {
            val fit = cluster(votes, anchors, scale, maxShiftSec) ?: continue
            // A rate change drifts further every minute: it needs words from several places.
            if (fit.segments < MIN_RATE_SEGMENTS) continue
            if (fit.score > (best?.score ?: 0.0) * RATE_MARGIN && fit.score > unitScore * RATE_MARGIN) best = fit
        }
        if (best !== unit) return best
        // Counted word by word, the stretch heard most (around the playhead, often the opening)
        // decides: there every rate fits, so its words agree at 1x however wrong 1x is elsewhere.
        // Counted place by place instead (each part of the film a capped vote), a rate that words
        // from several far-apart places agree on can still be seen.
        val unitByPlace = cluster(votes, anchors, 1.0, maxShiftSec, PLACE_CAP_UNITS)
        var byPlace: AnchorFit? = null
        for (scale in RATE_CANDIDATES) {
            val fit = cluster(votes, anchors, scale, maxShiftSec, PLACE_CAP_UNITS) ?: continue
            if (fit.segments < MIN_RATE_SEGMENTS || fit.spanSec < MIN_RATE_SPAN_SEC || !fit.isConfident) continue
            if (fit.score > (byPlace?.score ?: 0.0) * RATE_MARGIN && fit.score > (unitByPlace?.score ?: 0.0) * RATE_MARGIN) {
                byPlace = fit
            }
        }
        return byPlace ?: unit
    }

    /**
     * Confident fits of the words heard within each [windowSec] of the film, at [scale]: where a
     * release differs from the subtitle by an inserted or removed scene, the offset differs between
     * regions, which one fit over all words cannot show. Sorted by [AnchorFit.anchorSec].
     */
    fun localFits(heard: List<HeardWord>, scale: Double, windowSec: Double = LOCAL_WINDOW_SEC): List<AnchorFit> =
        localFits(votes(heard), scale, windowSec)

    fun localFits(votes: Votes, scale: Double, windowSec: Double = LOCAL_WINDOW_SEC): List<AnchorFit> {
        require(votes.owner === this)
        if (votes.size == 0) return emptyList()
        val regions = LinkedHashMap<Int, IntArrayList>()
        for (a in votes.all) regions.getOrPut((votes.mediaSec[a] / windowSec).toInt()) { IntArrayList() }.add(a)
        return regions.values
            .mapNotNull { region -> cluster(votes, region.toArray(), scale, MAX_SHIFT_SEC)?.takeIf { it.isConfident } }
            .sortedBy { it.anchorSec }
    }

    /**
     * The confident fit at [scale] with places counted, not words (see [cluster]): what words
     * from several parts of the film agree on, even when one part was heard far more than the rest.
     */
    fun fitByPlace(heard: List<HeardWord>, scale: Double): AnchorFit? = fitByPlace(votes(heard), scale)

    fun fitByPlace(votes: Votes, scale: Double): AnchorFit? {
        require(votes.owner === this)
        if (votes.size == 0) return null
        return cluster(votes, votes.all, scale, MAX_SHIFT_SEC, PLACE_CAP_UNITS)?.takeIf { it.isConfident }
    }

    /**
     * Running totals of a window of votes: how many distinct speech segments it holds, and its
     * weight (each place's share capped at [capUnits] when places are counted). Votes enter and
     * leave one at a time, so sliding a window over n votes costs O(n), not O(n * window).
     */
    private class Tally(votes: Votes, private val capUnits: Int) {
        private val voteUnits = votes.units
        private val voteSegment = votes.segment
        private val votePlace = votes.place
        private val perSegment = IntArray(votes.segmentCount)
        private val perPlace = if (capUnits == NO_CAP) IntArray(0) else IntArray(votes.placeCount)
        var segments = 0
            private set
        var units = 0L
            private set

        fun add(a: Int) {
            if (perSegment[voteSegment[a]]++ == 0) segments++
            if (capUnits == NO_CAP) {
                units += voteUnits[a]
                return
            }
            val old = perPlace[votePlace[a]]
            val new = old + voteUnits[a]
            perPlace[votePlace[a]] = new
            units += minOf(new, capUnits) - minOf(old, capUnits)
        }

        fun remove(a: Int) {
            if (--perSegment[voteSegment[a]] == 0) segments--
            if (capUnits == NO_CAP) {
                units -= voteUnits[a]
                return
            }
            val old = perPlace[votePlace[a]]
            val new = old - voteUnits[a]
            perPlace[votePlace[a]] = new
            units += minOf(new, capUnits) - minOf(old, capUnits)
        }
    }

    /**
     * The tightest, best-supported group of votes at [scale]. With [placeCapUnits], the words of
     * each [LOCAL_WINDOW_SEC] of the film count for at most that much together, so agreement
     * between places outweighs many words from one place.
     */
    private fun cluster(
        votes: Votes,
        anchors: IntArray,
        scale: Double,
        maxShiftSec: Double,
        placeCapUnits: Int = NO_CAP,
    ): AnchorFit? {
        var count = 0
        for (a in anchors) if (abs(votes.mediaSec[a] - scale * votes.subSec[a]) <= maxShiftSec) count++
        if (count == 0) return null
        val keptVotes = IntArray(count)
        val keptShifts = DoubleArray(count)
        count = 0
        for (a in anchors) {
            val s = votes.mediaSec[a] - scale * votes.subSec[a]
            if (abs(s) <= maxShiftSec) {
                keptVotes[count] = a
                keptShifts[count] = s
                count++
            }
        }
        // Stable, like the list sort it replaces: votes with equal shifts keep their order.
        val order = IntArray(count) { it }.sortedStableBy(keptShifts)
        val entries = IntArray(count) { keptVotes[order[it]] }
        val shifts = DoubleArray(count) { keptShifts[order[it]] }
        var bestScore = -1L
        var bestFrom = 0
        var bestTo = 0
        var bestSegments = 0
        var from = 0
        val window = Tally(votes, placeCapUnits)
        for (to in entries.indices) {
            window.add(entries[to])
            while (shifts[to] - shifts[from] > WINDOW_SEC) window.remove(entries[from++])
            val segments = window.segments
            val score = window.units * minOf(segments, 6)
            if (score > bestScore) {
                bestScore = score
                bestFrom = from
                bestTo = to
                bestSegments = segments
            }
        }
        val firsts = ArrayList<Double>()
        val inCluster = ArrayList<Double>(bestTo - bestFrom + 1)
        val times = ArrayList<Double>(bestTo - bestFrom + 1)
        for (k in bestFrom..bestTo) {
            inCluster += shifts[k]
            times += votes.mediaSec[entries[k]]
            if (votes.first[entries[k]]) firsts += shifts[k]
        }
        val center = if (firsts.size >= 2) median(firsts) else median(inCluster)
        // Strongest competing cluster more than 2 s away.
        val far = entries.indices.filter { abs(shifts[it] - center) > 2.0 }
        var runnerUp = 0L
        var f = 0
        val farWindow = Tally(votes, placeCapUnits)
        for (t in far.indices) {
            farWindow.add(entries[far[t]])
            while (shifts[far[t]] - shifts[far[f]] > WINDOW_SEC) farWindow.remove(entries[far[f++]])
            runnerUp = maxOf(runnerUp, farWindow.units)
        }
        return AnchorFit(
            scale = scale,
            shiftSec = center,
            score = bestScore / (WEIGHT_UNITS * 6.0),
            segments = bestSegments,
            runnerUp = runnerUp / WEIGHT_UNITS.toDouble(),
            spanSec = times.max() - times.min(),
            anchorSec = median(times),
            firstSec = times.min(),
            lastSec = times.max(),
        )
    }

    /** A growable list of vote indices without boxing. */
    private class IntArrayList {
        private var values = IntArray(16)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray(): IntArray = values.copyOf(size)
    }

    companion object {
        const val MAX_SHIFT_SEC = 60.0
        private const val WINDOW_SEC = 0.7
        private const val MAX_OCCURRENCES = 6
        private const val MIN_RATE_SPAN_SEC = 180.0
        private const val MIN_RATE_SEGMENTS = 5
        private const val LOCAL_WINDOW_SEC = 120.0
        private const val RATE_MARGIN = 1.3

        /**
         * A vote's weight is 1/occurrences (at most [MAX_OCCURRENCES]) plus whole neighbour
         * bonuses, so in 60ths every weight, and every sum of them, is a whole number.
         */
        private const val WEIGHT_UNITS = 60

        /** Most one place (see [LOCAL_WINDOW_SEC]) adds when places are counted: a few matched words (1.5). */
        private const val PLACE_CAP_UNITS = 90
        private const val NO_CAP = Int.MAX_VALUE
        private const val INITIAL_CAPACITY = 64
        private val RATE_CANDIDATES = doubleArrayOf(
            24_000.0 / 23_976.0,
            23_976.0 / 24_000.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
        )

        private val STOP_WORDS = (
            "the a an and or but to of in on at for is it i you he she we they me my your our his her its " +
                "be are was were am do did not no yes so that this with as what who how why oh uh um"
            ).split(' ').toHashSet()

        private val tagPattern = Regex("<[^>]+>|\\{[^}]*\\}")
        private val nonWord = Regex("[^\\p{L}\\p{Nd}']")

        fun normalize(word: String): String = word.lowercase().replace(nonWord, "").trim('\'')

        fun tokenize(text: String): List<String> =
            text.replace(tagPattern, " ").split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }

        init {
            check((1..MAX_OCCURRENCES).all { WEIGHT_UNITS % it == 0 })
        }

        /** These indices ordered by [keys], stably (equal keys keep their order). */
        private fun IntArray.sortedStableBy(keys: DoubleArray): IntArray {
            val boxed = toTypedArray()
            boxed.sortWith { a, b -> keys[a].compareTo(keys[b]) }
            return boxed.toIntArray()
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
