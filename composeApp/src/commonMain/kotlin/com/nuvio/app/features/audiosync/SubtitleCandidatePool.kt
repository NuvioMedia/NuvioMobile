package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AnchorFit
import com.nuvio.app.features.audiosync.asr.HeardWord
import com.nuvio.app.features.audiosync.asr.WordAnchorMatcher
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The other subtitles in the chosen subtitle's language, tested against the same audio evidence so
 * a file that fits the audio can replace one that never will (another cut, another frame rate, a
 * badly timed rip).
 *
 * Cheapest checks first:
 * 1. on arrival, copies of the chosen file or of each other (same timing) are dropped, and so are
 *    files whose length cannot fit the video at any common frame rate;
 * 2. every [update] scores a few remaining files in turn against one shared transform of the
 *    detected speech; an inconclusive score keeps the file eligible for later evidence;
 * 3. once recognised words pin an English reference to the audio, the files are aligned to that
 *    pinned reference, which settles translated files without waiting for more audio.
 * A file wins when it meets the same confirmation rules as a normal lock. The caller only asks while
 * the chosen subtitle's own tracker has not locked; when the pinned reference shows the chosen
 * subtitle fits, the search stops.
 *
 * [addCandidate] and [addReference] may be called from any thread; [update] from one thread.
 */
