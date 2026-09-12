package com.nuvio.app.features.simkl

import com.nuvio.app.features.tracking.TrackingRefreshIntent

internal const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
internal const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS =
    SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L
internal val simklConnectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC
internal val simklProgressRefreshIntent = TrackingRefreshIntent.AUTOMATIC

internal fun shouldRunSimklRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS,
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true

    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

internal typealias SimklRefreshGate = com.nuvio.app.features.tracking.TrackingRefreshGate
internal typealias SimklRefreshGateOutcome = com.nuvio.app.features.tracking.TrackingRefreshGateOutcome
