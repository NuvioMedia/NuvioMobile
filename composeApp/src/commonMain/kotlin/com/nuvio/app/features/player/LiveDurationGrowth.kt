package com.nuvio.app.features.player

/** First duration seen for [mediaKey] (0 = none yet) and whether it has grown since. */
data class LiveDurationGrowth(
    val mediaKey: String? = null,
    val firstDurationMs: Long = 0L,
    val grew: Boolean = false,
) {
    /**
     * mpv reports a live HLS stream's duration as what it has loaded so far, so it keeps growing,
     * while a VOD's stays put. Readings taken before mpv reports the requested media are skipped:
     * they can still be the previous file's. A new [mediaKey] starts over.
     */
    fun next(mediaKey: String?, durationMs: Long, fromRequestedMedia: Boolean): LiveDurationGrowth {
        val current = if (this.mediaKey == mediaKey) this else LiveDurationGrowth(mediaKey)
        if (!fromRequestedMedia || durationMs <= 0L || current.grew) return current
        if (current.firstDurationMs == 0L) return current.copy(firstDurationMs = durationMs)
        return if (durationMs > current.firstDurationMs + LiveDurationGrowthMs) current.copy(grew = true) else current
    }
}

/** Growth past the first reading that marks a live stream (a VOD's duration only settles by a little). */
internal const val LiveDurationGrowthMs = 10_000L
