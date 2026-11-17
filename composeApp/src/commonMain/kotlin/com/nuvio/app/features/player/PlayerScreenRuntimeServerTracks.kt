package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerPlaybackSession
import com.nuvio.app.features.servers.label
import com.nuvio.app.features.servers.readableTranscodeReason
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_audio_switch_failed
import nuvio.composeapp.generated.resources.servers_subtitle_switch_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal val PlayerScreenRuntime.hasBurnedInServerSubtitle: Boolean
    get() = ServerPlayback.burnInSubtitles(activeSourceUrl).any { it.selected }

internal fun PlayerScreenRuntime.refreshServerTracks() {
    serverAudioTracks = ServerPlayback.audioTracks(activeSourceUrl).map { track ->
        AudioTrack(
            index = track.index,
            id = track.index.toString(),
            label = track.label,
            language = track.language,
            isSelected = track.selected,
        )
    }
    serverSubtitleTracks = ServerPlayback.burnInSubtitles(activeSourceUrl).map { track ->
        SubtitleTrack(
            index = track.index,
            id = track.index.toString(),
            label = track.label,
            language = track.language,
            isSelected = track.selected,
        )
    }
    if (serverSubtitleTracks.any { it.isSelected }) {
        isUserExplicitSubtitleSelection = true
        preferredSubtitleSelectionApplied = true
    }
}

@Composable
internal fun PlayerScreenRuntime.serverPlaybackSummary(): String? {
    val session = ServerPlayback.session(activeSourceUrl) ?: return null
    val method = stringResource(session.playMethod.label())
    val reasons = session.transcodeReasons.joinToString(", ", transform = ::readableTranscodeReason)
    return if (reasons.isEmpty()) method else "$method · $reasons"
}

internal fun PlayerScreenRuntime.applyPreferredServerAudioTrack() {
    if (serverAudioTracks.isEmpty() || isUserExplicitAudioSelection) return
    val key = "$activeSourceIdentityKey:$activeVideoId"
    if (serverAudioPreferenceKey == key) return
    serverAudioPreferenceKey = key
    val index = preferredServerAudioIndex(
        tracks = serverAudioTracks,
        preference = PlayerTrackPreferenceStorage.load(parentMetaId),
        targets = preferredAudioLanguageTargets,
    ) ?: return
    switchServerAudioTrack(index)
}

internal fun preferredServerAudioIndex(
    tracks: List<AudioTrack>,
    preference: PersistedPlayerTrackPreference?,
    targets: List<String>,
): Int? =
    preference?.let { findPersistedAudioTrackIndex(tracks, it) }?.takeIf { it >= 0 }
        ?: targets.firstNotNullOfOrNull { target -> tracks.firstOrNull { audioTrackMatchesLanguage(it, target) }?.index }

internal fun PlayerScreenRuntime.selectServerAudioTrack(index: Int) {
    persistAudioPreference(serverAudioTracks.firstOrNull { it.index == index })
    switchServerAudioTrack(index)
}

internal fun PlayerScreenRuntime.selectServerSubtitleTrack(index: Int) {
    if (serverSubtitleTracks.any { it.isSelected && it.index == index }) return
    isUserExplicitSubtitleSelection = true
    preferredSubtitleSelectionApplied = true
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    if (useCustomSubtitles) {
        playerController?.clearExternalSubtitleAndSelect(-1)
    } else {
        playerController?.selectSubtitleTrack(-1)
    }
    useCustomSubtitles = false
    restartServerStream(Res.string.servers_subtitle_switch_failed) { url -> ServerPlayback.switchSubtitle(url, index) }
}

internal fun PlayerScreenRuntime.clearServerSubtitleTrack() {
    restartServerStream(Res.string.servers_subtitle_switch_failed) { url -> ServerPlayback.switchSubtitle(url, null) }
}

private fun PlayerScreenRuntime.switchServerAudioTrack(index: Int) {
    if (serverAudioTracks.any { it.isSelected && it.index == index }) return
    restartServerStream(Res.string.servers_audio_switch_failed) { url -> ServerPlayback.switchAudio(url, index) }
}

private fun PlayerScreenRuntime.restartServerStream(
    failureMessage: StringResource,
    restart: suspend (String) -> ServerPlaybackSession?,
) {
    if (serverTrackSwitchJob?.isActive == true) return
    val url = activeSourceUrl
    val positionMs = if (initialSeekApplied) playbackSnapshot.positionMs.coerceAtLeast(0L) else activeInitialPositionMs
    serverTrackSwitchJob = scope.launch {
        val playback = restart(url)
        if (playback == null) {
            NuvioToastController.show(getString(failureMessage))
            return@launch
        }
        if (activeSourceUrl != url) {
            ServerPlayback.stop(playback.url)
            return@launch
        }
        externalSubtitles = playback.subtitles
        activeSourceUrl = playback.url
        activeSourceHeaders = playback.headers
        activeInitialPositionMs = positionMs
        activeInitialProgressFraction = null
        trackPreferenceRestoreApplied = false
    }
}
