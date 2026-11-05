package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.ExternalPlayerApp
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackPlayerSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showExternalPlayerDialog by remember { mutableStateOf(false) }
    var showExternalPlayerAppDialog by remember { mutableStateOf(false) }
    var showPlaybackEngineDialog by remember { mutableStateOf(false) }
    val availableExternalPlayers = ExternalPlayerPlatform.availablePlayers()
    val selectedExternalPlayer = availableExternalPlayers.firstOrNull { it.id == settings.externalPlayerId }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_player),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_player_preference),
                    description = if (settings.externalPlayerEnabled) {
                        stringResource(Res.string.settings_playback_player_preference_external)
                    } else {
                        stringResource(Res.string.settings_playback_player_preference_internal)
                    },
                    isTablet = isTablet,
                    onClick = { showExternalPlayerDialog = true },
                )
                if (isIos && settings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_external_player_app),
                        description = selectedExternalPlayer?.name
                            ?: if (availableExternalPlayers.isEmpty()) {
                                stringResource(Res.string.settings_playback_external_player_none_available)
                            } else {
                                stringResource(Res.string.settings_playback_not_set)
                            },
                        isTablet = isTablet,
                        onClick = { showExternalPlayerAppDialog = true },
                    )
                }
                if (!isIos && settings.externalPlayerEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_external_player_forward_subtitles),
                        description = stringResource(Res.string.settings_playback_external_player_forward_subtitles_description),
                        checked = settings.externalPlayerForwardSubtitles,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setExternalPlayerForwardSubtitles,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_external_player_send_skip_segments),
                        description = stringResource(Res.string.settings_playback_external_player_send_skip_segments_description),
                        checked = settings.externalPlayerSendSkipSegments,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setExternalPlayerSendSkipSegments,
                    )
                }
            }
        }

        if (!isIos) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_decoder),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_engine),
                        description = settings.androidPlaybackEngine.label,
                        enabled = !settings.externalPlayerEnabled,
                        isTablet = isTablet,
                        onClick = { showPlaybackEngineDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_auto_switch_engine_on_error),
                        description = stringResource(Res.string.settings_playback_auto_switch_engine_on_error_description),
                        checked = settings.autoSwitchPlaybackEngineOnError,
                        enabled = !settings.externalPlayerEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setAutoSwitchPlaybackEngineOnError,
                    )
                }
            }
        }
    }

    if (showExternalPlayerDialog) {
        PlayerPreferenceDialog(
            isExternal = settings.externalPlayerEnabled,
            onPreferenceSelected = { external ->
                PlayerSettingsRepository.setExternalPlayerEnabled(external)
                showExternalPlayerDialog = false
            },
            onDismiss = { showExternalPlayerDialog = false },
        )
    }

    if (showExternalPlayerAppDialog) {
        ExternalPlayerSelectionDialog(
            players = availableExternalPlayers,
            selectedPlayerId = settings.externalPlayerId,
            onPlayerSelected = { playerId ->
                PlayerSettingsRepository.setExternalPlayerId(playerId)
                showExternalPlayerAppDialog = false
            },
            onDismiss = { showExternalPlayerAppDialog = false },
        )
    }

    if (showPlaybackEngineDialog) {
        PlaybackEngineDialog(
            selectedEngine = settings.androidPlaybackEngine,
            onEngineSelected = { engine ->
                PlayerSettingsRepository.setAndroidPlaybackEngine(engine)
                showPlaybackEngineDialog = false
            },
            onDismiss = { showPlaybackEngineDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlayerPreferenceDialog(
    isExternal: Boolean,
    onPreferenceSelected: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_player_preference),
    ) {
        Text(
            text = stringResource(Res.string.settings_playback_player_preference_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val internalSelected = !isExternal
            DialogOption(
                text = stringResource(Res.string.settings_playback_player_preference_internal),
                selected = internalSelected,
                onClick = { onPreferenceSelected(false) },
            )
            DialogOption(
                text = stringResource(Res.string.settings_playback_player_preference_external),
                selected = isExternal,
                onClick = { onPreferenceSelected(true) },
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ExternalPlayerSelectionDialog(
    players: List<ExternalPlayerApp>,
    selectedPlayerId: String?,
    onPlayerSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_external_player_app),
    ) {
        if (players.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_playback_external_player_none_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                players.forEach { player ->
                    val isSelected = player.id == selectedPlayerId
                    DialogOption(
                        text = player.name,
                        selected = isSelected,
                        onClick = { onPlayerSelected(player.id) },
                    )
                }
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaybackEngineDialog(
    selectedEngine: AndroidPlaybackEngine,
    onEngineSelected: (AndroidPlaybackEngine) -> Unit,
    onDismiss: () -> Unit,
) {
    val engineOrder = listOf(
        AndroidPlaybackEngine.ExoPlayer,
        AndroidPlaybackEngine.Libmpv,
        AndroidPlaybackEngine.Auto,
    )
    val descriptions = mapOf(
        AndroidPlaybackEngine.Auto to Res.string.settings_playback_engine_auto_description,
        AndroidPlaybackEngine.ExoPlayer to Res.string.settings_playback_engine_exoplayer_description,
        AndroidPlaybackEngine.Libmpv to Res.string.settings_playback_engine_libmpv_description,
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_engine),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            engineOrder.forEach { engine ->
                val isSelected = engine == selectedEngine
                DialogOption(
                    text = engine.label,
                    description = stringResource(descriptions.getValue(engine)),
                    selected = isSelected,
                    onClick = { onEngineSelected(engine) },
                )
            }
        }
    }
}
