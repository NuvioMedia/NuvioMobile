package com.nuvio.app.features.player

import android.media.MediaMetadata

/** Shared display metadata; session ownership and action routing stay with each controller. */
internal fun buildNowPlayingBaseMetadata(info: PlayerNowPlayingInfo, durationMs: Long): MediaMetadata =
    MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, info.title.trim())
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, info.title.trim())
        .apply {
            info.subtitle?.trim()?.takeIf(String::isNotEmpty)?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            if (durationMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        }.build()
