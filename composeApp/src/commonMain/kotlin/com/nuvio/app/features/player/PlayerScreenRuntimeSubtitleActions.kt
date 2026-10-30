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
    if (extras?.videoHash == null && activeSourceUrl.isNotBlank() &&
        (activeSourceUrl.startsWith("http://", true) || activeSourceUrl.startsWith("https://", true))
    ) {
        // Compute hash lazily — same pattern as NuvioTV.
        SubtitleRepository.fetchAddonSubtitlesWithHashComputation(
            type = type,
            videoId = videoId,
            extras = extras,
            sourceUrl = activeSourceUrl,
            sourceHeaders = activeSourceHeaders,
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
 * After lazy hash computation succeeds, update the cached stream link entry so that
 * subsequent resumes use the hash directly without re-computing. Mirrors NuvioTV's
 * behaviour in `fetchAddonSubtitlesNow`.
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
    val infoHash = activeTorrentInfoHash
    if (infoHash != null) {
        StreamLinkCacheRepository.save(
            contentKey = cacheKey,
            url = "",
            streamName = activeStreamTitle,
            addonName = activeProviderName,
            addonId = activeProviderAddonId ?: "",
            filename = enriched.filename,
            videoHash = enriched.videoHash,
            videoSize = enriched.videoSize,
            infoHash = infoHash,
            fileIdx = activeTorrentFileIdx,
            sources = activeTorrentTrackers,
            bingeGroup = currentStreamBingeGroup,
            contentLanguage = contentLanguage,
        )
    } else if (activeSourceUrl.isNotBlank()) {
        StreamLinkCacheRepository.save(
            contentKey = cacheKey,
            url = activeSourceUrl,
            streamName = activeStreamTitle,
            addonName = activeProviderName,
            addonId = activeProviderAddonId ?: "",
            requestHeaders = activeSourceHeaders,
            responseHeaders = activeSourceResponseHeaders,
            filename = enriched.filename,
            videoHash = enriched.videoHash,
            videoSize = enriched.videoSize,
            bingeGroup = currentStreamBingeGroup,
            streamType = activeStreamType,
            contentLanguage = contentLanguage,
        )
    }
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
