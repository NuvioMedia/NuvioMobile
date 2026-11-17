package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.isIos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SOURCE_FALLBACK_POLL_COUNT = 30
private const val SOURCE_FALLBACK_POLL_INTERVAL_MS = 500L

private val log = Logger.withTag("PlayerSourceFallback")

/**
 * Plays [stream] at [url] in place of the current source. Before playback has started the
 * pending resume position is kept, otherwise playback continues from the current position.
 */
internal fun PlayerScreenRuntime.playReplacementSource(stream: StreamItem, url: String, videoId: String) {
    if (playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, videoId, activeSeasonNumber, activeEpisodeNumber)
    }
    if (activeSourceUrl == autoSelectedSourceUrl) autoSelectedSourceUrl = url
    if (initialLoadCompleted) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    flushWatchProgress()
    stopActiveP2pStream()
    externalSubtitles = stream.externalSubtitles
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = stream.playerSourceIdentityKey() ?: activeSourceIdentityKey
    activeSourceFilename = stream.behaviorHints.filename
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeStreamFilename = streamPlaybackFilename(stream, url)
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    activeStream = stream
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints, url)
    currentStreamFilename = stream.behaviorHints.filename
    currentStreamInfoHash = stream.infoHash
    showSourcesPanel = false
    controlsVisible = true
}

/** Shows [message] as the playback error unless the next matching source is tried instead. */
internal fun PlayerScreenRuntime.showErrorUnlessSourceFallback(message: String?) {
    if (tryFallbackToNextSourceAfterError(message)) return
    errorMessage = message
    controlsVisible = !playerControlsLocked
}

/**
 * When the auto-selected source fails before playback starts, quietly plays the next source that
 * auto source selection would pick, up to the configured number of further sources. It never
 * applies to a source the user picked, and it excludes the engine switch on error.
 *
 * Returns `true` when a fallback was started and the error must not be shown yet.
 */
internal fun PlayerScreenRuntime.tryFallbackToNextSourceAfterError(message: String?): Boolean {
    if (sourceFallbackJob?.isActive == true) return true
    val settings = playerSettingsUiState
    if (!settings.streamAutoPlayFallbackActive) return false
    if (!isIos && settings.autoSwitchPlaybackEngineOnError) return false
    if (initialLoadCompleted) return false
    val failedUrl = activeSourceUrl
    if (failedUrl.isBlank() || failedUrl != autoSelectedSourceUrl) return false
    if (sourceFallbackAttempts >= settings.streamAutoPlayFallbackMaxSources) return false
    val videoId = activeVideoId ?: return false

    activeStream?.playerSourceIdentityKey()?.let(sourceFallbackTriedKeys::add)
    val failedInfoHash = activeStream?.p2pInfoHash ?: currentStreamInfoHash
    val failedAddonId = activeProviderAddonId
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    errorMessage = null

    sourceFallbackJob = scope.launch {
        try {
            // Joins the shared source search, so results the stream screen already found are reused.
            PlayerStreamsRepository.loadSources(
                type = contentType ?: parentMetaType,
                videoId = videoId,
                season = season,
                episode = episode,
            )
            var polls = 0
            while (sourceFallbackAttempts < playerSettingsUiState.streamAutoPlayFallbackMaxSources) {
                val state = PlayerStreamsRepository.sourceState.value
                val next = nextSourceFallbackCandidate(
                    streams = state.groups.flatMap { it.streams },
                    settings = playerSettingsUiState,
                    triedKeys = sourceFallbackTriedKeys,
                    failedAddonId = failedAddonId,
                    failedInfoHash = failedInfoHash,
                )
                if (next == null) {
                    val searchDone = state.emptyStateReason != null || (!state.isAnyLoading && state.groups.isNotEmpty())
                    if (searchDone || polls >= SOURCE_FALLBACK_POLL_COUNT) break
                    delay(SOURCE_FALLBACK_POLL_INTERVAL_MS)
                    polls++
                    continue
                }
                next.playerSourceIdentityKey()?.let(sourceFallbackTriedKeys::add)
                sourceFallbackAttempts++

                val playable = when (
                    val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(next, season, episode)
                ) {
                    is DirectDebridPlayableResult.Success -> resolved.stream
                    else -> null
                }
                val url = playable?.playableDirectUrl
                if (playable == null || url.isNullOrBlank() || url == failedUrl) {
                    log.d { "Fallback source from ${next.addonName} has no playable link, trying the next one" }
                    continue
                }
                log.i { "Source failed to start, falling back to ${playable.streamLabel} from ${playable.addonName}" }
                playReplacementSource(playable, url, videoId)
                return@launch
            }
        } finally {
            PlayerStreamsRepository.stopSourcesLoading()
        }
        // The last source that failed may have been saved for reuse.
        removeFailedStreamFromCache()
        errorMessage = message
        controlsVisible = !playerControlsLocked
    }
    return true
}

/**
 * The best stream auto source selection would pick from [streams], leaving out the sources
 * already tried and other copies of the torrent that failed.
 */
internal fun nextSourceFallbackCandidate(
    streams: List<StreamItem>,
    settings: PlayerSettingsUiState,
    triedKeys: Set<String>,
    failedAddonId: String?,
    failedInfoHash: String?,
    installedAddonNames: Set<String> = AddonRepository.uiState.value.addons.enabledAddons().map { it.displayTitle }.toSet(),
    debridEnabled: Boolean = DebridSettingsRepository.snapshot().canResolvePlayableLinks,
    activeResolverProviderId: String? = DebridSettingsRepository.snapshot().activeResolverProviderId,
): StreamItem? {
    val untried = streams.filterNot { stream ->
        val key = stream.playerSourceIdentityKey()
        (key != null && key in triedKeys) ||
            (failedInfoHash != null && stream.addonId == failedAddonId &&
                stream.p2pInfoHash.equals(failedInfoHash, ignoreCase = true))
    }
    return StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = untried,
        mode = settings.streamAutoPlayMode,
        regexPattern = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
    ).readyStreams.firstOrNull()
}
