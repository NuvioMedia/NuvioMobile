package com.nuvio.app.features.autosync

import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

private const val SIDECAR_WAIT_MS = 15_000L
private const val SIDECAR_WAIT_POLL_MS = 25L
private const val BOUNDARY_TOLERANCE_MS = 500L
private const val DIRECT_INDEX_TOLERANCE_MS = 50L

internal suspend fun applyAutoSyncSidecarTimeline(
    sidecar: SidecarSubtitleController,
    url: String,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident || sidecar.activeSidecarSubtitleKey != url) return false
    val expectedGeneration = sidecar.currentGenerationFor(url) ?: return false

    val current = sidecar.sidecarTimedCues.takeIf { it.isNotEmpty() } ?: withTimeoutOrNull(
        SIDECAR_WAIT_MS,
    ) {
        while (
            sidecar.activeSidecarSubtitleKey == url &&
            sidecar.currentGenerationFor(url) == expectedGeneration &&
            sidecar.sidecarTimedCues.isEmpty()
        ) {
            delay(SIDECAR_WAIT_POLL_MS)
        }
        sidecar.sidecarTimedCues.takeIf {
            sidecar.activeSidecarSubtitleKey == url &&
                sidecar.currentGenerationFor(url) == expectedGeneration &&
                it.isNotEmpty()
        }
    } ?: return false

    val retimed = withContext(Dispatchers.Default) {
        retimeSidecarTimedCues(current, timeline)
    }

    val committed = sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentUrl = url,
        newUrl = url,
        cues = retimed,
        expectedGeneration = expectedGeneration,
    )
    return committed
}

internal suspend fun replaceAutoSyncSidecarSubtitle(
    sidecar: SidecarSubtitleController,
    expectedCurrentUrl: String,
    url: String,
    headers: Map<String, String>,
    rawBody: String? = null,
    useLibass: Boolean,
    timeline: AutoSyncTimelineRetimeResult,
): Boolean {
    if (!timeline.confident) return false
    if (!sidecar.canAttachAddonSubtitleViaSidecar(url, useLibass)) return false
    if (sidecar.activeSidecarSubtitleKey != expectedCurrentUrl) return false
    val expectedGeneration =
        sidecar.currentGenerationFor(expectedCurrentUrl) ?: return false

    val retimed = try {
        val body = rawBody ?: withContext(Dispatchers.IO) {
            httpGetTextWithHeaders(url = url, headers = headers)
        }
        val parsed = withContext(Dispatchers.Default) {
            parseSidecarTimedCuesRobust(body, url).cues
        }
        if (parsed.isEmpty()) {
            return false
        }

        val prepared = withContext(Dispatchers.Default) {
            retimeSidecarTimedCues(parsed, timeline)
        }
        prepared
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        return false
    }

    currentCoroutineContext().ensureActive()
    if (
        sidecar.activeSidecarSubtitleKey != expectedCurrentUrl ||
        sidecar.currentGenerationFor(expectedCurrentUrl) != expectedGeneration
    ) {
        return false
    }

    val committed = sidecar.commitPreparedSidecarSubtitle(
        expectedCurrentUrl = expectedCurrentUrl,
        newUrl = url,
        cues = retimed,
        expectedGeneration = expectedGeneration,
    )
    return committed
}


private fun retimeSidecarTimedCuesByIndexIfCompatible(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming>? {
    if (source.size != timeline.cues.size || source.isEmpty()) return null

    val out = ArrayList<CuesWithTiming>(source.size)
    for (index in source.indices) {
        val entry = source[index]
        if (entry.startTimeUs == C.TIME_UNSET) return null

        val originalStartMs = entry.startTimeUs / 1_000L
        val originalEndMs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
            entry.durationUs != C.TIME_UNSET -> originalStartMs + entry.durationUs / 1_000L
            else -> return null
        }.coerceAtLeast(originalStartMs + 1L)

        val retimedCue = timeline.cues[index]
        if (
            kotlin.math.abs(originalStartMs - retimedCue.originalStartTimeMs) > DIRECT_INDEX_TOLERANCE_MS ||
            kotlin.math.abs(originalEndMs - retimedCue.originalEndTimeMs) > DIRECT_INDEX_TOLERANCE_MS
        ) {
            return null
        }

        val startMs = retimedCue.startTimeMs.coerceAtLeast(0L)
        val endMs = retimedCue.endTimeMs.coerceAtLeast(startMs + 1L)
        out += CuesWithTiming(
            entry.cues,
            startMs * 1_000L,
            (endMs - startMs) * 1_000L,
        )
    }

    clampIntroducedSidecarOverlaps(source, out)
    return out
}

