package com.nuvio.app.features.player

import kotlin.math.abs

/** How far video may trail the audio clock outside a seek before its position is distrusted. */
internal const val MPV_VIDEO_PTS_GLITCH_MIN_SEC = 10.0

/**
 * h264_mediacodec stamps a packet that has no PTS with 0 instead of leaving it unset. On streams
 * with such packets (seen on some HLS hosts) mpv shows that frame as `time-pos` 0 while audio keeps
 * playing at the real position, so the position snaps to the start for a moment: the Skip Intro
 * button flashes mid-episode, or an intro starting at 0 can be auto-skipped there. When the video
 * position is far behind a running audio clock outside a seek, the audio clock holds the real
 * position.
 */
internal fun mpvPlaybackPositionSec(
    timePosSec: Double,
    audioPtsSec: Double?,
    seeking: Boolean,
    audioDelaySec: Double
): Double {
    if (audioPtsSec == null || seeking) return timePosSec
    val glitchMinSec = MPV_VIDEO_PTS_GLITCH_MIN_SEC + abs(audioDelaySec)
    return if (audioPtsSec - timePosSec > glitchMinSec) audioPtsSec else timePosSec
}
