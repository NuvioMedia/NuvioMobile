package com.nuvio.app.features.settings

import com.nuvio.app.features.player.AndroidPlaybackEngine
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal enum class PlaybackSection(
    val page: SettingsPage,
    val descriptionRes: StringResource,
) {
    Player(SettingsPage.PlaybackPlayer, Res.string.settings_playback_page_player_description),
    StreamSelection(SettingsPage.PlaybackStreamSelection, Res.string.settings_playback_page_stream_selection_description),
    UpNext(SettingsPage.PlaybackUpNext, Res.string.settings_playback_page_up_next_description),
    SkipSegments(SettingsPage.PlaybackSkipSegments, Res.string.settings_playback_page_skip_description),
    Interface(SettingsPage.PlaybackInterface, Res.string.settings_playback_page_interface_description),
    Audio(SettingsPage.PlaybackAudio, Res.string.settings_playback_page_audio_description),
    Subtitles(SettingsPage.PlaybackSubtitles, Res.string.settings_playback_page_subtitles_description),
    Video(SettingsPage.PlaybackVideo, Res.string.settings_playback_page_video_description),
    Buffer(SettingsPage.PlaybackBuffer, Res.string.settings_playback_page_buffer_description),
    P2p(SettingsPage.PlaybackP2p, Res.string.settings_playback_page_p2p_description),
}

internal fun visiblePlaybackSections(
    ios: Boolean,
    engine: AndroidPlaybackEngine,
    p2pVisible: Boolean,
): List<PlaybackSection> = PlaybackSection.entries.filter { section ->
    when (section) {
        PlaybackSection.Buffer -> !ios && engine != AndroidPlaybackEngine.Libmpv
        PlaybackSection.P2p -> p2pVisible
        else -> true
    }
}