private fun clampIntroducedSidecarOverlaps(
    source: List<CuesWithTiming>,
    out: MutableList<CuesWithTiming>,
) {
    for (index in 0 until out.lastIndex) {
        val sourceCurrent = source[index]
        val sourceNext = source[index + 1]
        if (sourceCurrent.startTimeUs == C.TIME_UNSET || sourceNext.startTimeUs == C.TIME_UNSET) continue

        val sourceCurrentEndUs = when {
            sourceCurrent.endTimeUs != C.TIME_UNSET -> sourceCurrent.endTimeUs
            sourceCurrent.durationUs != C.TIME_UNSET -> sourceCurrent.startTimeUs + sourceCurrent.durationUs
            else -> continue
        }
        if (sourceCurrentEndUs > sourceNext.startTimeUs) continue

        val current = out[index]
        val next = out[index + 1]
        if (current.startTimeUs == C.TIME_UNSET || next.startTimeUs == C.TIME_UNSET) continue
        val currentEndUs = when {
            current.endTimeUs != C.TIME_UNSET -> current.endTimeUs
            current.durationUs != C.TIME_UNSET -> current.startTimeUs + current.durationUs
            else -> continue
        }
        if (currentEndUs > next.startTimeUs && next.startTimeUs > current.startTimeUs) {
            out[index] = CuesWithTiming(
                current.cues,
                current.startTimeUs,
                (next.startTimeUs - current.startTimeUs).coerceAtLeast(1L),
            )
        }
    }
}

private data class TimingBoundary(
    val originalMs: Long,
    val retimedMs: Long,
)

private fun retimeSidecarTimedCues(
    source: List<CuesWithTiming>,
    timeline: AutoSyncTimelineRetimeResult,
): List<CuesWithTiming> {
    retimeSidecarTimedCuesByIndexIfCompatible(source, timeline)?.let { direct ->
        return direct
    }

    val startBoundaries = ArrayList<TimingBoundary>(timeline.cues.size)
    val endBoundaries = ArrayList<TimingBoundary>(timeline.cues.size)
    timeline.cues.forEach { cue ->
        startBoundaries += TimingBoundary(cue.originalStartTimeMs, cue.startTimeMs)
        endBoundaries += TimingBoundary(cue.originalEndTimeMs, cue.endTimeMs)
    }
    startBoundaries.sortBy { it.originalMs }
    endBoundaries.sortBy { it.originalMs }

    fun mapTime(originalMs: Long, boundaries: List<TimingBoundary>): Long {
        if (boundaries.isNotEmpty()) {
            var low = 0
            var high = boundaries.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (boundaries[mid].originalMs < originalMs) low = mid + 1 else high = mid
            }

            val first = (low - 2).coerceAtLeast(0)
            val last = (low + 2).coerceAtMost(boundaries.lastIndex)
            var best: TimingBoundary? = null
            var bestError = Long.MAX_VALUE
            if (first <= last) {
                for (index in first..last) {
                    val candidate = boundaries[index]
                    val error = kotlin.math.abs(candidate.originalMs - originalMs)
                    if (error < bestError) {
                        best = candidate
                        bestError = error
                    }
                }
            }
            if (best != null && bestError <= BOUNDARY_TOLERANCE_MS) {
                return best.retimedMs.coerceAtLeast(0L)
            }
        }

        return (
            originalMs.toDouble() * timeline.alignmentScale +
                timeline.alignmentInterceptMs
            ).roundToLong().coerceAtLeast(0L)
    }

    val out = ArrayList<CuesWithTiming>(source.size)
    source.forEach { entry ->
        if (entry.startTimeUs == C.TIME_UNSET) {
            out += entry
            return@forEach
        }

        val originalStartMs = entry.startTimeUs / 1_000L
        val originalEndMs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs / 1_000L
            entry.durationUs != C.TIME_UNSET ->
                originalStartMs + entry.durationUs / 1_000L
            else -> originalStartMs + 1L
        }.coerceAtLeast(originalStartMs + 1L)

        val startMs = mapTime(originalStartMs, startBoundaries)
        val endMs = mapTime(originalEndMs, endBoundaries).coerceAtLeast(startMs + 1L)
        out += CuesWithTiming(
            entry.cues,
            startMs * 1_000L,
            (endMs - startMs) * 1_000L,
        )
    }

    clampIntroducedSidecarOverlaps(source, out)

    return out
}
