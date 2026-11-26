package com.nuvio.app.features.audiosync.asr

import com.nuvio.app.features.audiosync.AudioSyncTracker
import com.nuvio.app.features.audiosync.SileroVad
import com.nuvio.app.features.audiosync.SpeechTimeline
import com.nuvio.app.features.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.audiosync.SubtitleSpeechTrack
import com.nuvio.app.features.audiosync.SubtitleSyncSegment
import com.nuvio.app.features.audiosync.audioSyncNanoTime
import com.nuvio.app.features.audiosync.startAudioSyncThread
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt

/** Speech recogniser returning (seconds from segment start, word) pairs. */
fun interface SpeechToText {
    fun transcribe(samples: FloatArray): List<Pair<Double, String>>
}

/** A same-language (English) subtitle the heard words can be matched against. */
internal class ReferenceSubtitle(
    val key: String,
    cues: List<Triple<Long, Long, String>>,
    /** How the target subtitle maps onto this one; null when the reference is the target itself. */
    val bridge: BridgeFit?,
) {
    val matcher = WordAnchorMatcher(cues)
    private val track = SubtitleSpeechTrack.fromCues(cues)
    private val localShifts = HashMap<Pair<SubtitleSpeechTrack, Int>, Double?>()
    private val localShiftsLock = SynchronizedObject()

    /**
     * The [bridge] shift around [referenceSec] (see [SubtitleBridge.localShiftSec]), falling back
     * to the whole-file one. Kept per [LOCAL_STEP_SEC] of reference time, as regions recur.
     */
    fun bridgeShiftSecAt(target: SubtitleSpeechTrack, referenceSec: Double): Double {
        val bridge = bridge ?: return 0.0
        val step = (referenceSec / LOCAL_STEP_SEC).roundToInt()
        val local = synchronized(localShiftsLock) {
            localShifts.getOrPut(target to step) {
                SubtitleBridge.localShiftSec(target, track, bridge, step * LOCAL_STEP_SEC)
            }
        }
        return local ?: bridge.shiftSec
    }

    private companion object {
        const val LOCAL_STEP_SEC = 5.0
    }
}

/** Target subtitle mapping found from recognised words. */
internal data class AsrLock(
    val scale: Double,
    val shiftMs: Double,
    val referenceKey: String,
    val anchorScore: Double,
    val fineTuned: Boolean,
    /** False while the frame rate is still assumed (short span, no bridge); an update may follow. */
    val final: Boolean,
    /**
     * The full mapping, in media order: one segment normally, several when the release and the
     * subtitle differ by an inserted or removed scene (a different offset from some point on).
     */
    val segments: List<SubtitleSyncSegment> = listOf(SubtitleSyncSegment(0L, scale, shiftMs)),
)

/**
 * Layer 2 of the audio sync: recognises English words in the dialogue and pins subtitles to them.
 *
 * Speech segments arrive from [SpeechSegmenter]s (look-ahead and live), are transcribed on one
 * low-priority worker, and every recognised word is kept for the whole stream, so switching
 * subtitles re-uses what was already heard. Each reference subtitle is matched against the words;
 * the first confident match is turned into a target mapping (through the timing bridge for
 * translated subtitles) and fine-tuned against the speech timeline.
 */
