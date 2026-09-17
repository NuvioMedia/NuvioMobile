package com.nuvio.app.features.player

/**
 * A missing HLS media segment is specific to the active rendition. When the
 * master playlist has another eligible track, let Media3 exclude the failing
 * one rather than make the whole playback terminal.
 */
internal fun shouldPreferAlternativeHlsTrack(
    responseCode: Int?,
    isMediaSegment: Boolean,
    alternativeTrackAvailable: Boolean,
): Boolean =
    responseCode == 404 && isMediaSegment && alternativeTrackAvailable
