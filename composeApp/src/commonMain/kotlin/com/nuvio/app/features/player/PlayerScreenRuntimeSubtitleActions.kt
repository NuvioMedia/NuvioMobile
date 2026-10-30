package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    // Without a network the addon lookup can only hang; keep the local state untouched.
    if (isNetworkOffline()) return
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    val extras = activeSubtitleExtras
    // For P2P streams activeSourceUrl is a torrent:// sentinel — the real HTTP URL
    // comes from the local engine once the stream is resolved, mirroring NuvioTV where
    // currentStreamUrl is set to the local engine URL for torrents.
    val hashableUrl = p2pResolvedSourceUrl ?: activeSourceUrl
    if (extras?.videoHash == null && hashableUrl.isNotBlank() &&
        (hashableUrl.startsWith("http://", true) || hashableUrl.startsWith("https://", true))
    ) {
        // Compute hash lazily — same pattern as NuvioTV.
        SubtitleRepository.fetchAddonSubtitlesWithHashComputation(
            type = type,
            videoId = videoId,
            extras = extras,
            sourceUrl = hashableUrl,
            sourceHeaders = if (p2pResolvedSourceUrl != null) emptyMap() else activeSourceHeaders,
            onExtrasUpdated = { enriched ->
                activeSubtitleExtras = enriched
                persistComputedVideoHash(enriched)
            },
        )
    } else {
        SubtitleRepository.fetchAddonSubtitles(type, videoId, extras)
    }
}

/**
 * After lazy hash computation succeeds, patch the *existing* cached stream link
 * entry with the newly computed hash. Uses a read-modify-write so that we never
 * create a new entry (respecting server-stream exclusions and saveForReuse=false)
 * and never reset cachedAtMs.
 */
private fun PlayerScreenRuntime.persistComputedVideoHash(enriched: SubtitleRequestExtras) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled) return
    val vid = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = vid,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.updateVideoHash(cacheKey, enriched.videoHash, enriched.videoSize)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    (playerController as? AutoSyncPlayerController)?.cancelForManualSubtitleDelay() // AutoSync hook
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    // Offline: the cue download would only hang, so skip it and leave the state as is.
    if (isNetworkOffline()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
