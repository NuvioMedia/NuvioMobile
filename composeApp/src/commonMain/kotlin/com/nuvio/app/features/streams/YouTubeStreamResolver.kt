package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.TrailerPlaybackMode
import com.nuvio.app.features.trailer.TrailerPlaybackResolver

/**
 * Makes streams that only carry a YouTube id ([StreamItem.ytId]) playable.
 *
 * With in-app trailer playback the video is resolved on the device to a URL the
 * player can open. Without it, the stream becomes an external link to the
 * video's watch page, the same way trailers open in that case.
 */
class YouTubeStreamResolver(
    private val inAppPlaybackEnabled: Boolean,
    private val extractSingleUrl: suspend (String) -> String?,
) {
    /**
     * Returns [stream] unchanged when it doesn't need resolving, a copy that can
     * be played when it does, or null when the video couldn't be resolved.
     */
    suspend fun resolve(stream: StreamItem): StreamItem? {
        val videoId = stream.youTubeIdToResolve ?: return stream
        val watchUrl = watchUrl(videoId)
        if (!inAppPlaybackEnabled) return stream.copy(externalUrl = watchUrl)
        val url = extractSingleUrl(watchUrl)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return stream.copy(url = url)
    }

    companion object {
        val Default: YouTubeStreamResolver by lazy {
            YouTubeStreamResolver(
                inAppPlaybackEnabled = AppFeaturePolicy.trailerPlaybackMode == TrailerPlaybackMode.IN_APP,
                extractSingleUrl = { url -> TrailerPlaybackResolver.resolveSingleUrlFromYouTubeUrl(url) },
            )
        }

        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
    }
}
