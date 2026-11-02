package com.nuvio.app.features.livetv.player

import com.nuvio.app.features.livetv.data.CatchupUrlBuilder
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.PlaylistSource

/** What the player opens for a catch-up replay: the link, the other links to try, and the window asked for. */
internal data class CatchupPlan(
    val url: String,
    /** Other forms of the same request, tried in turn when [url] fails (see [CatchupUrlBuilder.alternatives]). */
    val fallbacks: List<String>,
    /** The real time the replay starts at: the show's start, plus the offset asked for. */
    val startMs: Long,
    /** The real time the window asked for ends at. */
    val stopMs: Long,
    /** The link asks for HLS (.m3u8), which plays with a length and a seek bar. */
    val preferHls: Boolean,
)

/**
 * Catch-up (replaying past shows from the provider's archive), the TV app's rules:
 *  - a show can be replayed when its channel has catch-up, it has started, and it started within
 *    the channel's catch-up window (so many days back);
 *  - a show on air now can be watched from its beginning the same way;
 *  - the link asks for more than the show itself (up to [CONTINUE_MS] past its end, but not past
 *    what has aired), so a show that runs over keeps playing and the next show follows on;
 *  - Xtream links ask for HLS first unless this provider's HLS replays failed within the last
 *    week ([HLS_RETRY_MS]), in which case TS is asked for straight away.
 */
internal object LiveTvCatchup {
    /** How far past the end of a show a replay keeps going (into the next shows). */
    const val CONTINUE_MS = 3L * 60 * 60 * 1000

    /** How long catch-up sticks to TS on a provider whose HLS replays failed, before trying HLS again. */
    const val HLS_RETRY_MS = 7L * 24 * 3_600_000L

    /** Whether [program] on [channel] can be replayed at [now] (it has started and is still in the archive). */
    fun isReplayable(channel: LiveChannel, program: EpgProgram, now: Long): Boolean =
        CatchupUrlBuilder.isAvailable(channel.catchup, program.startMs, now)

    /** A show on air now that can be restarted from its beginning. */
    fun canWatchFromStart(channel: LiveChannel, program: EpgProgram, now: Long): Boolean =
        program.isLive(now) && isReplayable(channel, program, now)

    /**
     * The time to take off the guide's times when asking the archive: the guide's own time shift
     * is taken back off (the archive goes by the guide's real times), then the catch-up time shift
     * is added, for providers whose archive runs on another clock.
     */
    fun shiftMs(settings: LiveTvSettings): Long =
        settings.epgOffsetMinutes * 60_000L - settings.catchupShiftMinutes * 60_000L

    /** Ask for HLS: the setting is on and this provider's HLS replays haven't failed lately. */
    fun preferHls(settings: LiveTvSettings, playlist: PlaylistSource?, now: Long): Boolean =
        settings.catchupPreferHls && now - (playlist?.catchupNoHlsSince ?: 0L) > HLS_RETRY_MS

    /** The replay window for [program] starting [offsetMs] into it: its start (plus the offset) to [CONTINUE_MS] past its end, never past what has aired. */
    fun window(program: EpgProgram, offsetMs: Long, now: Long): Pair<Long, Long> {
        val start = program.startMs + offsetMs.coerceAtLeast(0)
        val stop = maxOf(program.stopMs, minOf(now - 30_000, program.stopMs + CONTINUE_MS))
        return start to stop
    }

    /**
     * The links for replaying [program] on [channel] from [offsetMs] into it, or null when the
     * channel has no catch-up or the show is outside its window.
     */
    fun plan(
        channel: LiveChannel,
        program: EpgProgram,
        settings: LiveTvSettings,
        playlist: PlaylistSource?,
        now: Long,
        offsetMs: Long = 0L,
    ): CatchupPlan? {
        val catchup = channel.catchup ?: return null
        if (!CatchupUrlBuilder.isAvailable(catchup, program.startMs, now)) return null
        val shift = shiftMs(settings)
        val (start, stop) = window(program, offsetMs, now)
        val zone = playlist?.serverTimezone?.takeIf { it.isNotBlank() }
        val preferHls = preferHls(settings, playlist, now)
        val url = CatchupUrlBuilder.build(
            channel.url, catchup, start - shift, stop - shift, now,
            preferHls = preferHls,
            serverTimezone = zone,
        ) ?: return null
        val fallbacks = CatchupUrlBuilder.alternatives(
            url, zone,
            showMinutes = (program.stopMs - start) / 60_000,
            airedMinutes = if (now < program.stopMs) (now - start) / 60_000 else null,
        )
        return CatchupPlan(url = url, fallbacks = fallbacks, startMs = start, stopMs = stop, preferHls = preferHls)
    }

    /** An Xtream catch-up link asking for HLS. */
    fun isHlsTimeshift(url: String): Boolean = url.substringBefore('?').let { it.contains("/timeshift/") && it.endsWith(".m3u8", ignoreCase = true) }

    /** An Xtream catch-up link asking for TS. */
    fun isTsTimeshift(url: String): Boolean = url.substringBefore('?').let { it.contains("/timeshift/") && it.endsWith(".ts", ignoreCase = true) }
}
