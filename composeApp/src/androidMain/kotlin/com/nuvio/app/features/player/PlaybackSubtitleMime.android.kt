package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.nuvio.app.features.streams.StreamSubtitle

internal fun startupSubtitleConfigurations(
    subtitles: List<StreamSubtitle>,
): List<MediaItem.SubtitleConfiguration> =
    subtitles.mapNotNull { subtitle ->
        if (!subtitle.url.isLocalSubtitleUri()) return@mapNotNull null
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
            .setMimeType(PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url))
            .setLanguage(subtitle.language)
            .setLabel(subtitle.name ?: subtitle.language)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
    }