internal class SubtitleCandidatePool(
    private val target: SubtitleSpeechTrack,
    private val log: (String) -> Unit = {},
) {
    /** [chosen]: the mapping is for the chosen subtitle itself (it fits; no switch needed). */
    class Winner(val key: String, val model: SubtitleSyncModel, val method: String, val chosen: Boolean = false)

    private class Candidate(val key: String, val track: SubtitleSpeechTrack) {
        /** Frame-rate ratios that fit the video's length; null until the length is known. */
        var scales: DoubleArray? = null
    }

    private class Reference(val key: String, val track: SubtitleSpeechTrack, val matcher: WordAnchorMatcher)

    private val lock = SynchronizedObject()
    private val active = ArrayList<Candidate>()
    private val references = ArrayList<Reference>()
    private val fingerprints = hashSetOf(fingerprint(target))
    private var arrived = 0
    private var ruledOut = 0
    private var dirty = false

    /** The chosen subtitle already fits; nothing left to do. */
    @Volatile
    var finished = false
        private set

    @Volatile
    var mediaDurationMs = 0L

    private var lastUpdateAtMs: Long? = null
    private var lastVersion = -1L
    private var lastWordCount = -1
    private var lastPin: String? = null
    private var cursor = 0
    private var scoredInPass = 0

    /** Short progress line, e.g. "testing 4 · 7 ruled out". */
    val summary: String
        get() = synchronized(lock) {
            when {
                arrived == 0 -> ""
                active.isEmpty() -> "none of $arrived fit the audio"
                else -> "testing ${active.size}" + if (ruledOut > 0) " · $ruledOut ruled out" else ""
            }
        }

    /** Adds an alternative subtitle file; false when it was a copy or obviously unusable. */
    fun addCandidate(key: String, cues: List<Triple<Long, Long, String>>): Boolean {
        val track = SubtitleSpeechTrack.fromCues(cues)
        synchronized(lock) {
            if (finished) return false
            arrived++
            val accepted = track.size >= MIN_CUES && fingerprints.add(fingerprint(track))
            if (!accepted) {
                ruledOut++
                return false
            }
            active += Candidate(key, track)
            dirty = true
        }
        return true
    }

    /** Adds an English subtitle that recognised words can be matched against. */
    fun addReference(key: String, cues: List<Triple<Long, Long, String>>) {
        val reference = Reference(key, SubtitleSpeechTrack.fromCues(cues), WordAnchorMatcher(cues))
        if (reference.matcher.isEmpty) return
        synchronized(lock) {
            if (references.none { it.key == key }) {
                references += reference
                dirty = true
            }
        }
    }

    /** True when [update] still has candidates or new evidence to score. */
    fun hasPendingWork(timelineVersion: Long, wordCount: Int): Boolean {
        if (finished) return false
        return synchronized(lock) {
            active.isNotEmpty() && (
                timelineVersion != lastVersion ||
                    wordCount != lastWordCount ||
                    dirty ||
                    scoredInPass < active.size
                )
        }
    }

    /**
     * Scores the candidates on the evidence so far. Returns a candidate that is confirmed to fit the
     * audio, or null. [words] are the recognised words, when recognition runs.
     */
    fun update(timeline: SpeechTimeline, words: List<HeardWord>?, nowMs: Long): Winner? {
        if (finished) return null
        val version = timeline.version
        val wordCount = words?.size ?: 0
        if (!hasPendingWork(version, wordCount)) return null
        lastUpdateAtMs?.let { if (nowMs - it < UPDATE_INTERVAL_MS) return null }
        var versionChanged = false
        var newWords = false
        var wasDirty = false
        val candidates = synchronized(lock) {
            applyShapeCheck()
            val list = active.toList()
            if (list.isEmpty()) return null
            versionChanged = version != lastVersion
            newWords = wordCount != lastWordCount
            wasDirty = dirty
            dirty = false
            lastVersion = version
            lastWordCount = wordCount
            if (versionChanged || wasDirty) scoredInPass = 0
            list
        }
        lastUpdateAtMs = nowMs
        if ((newWords || wasDirty) && !words.isNullOrEmpty()) {
            fromRecognition(words, candidates)?.let { return it }
            if (finished) return null
        }
        return fromSpeech(timeline, candidates)
    }

    /** Drops files that cannot span the video at any common frame rate, once its length is known. */
    private fun applyShapeCheck() {
        val durationMs = mediaDurationMs
        if (durationMs <= 0) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.scales != null) continue
            val lastEndMs = candidate.track.endsMs.max()
            val scales = SubtitleAudioAligner.CANDIDATE_SCALES.filter { scale ->
                val mappedEndMs = lastEndMs * scale
                mappedEndMs <= durationMs + AudioSyncTracker.MAX_SHIFT_MS &&
                    mappedEndMs >= durationMs * MIN_LENGTH_COVERAGE
            }.toDoubleArray()
            if (scales.isEmpty()) {
                iterator.remove()
                ruledOut++
                log("candidate ${candidate.key} ruled out: ends at ${lastEndMs / 1_000}s, video ${durationMs / 1_000}s")
            } else {
                candidate.scales = scales
            }
        }
    }

    /**
     * Speech detection: a few candidates per call (in turn) against one transform of the latest
     * detected speech. The chosen subtitle is left to its own tracker, which searches more audio.
     */
    private fun fromSpeech(timeline: SpeechTimeline, candidates: List<Candidate>): Winner? {
        val segments = timeline.segments(maxFrames = WINDOW_FRAMES)
        val knownFrames = segments.sumOf { it.knownFrames }
        if (knownFrames < MIN_SCORING_FRAMES) {
            synchronized(lock) { scoredInPass = candidates.size }
            return null
        }
        val speech = SubtitleAudioAligner.prepare(
            segments = segments,
            minShiftMs = -AudioSyncTracker.MAX_SHIFT_MS,
            maxShiftMs = AudioSyncTracker.MAX_SHIFT_MS,
        ) ?: run {
            synchronized(lock) { scoredInPass = candidates.size }
            return null
        }

        var best: Pair<Candidate, SubtitleAudioAligner.Estimate>? = null
        val batch = List(minOf(BATCH_SIZE, candidates.size)) { candidates[(cursor + it) % candidates.size] }
        cursor = (cursor + batch.size) % candidates.size
        synchronized(lock) { scoredInPass += batch.size }
        for (candidate in batch) {
            val scales = candidate.scales ?: SubtitleAudioAligner.CANDIDATE_SCALES
            val estimate = speech.estimate(candidate.track, scales)
            if (estimate != null && AudioSyncTracker.isLockable(
                    estimate, segments, knownFrames, candidate.track,
                    AudioSyncTracker.rivalRate(speech, candidate.track, estimate, scales),
                )
            ) {
                if (best == null || estimate.prominence > best.second.prominence) best = candidate to estimate
            }
            // VAD is probabilistic. Music, sparse dialogue, or a different opening can make a
            // correct file look weak now; a later sampled scene may confirm it. Keep every
            // inconclusive candidate and rotate through the bounded batch on future updates.
        }
        best?.let { (candidate, estimate) ->
            log("candidate ${candidate.key} locked by speech detection: $estimate")
            return win(candidate, estimate.scale, estimate.shiftMs, "speech detection")
        }
        return null
    }

    /**
     * Speech recognition: the English reference the heard words agree on is placed on the video's
     * timeline, and each candidate is aligned to it like one subtitle file to another.
     */
    private fun fromRecognition(words: List<HeardWord>, candidates: List<Candidate>): Winner? {
        val refs = synchronized(lock) { references.toList() }
        var pinned: Pair<Reference, AnchorFit>? = null
        for (reference in refs) {
            val fit = reference.matcher.fit(words) ?: continue
            // Placing the reference on the timeline needs its frame rate, known only from a long
            // span of words (or once the words chose another rate).
            if (!fit.isConfident || (fit.scale == 1.0 && fit.spanSec < RATE_SPAN_SEC)) continue
            if (pinned == null || fit.score > pinned.second.score) pinned = reference to fit
        }
        val (reference, fit) = pinned ?: return null
        val pin = "${reference.key}|${fit.scale}|${(fit.shiftSec * 10).roundToInt()}|${candidates.size}"
        if (pin == lastPin) return null
        lastPin = pin

        // The reference's lines on the video's timeline stand in for detected speech.
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val centreSec = words[words.size / 2].timeSec
        val from = ((centreSec * 1_000 - PINNED_WINDOW_MS / 2).coerceAtLeast(0.0) / frameMs).toInt()
        val to = from + (PINNED_WINDOW_MS / frameMs).toInt()
        val shiftFrames = (fit.shiftSec * 1_000 / frameMs).roundToInt()
        val coverage = reference.track.render(from - shiftFrames, to - shiftFrames, fit.scale)
        val probabilities = FloatArray(coverage.size) { coverage[it].toFloat() }
        val pinnedSpeech = SubtitleAudioAligner.prepare(
            probabilities = probabilities,
            fromFrame = from,
            minShiftMs = -AudioSyncTracker.MAX_SHIFT_MS,
            maxShiftMs = AudioSyncTracker.MAX_SHIFT_MS,
            detectorBiasMs = 0.0,
        ) ?: return null

        fun fits(track: SubtitleSpeechTrack, scales: DoubleArray): SubtitleAudioAligner.Estimate? =
            pinnedSpeech.estimate(track, scales)?.takeIf {
                !it.atSearchEdge && it.peak >= PINNED_MIN_PEAK && it.prominence >= PINNED_MIN_PROMINENCE &&
                    it.cueCount >= MIN_CUES
            }
        // The subtitle's own rate first, and never a 0.1% stretch: over the window it moves lines by
        // barely a second, so it can split the difference of a scene the release adds and then drift
        // for the rest of the film. A real 0.1% rate is left to recognition across the whole film.
        (fits(target, doubleArrayOf(1.0)) ?: fits(target, measurableScales(null)))?.let { estimate ->
            // The chosen subtitle lines up with the reference where the words were heard, even if
            // the two files differ elsewhere (so no whole-file bridge was found): sync it with that.
            finish("the chosen subtitle matches the recognised reference: $estimate")
            val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
            return Winner("", model, "speech recognition", chosen = true)
        }
        var best: Pair<Candidate, SubtitleAudioAligner.Estimate>? = null
        for (candidate in candidates) {
            val estimate = fits(candidate.track, measurableScales(candidate.scales)) ?: continue
            if (best == null || estimate.peak > best.second.peak) best = candidate to estimate
        }
        val (candidate, estimate) = best ?: return null
        log("candidate ${candidate.key} locked through reference ${reference.key}: fit=$fit $estimate")
        return win(candidate, estimate.scale, estimate.shiftMs, "speech recognition")
    }

    /** The unstretched rate and the frame-rate conversions large enough to show within the window. */
    private fun measurableScales(scales: DoubleArray?): DoubleArray =
        (scales ?: SubtitleAudioAligner.CANDIDATE_SCALES)
            .filter { it == 1.0 || abs(it - 1.0) > MIN_PINNED_STRETCH }.toDoubleArray()

    private fun win(candidate: Candidate, scale: Double, shiftMs: Double, method: String): Winner {
        finished = true
        return Winner(candidate.key, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, scale, shiftMs))), method)
    }

    private fun finish(reason: String) {
        finished = true
        log("candidate search stopped: $reason")
    }

    companion object {
        private const val MIN_CUES = 20
        private const val UPDATE_INTERVAL_MS = 8_000L
        private val FRAMES_PER_SECOND = 1_000.0 / SpeechTimeline.FRAME_DURATION_MS
        private val MIN_SCORING_FRAMES = (60 * FRAMES_PER_SECOND).toInt()

        /** Known audio candidates are scored on; enough to confirm a lock, half the tracker's cost. */
        private val WINDOW_FRAMES = (10 * 60 * FRAMES_PER_SECOND).toInt()

        /** Candidates scored per call, so the cost per call stays flat however many there are. */
        private const val BATCH_SIZE = 4

        /** A file for this video covers at least this share of it (end credits have no dialogue). */
        private const val MIN_LENGTH_COVERAGE = 0.5
        private const val PINNED_WINDOW_MS = 20 * 60_000.0
        private const val RATE_SPAN_SEC = 180.0
        private const val PINNED_MIN_PEAK = 0.35
        private const val PINNED_MIN_PROMINENCE = 0.15
        private const val MIN_PINNED_STRETCH = 0.01

        /** Identical timing, whatever the text or encoding, means the same file. */
        private fun fingerprint(track: SubtitleSpeechTrack): Long {
            var hash = track.size.toLong()
            for (i in 0 until track.size) hash = hash * 31 + track.startsMs[i] / 100
            return hash
        }

        private val bibliographic = mapOf(
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
            "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
            "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "mol" to "ron",
            "slo" to "slk", "tib" to "bod", "wel" to "cym", "scc" to "srp", "scr" to "hrv",
        )

        /** Subtitle site codes that name a regional variant. */
        private val variantCodes = mapOf(
            "pob" to "por-BR", "pb" to "por-BR",
            "spl" to "spa-419", "ea" to "spa-419", "spn" to "spa", "sp" to "spa",
            "zht" to "zho-Hant", "zhs" to "zho",
        )
        private val brazilian = setOf("br", "bra", "brazil", "brasil", "brazilian", "brasileiro")
        private val latinAmerican = setOf("419", "la", "lat", "latam", "latin", "latino", "latinoamericano", "mx")
        private val traditionalChinese = setOf("hant", "tw", "hk", "mo", "taiwan", "hong", "traditional", "cht")
        private val simplifiedChinese = setOf("hans", "cn", "simplified")

        /**
         * ISO 639-2/T code for a language code or English name ("ro", "rum", "Romanian" -> "ron").
         * Brazilian Portuguese, Latin American Spanish and Traditional Chinese are separate
         * translations and keep a suffix ("pt-BR" -> "por-BR", "es-419" -> "spa-419",
         * "zh-Hant" -> "zho-Hant"). Without a region, these languages count as the European and
         * Simplified variants.
         */
        fun languageKey(language: String?): String? {
            val value = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val words = value.split('-', '_', ' ', '(', ')', ',', '/').filter { it.isNotEmpty() }
            val code = words.firstOrNull() ?: return value
            variantCodes[code]?.let { return it }
            val base = baseLanguageKey(value, code)
            val variant = when (base) {
                "por" -> "BR".takeIf { words.any { it in brazilian } }
                "spa" -> "419".takeIf { words.any { it in latinAmerican } }
                "zho" -> "Hant".takeIf { words.any { it in traditionalChinese } && words.none { it in simplifiedChinese } }
                else -> null
            }
            return if (variant == null) base else "$base-$variant"
        }

        private fun baseLanguageKey(value: String, code: String): String {
            bibliographic[code]?.let { return it }
            audioSyncIso3Language(value, code)?.let { iso3 ->
                return bibliographic[iso3] ?: iso3
            }
            return value
        }
    }
}
