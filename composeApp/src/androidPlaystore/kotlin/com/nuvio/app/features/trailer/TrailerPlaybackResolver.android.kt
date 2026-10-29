package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? = null

    actual suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String? = null
}