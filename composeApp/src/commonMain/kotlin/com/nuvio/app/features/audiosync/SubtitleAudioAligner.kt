package com.nuvio.app.features.audiosync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds where a subtitle file lines up with detected speech.
 *
 * For each candidate playback-rate ratio the subtitle track is rendered to the 32 ms speech grid
 * and the masked Pearson correlation against speech probability is computed for every lag with
 * FFTs. The mapping is media time = subtitle time * scale + shift.
 */
internal object SubtitleAudioAligner {
    /** Common frame-rate mismatches between subtitle and video releases. 1.0 must stay first. */
    val CANDIDATE_SCALES = doubleArrayOf(
        1.0,
        24_000.0 / 23_976.0,
        23_976.0 / 24_000.0,
        25.0 / 23.976,
        23.976 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
    )

    /**
     * Silero reacts ~45 ms after speech starts and trails off after it ends, and subtitles usually
     * lead speech slightly, so the raw correlation peak sits this much after the subtitle author's
     * intended timing. Swept on 10 offline corpora (about 590 subtitles): at 100 ms, 27 synced files
     * showed lines over 250 ms after the release's own subtitle against 6 early; at 200 ms, 10 late
     * against 8 early, 15 more within 250 ms, and no more locks on other films' subtitles.
     */
    const val DETECTOR_BIAS_MS = 200.0

    /** Excluded neighbourhood around the peak when measuring how much it stands out. */
    private const val PROMINENCE_EXCLUSION_MS = 2_500.0

    /** Peaks this close to either end of the searched shift range are flagged as unreliable. */
    const val EDGE_MARGIN_MS = 1_500.0

    /** Searches over at most this many lags (about ±4 s) skip the FFTs; see [accumulateIntervalTerms]. */
    private const val INTERVAL_MAX_LAGS = 256

    /** Keeps a mistaken ~0.1% ratio from winning on noise over the plain 1.0 hypothesis. */
    private const val SCALE_PREFERENCE_MARGIN = 0.01

    data class Estimate(
        val scale: Double,
        val shiftMs: Double,
        /** Pearson correlation at the peak. */
        val peak: Double,
        /** Peak minus the best correlation more than 2.5 s away from it. */
        val prominence: Double,
        val analysedSeconds: Double,
        val speechSeconds: Double,
        val cueCount: Int,
        /** True when the peak sits at the edge of the searched range, so it may not be a real maximum. */
        val atSearchEdge: Boolean = false,
        /**
         * Peak minus the best competing alignment: the highest correlation outside the peak's own
         * lobe. Dialogue lines last seconds, so the correlation falls off slowly around a true
         * match; [prominence] compares the peak with that slope 2.5 s away and so stays low for real
         * matches, while this compares it with another, separate peak.
         */
        val separation: Double = prominence,
    )

    /**
     * @param probabilities speech probability per frame starting at [fromFrame], NaN when unknown.
     * @param minShiftMs lowest shift considered; together with [maxShiftMs] bounds the lag search.
     */
    fun estimate(
        probabilities: FloatArray,
        fromFrame: Int,
        track: SubtitleSpeechTrack,
        scales: DoubleArray = CANDIDATE_SCALES,
        minShiftMs: Double,
        maxShiftMs: Double,
        /** Latency of the speech detector; 0 when [probabilities] is not detector output. */
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): Estimate? = estimate(listOf(SpeechSegment(fromFrame, probabilities)), track, scales, minShiftMs, maxShiftMs, detectorBiasMs)

    /** Like the single-array form, over separate stretches of known speech (see [SpeechTimeline.segments]). */
    fun estimate(
        segments: List<SpeechSegment>,
        track: SubtitleSpeechTrack,
        scales: DoubleArray = CANDIDATE_SCALES,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): Estimate? = prepare(segments, minShiftMs, maxShiftMs, detectorBiasMs)?.estimate(track, scales)

    fun prepare(
        probabilities: FloatArray,
        fromFrame: Int,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): PreparedSpeech? = prepare(listOf(SpeechSegment(fromFrame, probabilities)), minShiftMs, maxShiftMs, detectorBiasMs)

