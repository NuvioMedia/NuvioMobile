package com.nuvio.app.features.party

import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class PartyClock(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var anchorServerMs: Long? = null
    private var anchorMark: TimeMark? = null
    private var bestRoundTripMs = Long.MAX_VALUE

    val isSynced: Boolean
        get() = anchorServerMs != null

    fun mark(): TimeMark = timeSource.markNow()

    fun nowMs(): Long {
        val server = anchorServerMs ?: return wallClockMs()
        val mark = anchorMark ?: return wallClockMs()
        return server + mark.elapsedNow().inWholeMilliseconds
    }

    fun addSample(serverMs: Long, sentAt: TimeMark) {
        val roundTripMs = sentAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
        if (roundTripMs > bestRoundTripMs) return
        bestRoundTripMs = roundTripMs
        anchorServerMs = serverMs + roundTripMs / 2
        anchorMark = timeSource.markNow()
    }

    fun reset() {
        anchorServerMs = null
        anchorMark = null
        bestRoundTripMs = Long.MAX_VALUE
    }
}