internal class AsrSyncEngine(
    private val timeline: SpeechTimeline,
    private val onLock: (AsrLock) -> Unit,
    private val log: (String) -> Unit = {},
    /** Runs on the worker thread before it starts (e.g. to lower its priority). */
    private val workerSetup: () -> Unit = {},
) {
    /** [spread]: sampled away from the playhead, recognised first (see [nextSegment]). */
    private class Segment(val startFrame: Int, val samples: FloatArray, val spread: Boolean) {
        val endFrame: Int get() = startFrame + samples.size / SileroVad.CHUNK_SAMPLES
    }

    private val lock = SynchronizedObject()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var playheadFrame = 0

    /** Nearest upcoming speech first: that is what will be on screen soonest. */
    private val queue = ArrayList<Segment>()
    private var queuedSamples = 0L
    private var expectingUntilNanos = 0L
    private val covered = ArrayList<IntRange>()
    private val heard = ArrayList<HeardWord>()
    private var segmentCounter = 0

    /** Bumped when heard words are forgotten, so a recognition already running is discarded. */
    private var heardGeneration = 0L

    @Volatile
    private var target: SubtitleSpeechTrack? = null

    @Volatile
    private var references: List<ReferenceSubtitle> = emptyList()

    /** A final lock was reported; from then on only speech near the playhead is recognised. */
    @Volatile
    private var locked = false

    /** The reference-to-video ratio [mostLikelyRate] chose, for how many words and in what situation. */
    private class ChosenRate(val situation: String, val words: Int, val rate: Double)

    @Volatile
    private var chosenRate: ChosenRate? = null

    /** Last reported (not yet final) lock, to report only real changes. */
    private var provisional: AsrLock? = null

    /** A different final mapping seen once; it replaces the reported one if it comes back next. */
    private var pendingMove: AsrLock? = null

    @Volatile
    private var stt: SpeechToText? = null

    @Volatile
    private var released = false
    private var workerStarted = false

    /** False while recognition cannot help (no recogniser, no usable reference, foreign audio). */
    @Volatile
    private var listening = true

    /** Heard words so far; exposed for diagnostics. */
    val heardWordCount: Int get() = synchronized(lock) { heard.size }

    /** Snapshot of every word heard so far, in time order. */
    fun heardWords(): List<HeardWord> = synchronized(lock) { heard.toList() }

    fun setRecognizer(recognizer: SpeechToText?) {
        stt = recognizer
        synchronized(lock) { wake() }
    }

    /**
     * Whether speech is worth keeping for recognition. Off, nothing is queued (queued speech is
     * dropped), so a stream that can never be recognised holds no audio; words already heard stay.
     */
    fun setListening(on: Boolean) {
        synchronized(lock) {
            if (listening == on) return
            listening = on
            if (!on) {
                queue.clear()
                queuedSamples = 0
            }
            wake()
        }
    }

    fun onPlayhead(positionMs: Long) {
        playheadFrame = (positionMs / SpeechTimeline.FRAME_DURATION_MS).toInt()
    }

    /**
     * Queues a speech segment for recognition. [spread] marks speech sampled across the film: words
     * from far-apart places pin a reference fastest, so it goes first.
     */
    fun offerSegment(startFrame: Int, samples: FloatArray, spread: Boolean = false) {
        val segment = Segment(startFrame, samples, spread)
        synchronized(lock) {
            if (released || !listening) return
            val range = segment.startFrame until segment.endFrame
            // The look-ahead and live paths can deliver the same stretch; skip what is mostly known.
            val overlap = covered.sumOf { overlap(it, range) } + queue.sumOf { overlap(it.startFrame until it.endFrame, range) }
            if (overlap * 2 > range.count()) return
            while (queuedSamples + samples.size > MAX_QUEUED_SAMPLES && queue.isNotEmpty()) {
                val dropped = queue.maxByOrNull(::segmentPriority) ?: break
                queue.remove(dropped)
                queuedSamples -= dropped.samples.size
            }
            queue.add(segment)
            queuedSamples += samples.size
            ensureWorker()
            wake()
        }
    }

    /** Starts matching for a new target subtitle; previously heard words are kept. */
    fun startSession(targetTrack: SubtitleSpeechTrack, referenceSubtitles: List<ReferenceSubtitle>) {
        synchronized(lock) {
            target = targetTrack
            references = referenceSubtitles
            expectingUntilNanos = 0L
            locked = false
            provisional = null
            pendingMove = null
            wake()
        }
        evaluate()
    }

    /**
     * References for the current session are on their way and should arrive within [forMs]: until
     * then (or until called with 0), speech is recognised ahead of them. A download that fails
     * never arrives, hence the limit. Cleared by [startSession] and [stopSession].
     */
    fun expectReferences(forMs: Long) {
        synchronized(lock) {
            expectingUntilNanos = if (forMs > 0) audioSyncNanoTime() + forMs * 1_000_000L else 0L
            wake()
        }
    }

    private fun expectingReferences(): Boolean = expectingUntilNanos != 0L && audioSyncNanoTime() < expectingUntilNanos

    private fun wake() {
        wakeups.trySend(Unit)
    }

    /** Adds a reference that finished downloading after the session started. */
    fun addReference(reference: ReferenceSubtitle) {
        synchronized(lock) {
            if (references.any { it.key == reference.key }) return
            references = references + reference
            wake()
        }
        evaluate()
    }

    fun stopSession() {
        synchronized(lock) {
            target = null
            references = emptyList()
            expectingUntilNanos = 0L
            locked = false
            provisional = null
            pendingMove = null
            // Pending audio (up to ~30 MB) is only useful to a running session; free it now rather
            // than holding it until the next stream. Words already recognized are small and kept.
            queue.clear()
            queuedSamples = 0
        }
    }

    /** Another audio track: forget what was heard on the previous one, keeping any lock. */
    fun forgetHeard() {
        synchronized(lock) {
            queue.clear()
            queuedSamples = 0
            covered.clear()
            heard.clear()
            heardGeneration++
        }
    }

    /** New stream: forget everything that was heard. */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            queuedSamples = 0
            covered.clear()
            heard.clear()
            heardGeneration++
            locked = false
            provisional = null
            pendingMove = null
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
            wake()
        }
    }

    private fun segmentPriority(segment: Segment): Long {
        if (segment.spread) return SPREAD_PRIORITY
        val distance = segment.startFrame - playheadFrame
        // Upcoming speech first (nearest first), then the most recent past speech.
        return if (distance >= -BEHIND_GRACE_FRAMES) distance.toLong() else 1_000_000L - distance
    }

    /**
     * The next segment to recognise. Sampled speech first, taking the place with the fewest
     * recognised segments around it, so words arrive from every sampled place early; then the
     * nearest upcoming speech. Call with [lock] held and the queue not empty.
     */
    private fun nextSegment(): Segment {
        val head = queue.minBy(::segmentPriority)
        if (!head.spread) {
            queue.remove(head)
            return head
        }
        val next = queue.filter { it.spread }.minWith(
            compareBy<Segment>({ segment -> covered.count { abs(it.first - segment.startFrame) <= SPREAD_RADIUS_FRAMES } })
                .thenBy { it.startFrame },
        )
        queue.remove(next)
        return next
    }

    private fun ensureWorker() {
        if (workerStarted) return
        workerStarted = true
        startAudioSyncThread("NuvioAsrSync") { runWorker() }
    }

    private fun runWorker() {
        runCatching(workerSetup)
        while (true) {
            var generation = 0L
            val next = synchronized(lock) {
                generation = heardGeneration
                if (released) return
                // No reference to match words against: recognising is wasted work, unless one is
                // still downloading (a translated subtitle's English references). Words heard
                // meanwhile are kept and matched the moment one is added.
                if (queue.isEmpty() || stt == null || target == null ||
                    (references.isEmpty() && !expectingReferences())
                ) {
                    null
                } else {
                    nextSegment().also { queuedSamples -= it.samples.size } to stt!!
                }
            }
            if (next == null) {
                runBlocking { wakeups.receive() }
                continue
            }
            val (segment, recognizer) = next
            // Once synced, keep listening just ahead of the playhead: a scene the release adds or
            // cuts shows up as a new offset there before it is on screen.
            if (locked && !segment.spread && !nearPlayhead(segment)) continue
            val words = try {
                recognizer.transcribe(segment.samples)
            } catch (error: Throwable) {
                log("recognition failed: ${error.message}")
                emptyList()
            }
            val startSec = segment.startFrame * SpeechTimeline.FRAME_DURATION_MS / 1_000.0
            synchronized(lock) {
                // Heard on a track or stream that has since been forgotten.
                if (heardGeneration != generation) return@synchronized
                covered += segment.startFrame until segment.endFrame
                val id = segmentCounter++
                words.forEach { (offsetSec, text) -> heard += HeardWord(startSec + offsetSec, text, id) }
                heard.sortBy { it.timeSec }
            }
            evaluate()
        }
    }

    private fun nearPlayhead(segment: Segment): Boolean {
        val distance = segment.startFrame - playheadFrame
        return distance >= -BEHIND_GRACE_FRAMES && distance <= MAINTAIN_AHEAD_FRAMES
    }

    private fun evaluate() {
        val (words, refs, targetTrack) = synchronized(lock) {
            Triple(heard.toList(), references, target ?: return)
        }
        if (words.isEmpty()) return
        var best: Pair<ReferenceSubtitle, AnchorFit>? = null
        var bestLocals: List<AnchorFit> = emptyList()
        var bestVotes: WordAnchorMatcher.Votes? = null
        val confident = ArrayList<Pair<ReferenceSubtitle, AnchorFit>>()
        for (reference in refs) {
            // Built once: the fit, the regions and the place count below all start from them.
            val votes = reference.matcher.votes(words)
            val fit = reference.matcher.fit(votes) ?: continue
            val locals = reference.matcher.localFits(votes, fit.scale)
            // A scene the release adds splits the words into two strong clusters, so one fit over
            // all words looks ambiguous; regions that are each confident explain it.
            if (!fit.isConfident && !explainsSteps(locals)) continue
            confident += reference to fit
            if (best == null || fit.score > best.second.score) {
                best = reference to fit
                bestLocals = locals
                bestVotes = votes
            }
        }
        val (reference, fit) = best ?: return
        val bridge = reference.bridge
        // Words over a short stretch pin where the reference is, not its frame rate relative to the
        // video: that is only known from a long span of words (or the words chose another rate).
        val localSpanSec = if (bestLocals.size >= 3) bestLocals.last().anchorSec - bestLocals.first().anchorSec else 0.0
        val rateKnown = fit.spanSec >= FINAL_SPAN_SEC || fit.scale != 1.0 || localSpanSec >= FINAL_SPAN_SEC
        val (scale, coarseShiftMs, fine) = if (rateKnown) {
            // Compose target -> reference -> media.
            val scale = fit.scale * (bridge?.scale ?: 1.0)
            val coarseShiftMs = (fit.scale * (bridge?.shiftSec ?: 0.0) + fit.shiftSec) * 1_000.0
            val chosen = Triple(scale, coarseShiftMs, fineTune(targetTrack, scale, coarseShiftMs))
            if (fit.scale != 1.0) neighbouringRate(targetTrack, fit, bridge, chosen) else chosen
        } else {
            // Null: another frame rate fits the speech as well, so a guess would misplace most lines.
            mostLikelyRate(targetTrack, words, fit, bridge) ?: return
        }
        val trustedFine = fine?.takeIf { isTrustedMove(it, coarseShiftMs) }
        val shiftMs = trustedFine?.shiftMs ?: coarseShiftMs
        val segments = piecewise(targetTrack, bestVotes!!, bestLocals, reference, fit, scale, shiftMs)
            ?: listOf(SubtitleSyncSegment(0L, scale, shiftMs))
        // Through a bridge, the mapping is only as good as the timing match between the two
        // subtitles, which can be ambiguous (similar line patterns at several offsets or rates).
        // It is confirmed, or moves a confirmed sync, only when the speech timing near it agrees
        // (fine-tuned) or another reference with its own bridge puts the lines in the same place.
        val corroborated = bridge == null || trustedFine != null ||
            confident.any { (other, otherFit) -> agreesThroughOwnBridge(other, otherFit, reference, scale, coarseShiftMs, words) }
        if (!corroborated && locked) {
            log("words=${words.size} reference=${reference.key} bridge=$bridge: move not corroborated, kept")
            return
        }
        val result = AsrLock(
            scale = scale,
            shiftMs = segments.first().shiftMs,
            referenceKey = reference.key,
            anchorScore = fit.score,
            fineTuned = trustedFine != null,
            final = (rateKnown || locked) && corroborated,
            segments = segments,
        )
        synchronized(lock) {
            if (target !== targetTrack) return
            val previous = provisional
            val tolerance = if (previous?.final == true) SETTLED_UPDATE_MIN_MS else UPDATE_MIN_MS
            if (previous != null && previous.final == result.final && sameMapping(previous, result, tolerance)) {
                pendingMove = null
                return
            }
            if (previous?.final == true && result.final) {
                val again = pendingMove?.let { sameMapping(it, result, FINAL_MOVE_REPEAT_MS) } == true
                if (!again) {
                    pendingMove = result
                    return
                }
            }
            pendingMove = null
            if (result.final) locked = true
            provisional = result
        }
        log(
            "words=${words.size} reference=${reference.key} fit=$fit bridge=$bridge " +
                "coarse=${coarseShiftMs.toLong()}ms fine=${fine?.shiftMs?.toLong()} rateKnown=$rateKnown",
        )
        onLock(result)
    }

    /**
     * Whether [other] (another reference, bridged on its own) maps the target where the chosen
     * reference does: within [BRIDGE_AGREE_MS] at the first and last heard word. A copy of the
     * chosen reference has the same bridge and proves nothing, so it doesn't count.
     */
    private fun agreesThroughOwnBridge(
        other: ReferenceSubtitle,
        otherFit: AnchorFit,
        chosen: ReferenceSubtitle,
        scale: Double,
        shiftMs: Double,
        words: List<HeardWord>,
    ): Boolean {
        if (other === chosen) return false
        val otherBridge = other.bridge ?: return false
        val chosenBridge = chosen.bridge ?: return false
        if (otherBridge.scale == chosenBridge.scale && abs(otherBridge.shiftSec - chosenBridge.shiftSec) < SAME_BRIDGE_SEC) return false
        val otherScale = otherFit.scale * otherBridge.scale
        val otherShiftMs = (otherFit.scale * otherBridge.shiftSec + otherFit.shiftSec) * 1_000.0
        return listOf(words.first().timeSec, words.last().timeSec).all { mediaSec ->
            // The target time shown at this media time by the chosen mapping, and where the other puts it.
            val targetMs = (mediaSec * 1_000.0 - shiftMs) / scale
            abs(targetMs * otherScale + otherShiftMs - mediaSec * 1_000.0) <= BRIDGE_AGREE_MS
        }
    }

    /** Confident regions in at least two places, with enough evidence to trust a step. */
    private fun explainsSteps(locals: List<AnchorFit>): Boolean =
        locals.size >= 2 && locals.sumOf { it.segments } >= 2 * MIN_PART_SEGMENTS

    /** Whether two results would place subtitles the same (within [toleranceMs] everywhere). */
    private fun sameMapping(a: AsrLock, b: AsrLock, toleranceMs: Double): Boolean {
        if (a.segments.size != b.segments.size) return false
        return a.segments.zip(b.segments).all { (x, y) ->
            x.scale == y.scale && abs(x.shiftMs - y.shiftMs) < toleranceMs &&
                abs(x.fromMediaMs - y.fromMediaMs) < CHANGE_POINT_TOLERANCE_MS
        }
    }

    /**
     * When the words in different parts of the film put the subtitle at clearly different offsets
     * (the release adds or cuts a scene the subtitle was not made for, or the subtitle and the
     * reference were made for different cuts), maps each part with its own offset, switching where
     * the detected speech shows the change. Null when the whole-film mapping ([shiftMs]) fits
     * everywhere the words were heard.
     */
    private fun piecewise(
        track: SubtitleSpeechTrack,
        votes: WordAnchorMatcher.Votes,
        /** [WordAnchorMatcher.localFits] of [votes] at [fit]'s scale. */
        confidentLocals: List<AnchorFit>,
        reference: ReferenceSubtitle,
        fit: AnchorFit,
        scale: Double,
        shiftMs: Double,
    ): List<SubtitleSyncSegment>? {
        if (confidentLocals.isEmpty()) return null
        val bridgeScale = reference.bridge?.scale ?: 1.0

        // Target -> media shift a fit implies at the chosen scale, through its anchor. The target
        // is placed against the reference by the lines around there, not the whole file.
        fun impliedShiftMs(local: AnchorFit): Double {
            val referenceSec = (local.anchorSec - local.shiftSec) / local.scale
            val targetSec = (referenceSec - reference.bridgeShiftSecAt(track, referenceSec)) / bridgeScale
            return (local.anchorSec - scale * targetSec) * 1_000.0
        }
        // Words from several far-apart places (short sampled spots) can agree on an offset while
        // few of the places are confident alone. That agreement is a part of its own, over the
        // stretch its words come from (standing for the confident regions there that agree with
        // it); otherwise one strong region (say the opening, heard in full) would decide the whole
        // film. Not when a confident region inside that stretch says otherwise.
        val spread = reference.matcher.fitByPlace(votes, fit.scale)?.takeIf { spread ->
            val shift = impliedShiftMs(spread)
            spread.segments >= MIN_PART_SEGMENTS && spread.spanSec >= SPREAD_MIN_SPAN_SEC &&
                confidentLocals.none {
                    it.anchorSec in spread.firstSec..spread.lastSec && abs(impliedShiftMs(it) - shift) > STEP_TOLERANCE_MS
                }
        }
        val locals = if (spread == null) {
            confidentLocals
        } else {
            (confidentLocals.filter { it.anchorSec !in spread.firstSec..spread.lastSec } + spread).sortedBy { it.anchorSec }
        }
        val targetShifts = locals.associateWith(::impliedShiftMs)
        fun targetShiftMs(local: AnchorFit): Double = targetShifts.getValue(local)
        // Where a part's words begin and end: a region's anchor, or the spread agreement's extent.
        fun startSec(local: AnchorFit): Double = if (local === spread) local.firstSec else local.anchorSec
        fun endSec(local: AnchorFit): Double = if (local === spread) local.lastSec else local.anchorSec

        class Part(val fits: MutableList<AnchorFit>, var shiftMs: Double)
        val parts = ArrayList<Part>()
        for (local in locals) {
            val shift = targetShiftMs(local)
            val last = parts.lastOrNull()
            if (last != null && abs(last.shiftMs - shift) <= STEP_TOLERANCE_MS) {
                last.fits += local
                last.shiftMs = last.fits.sumOf { targetShiftMs(it) * it.score } / last.fits.sumOf { it.score }
            } else {
                parts += Part(mutableListOf(local), shift)
            }
        }
        // A step needs solid evidence on both sides; stray regions are ignored.
        parts.removeAll { part -> part.fits.sumOf { it.segments } < MIN_PART_SEGMENTS }
        if (parts.isEmpty()) return null
        // One part: words heard in one place only. Its offset wins over the whole-film mapping
        // where the subtitle and the reference differ there.
        if (parts.size == 1 && abs(parts[0].shiftMs - shiftMs) <= STEP_TOLERANCE_MS) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val tuned = parts.map { part ->
            val fromFrame = ((startSec(part.fits.first()) - PART_CONTEXT_SEC) * 1_000 / frameMs).toInt().coerceAtLeast(0)
            val toFrame = ((endSec(part.fits.last()) + PART_CONTEXT_SEC) * 1_000 / frameMs).toInt()
            fineTune(track, scale, part.shiftMs, fromFrame, toFrame)
                ?.takeIf { isTrustedMove(it, part.shiftMs) }?.shiftMs ?: part.shiftMs
        }
        val segments = ArrayList<SubtitleSyncSegment>()
        segments += SubtitleSyncSegment(0L, scale, tuned.first())
        for (i in 1 until parts.size) {
            if (abs(tuned[i] - segments.last().shiftMs) <= STEP_TOLERANCE_MS) continue
            val afterMs = endSec(parts[i - 1].fits.last()) * 1_000
            val beforeMs = startSec(parts[i].fits.first()) * 1_000
            val split = AudioSyncTracker.changePoint(
                timeline = timeline,
                track = track,
                from = (afterMs / frameMs).toInt(),
                to = (beforeMs / frameMs).toInt(),
                scale = scale,
                oldShiftMs = segments.last().shiftMs,
                newShiftMs = tuned[i],
            ) ?: ((afterMs + beforeMs) / 2).toLong()
            segments += SubtitleSyncSegment(split.coerceAtLeast(segments.last().fromMediaMs + 1), scale, tuned[i])
        }
        return segments.takeIf { it.size >= 2 || abs(it[0].shiftMs - shiftMs) > STEP_TOLERANCE_MS }
    }

    /**
     * While the reference's frame rate relative to the video is unknown, the target keeps its own
     * rate (the usual case: a subtitle made for this release), anchored where the words were heard.
     * Another common ratio is used only when the detected speech clearly confirms it over plenty
     * of audio: a wrong stretch drifts further with every minute. Returns the target mapping
     * (scale, coarse shift) and its fine-tuned estimate, if any.
     */
    private fun mostLikelyRate(
        track: SubtitleSpeechTrack,
        words: List<HeardWord>,
        fit: AnchorFit,
        bridge: BridgeFit?,
    ): Triple<Double, Double, SubtitleAudioAligner.Estimate?>? {
        val anchorMs = fit.anchorSec * 1_000.0
        val referenceAtAnchorMs = anchorMs - fit.shiftSec * 1_000.0
        val bridgeScale = bridge?.scale ?: 1.0

        /** Target -> media mapping when the reference runs at [rate] relative to the video. */
        fun hypothesis(rate: Double): Triple<Double, Double, SubtitleAudioAligner.Estimate?> {
            // Reference -> media at this rate, passing through the anchor; then target -> media.
            val referenceShiftMs = anchorMs - rate * referenceAtAnchorMs
            val scale = rate * bridgeScale
            val coarseShiftMs = rate * (bridge?.shiftSec ?: 0.0) * 1_000.0 + referenceShiftMs
            return Triple(scale, coarseShiftMs, fineTune(track, scale, coarseShiftMs))
        }

        val unstretched = hypothesis(1.0 / bridgeScale)
        val knownFrames = timeline.segments(maxFrames = FINE_TUNE_MAX_FRAMES).sumOf { it.knownFrames }
        if (knownFrames < STRETCH_MIN_KNOWN_FRAMES) {
            // Too little audio to choose a rate, so the plain one is assumed. If another rate (for
            // instance the one a bridge to a 25 fps file implies) already fits the speech at least
            // as well, the assumption would misplace most of the film's lines: publish nothing yet.
            val unstretchedPeak = unstretched.third?.peak
            val rival = SubtitleAudioAligner.CANDIDATE_SCALES.asSequence()
                .map(::hypothesis)
                .filter { abs(it.first - unstretched.first) > 1e-9 }
                .mapNotNull { it.third }
                .filter { !it.atSearchEdge }
                .maxOfOrNull { it.peak }
            // A bridge that changes the rate says the two files run at different rates, so one of
            // them is stretched against the video: without speech evidence for the plain rate the
            // assumption is a coin flip, and a wrong one is off by minutes far from the anchor.
            val bridgeStretches = bridge != null && abs(bridgeScale - 1.0) > BRIDGE_STRETCH_MIN
            return if (bridgeStretches) {
                // Publish only when the speech clearly prefers the plain rate for this file.
                unstretched.takeIf { unstretchedPeak != null && rival != null && unstretchedPeak >= rival + EARLY_RATE_VETO_MARGIN }
            } else {
                // Near the word anchor every rate fits about equally, so only a rate that fits
                // clearly better blocks the estimate; without speech evidence for the plain rate
                // there is nothing to compare, and the estimate stands as before.
                unstretched.takeUnless { unstretchedPeak != null && rival != null && rival >= unstretchedPeak + EARLY_RATE_VETO_MARGIN }
            }
        }
        // Trying every ratio is the expensive part: keep the ratio chosen last time until clearly
        // more words or audio are in (or the target or bridge changed), then judge again.
        val situation = "${track.hashCode()}|${bridge?.shiftSec}|${knownFrames / STRETCH_RECHECK_FRAMES}"
        chosenRate?.let { last ->
            if (last.situation == situation && words.size < last.words * 5 / 4) return hypothesis(last.rate)
        }
        val floor = unstretched.third?.peak?.plus(OTHER_RATE_MARGIN) ?: Double.NEGATIVE_INFINITY
        var best = unstretched
        var bestPeak = floor
        for (rate in SubtitleAudioAligner.CANDIDATE_SCALES) {
            val candidate = hypothesis(rate)
            if (abs(candidate.first - 1.0) < 1e-9) continue
            val fine = candidate.third ?: continue
            if (fine.atSearchEdge || fine.prominence < STRETCH_MIN_PROMINENCE) continue
            if (fine.peak > bestPeak) {
                bestPeak = fine.peak
                best = candidate
            }
        }
        chosenRate = ChosenRate(situation, words.size, best.first / bridgeScale)
        return best
    }

    /**
     * Words tell a 25 fps file from a 23.976 one, but not 25/24 from 25/23.976: those differ by
     * 0.1%, and the word fit keeps whichever rate it tried first. The speech timeline can tell them
     * apart, so the rates 0.1% either side, passing through the same word anchor, are compared
     * there and kept only when they fit clearly better.
     */
    private fun neighbouringRate(
        track: SubtitleSpeechTrack,
        fit: AnchorFit,
        bridge: BridgeFit?,
        chosen: Triple<Double, Double, SubtitleAudioAligner.Estimate?>,
    ): Triple<Double, Double, SubtitleAudioAligner.Estimate?> {
        val chosenPeak = chosen.third?.peak ?: return chosen
        val anchorMs = fit.anchorSec * 1_000.0
        val referenceAtAnchorMs = (fit.anchorSec - fit.shiftSec) / fit.scale * 1_000.0
        var best = chosen
        var bestPeak = chosenPeak + NEIGHBOUR_RATE_MARGIN
        for (rate in doubleArrayOf(fit.scale * NEAR_RATE, fit.scale / NEAR_RATE)) {
            val referenceShiftMs = anchorMs - rate * referenceAtAnchorMs
            val scale = rate * (bridge?.scale ?: 1.0)
            val coarseShiftMs = rate * (bridge?.shiftSec ?: 0.0) * 1_000.0 + referenceShiftMs
            val fine = fineTune(track, scale, coarseShiftMs) ?: continue
            if (fine.atSearchEdge || fine.peak < bestPeak) continue
            best = Triple(scale, coarseShiftMs, fine)
            bestPeak = fine.peak
        }
        return best
    }

    /**
     * Word anchors are exact about *which* line is spoken but only approximately about when inside
     * it; the speech timeline pins the edges. Search a narrow window so it cannot jump elsewhere.
     */
    private fun fineTune(
        track: SubtitleSpeechTrack,
        scale: Double,
        coarseShiftMs: Double,
        fromFrame: Int = 0,
        toFrame: Int = Int.MAX_VALUE,
    ): SubtitleAudioAligner.Estimate? {
        val segments = timeline.segments(fromFrame = fromFrame, toFrame = toFrame, maxFrames = FINE_TUNE_MAX_FRAMES)
        if (segments.isEmpty()) return null
        val estimate = SubtitleAudioAligner.estimate(
            segments = segments,
            track = track,
            scales = doubleArrayOf(scale),
            minShiftMs = coarseShiftMs - FINE_TUNE_WINDOW_MS,
            maxShiftMs = coarseShiftMs + FINE_TUNE_WINDOW_MS,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.cueCount < FINE_TUNE_MIN_CUES) return null
        if (abs(estimate.shiftMs - coarseShiftMs) > FINE_TUNE_MAX_MOVE_MS) return null
        return estimate
    }

    /**
     * Whether a fine-tune estimate may move the word-based offset [coarseShiftMs]. Moves within the
     * words' own timing uncertainty are taken as before; a larger one needs a peak that stands out,
     * since a flat speech pattern (dense dialogue, music) could otherwise pull a good word-based
     * offset by up to [FINE_TUNE_MAX_MOVE_MS].
     */
    private fun isTrustedMove(estimate: SubtitleAudioAligner.Estimate, coarseShiftMs: Double): Boolean =
        abs(estimate.shiftMs - coarseShiftMs) <= FINE_TUNE_FREE_MOVE_MS ||
            estimate.prominence >= FINE_TUNE_MIN_PROMINENCE

    private fun overlap(a: IntRange, b: IntRange): Int =
        (minOf(a.last, b.last) - maxOf(a.first, b.first) + 1).coerceAtLeast(0)

    companion object {
        /** About 8 minutes of speech at 16 kHz. */
        private const val MAX_QUEUED_SAMPLES = 16_000L * 60 * 8
        private const val BEHIND_GRACE_FRAMES = 94 // 3 s
        private const val SPREAD_PRIORITY = -2_000_000L
        private const val SPREAD_RADIUS_FRAMES = 2_813 // 90 s
        private val FINE_TUNE_MAX_FRAMES = (10 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        /** Searched either side of the coarse shift; the aligner distrusts the outer 1.5 s of any range. */
        private const val FINE_TUNE_WINDOW_MS = 3_000.0
        private const val FINE_TUNE_MAX_MOVE_MS = 1_200.0
        private const val FINE_TUNE_MIN_CUES = 5

        /** Fine-tune moves up to this need no clear peak: about how far off a word's time can be. */
        private const val FINE_TUNE_FREE_MOVE_MS = 500.0

        /** Peak lead over any offset more than 2.5 s away that a larger move needs. */
        private const val FINE_TUNE_MIN_PROMINENCE = 0.05
        private const val FINAL_SPAN_SEC = 180.0

        /** Correlation a stretched mapping must win by over the unstretched one. */
        private const val OTHER_RATE_MARGIN = 0.04

        /**
         * Before the rate is known, no estimate is published while another rate fits the speech
         * this much better than the assumed plain one. Not 0 as in the speech-activity tracker:
         * there the search is global, here every rate passes through the same word anchor, where
         * they all fit about equally (0 withheld good estimates for normal-rate films).
         */
        private const val EARLY_RATE_VETO_MARGIN = OTHER_RATE_MARGIN

        /** A bridge rate further than this from 1 is a frame-rate change between the two files. */
        private const val BRIDGE_STRETCH_MIN = 0.002

        /**
         * A confirmed (final) sync moves only when the same new mapping comes back on the next
         * evaluation, within this: words from different parts of the film can favour two nearby
         * offsets in turn (a reference with its own small step), and following each would make the
         * subtitles jump back and forth.
         */
        private const val FINAL_MOVE_REPEAT_MS = 500.0

        /** Two references' bridged mappings agree when they place the heard lines this close. */
        private const val BRIDGE_AGREE_MS = 600.0

        /** Bridges this close (at the same rate) are the same bridge, e.g. two copies of one file. */
        private const val SAME_BRIDGE_SEC = 0.1

        /** 24/23.976: the step between 25/24 and 25/23.976 (and between 1 and 24/23.976). */
        private const val NEAR_RATE = 24_000.0 / 23_976.0

        /** Correlation a neighbouring rate must win by to replace the one the words chose. */
        private const val NEIGHBOUR_RATE_MARGIN = 0.005

        /** Like a speech-detection lock at a non-standard rate: 0.12 plus the 0.04 penalty. */
        private const val STRETCH_MIN_PROMINENCE = 0.16

        /** More heard audio that makes the ratio worth judging again (30 s). */
        private val STRETCH_RECHECK_FRAMES = (30 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()

        /** Heard audio needed before a stretch can be judged at all (5 minutes). */
        private val STRETCH_MIN_KNOWN_FRAMES = (5 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private const val UPDATE_MIN_MS = 150.0

        /** Once synced, only a real change moves the subtitles, not fine-tuning jitter. */
        private const val SETTLED_UPDATE_MIN_MS = 300.0

        /** Offsets within this of each other are the same part of the film. */
        private const val STEP_TOLERANCE_MS = 600.0

        /** Word segments a part of the film needs before its offset counts. */
        private const val MIN_PART_SEGMENTS = 4

        /** Audio around a part's words used to fine-tune its offset. */
        private const val PART_CONTEXT_SEC = 150.0

        /** Agreement between places counts as a part only when its words span this much of the film. */
        private const val SPREAD_MIN_SPAN_SEC = 180.0
        private const val CHANGE_POINT_TOLERANCE_MS = 5_000L

        /** After syncing, speech up to this far ahead of the playhead is still recognised (3 min). */
        private const val MAINTAIN_AHEAD_FRAMES = 5_625
    }
}
