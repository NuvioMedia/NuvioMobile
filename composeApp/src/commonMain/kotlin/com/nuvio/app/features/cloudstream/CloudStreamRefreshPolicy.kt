package com.nuvio.app.features.cloudstream

internal const val CLOUD_STREAM_REPOSITORY_REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1_000L

/**
 * Keep successful repository metadata cached between launches, but retry repositories
 * that have never refreshed or whose timestamp is invalid.
 */
internal fun shouldRefreshCloudStreamRepository(
    lastRefreshedAtEpochMs: Long,
    nowEpochMs: Long,
    refreshIntervalMs: Long = CLOUD_STREAM_REPOSITORY_REFRESH_INTERVAL_MS,
): Boolean =
    lastRefreshedAtEpochMs <= 0L ||
        nowEpochMs < lastRefreshedAtEpochMs ||
        nowEpochMs - lastRefreshedAtEpochMs >= refreshIntervalMs
