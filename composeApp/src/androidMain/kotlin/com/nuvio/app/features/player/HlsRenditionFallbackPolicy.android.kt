package com.nuvio.app.features.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Prefer a healthy HLS rendition when the selected rendition loses a segment. */
internal class HlsRenditionFallbackPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val responseCode = loadErrorInfo.exception
            .findCause<HttpDataSource.InvalidResponseCodeException>()
            ?.responseCode
        if (
            shouldPreferAlternativeHlsTrack(
                responseCode = responseCode,
                isMediaSegment = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA,
                alternativeTrackAvailable = fallbackOptions.isFallbackAvailable(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK
                ),
            )
        ) {
            return LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS,
            )
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
