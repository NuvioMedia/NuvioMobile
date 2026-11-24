package com.nuvio.app.features.audiosync.asr

import com.nuvio.app.features.audiosync.SpeechTimeline
import com.nuvio.app.features.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.audiosync.SubtitleSpeechTrack

/** Reference time = target subtitle time * [scale] + [shiftSec]. */
internal data class BridgeFit(val scale: Double, val shiftSec: Double, val peak: Double, val prominence: Double)

/**
 * Aligns a subtitle in any language to a reference subtitle of the same film by their timing
 * patterns alone. Translations are usually made from an English file and keep its line breaks, so
 * the two on-screen patterns match almost exactly. No audio is needed, so this runs in a few
 * milliseconds as soon as both files are downloaded.
 */
internal object SubtitleBridge {
    private const val MIN_PROMINENCE = 0.15
    private const val MIN_PEAK = 0.35

    fun align(target: SubtitleSpeechTrack, reference: SubtitleSpeechTrack): BridgeFit? {
        if (target.size == 0 || reference.size == 0) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val endMs = reference.endsMs.max()
        val frames = (endMs / frameMs).toInt() + 1
        // The reference's on-screen coverage stands in for "speech" in the audio aligner.
        val coverage = reference.render(0, frames, 1.0)
        val probabilities = FloatArray(frames) { coverage[it].toFloat() }
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = 0,
            track = target,
            minShiftMs = -WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            maxShiftMs = WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            detectorBiasMs = 0.0,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.peak < MIN_PEAK || estimate.prominence < MIN_PROMINENCE) return null
        return BridgeFit(estimate.scale, estimate.shiftMs / 1_000.0, estimate.peak, estimate.prominence)
    }

    /**
     * The bridge's shift around [referenceSec] only: the lines within a few minutes of it, at the
     * whole-file [bridge]'s scale. It differs from the bridge's own shift where one file has a
     * scene the other lacks (another cut of the film). Null when the lines there don't match
     * clearly, e.g. little dialogue nearby.
     */
    fun localShiftSec(
        target: SubtitleSpeechTrack,
        reference: SubtitleSpeechTrack,
        bridge: BridgeFit,
        referenceSec: Double,
    ): Double? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val fromFrame = ((referenceSec - LOCAL_WINDOW_SEC) * 1_000 / frameMs).toInt().coerceAtLeast(0)
        val toFrame = ((referenceSec + LOCAL_WINDOW_SEC) * 1_000 / frameMs).toInt()
        if (toFrame <= fromFrame) return null
        val coverage = reference.render(fromFrame, toFrame, 1.0)
        val probabilities = FloatArray(coverage.size) { coverage[it].toFloat() }
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = fromFrame,
            track = target,
            scales = doubleArrayOf(bridge.scale),
            minShiftMs = (bridge.shiftSec - LOCAL_SEARCH_SEC) * 1_000.0,
            maxShiftMs = (bridge.shiftSec + LOCAL_SEARCH_SEC) * 1_000.0,
            detectorBiasMs = 0.0,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.peak < LOCAL_MIN_PEAK || estimate.prominence < MIN_PROMINENCE ||
            estimate.cueCount < LOCAL_MIN_CUES
        ) {
            return null
        }
        return estimate.shiftMs / 1_000.0
    }

    /** Lines within this distance of the place asked about decide the local shift. */
    private const val LOCAL_WINDOW_SEC = 150.0

    /** How far the local shift may be from the whole-file one: an added or cut scene or two. */
    private const val LOCAL_SEARCH_SEC = 240.0
    private const val LOCAL_MIN_PEAK = 0.5
    private const val LOCAL_MIN_CUES = 20
}
