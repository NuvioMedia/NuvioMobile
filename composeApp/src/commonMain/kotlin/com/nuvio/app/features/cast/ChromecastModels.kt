package com.nuvio.app.features.cast

enum class CastProtocol {
    CHROMECAST,
    DLNA,
    DIAL,
    AIRPLAY
}

data class UnifiedCastDevice(
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val dlnaDevice: DlnaDevice? = null,
    val chromecastRouteId: String? = null,
    val chromecastDescription: String? = null,
    val ipAddress: String? = null,
)

data class SubtitleTrackInfo(
    val url: String,
    val name: String,
    val lang: String = "en",
)

data class ChromecastMediaRequest(
    val proxyUrl: String,
    val title: String,
    val subtitle: String? = null,
    val subtitleUrl: String? = null,
    val subtitleName: String? = null,
    val subtitleMime: String = "text/vtt",
    val subtitleLanguage: String = "en",
    val subtitles: List<SubtitleTrackInfo> = emptyList(),
    val selectedSubtitleIndex: Int? = null,
    val mimeType: String = "video/mp4",
    val durationMs: Long? = null,
    val startPositionMs: Long = 0L,
    val posterUrl: String? = null,
)
