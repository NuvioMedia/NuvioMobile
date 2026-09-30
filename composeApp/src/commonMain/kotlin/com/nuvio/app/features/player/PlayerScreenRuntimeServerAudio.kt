package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.servers.ServerPlayback
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_audio_switch_failed
import org.jetbrains.compose.resources.getString

internal fun PlayerScreenRuntime.refreshServerAudioTracks() {
    serverAudioTracks = ServerPlayback.audioTracks(activeSourceUrl).map { track ->
        AudioTrack(
            index = track.index,
            id = track.index.toString(),
            label = track.label,
            language = track.language,
            isSelected = track.selected,
        )
    }
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

private fun PlayerScreenRuntime.switchServerAudioTrack(index: Int) {
    if (serverAudioTracks.any { it.isSelected && it.index == index }) return
    if (serverAudioSwitchJob?.isActive == true) return
    val url = activeSourceUrl
    val positionMs = if (initialSeekApplied) playbackSnapshot.positionMs.coerceAtLeast(0L) else activeInitialPositionMs
    serverAudioSwitchJob = scope.launch {
        val playback = ServerPlayback.switchAudio(url, index)
        if (playback == null) {
            NuvioToastController.show(getString(Res.string.servers_audio_switch_failed))
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
