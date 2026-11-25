package com.nuvio.app.features.audiosync

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.atomicfu.atomic

/** Piecewise mapping media time = subtitle time * scale + shift, each piece starting at [fromMediaMs]. */
internal data class SubtitleSyncSegment(
    val fromMediaMs: Long,
    val scale: Double,
    val shiftMs: Double,
)

internal class SubtitleSyncModel(val segments: List<SubtitleSyncSegment>) {
    init {
        require(segments.isNotEmpty())
    }

    fun segmentAt(positionMs: Long): SubtitleSyncSegment =
        segments.lastOrNull { it.fromMediaMs <= positionMs } ?: segments.first()

    /** Delay that makes the subtitle renderer show subtitle time (position - delay) at [positionUs]. */
    fun delayUsAt(positionUs: Long): Long {
        val segment = segmentAt(positionUs / 1_000L)
        val subtitleTimeUs = (positionUs - segment.shiftMs * 1_000.0) / segment.scale
        return (positionUs - subtitleTimeUs).roundToLong()
    }

    /**
     * Where subtitle time [subtitleUs] plays on the media timeline: the first segment that places it
     * inside its own span, as [delayUsAt] would show it. Null for a line of a scene the release
     * cuts (no segment places it in its span), which [delayUsAt] never shows either.
     */
    fun mediaTimeUs(subtitleUs: Long): Long? {
        for (index in segments.indices) {
            val segment = segments[index]
            val mediaUs = (subtitleUs * segment.scale + segment.shiftMs * 1_000.0).roundToLong()
            val fromUs = if (index == 0) Long.MIN_VALUE else segment.fromMediaMs * 1_000L
            val untilUs = segments.getOrNull(index + 1)?.fromMediaMs?.times(1_000L) ?: Long.MAX_VALUE
            if (mediaUs in fromUs until untilUs) return mediaUs
        }
        return null
    }

    override fun toString(): String = segments.joinToString(prefix = "[", postfix = "]") {
        "from=${it.fromMediaMs}ms scale=${it.scale.toFixed(5)} shift=${it.shiftMs.roundToLong()}ms"
    }
}

/**
 * Decides when the audio evidence is strong enough to move subtitles, then keeps refining the
 * result and watches for a later jump (a subtitle made for a different cut).
 *
 * Pure logic so it can be replayed off-device; [update] is called periodically from a background
 * thread with the growing [SpeechTimeline].
 */