    /**
     * Transforms the speech side once so several subtitle files can be scored against the same
     * audio for the cost of one extra FFT pair each. The correlation terms are sums over frames, so
     * separate stretches are transformed on their own and their terms added: exactly the result of
     * one array covering all of them, at the cost of only the known audio. Null when there is too
     * little known speech.
     */
    fun prepare(
        segments: List<SpeechSegment>,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): PreparedSpeech? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val minLag = (minShiftMs + detectorBiasMs).roundToInt().floorDiv(frameMs.toInt())
        val maxLag = (maxShiftMs + detectorBiasMs).roundToInt().floorDiv(frameMs.toInt()) + 1
        val lagCount = maxLag - minLag + 1

        // One mean and norm over every known frame, as if the stretches were a single array.
        var count = 0
        var sum = 0.0
        for (segment in segments) for (p in segment.probabilities) if (!p.isNaN()) {
            count++
            sum += p
        }
        if (count < 2) return null
        val mean = sum / count
        var energy = 0.0
        var speechSeconds = 0.0
        val parts = ArrayList<Part>(segments.size)
        for (segment in segments) {
            val n = segment.probabilities.size
            if (n == 0) continue
            // g covers frames [fromFrame - maxLag, fromFrame + n - minLag).
            val gLength = n + lagCount - 1
            if (lagCount <= INTERVAL_MAX_LAGS) {
                // Few lags: running sums of the audio side replace the transforms (see accumulateIntervalTerms).
                val centered = DoubleArray(n + 1)
                val mask = DoubleArray(n + 1)
                var known = 0
                for (i in 0 until n) {
                    val p = segment.probabilities[i]
                    var c = 0.0
                    if (!p.isNaN()) {
                        c = p - mean
                        energy += c * c
                        speechSeconds += p * frameMs / 1_000.0
                        known++
                    }
                    centered[i + 1] = centered[i] + c
                    mask[i + 1] = mask[i] + if (p.isNaN()) 0.0 else 1.0
                }
                if (known == 0) continue
                parts += Part(null, centered, mask, n, segment.fromFrame, gLength, fullyKnown = known == n)
                continue
            }
            // Lags k < lagCount only read g[i + k] with i < n, so i + k < gLength: no wrap-around.
            val size = Fft.sizeFor(gLength)
            // Audio side, packed as (centered + i * mask), shared by every scale and subtitle.
            val audioRe = DoubleArray(size)
            val audioIm = DoubleArray(size)
            var known = 0
            for (i in 0 until n) {
                val p = segment.probabilities[i]
                if (p.isNaN()) continue
                val c = p - mean
                audioRe[i] = c
                audioIm[i] = 1.0
                energy += c * c
                speechSeconds += p * frameMs / 1_000.0
                known++
            }
            if (known == 0) continue
            val fft = Fft.of(size)
            fft.transform(audioRe, audioIm)
            parts += Part(fft, audioRe, audioIm, n, segment.fromFrame, gLength, fullyKnown = known == n)
        }
        if (energy <= 1e-9 || parts.isEmpty()) return null
        return PreparedSpeech(parts, count, sqrt(energy), speechSeconds, minLag, maxLag, detectorBiasMs)
    }

    /**
     * One stretch of speech: transformed, or with no [fft] (a narrow search), [audioRe] and
     * [audioIm] are running sums of the centered values and of the known-frame mask instead.
     */
    internal class Part(
        val fft: Fft?,
        val audioRe: DoubleArray,
        val audioIm: DoubleArray,
        val n: Int,
        val fromFrame: Int,
        val gLength: Int,
        /** No unknown frames: the mask terms are plain window sums (see [accumulateWindowSums]). */
        val fullyKnown: Boolean,
    )

    /**
     * The speech side of a correlation, transformed once; see [prepare]. Correlations already
     * computed for a subtitle and ratio are kept, so asking again (another ratio subset, or a
     * narrower shift range) costs nothing. Not thread-safe.
     */
    class PreparedSpeech internal constructor(
        private val parts: List<Part>,
        private val count: Int,
        private val norm: Double,
        private val speechSeconds: Double,
        private val minLag: Int,
        private val maxLag: Int,
        private val detectorBiasMs: Double,
    ) {
        private val correlations = HashMap<Pair<SubtitleSpeechTrack, Double>, DoubleArray>()
        private val scratchBySize = HashMap<Int, Scratch>()

        private val renderBuffers = HashMap<Int, ArrayList<DoubleArray>>()

        private fun scratch(size: Int): Scratch = scratchBySize.getOrPut(size) { Scratch(size) }

        private fun renderBuffers(length: Int, count: Int): List<DoubleArray> {
            val buffers = renderBuffers.getOrPut(length) { ArrayList() }
            while (buffers.size < count) buffers += DoubleArray(length)
            return buffers
        }

        /**
         * Best mapping of [track] onto the prepared speech among [scales], with the shift searched
         * in [minShiftMs, maxShiftMs] (clipped to the prepared range; null keeps the full range).
         */
        fun estimate(
            track: SubtitleSpeechTrack,
            scales: DoubleArray = CANDIDATE_SCALES,
            minShiftMs: Double? = null,
            maxShiftMs: Double? = null,
        ): Estimate? {
            if (track.size == 0) return null
            val frameMs = SpeechTimeline.FRAME_DURATION_MS
            // Index k = maxLag - lag; a narrower shift range is a slice of the same lags.
            val rangeMaxLag = maxShiftMs?.let { (it + detectorBiasMs).roundToInt().floorDiv(frameMs.toInt()) + 1 }
                ?.coerceAtMost(maxLag) ?: maxLag
            val rangeMinLag = minShiftMs?.let { (it + detectorBiasMs).roundToInt().floorDiv(frameMs.toInt()) }
                ?.coerceAtLeast(minLag) ?: minLag
            if (rangeMinLag > rangeMaxLag) return null
            val fromIndex = maxLag - rangeMaxLag
            val toIndex = maxLag - rangeMinLag + 1
            var best: Estimate? = null
            var bestPeak = Double.NEGATIVE_INFINITY
            var unitScale: Estimate? = null
            // A ratio computed as e.g. 1.0 / (24000 / 23976) differs from the listed one in the last
            // bit; use the listed value so its cached correlation is reused instead of recomputed.
            val ratios = scales.map { s -> CANDIDATE_SCALES.firstOrNull { abs(it - s) < 1e-12 } ?: s }
            computeCorrelations(track, ratios.filter { (track to it) !in correlations }.distinct())
            for (scale in ratios) {
                val correlation = correlations.getValue(track to scale).copyOfRange(fromIndex, toIndex)
                val estimate = correlationPeak(correlation, rangeMaxLag, scale, frameMs, detectorBiasMs) ?: continue
                val cues = parts.sumOf { part ->
                    track.countCuesIn(
                        fromMs = part.fromFrame * frameMs,
                        toMs = (part.fromFrame + part.n) * frameMs,
                        scale = scale,
                        shiftMs = estimate.shiftMs,
                    )
                }
                val complete = estimate.copy(
                    analysedSeconds = count * frameMs / 1_000.0,
                    speechSeconds = speechSeconds,
                    cueCount = cues,
                )
                if (scale == 1.0) unitScale = complete
                if (complete.peak > bestPeak) {
                    bestPeak = complete.peak
                    best = complete
                }
            }
            val unit = unitScale
            if (unit != null && best != null && best !== unit && unit.peak >= best.peak - SCALE_PREFERENCE_MARGIN &&
                isNearUnit(best.scale)
            ) {
                return unit
            }
            return best
        }

        /**
         * Masked Pearson correlation of [track] at each of [scales] for every prepared lag (NaN
         * where undefined), added to the cache. On stretches without gaps two ratios share each
         * transform and the mask terms come from window sums: the same numbers for a third of the work.
         */
        private fun computeCorrelations(track: SubtitleSpeechTrack, scales: List<Double>) {
            if (scales.isEmpty()) return
            val lagCount = maxLag - minLag + 1
            val c1 = List(scales.size) { DoubleArray(lagCount) }
            val cm = List(scales.size) { DoubleArray(lagCount) }
            val cm2 = List(scales.size) { DoubleArray(lagCount) }
            for (part in parts) {
                val reusable = renderBuffers(part.gLength, scales.size)
                val g = scales.mapIndexed { j, scale -> track.renderInto(reusable[j], part.fromFrame - maxLag, scale) }
                if (part.fft == null) {
                    for (j in scales.indices) accumulateIntervalTerms(part, g[j], lagCount, c1[j], cm[j], cm2[j])
                    continue
                }
                val buffers = scratch(part.fft.size)
                if (part.fullyKnown) {
                    for (first in scales.indices step 2) {
                        val second = (first + 1).takeIf { it < scales.size }
                        accumulateCrossTerms(part, g[first], second?.let(g::get), lagCount, c1[first], second?.let(c1::get), buffers)
                    }
                    for (j in scales.indices) accumulateWindowSums(g[j], part.n, lagCount, cm[j], cm2[j], scratch(g[j].size + 1))
                } else {
                    for (j in scales.indices) accumulateTerms(part, g[j], lagCount, c1[j], cm[j], cm2[j], buffers)
                }
            }
            for (j in scales.indices) {
                correlations[track to scales[j]] = DoubleArray(lagCount) { k ->
                    val variance = cm2[j][k] - cm[j][k] * cm[j][k] / count
                    if (variance > 1e-6 * count && cm[j][k] > 0.5) c1[j][k] / (norm * sqrt(variance)) else Double.NaN
                }
            }
        }
    }

    private fun isNearUnit(scale: Double): Boolean = abs(scale - 1.0) < 0.002

    private class Scratch(val size: Int) {
        val a by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
        val b by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
        val c by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
        val d by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
        val e by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
        val f by lazy(LazyThreadSafetyMode.NONE) { DoubleArray(size) }
    }

    /**
     * Adds this stretch's correlation terms per lag: c1 = sum(centered * g), cm = sum(mask * g) and
     * cm2 = sum(mask * g^2), where index k = maxLag - lag.
     */
    private fun accumulateTerms(
        part: Part,
        g: DoubleArray,
        lagCount: Int,
        c1: DoubleArray,
        cm: DoubleArray,
        cm2: DoubleArray,
        scratch: Scratch,
    ) {
        val fft = part.fft!!
        val size = fft.size
        val audioRe = part.audioRe
        val audioIm = part.audioIm
        // Subtitle side, packed as (g + i * g^2).
        val subRe = scratch.a
        val subIm = scratch.b
        for (i in g.indices) {
            subRe[i] = g[i]
            subIm[i] = g[i] * g[i]
        }
        subRe.fill(0.0, g.size, size)
        subIm.fill(0.0, g.size, size)
        fft.transform(subRe, subIm)

        // Unpack X = FFT(centered), M = FFT(mask), G = FFT(g), G2 = FFT(g^2) from the packed spectra
        // and form conj(X) * G + i * conj(M) * G (-> c1 + i * cm) and conj(M) * G2 (-> cm2).
        val c1cmRe = scratch.c
        val c1cmIm = scratch.d
        val cm2Re = scratch.e
        val cm2Im = scratch.f
        for (k in 0 until size) {
            val j = if (k == 0) 0 else size - k
            val aRe = audioRe[k]; val aIm = audioIm[k]
            val aRej = audioRe[j]; val aImj = -audioIm[j]
            val xRe = 0.5 * (aRe + aRej); val xIm = 0.5 * (aIm + aImj)
            val mRe = 0.5 * (aIm - aImj); val mIm = -0.5 * (aRe - aRej)
            val sRe = subRe[k]; val sIm = subIm[k]
            val sRej = subRe[j]; val sImj = -subIm[j]
            val gRe = 0.5 * (sRe + sRej); val gIm = 0.5 * (sIm + sImj)
            val g2Re = 0.5 * (sIm - sImj); val g2Im = -0.5 * (sRe - sRej)
            // conj(X) * G
            val p1Re = xRe * gRe + xIm * gIm
            val p1Im = xRe * gIm - xIm * gRe
            // conj(M) * G
            val p2Re = mRe * gRe + mIm * gIm
            val p2Im = mRe * gIm - mIm * gRe
            c1cmRe[k] = p1Re - p2Im
            c1cmIm[k] = p1Im + p2Re
            cm2Re[k] = mRe * g2Re + mIm * g2Im
            cm2Im[k] = mRe * g2Im - mIm * g2Re
        }
        fft.transform(c1cmRe, c1cmIm, inverse = true)
        fft.transform(cm2Re, cm2Im, inverse = true)
        val inv = 1.0 / size
        for (k in 0 until lagCount) {
            c1[k] += c1cmRe[k] * inv
            cm[k] += c1cmIm[k] * inv
            cm2[k] += cm2Re[k] * inv
        }
    }

    /**
     * Adds sum(centered * g) per lag for one or two subtitle renderings [gA] and [gB] of a stretch
     * without gaps: both real, so they share one forward and one inverse transform.
     */
    private fun accumulateCrossTerms(
        part: Part,
        gA: DoubleArray,
        gB: DoubleArray?,
        lagCount: Int,
        c1A: DoubleArray,
        c1B: DoubleArray?,
        scratch: Scratch,
    ) {
        val fft = part.fft!!
        val size = fft.size
        val audioRe = part.audioRe
        val audioIm = part.audioIm
        // Subtitle side, packed as (gA + i * gB).
        val subRe = scratch.a
        val subIm = scratch.b
        gA.copyInto(subRe)
        subRe.fill(0.0, gA.size, size)
        if (gB != null) {
            gB.copyInto(subIm)
            subIm.fill(0.0, gB.size, size)
        } else {
            subIm.fill(0.0)
        }
        fft.transform(subRe, subIm)
        // Unpack X = FFT(centered), GA and GB, and form conj(X) * GA + i * conj(X) * GB.
        val outRe = scratch.c
        val outIm = scratch.d
        for (k in 0 until size) {
            val j = if (k == 0) 0 else size - k
            val xRe = 0.5 * (audioRe[k] + audioRe[j]); val xIm = 0.5 * (audioIm[k] - audioIm[j])
            val aRe = 0.5 * (subRe[k] + subRe[j]); val aIm = 0.5 * (subIm[k] - subIm[j])
            val bRe = 0.5 * (subIm[k] + subIm[j]); val bIm = -0.5 * (subRe[k] - subRe[j])
            val pRe = xRe * aRe + xIm * aIm
            val pIm = xRe * aIm - xIm * aRe
            val qRe = xRe * bRe + xIm * bIm
            val qIm = xRe * bIm - xIm * bRe
            outRe[k] = pRe - qIm
            outIm[k] = pIm + qRe
        }
        fft.transform(outRe, outIm, inverse = true)
        val inv = 1.0 / size
        for (k in 0 until lagCount) {
            c1A[k] += outRe[k] * inv
            c1B?.let { it[k] += outIm[k] * inv }
        }
    }

    /**
     * Adds sum(mask * g) and sum(mask * g^2) per lag for a stretch without gaps, where the mask is
     * all ones and both are sliding sums over [n] frames: g[k] + ... + g[k + n - 1].
     */
    private fun accumulateWindowSums(g: DoubleArray, n: Int, lagCount: Int, cm: DoubleArray, cm2: DoubleArray, scratch: Scratch) {
        val prefix = scratch.a
        val prefixSquares = scratch.b
        prefix[0] = 0.0
        prefixSquares[0] = 0.0
        for (i in g.indices) {
            prefix[i + 1] = prefix[i] + g[i]
            prefixSquares[i + 1] = prefixSquares[i] + g[i] * g[i]
        }
        for (k in 0 until lagCount) {
            cm[k] += prefix[k + n] - prefix[k]
            cm2[k] += prefixSquares[k + n] - prefixSquares[k]
        }
    }

    /**
     * The same terms as [accumulateTerms] for a stretch prepared without transforms. The rendered
     * subtitle is constant over runs of frames, so each run adds its value times a difference of
     * running sums per lag: work grows with runs times lags rather than with the stretch's length.
     */
    private fun accumulateIntervalTerms(part: Part, g: DoubleArray, lagCount: Int, c1: DoubleArray, cm: DoubleArray, cm2: DoubleArray) {
        val centered = part.audioRe
        val mask = part.audioIm
        val n = part.n
        var from = 0
        while (from < g.size) {
            val value = g[from]
            var to = from + 1
            while (to < g.size && g[to] == value) to++
            // Audio frames i with i + k in [from, to), clipped to [0, n).
            if (value != 0.0) for (k in maxOf(0, from - n + 1)..minOf(lagCount - 1, to - 1)) {
                val lo = maxOf(from - k, 0)
                val hi = minOf(to - k, n)
                if (hi <= lo) continue
                val known = mask[hi] - mask[lo]
                c1[k] += value * (centered[hi] - centered[lo])
                cm[k] += value * known
                cm2[k] += value * value * known
            }
            from = to
        }
    }

    /** Peak of [correlation] (index k = [maxLag] - lag), its prominence and refined shift. */
    private fun correlationPeak(
        correlation: DoubleArray,
        maxLag: Int,
        scale: Double,
        frameMs: Double,
        detectorBiasMs: Double,
    ): Estimate? {
        val lagCount = correlation.size
        var peakIndex = -1
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && (peakIndex < 0 || value > correlation[peakIndex])) peakIndex = k
        }
        if (peakIndex < 0) return null
        val peak = correlation[peakIndex]
        val exclusion = (PROMINENCE_EXCLUSION_MS / frameMs).toInt()
        var runnerUp = Double.NEGATIVE_INFINITY
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && abs(k - peakIndex) > exclusion && value > runnerUp) runnerUp = value
        }
        // The peak's lobe: outwards from the peak while the curve keeps falling. A rise smaller than
        // LOBE_TOLERANCE is noise on the slope; a larger one starts another peak.
        var lobeFrom = peakIndex
        var lowest = peak
        while (lobeFrom > 0) {
            val value = correlation[lobeFrom - 1]
            if (value.isNaN() || value > lowest + LOBE_TOLERANCE) break
            if (value < lowest) lowest = value
            lobeFrom--
        }
        var lobeTo = peakIndex
        lowest = peak
        while (lobeTo < lagCount - 1) {
            val value = correlation[lobeTo + 1]
            if (value.isNaN() || value > lowest + LOBE_TOLERANCE) break
            if (value < lowest) lowest = value
            lobeTo++
        }
        lobeFrom = minOf(lobeFrom, peakIndex - exclusion)
        lobeTo = maxOf(lobeTo, peakIndex + exclusion)
        var competitor = Double.NEGATIVE_INFINITY
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && (k < lobeFrom || k > lobeTo) && value > competitor) competitor = value
        }
        var refinedIndex = peakIndex.toDouble()
        if (peakIndex in 1 until lagCount - 1) {
            val left = correlation[peakIndex - 1]
            val right = correlation[peakIndex + 1]
            val curvature = left - 2 * peak + right
            if (!left.isNaN() && !right.isNaN() && curvature < 0) {
                refinedIndex += (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5)
            }
        }
        val lagFrames = maxLag - refinedIndex
        val edgeFrames = (EDGE_MARGIN_MS / frameMs).toInt()
        return Estimate(
            scale = scale,
            shiftMs = lagFrames * frameMs - detectorBiasMs,
            peak = peak,
            prominence = if (runnerUp.isFinite()) peak - runnerUp else peak,
            analysedSeconds = 0.0,
            speechSeconds = 0.0,
            cueCount = 0,
            atSearchEdge = peakIndex < edgeFrames || peakIndex >= lagCount - edgeFrames,
            separation = if (competitor.isFinite()) peak - competitor else peak,
        )
    }

    /**
     * Where speech starts in [segments]: how much the speech probability rises across about 100 ms
     * either side of each frame (0 where it falls or stays), unknown where either side is unknown.
     * Correlated with a track's [SubtitleSpeechTrack.starts].
     */
    fun speechStarts(segments: List<SpeechSegment>): List<SpeechSegment> = segments.mapNotNull { segment ->
        val p = segment.probabilities
        // The first and last START_SPAN_FRAMES have no neighbour on one side: left out rather than
        // kept unknown, so a stretch with no gaps keeps the cheaper gap-free correlation.
        val size = p.size - 2 * START_SPAN_FRAMES
        if (size <= 0) return@mapNotNull null
        val rises = FloatArray(size) { j ->
            val i = j + START_SPAN_FRAMES
            val before = p[i - START_SPAN_FRAMES]
            val after = p[i + START_SPAN_FRAMES]
            if (before.isNaN() || after.isNaN() || p[i].isNaN()) Float.NaN else maxOf(after - before, 0f)
        }
        SpeechSegment(segment.fromFrame + START_SPAN_FRAMES, rises)
    }

    /** Frames either side of a frame compared by [speechStarts]: about 100 ms. */
    private const val START_SPAN_FRAMES = 3

    /** Rises on a peak's slope smaller than this are noise, not the start of another peak. */
    private const val LOBE_TOLERANCE = 0.02
}
