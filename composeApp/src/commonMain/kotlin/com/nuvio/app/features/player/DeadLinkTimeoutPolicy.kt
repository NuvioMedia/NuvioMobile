package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

internal object DeadLinkTimeoutPolicy {
    const val DEFAULT_SECONDS = 10
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60

    fun normalizeSeconds(seconds: Int): Int =
        seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}

internal fun deadLinkSourcePriority(stream: StreamItem): Int =
    if (stream.isInstalledAddonStream) 0 else 1
