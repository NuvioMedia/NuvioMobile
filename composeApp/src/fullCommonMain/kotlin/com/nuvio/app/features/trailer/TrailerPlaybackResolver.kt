package com.nuvio.app.features.trailer

actual object TrailerPlaybackResolver {
    private val extractor by lazy { InAppYouTubeExtractor() }

    actual suspend fun resolveFromYouTubeUrl(youtubeUrl: String): TrailerPlaybackSource? {
        if (youtubeUrl.isBlank()) return null
        return extractor.extractPlaybackSource(youtubeUrl)
    }

    actual suspend fun resolveSingleUrlFromYouTubeUrl(youtubeUrl: String): String? {
        if (youtubeUrl.isBlank()) return null
        return extractor.extractSingleUrl(youtubeUrl)
    }
}