internal class AudioSyncTracker(
    private val track: SubtitleSpeechTrack,
    private val provisionalPolicy: ProvisionalPolicy = ProvisionalPolicy(),
) {
    /**
     * Thresholds for the early, unconfirmed estimate shown while evidence is still thin. Looser
     * than the lock, but it must repeat on two consecutive runs before it is applied.
     */
    data class ProvisionalPolicy(
        val enabled: Boolean = true,
        val minKnownSeconds: Double = 20.0,
        val minCues: Int = 8,
        val minSpeechSeconds: Double = 5.0,
        val minProminence: Double = 0.15,
        val agreementMs: Double = 300.0,
        val minMovementMs: Double = 120.0,
        /** Consecutive runs that must agree before the estimate is applied. */
        val requiredRepeats: Int = 3,
        /**
         * Frame-rate mismatches are rare and easy to confuse on little evidence, so early estimates
         * assume the normal rate; the confirmed lock still detects them.
         */
        val unitScaleOnly: Boolean = true,
        /**
         * Runs a few seconds apart share almost all their audio, so agreement alone proves little.
         * The agreeing streak must also span at least this much newly analysed audio.
         */
        val minEvidenceGrowthSeconds: Double = 20.0,
        /**
         * Most subtitle offsets are small. When the best match lies within this range, far fewer
         * offsets could have matched by chance, so the looser "near" thresholds below apply and
         * subtitles can move after only a few lines of dialogue.
         */
        val nearRangeMs: Double = 8_000.0,
        val nearMinCues: Int = 5,
        val nearMinProminence: Double = 0.08,
        val nearRequiredRepeats: Int = 2,
        val nearMinEvidenceGrowthSeconds: Double = 10.0,
        /**
         * An early estimate may use a frame-rate correction only when it beats the normal rate by
         * this much correlation; negative disables it.
         */
        val nearRateMargin: Double = -1.0,
        /**
         * When the full search's best match uses another frame rate and beats the normal rate by
         * this much correlation, no normal-rate early estimate is shown: it would put most of the
         * film's lines in the wrong place. Negative disables it. Measured on 34 releases: with 0,
         * timings more than 1 s off were on screen for 15 s instead of 126 s, and early estimates on
         * subtitles of other films dropped from 6 to 1, with no fewer files fixed.
         */
        val rateVetoMargin: Double = 0.0,
    )

    /** Confirmed mapping, or null while still searching. */
    var model: SubtitleSyncModel? = null
        private set

    /** Best unconfirmed mapping so far; superseded by [model] once confirmed. */
    var provisionalModel: SubtitleSyncModel? = null
        private set

    private var previousCandidate: SubtitleAudioAligner.Estimate? = null
    private var agreeingRuns = 0
    private var disagreeingRuns = 0

    /** After a retraction only a confirmed lock may move subtitles again. */
    private var provisionalBlocked = false
    private var streakStartAnalysedSeconds = 0.0

    @Volatile
    var lastEstimate: SubtitleAudioAligner.Estimate? = null
        private set

    /** Why speech detection has not confirmed a mapping; independent of English recognition. */
    @Volatile
    var waitingReason: String = "Waiting for analysed audio"
        private set

    private var followRuns = 0
    private var pendingJump: SubtitleAudioAligner.Estimate? = null

    /**
     * Whether the audio near the playhead has matched the active mapping since it was locked. A
     * lock can come from sampled spots far from the playhead; until playback hears it agree, an
     * offset change near the playhead is more likely a wrong lock than a scene the release cut.
     */
    private var liveConfirmed = false

    /** Known audio when the lock was last re-checked against everything heard. */
    private var revalidatedFrames = 0

    /** Known audio and playhead at the last full search, to skip searches that would see almost nothing new. */
    private var searchedFrames = Int.MIN_VALUE / 2
    private var searchedPlayheadFrame = 0

    sealed interface Outcome {
        data object NotEnoughEvidence : Outcome
        data class Searching(val estimate: SubtitleAudioAligner.Estimate?) : Outcome
        data class Provisional(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome

        /** The early estimate lost its support and was withdrawn; subtitles go back to file timing. */
        data class Retracted(val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Locked(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Refined(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Jumped(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Unchanged(val estimate: SubtitleAudioAligner.Estimate?) : Outcome
    }

    /**
     * Takes a mapping found elsewhere (speech recognition, a remembered result) as the confirmed
     * lock; from here on this tracker only refines it and watches for jumps.
     */
    fun adopt(adopted: SubtitleSyncModel) {
        model = adopted
        provisionalModel = null
        pendingJump = null
        // Found by recognition or remembered: trusted as if playback had confirmed it.
        liveConfirmed = true
        agreeingRuns = 0
        disagreeingRuns = 0
    }

    fun update(timeline: SpeechTimeline, playbackPositionMs: Long): Outcome {
        val known = timeline.knownRange() ?: run {
            waitingReason = "Waiting for analysed audio"
            return Outcome.NotEnoughEvidence
        }
        val current = model
        return if (current == null) search(timeline, playbackPositionMs) else follow(timeline, known, current, playbackPositionMs)
    }

    private fun search(timeline: SpeechTimeline, playbackPositionMs: Long): Outcome {
        // All known audio up to the analysis budget: the look-ahead buffer and any sampled spots.
        val segments = timeline.segments(maxFrames = MAX_ANALYSIS_FRAMES)
        val knownFrames = segments.sumOf { it.knownFrames }
        val provisionalFrames = (provisionalPolicy.minKnownSeconds * FRAMES_PER_SECOND).toInt()
        val minFrames = if (provisionalPolicy.enabled) minOf(MIN_KNOWN_FRAMES, provisionalFrames) else MIN_KNOWN_FRAMES
        if (knownFrames < minFrames) {
            waitingReason = "Collecting audio (need ${(minFrames / FRAMES_PER_SECOND).toInt()}s for a first estimate)"
            return Outcome.NotEnoughEvidence
        }
        // Over long known audio a search costs the most, and a subtitle that clearly doesn't match
        // (correct ones score at least 0.23 here) won't lock after a few more seconds of audio.
        val playheadFrame = (playbackPositionMs / SpeechTimeline.FRAME_DURATION_MS).toInt()
        if (knownFrames - searchedFrames < SEARCH_MIN_NEW_FRAMES && knownFrames >= SEARCH_SKIP_FROM_FRAMES &&
            (lastEstimate?.peak ?: 1.0) < SEARCH_SKIP_MAX_PEAK &&
            abs(playheadFrame - searchedPlayheadFrame) < SEARCH_MIN_NEW_FRAMES
        ) {
            return Outcome.Searching(lastEstimate)
        }
        searchedFrames = knownFrames
        searchedPlayheadFrame = playheadFrame
        // One transform of the speech serves every check below; each ratio is correlated once.
        val speech = SubtitleAudioAligner.prepare(segments, -MAX_SHIFT_MS, MAX_SHIFT_MS)
            ?: run {
                waitingReason = "Speech activity is too constant to compare (silence, music or flat detector output)"
                lastEstimate = null
                return Outcome.NotEnoughEvidence
            }
        val estimate = speech.estimate(track)?.let { stretchedWhenClear(speech, segments, it) } ?: run {
            waitingReason = "No usable subtitle overlap within the 60s offset search"
            lastEstimate = null
            return Outcome.NotEnoughEvidence
        }
        lastEstimate = estimate
        val blocker = lockBlocker(estimate, segments, knownFrames, track, rivalRate(speech, track, estimate))
        if (blocker == null) {
            waitingReason = ""
            val locked = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
            model = locked
            provisionalModel = null
            liveConfirmed = false
            revalidatedFrames = knownFrames
            return Outcome.Locked(locked, estimate)
        }
        localLock(timeline, segments, playbackPositionMs)?.let { return it }
        waitingReason = blocker
        val candidate = if (!provisionalPolicy.unitScaleOnly || estimate.scale == 1.0) {
            estimate
        } else {
            speech.estimate(track, doubleArrayOf(1.0)) ?: return Outcome.Searching(estimate)
        }
        val nearUnit = speech.estimate(
            track = track,
            scales = doubleArrayOf(1.0),
            minShiftMs = -provisionalPolicy.nearRangeMs,
            maxShiftMs = provisionalPolicy.nearRangeMs,
        )
        val nearRated = if (provisionalPolicy.nearRateMargin >= 0 && nearUnit != null) {
            speech.estimate(
                track = track,
                scales = NON_UNIT_SCALES,
                minShiftMs = -provisionalPolicy.nearRangeMs,
                maxShiftMs = provisionalPolicy.nearRangeMs,
            )?.takeIf { !it.atSearchEdge && it.peak >= nearUnit.peak + provisionalPolicy.nearRateMargin }
        } else {
            null
        }
        val near = nearRated ?: nearUnit
        val useNear = near != null && !near.atSearchEdge &&
            (abs(candidate.shiftMs) <= provisionalPolicy.nearRangeMs || near.peak >= candidate.peak - NEAR_PEAK_TOLERANCE)
        val best = if (useNear) near!! else candidate
        if (provisionalPolicy.rateVetoMargin >= 0 && best.scale == 1.0 && estimate.scale != 1.0 &&
            estimate.peak >= best.peak + provisionalPolicy.rateVetoMargin
        ) {
            retraction(estimate, estimate)?.let { return it }
            return Outcome.Searching(estimate)
        }
        retraction(best, estimate)?.let { return it }
        if (provisionalBlocked) return Outcome.Searching(estimate)
        return provisional(best, near = useNear) ?: Outcome.Searching(estimate)
    }

    /**
     * The plain rate is preferred over its 0.1% neighbours (23.976 vs 24 fps) unless one fits
     * clearly better, since over a few minutes they score almost alike. A subtitle that does need
     * the stretch drifts by seconds over a film when locked at 1.0. Take the neighbour when it beats
     * 1.0 on overlap by [STRETCH_OVERLAP_MARGIN] and line starts agree, preferring it by
     * [STRETCH_STARTS_MARGIN]: on 209 subtitles in this rate family that moved 5 to the right rate
     * and none away from it.
     */
    private fun stretchedWhenClear(
        speech: SubtitleAudioAligner.PreparedSpeech,
        segments: List<SpeechSegment>,
        estimate: SubtitleAudioAligner.Estimate,
    ): SubtitleAudioAligner.Estimate {
        RATE_23976_FOR[estimate.scale]?.let { return palRate(speech, segments, estimate, it) }
        if (estimate.scale != 1.0) return estimate
        val neighbour = speech.estimate(track, doubleArrayOf(NEAR_RATE, 1.0 / NEAR_RATE)) ?: return estimate
        if (neighbour.atSearchEdge || neighbour.peak < estimate.peak + STRETCH_OVERLAP_MARGIN) return estimate
        val starts = preparedStarts(segments)
            ?: return estimate
        val plainStarts = starts.estimate(track.starts, doubleArrayOf(1.0)) ?: return estimate
        val stretchedStarts = starts.estimate(track.starts, doubleArrayOf(neighbour.scale)) ?: return estimate
        return if (stretchedStarts.peak >= plainStarts.peak + STRETCH_STARTS_MARGIN) neighbour else estimate
    }

    /**
     * 25 fps subtitles on a 23.976 fps release (or the reverse) are far more common than 25 vs 24,
     * yet over a few minutes 25/24 and 25/23.976 score almost alike. Keep the 24-based rate only when
     * it beats the 23.976-based one on overlap by [STRETCH_OVERLAP_MARGIN] and line starts don't
     * prefer the 23.976 one by more than [STRETCH_STARTS_MARGIN]: on 138 subtitles in these rate
     * families that moved 10 to the right rate and none away from it.
     */
    private fun palRate(
        speech: SubtitleAudioAligner.PreparedSpeech,
        segments: List<SpeechSegment>,
        estimate: SubtitleAudioAligner.Estimate,
        rate23976: Double,
    ): SubtitleAudioAligner.Estimate {
        val neighbour = speech.estimate(track, doubleArrayOf(rate23976)) ?: return estimate
        if (neighbour.atSearchEdge) return estimate
        if (estimate.peak < neighbour.peak + STRETCH_OVERLAP_MARGIN) return neighbour
        val starts = preparedStarts(segments)
            ?: return estimate
        val baseStarts = starts.estimate(track.starts, doubleArrayOf(estimate.scale)) ?: return estimate
        val neighbourStarts = starts.estimate(track.starts, doubleArrayOf(rate23976)) ?: return estimate
        return if (neighbourStarts.peak > baseStarts.peak + STRETCH_STARTS_MARGIN) neighbour else estimate
    }

    /**
     * A subtitle made for another cut of the film follows different offsets in different parts, so
     * sampled spots far from the playhead and the audio around it disagree and no whole-film lock
     * passes. What is on screen is the part being played: lock on the audio around the playhead
     * alone when it passes every lock check by itself. Later parts are then followed as playback
     * reaches them (see [follow]). Only tried when the known audio reaches beyond that stretch,
     * only with line starts agreeing (see [startsAgree]) and only when the frame rate clearly beats
     * its 0.1% neighbour.
     */
    private fun localLock(
        timeline: SpeechTimeline,
        all: List<SpeechSegment>,
        playbackPositionMs: Long,
    ): Outcome.Locked? {
        val playheadFrame = (playbackPositionMs / SpeechTimeline.FRAME_DURATION_MS).toInt()
        val from = (playheadFrame - LOCAL_BACK_FRAMES).coerceAtLeast(0)
        val to = playheadFrame + LOCAL_AHEAD_FRAMES
        val local = timeline.segments(fromFrame = from, toFrame = to, maxFrames = MAX_ANALYSIS_FRAMES)
        val localFrames = local.sumOf { it.knownFrames }
        if (localFrames < MIN_LOCAL_FRAMES || localFrames >= all.sumOf { it.knownFrames }) return null
        val speech = SubtitleAudioAligner.prepare(local, -MAX_SHIFT_MS, MAX_SHIFT_MS) ?: return null
        val estimate = speech.estimate(track) ?: return null
        if (lockBlocker(estimate, local, localFrames, track, rivalRate(speech, track, estimate)) != null) return null
        // A few minutes can't tell a rate from its 0.1% neighbour (23.976 vs 24, or 25/24 vs
        // 25/23.976): over a whole film that is seconds of drift. Wait until the rate is clear.
        val neighbour = speech.estimate(track, doubleArrayOf(estimate.scale * NEAR_RATE, estimate.scale / NEAR_RATE))
        if (neighbour != null && neighbour.peak > estimate.peak - LOCAL_RATE_MARGIN) return null
        // A few minutes of audio can match an unrelated subtitle by chance; line starts and overlap
        // agreeing on the offset almost never do, so a local lock always needs both.
        val penalty = (if (estimate.scale != 1.0) NON_STANDARD_RATE_PENALTY else 0.0) +
            (if (abs(estimate.shiftMs) > NEAR_SHIFT_MS) FAR_SHIFT_PENALTY else 0.0)
        if (!startsAgree(local, estimate, track, penalty)) return null
        val locked = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
        model = locked
        lastEstimate = estimate
        provisionalModel = null
        waitingReason = ""
        // Heard around the playhead itself: a later offset change there is a cut, not a wrong lock.
        liveConfirmed = true
        revalidatedFrames = timeline.knownFrameCount()
        return Outcome.Locked(locked, estimate)
    }

    /**
     * Applies the current best guess early when it repeats on consecutive runs, so subtitles move
     * within the first minute instead of waiting for the full confirmation. It keeps being updated
     * as evidence grows and is replaced by the confirmed lock.
     */
    private fun provisional(estimate: SubtitleAudioAligner.Estimate, near: Boolean): Outcome? {
        val policy = provisionalPolicy
        val previous = previousCandidate
        previousCandidate = estimate
        if (!policy.enabled) return null
        val minProminence = if (near) policy.nearMinProminence else policy.minProminence
        val requiredProminence = if (isStandardRate(estimate.scale)) {
            minProminence
        } else {
            minProminence + NON_STANDARD_RATE_PENALTY
        }
        val credible = !estimate.atSearchEdge &&
            estimate.cueCount >= (if (near) policy.nearMinCues else policy.minCues) &&
            estimate.speechSeconds >= policy.minSpeechSeconds &&
            estimate.prominence >= requiredProminence
        val agrees = previous != null && previous.scale == estimate.scale &&
            abs(previous.shiftMs - estimate.shiftMs) <= policy.agreementMs
        agreeingRuns = when {
            !credible -> 0
            agrees -> agreeingRuns + 1
            else -> 1
        }
        if (agreeingRuns == 1) streakStartAnalysedSeconds = estimate.analysedSeconds
        val repeats = if (near) policy.nearRequiredRepeats else policy.requiredRepeats
        val growth = if (near) policy.nearMinEvidenceGrowthSeconds else policy.minEvidenceGrowthSeconds
        if (agreeingRuns < repeats) return null
        if (estimate.analysedSeconds - streakStartAnalysedSeconds < growth) return null
        val current = provisionalModel?.segments?.first()
        if (current != null && current.scale == estimate.scale &&
            abs(current.shiftMs - estimate.shiftMs) < policy.minMovementMs
        ) {
            return null
        }
        val updated = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
        provisionalModel = updated
        return Outcome.Provisional(updated, estimate)
    }

    /**
     * A real match is confirmed after roughly 20-25 dialogue lines. An early estimate that is still
     * unconfirmed well past that, or that newer audio keeps contradicting, was most likely chance:
     * withdraw it rather than leave subtitles at a wrong offset.
     */
    private fun retraction(
        best: SubtitleAudioAligner.Estimate,
        global: SubtitleAudioAligner.Estimate,
    ): Outcome? {
        val applied = provisionalModel?.segments?.first() ?: return null
        disagreeingRuns = if (abs(best.shiftMs - applied.shiftMs) > RETRACT_DISAGREEMENT_MS) disagreeingRuns + 1 else 0
        val stale = global.cueCount >= RETRACT_AFTER_CUES
        if (!stale && disagreeingRuns < RETRACT_DISAGREEING_RUNS) return null
        provisionalModel = null
        provisionalBlocked = stale
        agreeingRuns = 0
        disagreeingRuns = 0
        return Outcome.Retracted(best)
    }

    private fun follow(
        timeline: SpeechTimeline,
        known: IntRange,
        current: SubtitleSyncModel,
        playbackPositionMs: Long,
    ): Outcome {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val segment = current.segments.last()
        val segmentStartFrame = (segment.fromMediaMs / frameMs).toInt()
        val to = known.last + 1

        // 1. Look for a jump near the playhead (including buffered audio ahead of it, but not spots
        //    sampled far away).
        val playheadFrame = (playbackPositionMs / frameMs).toInt()
        val localFrom = maxOf(known.first, segmentStartFrame, playheadFrame - LOCAL_BACK_FRAMES)
        val localTo = minOf(to, playheadFrame + LOCAL_AHEAD_FRAMES)
        if (localTo - localFrom >= MIN_LOCAL_FRAMES && timeline.knownFramesIn(localFrom, localTo) >= MIN_LOCAL_FRAMES) {
            val local = SubtitleAudioAligner.estimate(
                probabilities = timeline.snapshot(localFrom, localTo),
                fromFrame = localFrom,
                track = track,
                scales = doubleArrayOf(segment.scale),
                minShiftMs = -MAX_SHIFT_MS,
                maxShiftMs = MAX_SHIFT_MS,
            )
            if (local != null && hasEnoughEvidence(local, minCues = MIN_LOCAL_CUES) && !local.atSearchEdge &&
                abs(local.shiftMs - segment.shiftMs) <= STABLE_SHIFT_TOLERANCE_MS && local.separation >= ACCEPT_SEPARATION
            ) {
                liveConfirmed = true
            }
            if (local != null && hasEnoughEvidence(local, minCues = MIN_LOCAL_CUES) && !local.atSearchEdge &&
                abs(local.shiftMs - segment.shiftMs) > JUMP_THRESHOLD_MS &&
                local.prominence >= ACCEPT_PROMINENCE
            ) {
                val pending = pendingJump
                pendingJump = local
                if (pending != null && abs(pending.shiftMs - local.shiftMs) <= STABLE_SHIFT_TOLERANCE_MS) {
                    pendingJump = null
                    // Playback never heard the old mapping agree: it came from far-away spots and was
                    // most likely wrong, so the new offset replaces it (scale included) rather than
                    // adding a step that keeps the wrong one for the start of the film.
                    if (!liveConfirmed && current.segments.size == 1) {
                        return relock(timeline, local, segment.scale)
                    }
                    val split = findChangePoint(timeline, localFrom, localTo, segment, local.shiftMs)
                    if (split != null) {
                        val jumped = SubtitleSyncModel(
                            current.segments + SubtitleSyncSegment(split, segment.scale, local.shiftMs),
                        )
                        model = jumped
                        lastEstimate = local
                        return Outcome.Jumped(jumped, local)
                    }
                }
                return Outcome.Unchanged(local)
            }
            pendingJump = null
        }

        // 2. Re-check a single-segment lock against everything heard since: if the audio now clearly
        //    supports a different mapping, the lock was made on too little evidence.
        if (current.segments.size == 1) revalidate(timeline, segment)?.let { return it }

        // 3. Refine the active segment with everything heard since it started.
        val heard = timeline.segments(fromFrame = segmentStartFrame, maxFrames = MAX_ANALYSIS_FRAMES)
        val heardFrames = heard.sumOf { it.knownFrames }
        if (heardFrames < MIN_KNOWN_FRAMES) return Outcome.Unchanged(null)
        followRuns++
        if (heardFrames >= RATE_CHECK_MIN_FRAMES && followRuns % RATE_CHECK_EVERY_RUNS == 0) {
            rateCorrection(heard, current, segment)?.let { return it }
        }
        val refined = SubtitleAudioAligner.estimate(
            segments = heard,
            track = track,
            scales = doubleArrayOf(segment.scale),
            minShiftMs = segment.shiftMs - REFINE_RANGE_MS,
            maxShiftMs = segment.shiftMs + REFINE_RANGE_MS,
        ) ?: return Outcome.Unchanged(null)
        lastEstimate = refined
        if (refined.prominence < REFINE_MIN_PROMINENCE || !hasEnoughEvidence(refined)) {
            return Outcome.Unchanged(refined)
        }
        val movement = abs(refined.shiftMs - segment.shiftMs)
        if (movement < REFINE_MIN_MOVEMENT_MS || movement > REFINE_RANGE_MS - 2 * frameMs) {
            return Outcome.Unchanged(refined)
        }
        val updated = SubtitleSyncModel(current.segments.dropLast(1) + segment.copy(shiftMs = refined.shiftMs))
        model = updated
        return Outcome.Refined(updated, refined)
    }

    /**
     * Replaces the whole mapping with one around [local]'s offset: the rate is chosen again on all
     * the audio heard (the wrong lock's rate is not trusted), with the plain rate preferred.
     */
    private fun relock(timeline: SpeechTimeline, local: SubtitleAudioAligner.Estimate, oldScale: Double): Outcome {
        val heard = timeline.segments(maxFrames = MAX_ANALYSIS_FRAMES)
        val scales = if (oldScale == 1.0) doubleArrayOf(1.0) else doubleArrayOf(1.0, oldScale)
        // The edge margin is added so the whole ±REFINE_RANGE_MS around [local] counts, not only its centre.
        val reach = REFINE_RANGE_MS + SubtitleAudioAligner.EDGE_MARGIN_MS
        val refined = SubtitleAudioAligner.prepare(heard, -MAX_SHIFT_MS, MAX_SHIFT_MS)
            ?.estimate(track, scales, local.shiftMs - reach, local.shiftMs + reach)
            ?.takeIf { !it.atSearchEdge }
            ?: local
        val relocked = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, refined.scale, refined.shiftMs)))
        model = relocked
        lastEstimate = refined
        liveConfirmed = true
        revalidatedFrames = timeline.knownFrameCount()
        return Outcome.Jumped(relocked, refined)
    }

    /**
     * Every [REVALIDATE_FRAMES] of new audio, searches everything heard as if no lock existed. When
     * that search would itself lock, and on a mapping more than [RELOCK_MIN_MS] away from the
     * current one, the current lock was wrong: switch to it.
     */
    private fun revalidate(timeline: SpeechTimeline, segment: SubtitleSyncSegment): Outcome? {
        val knownFrames = timeline.knownFrameCount()
        if (knownFrames - revalidatedFrames < REVALIDATE_FRAMES) return null
        revalidatedFrames = knownFrames
        val heard = timeline.segments(maxFrames = MAX_ANALYSIS_FRAMES)
        val heardFrames = heard.sumOf { it.knownFrames }
        val speech = SubtitleAudioAligner.prepare(heard, -MAX_SHIFT_MS, MAX_SHIFT_MS) ?: return null
        if (heardFrames >= SUPPORT_MIN_FRAMES) {
            val support = speech.estimate(
                track, doubleArrayOf(segment.scale), segment.shiftMs - SUPPORT_WINDOW_MS, segment.shiftMs + SUPPORT_WINDOW_MS,
            )
            if (support != null && support.peak < MIN_LOCK_SUPPORT) return withdrawLock(support)
        }
        val fresh = speech.estimate(track)?.let { stretchedWhenClear(speech, heard, it) } ?: return null
        if (lockBlocker(fresh, heard, heardFrames, track, rivalRate(speech, track, fresh)) != null) return null
        // Compare where the two mappings put a line in the middle of what was heard.
        val midMs = heard[heard.size / 2].let { (it.fromFrame + it.probabilities.size / 2) * SpeechTimeline.FRAME_DURATION_MS }
        val subtitleMs = (midMs - segment.shiftMs) / segment.scale
        val apart = abs(subtitleMs * fresh.scale + fresh.shiftMs - midMs)
        if (apart <= RELOCK_MIN_MS) return null
        val relocked = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, fresh.scale, fresh.shiftMs)))
        model = relocked
        lastEstimate = fresh
        liveConfirmed = false
        return Outcome.Jumped(relocked, fresh)
    }

    /**
     * The lock's own mapping barely matches speech over minutes of audio: every correct subtitle
     * tested scores well above [MIN_LOCK_SUPPORT] there, so the lock was chance (seen on subtitles
     * from other films). Subtitles go back to file timing and the search starts again.
     */
    private fun withdrawLock(support: SubtitleAudioAligner.Estimate): Outcome {
        model = null
        provisionalModel = null
        provisionalBlocked = true
        previousCandidate = null
        pendingJump = null
        liveConfirmed = false
        agreeingRuns = 0
        disagreeingRuns = 0
        lastEstimate = support
        return Outcome.Retracted(support)
    }

    /**
     * Over a long stretch a 0.1% rate difference (23.976 vs 24 fps) drifts by seconds, but it is
     * indistinguishable from 1.0 in the first minutes. Re-test the neighbouring ratios once enough
     * audio is in and switch when one clearly fits better.
     */
    private fun rateCorrection(
        heard: List<SpeechSegment>,
        current: SubtitleSyncModel,
        segment: SubtitleSyncSegment,
    ): Outcome? {
        val speech = SubtitleAudioAligner.prepare(heard, -MAX_SHIFT_MS, MAX_SHIFT_MS) ?: return null
        val here = speech.estimate(track, doubleArrayOf(segment.scale)) ?: return null
        val neighbour = speech.estimate(track, doubleArrayOf(segment.scale * NEAR_RATE, segment.scale / NEAR_RATE))
            ?: return null
        if (neighbour.atSearchEdge || neighbour.peak < here.peak + RATE_SWITCH_MARGIN) return null
        val updated = SubtitleSyncModel(
            current.segments.dropLast(1) + segment.copy(scale = neighbour.scale, shiftMs = neighbour.shiftMs),
        )
        model = updated
        lastEstimate = neighbour
        return Outcome.Refined(updated, neighbour)
    }

    /** Media time from which [newShiftMs] explains the speech better than the active segment. */
    private fun findChangePoint(
        timeline: SpeechTimeline,
        from: Int,
        to: Int,
        segment: SubtitleSyncSegment,
        newShiftMs: Double,
    ): Long? = changePoint(timeline, track, from, to, segment.scale, segment.shiftMs, newShiftMs)
        ?.coerceAtLeast(segment.fromMediaMs + MIN_SEGMENT_MS)

    companion object {
        /**
         * Media time within frames [from, to) where the subtitle stops following [oldShiftMs] and
         * starts following [newShiftMs] (same [scale]): the split that maximises the speech evidence
         * gained by switching. Null when that stretch holds no known audio.
         */
        fun changePoint(
            timeline: SpeechTimeline,
            track: SubtitleSpeechTrack,
            from: Int,
            to: Int,
            scale: Double,
            oldShiftMs: Double,
            newShiftMs: Double,
        ): Long? {
            val frameMs = SpeechTimeline.FRAME_DURATION_MS
            val probabilities = timeline.snapshot(from, to)
            var sum = 0.0
            var count = 0
            for (p in probabilities) if (!p.isNaN()) {
                sum += p
                count++
            }
            if (count == 0) return null
            val mean = sum / count
            val biasFrames = SubtitleAudioAligner.DETECTOR_BIAS_MS / frameMs
            val oldLag = ((oldShiftMs / frameMs) + biasFrames).roundToLong().toInt()
            val newLag = ((newShiftMs / frameMs) + biasFrames).roundToLong().toInt()
            val oldTrack = track.render(from - oldLag, to - oldLag, scale)
            val newTrack = track.render(from - newLag, to - newLag, scale)
            // Best split maximises the suffix sum of (new - old) agreement.
            var suffix = 0.0
            var bestSuffix = 0.0
            var bestIndex = -1
            for (i in probabilities.indices.reversed()) {
                val p = probabilities[i]
                if (!p.isNaN()) suffix += (p - mean) * (newTrack[i] - oldTrack[i])
                if (suffix > bestSuffix) {
                    bestSuffix = suffix
                    bestIndex = i
                }
            }
            if (bestIndex < 0) return null
            return ((from + bestIndex) * frameMs).roundToLong()
        }

        /**
         * Whether [estimate] (from [segments] of speech, [knownFrames] of them known)
         * is strong enough to confirm [track]'s mapping: enough dialogue and speech, a clear peak,
         * and the same answer on both halves of the audio. [rival] is [rivalRate] for it.
         */
        fun isLockable(
            estimate: SubtitleAudioAligner.Estimate,
            segments: List<SpeechSegment>,
            knownFrames: Int,
            track: SubtitleSpeechTrack,
            rival: SubtitleAudioAligner.Estimate? = null,
        ): Boolean = lockBlocker(estimate, segments, knownFrames, track, rival) == null

        /**
         * Best match of [track] at a rate of another family than [estimate]'s (1.0 and its 0.1%
         * neighbours, 25 fps over 24 or 23.976, or the reverse), among [scales]. When most of the
         * audio is one stretch, a 4% rate error is absorbed by the shift and can score like the
         * right rate: on a translated subtitle such a lock put lines a minute off. Null when no
         * other family is searched or its best match sits at the search edge.
         */
        fun rivalRate(
            speech: SubtitleAudioAligner.PreparedSpeech,
            track: SubtitleSpeechTrack,
            estimate: SubtitleAudioAligner.Estimate,
            scales: DoubleArray = SubtitleAudioAligner.CANDIDATE_SCALES,
        ): SubtitleAudioAligner.Estimate? = scales
            .filter { rateFamily(it) != rateFamily(estimate.scale) }
            .mapNotNull { speech.estimate(track, doubleArrayOf(it))?.takeIf { rival -> !rival.atSearchEdge } }
            .maxByOrNull { it.peak }

        /** 0 for 1.0 and its 0.1% neighbours, +1 / -1 for the PAL speed-up / slow-down rates. */
        private fun rateFamily(scale: Double): Int = when {
            scale > 1.02 -> 1
            scale < 0.98 -> -1
            else -> 0
        }

        private fun lockBlocker(
            estimate: SubtitleAudioAligner.Estimate,
            segments: List<SpeechSegment>,
            knownFrames: Int,
            track: SubtitleSpeechTrack,
            rival: SubtitleAudioAligner.Estimate? = null,
        ): String? {
            // Separation, not prominence: a true match's own slope 2.5 s away must not count as a
            // competitor. Any rate other than 1.0 (including 23.976/24) and offsets beyond the
            // usual few seconds are less likely a priori, so they need a clearer winner.
            var requiredSeparation = ACCEPT_SEPARATION
            if (estimate.scale != 1.0) requiredSeparation += NON_STANDARD_RATE_PENALTY
            if (abs(estimate.shiftMs) > NEAR_SHIFT_MS) requiredSeparation += FAR_SHIFT_PENALTY
            return when {
                knownFrames < MIN_KNOWN_FRAMES -> "Collecting audio (need 60s for confirmation)"
                estimate.cueCount < MIN_CUES -> "Too few overlapping dialogue lines (need $MIN_CUES)"
                !(estimate.speechSeconds >= MIN_SPEECH_SECONDS) -> "Too little detected speech (need ${MIN_SPEECH_SECONDS.toInt()}s)"
                estimate.atSearchEdge -> "Best timing is at the 60s search boundary; match is unreliable"
                rival != null && rival.peak >= estimate.peak - RATE_FAMILY_MARGIN &&
                    !startsPreferRate(segments, estimate, rival, track) ->
                    "Another frame rate matches almost as well; waiting for audio that tells them apart"
                !(estimate.separation >= requiredSeparation) &&
                    !(knownFrames >= START_MIN_KNOWN_FRAMES && startsAgree(segments, estimate, track, requiredSeparation - ACCEPT_SEPARATION)) ->
                    "Several timings match similarly; waiting for a clearer match"
                !confirmedByBothHalves(segments, estimate, track) -> "Timing does not agree across both halves of the analysed audio"
                else -> null
            }
        }

        /**
         * Overlap with speech is ambiguous in dense dialogue: offsets a few seconds apart score
         * alike. Where lines start is much sharper, and an unrelated subtitle's starts almost never
         * peak at the same offset as its overlap does. So an ambiguous [estimate] still counts as
         * clear when matching line starts against speech onsets alone finds the same offset, and
         * stands out there by [ACCEPT_START_SEPARATION] (plus [penalty] for a rate other than 1.0
         * or a far offset). Only computed when the overlap is ambiguous.
         */
        private fun startsAgree(
            segments: List<SpeechSegment>,
            estimate: SubtitleAudioAligner.Estimate,
            track: SubtitleSpeechTrack,
            penalty: Double,
        ): Boolean {
            val starts = preparedStarts(segments)?.estimate(track.starts, doubleArrayOf(estimate.scale)) ?: return false
            return !starts.atSearchEdge &&
                abs(starts.shiftMs - estimate.shiftMs) <= STARTS_AGREEMENT_MS &&
                starts.separation >= ACCEPT_START_SEPARATION + penalty / 2
        }

        /**
         * Settles a rate that overlap alone can't tell from [rival]'s: line starts, matched against
         * where speech rises, must find [estimate]'s offset at its rate and score higher there than
         * at the rival rate by [RATE_STARTS_MARGIN]. Only computed when the overlap is ambiguous.
         */
        private fun startsPreferRate(
            segments: List<SpeechSegment>,
            estimate: SubtitleAudioAligner.Estimate,
            rival: SubtitleAudioAligner.Estimate,
            track: SubtitleSpeechTrack,
        ): Boolean {
            val starts = preparedStarts(segments)
                ?: return false
            val own = starts.estimate(track.starts, doubleArrayOf(estimate.scale)) ?: return false
            val other = starts.estimate(track.starts, doubleArrayOf(rival.scale))?.peak ?: Double.NEGATIVE_INFINITY
            return !own.atSearchEdge && abs(own.shiftMs - estimate.shiftMs) <= STARTS_AGREEMENT_MS &&
                own.peak >= other + RATE_STARTS_MARGIN
        }

        private fun hasEnoughEvidence(
            estimate: SubtitleAudioAligner.Estimate,
            minCues: Int = MIN_CUES,
        ): Boolean = estimate.cueCount >= minCues && estimate.speechSeconds >= MIN_SPEECH_SECONDS

        private fun isStandardRate(scale: Double): Boolean = abs(scale - 1.0) < 0.002

        /**
         * A chance alignment of unrelated subtitles rarely survives being checked on two disjoint halves
         * of the audio, while a real one shows up in both.
         */
        private fun confirmedByBothHalves(
            segments: List<SpeechSegment>,
            estimate: SubtitleAudioAligner.Estimate,
            track: SubtitleSpeechTrack,
        ): Boolean {
            val halves = splitByKnownFrames(segments)
            return halves.all { half ->
                val result = SubtitleAudioAligner.estimate(
                    segments = half,
                    track = track,
                    scales = doubleArrayOf(estimate.scale),
                    minShiftMs = -MAX_SHIFT_MS,
                    maxShiftMs = MAX_SHIFT_MS,
                )
                result != null &&
                    !result.atSearchEdge &&
                    result.cueCount >= MIN_HALF_CUES &&
                    abs(result.shiftMs - estimate.shiftMs) <= HALF_AGREEMENT_MS
            }
        }

        /** Splits [segments] in time into two parts holding half of the known frames each. */
        /** The last line-start search prepared, reused while the same [segments] are checked. */
        private val lastStarts = atomic<Pair<List<SpeechSegment>, SubtitleAudioAligner.PreparedSpeech?>?>(null)

        /** Line starts of [segments] prepared for a full-range search; one search checks them several times. */
        private fun preparedStarts(segments: List<SpeechSegment>): SubtitleAudioAligner.PreparedSpeech? {
            lastStarts.value?.let { (key, prepared) -> if (key === segments) return prepared }
            val prepared = SubtitleAudioAligner.prepare(SubtitleAudioAligner.speechStarts(segments), -MAX_SHIFT_MS, MAX_SHIFT_MS)
            lastStarts.value = segments to prepared
            return prepared
        }

        private fun splitByKnownFrames(segments: List<SpeechSegment>): List<List<SpeechSegment>> {
            val half = segments.sumOf { it.knownFrames } / 2
            val first = ArrayList<SpeechSegment>()
            val second = ArrayList<SpeechSegment>()
            var seen = 0
            for (segment in segments) {
                val known = segment.knownFrames
                when {
                    seen >= half -> second += segment
                    seen + known <= half -> first += segment
                    else -> {
                        var split = 0
                        var count = seen
                        while (split < segment.probabilities.size && count < half) {
                            if (!segment.probabilities[split].isNaN()) count++
                            split++
                        }
                        val p = segment.probabilities
                        first += SpeechSegment(segment.fromFrame, p.copyOfRange(0, split))
                        second += SpeechSegment(segment.fromFrame + split, p.copyOfRange(split, p.size))
                    }
                }
                seen += known
            }
            return listOf(first, second)
        }

        private val FRAMES_PER_SECOND = 1_000.0 / SpeechTimeline.FRAME_DURATION_MS
        /** The most recent audio searched for a first lock. */
        val MAX_ANALYSIS_FRAMES = (20 * 60 * FRAMES_PER_SECOND).toInt()
        private val MIN_KNOWN_FRAMES = (60 * FRAMES_PER_SECOND).toInt()
        private val MIN_LOCAL_FRAMES = (120 * FRAMES_PER_SECOND).toInt()
        private val SEARCH_MIN_NEW_FRAMES = (30 * FRAMES_PER_SECOND).toInt()
        private val SEARCH_SKIP_FROM_FRAMES = (5 * 60 * FRAMES_PER_SECOND).toInt()
        private const val SEARCH_SKIP_MAX_PEAK = 0.15
        private val LOCAL_BACK_FRAMES = (180 * FRAMES_PER_SECOND).toInt()
        private val LOCAL_AHEAD_FRAMES = (180 * FRAMES_PER_SECOND).toInt()
        const val MAX_SHIFT_MS = 60_000.0
        private val RATE_CHECK_MIN_FRAMES = (15 * 60 * FRAMES_PER_SECOND).toInt()
        private const val RATE_CHECK_EVERY_RUNS = 6
        private const val NEAR_RATE = 24_000.0 / 23_976.0

        /** 24-based PAL rate -> its 23.976-based neighbour (as in [SubtitleAudioAligner.CANDIDATE_SCALES]). */
        private val RATE_23976_FOR = mapOf(25.0 / 24.0 to 25.0 / 23.976, 24.0 / 25.0 to 23.976 / 25.0)
        private const val RATE_SWITCH_MARGIN = 0.004

        /** A lock's rate must beat every rate of another family by this much correlation (see [rivalRate]). */
        private const val RATE_FAMILY_MARGIN = 0.03

        /** ...unless line starts prefer the lock's rate by this much (see [startsPreferRate]). */
        private const val RATE_STARTS_MARGIN = 0.03
        private const val MIN_CUES = 20
        private const val MIN_HALF_CUES = 8
        private const val HALF_AGREEMENT_MS = 400.0
        private const val NON_STANDARD_RATE_PENALTY = 0.04
        private const val MIN_LOCAL_CUES = 15
        private const val MIN_SPEECH_SECONDS = 12.0
        const val ACCEPT_PROMINENCE = 0.12
        /** Lock threshold on [SubtitleAudioAligner.Estimate.separation]. */
        const val ACCEPT_SEPARATION = 0.12

        /**
         * How far matching line starts alone must stand out to settle an ambiguous overlap. Measured
         * on 98 subtitles from 34 releases and 102 from other films: with 90 s or more of audio
         * known, no subtitle from another film had both offsets agree and pass this.
         */
        private const val ACCEPT_START_SEPARATION = 0.07

        /** Audio known before line starts may settle an ambiguous overlap; with less, one chance match passed. */
        private val START_MIN_KNOWN_FRAMES = (90 * FRAMES_PER_SECOND).toInt()

        /**
         * A playhead-only lock's rate must beat its 0.1% neighbour by this much correlation. Swept
         * on 4 corpora (about 140 subtitles): 0 let two locks drift by a rate 0.1% off, 0.004 blocked
         * every playhead lock, 0.001 kept five fixes with no regression.
         */
        private const val LOCAL_RATE_MARGIN = 0.001

        /** A 0.1% stretch replaces rate 1.0 when it fits the overlap better by this much... */
        private const val STRETCH_OVERLAP_MARGIN = 0.005

        /** ...and line starts prefer it by this much (see [stretchedWhenClear]). */
        private const val STRETCH_STARTS_MARGIN = 0.005

        /** Line starts and overlap agree on the offset within this. */
        private const val STARTS_AGREEMENT_MS = 250.0
        /** Offsets within this are the common case; beyond it a lock needs [FAR_SHIFT_PENALTY] more separation. */
        private const val NEAR_SHIFT_MS = 8_000.0
        private const val FAR_SHIFT_PENALTY = 0.04
        /** New audio between re-checks of a lock against everything heard (about 45 s). */
        private val REVALIDATE_FRAMES = (45 * FRAMES_PER_SECOND).toInt()
        /** A re-check must place lines this much differently to replace the lock. */
        private const val RELOCK_MIN_MS = 1_000.0
        /**
         * A lock whose mapping correlates below this with all heard speech is withdrawn (see
         * [withdrawLock]). On 383 correct subtitles the lowest was 0.23 (median 0.66); the two
         * false locks seen scored 0.10 and 0.15.
         */
        private const val MIN_LOCK_SUPPORT = 0.2
        private val SUPPORT_MIN_FRAMES = (240 * FRAMES_PER_SECOND).toInt()
        private const val SUPPORT_WINDOW_MS = 100.0
        private const val STABLE_SHIFT_TOLERANCE_MS = 250.0
        private const val JUMP_THRESHOLD_MS = 1_000.0
        private const val REFINE_RANGE_MS = 1_500.0
        private const val REFINE_MIN_PROMINENCE = 0.05
        private const val REFINE_MIN_MOVEMENT_MS = 40.0
        private const val MIN_SEGMENT_MS = 30_000L
        private const val NEAR_PEAK_TOLERANCE = 0.01
        private const val RETRACT_AFTER_CUES = 35
        private const val RETRACT_DISAGREEMENT_MS = 1_000.0
        private const val RETRACT_DISAGREEING_RUNS = 3
        private val NON_UNIT_SCALES = SubtitleAudioAligner.CANDIDATE_SCALES.filter { it != 1.0 }.toDoubleArray()
    }
}
