package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isIos
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
) {
    item {
        val settings by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        when (page) {
            SettingsPage.Playback -> {
                val sections = visiblePlaybackSections(isIos, settings.androidPlaybackEngine, P2pSettingsRepository.isVisible)
                SettingsGroup(isTablet = isTablet) {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(section.page.titleRes),
                            description = stringResource(section.descriptionRes),
                            isTablet = isTablet,
                            onClick = { onPageChange(section.page) },
                        )
                    }
                }
            }
            SettingsPage.PlaybackPlayer -> PlaybackPlayerSettings(isTablet, settings)
            SettingsPage.PlaybackStreamSelection -> PlaybackStreamSettings(isTablet, settings)
            SettingsPage.PlaybackUpNext -> PlaybackUpNextSettings(isTablet, settings)
            SettingsPage.PlaybackSkipSegments -> PlaybackSkipSettings(isTablet, settings)
            SettingsPage.PlaybackInterface -> PlaybackInterfaceSettings(isTablet, settings)
            SettingsPage.PlaybackAudio -> PlaybackAudioSettings(isTablet, settings)
            SettingsPage.PlaybackSubtitles -> PlaybackSubtitleSettings(isTablet, settings)
            SettingsPage.PlaybackVideo -> PlaybackVideoSettings(isTablet, settings)
            SettingsPage.PlaybackBuffer -> if (!isIos) PlaybackBufferSettings(isTablet, settings)
            SettingsPage.PlaybackP2p -> if (P2pSettingsRepository.isVisible) PlaybackP2pSettings(isTablet)
            else -> Unit
        }
    }
}
