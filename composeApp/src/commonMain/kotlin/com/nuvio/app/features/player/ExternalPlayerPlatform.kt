package com.nuvio.app.features.player

import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession

data class ExternalPlayerApp(
    val id: String,
    val name: String,
)

data class SubtitleInput(
    val url: String,
    val name: String,
    val lang: String,
)

data class ExternalPlayerPlaybackRequest(
    val sourceUrl: String,
    val title: String,
    val streamTitle: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val resumePositionMs: Long = 0L,
    val subtitles: List<SubtitleInput>? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    // JSON array of episode/movie skip segments, passed to players that support segment skipping.
    val skipSegmentsJson: String? = null,
    val durationMs: Long? = null,
    val playbackSession: WatchProgressPlaybackSession? = null,
) {
    /**
     * Builds a display title for external players.
     * For series: "Show Name - S02E05" or "Show Name - S02E05 - Episode Title"
     * For movies: just the content name (title).
     */
    fun buildPlayerTitle(includeEpisodeTitle: Boolean = false): String {
        if (season == null || episode == null) return title
        val seasonEp = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        return if (includeEpisodeTitle && !episodeTitle.isNullOrBlank()) {
            "$title - $seasonEp - $episodeTitle"
        } else {
            "$title - $seasonEp"
        }
    }
}

enum class ExternalPlayerOpenResult {
    Opened,
    NotConfigured,
    NoPlayerAvailable,
    Failed,
}

sealed interface ExternalPlayerIntentResult {
    data class Success(val intent: Any) : ExternalPlayerIntentResult
    data object NotConfigured : ExternalPlayerIntentResult
    data object Failed : ExternalPlayerIntentResult
}

internal expect object ExternalPlayerPlatform {
    fun defaultPlayerId(): String?
    fun availablePlayers(): List<ExternalPlayerApp>
    fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult
    fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult
}
