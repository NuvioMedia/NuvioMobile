package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import com.nuvio.app.core.ui.Slider
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackUpNextSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_next_episode),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_auto_play_next_episode),
                    description = stringResource(Res.string.settings_playback_auto_play_next_episode_description),
                    checked = settings.streamAutoPlayNextEpisodeEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeEnabled,
                )
                if (settings.streamAutoPlayNextEpisodeEnabled &&
                    settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_auto_play_next_episode_fallback),
                        description = stringResource(Res.string.settings_playback_auto_play_next_episode_fallback_description),
                        checked = settings.streamAutoPlayNextEpisodeFallbackEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayNextEpisodeFallbackEnabled,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_prefer_binge_group),
                    description = stringResource(Res.string.settings_playback_prefer_binge_group_description),
                    checked = settings.streamAutoPlayPreferBingeGroup,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayPreferBingeGroup,
                )
                if (settings.streamAutoPlayPreferBingeGroup) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_reuse_binge_group),
                        description = stringResource(Res.string.settings_playback_reuse_binge_group_description),
                        checked = settings.streamAutoPlayReuseBingeGroup,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayReuseBingeGroup,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_preload_next_episode),
                    description = stringResource(Res.string.settings_playback_preload_next_episode_description),
                    checked = settings.preloadNextEpisodeSources,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPreloadNextEpisodeSources,
                )
                SettingsGroupDivider(isTablet = isTablet)
                var showThresholdModeDialog by remember { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_threshold_mode),
                    description = stringResource(settings.nextEpisodeThresholdMode.labelRes),
                    isTablet = isTablet,
                    onClick = { showThresholdModeDialog = true },
                )
                if (showThresholdModeDialog) {
                    NextEpisodeThresholdModeDialog(
                        selected = settings.nextEpisodeThresholdMode,
                        onSelect = {
                            PlayerSettingsRepository.setNextEpisodeThresholdMode(it)
                            showThresholdModeDialog = false
                        },
                        onDismiss = { showThresholdModeDialog = false },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                when (settings.nextEpisodeThresholdMode) {
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE -> {
                        val thresholdPercent = settings.nextEpisodeThresholdPercent
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.settings_playback_threshold_percentage),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(Res.string.settings_playback_threshold_percentage_description),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                ValueBox(text = stringResource(
                                    Res.string.settings_playback_threshold_percentage_value,
                                    formatStep(thresholdPercent)), modifier = Modifier.wrapContentWidth())
                            }
                            var sliderValue by remember(thresholdPercent) { mutableFloatStateOf(thresholdPercent) }
                            var lastHapticPercent by remember(thresholdPercent) { mutableStateOf(thresholdPercent) }
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    val snapped = snapToStep(it, 0.5f)
                                    sliderValue = snapped

                                    if (snapped != lastHapticPercent) {
                                        lastHapticPercent = snapped
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onValueChangeFinished = {
                                    PlayerSettingsRepository.setNextEpisodeThresholdPercent(sliderValue)
                                },
                                valueRange = 97f..100f,
                                steps = calculateSteps(97f, 100f, 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val thresholdMinutes = settings.nextEpisodeThresholdMinutesBeforeEnd
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = stringResource(Res.string.settings_playback_minutes_before_end),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(Res.string.settings_playback_minutes_before_end_description),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                ValueBox(text = stringResource(
                                        Res.string.settings_playback_minutes_value,
                                        formatStep(thresholdMinutes)), modifier = Modifier.wrapContentWidth())
                            }
                            var sliderValue by remember(thresholdMinutes) { mutableFloatStateOf(thresholdMinutes) }
                            var lastHapticMin by remember(thresholdMinutes) { mutableStateOf(thresholdMinutes) }
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    val snapped = snapToStep(it, 0.5f)
                                    sliderValue = snapped

                                    if (snapped != lastHapticMin) {
                                        lastHapticMin = snapped
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onValueChangeFinished = {
                                    PlayerSettingsRepository.setNextEpisodeThresholdMinutesBeforeEnd(sliderValue)
                                },
                                valueRange = 0f..3.5f,
                                steps = calculateSteps(0f, 3.5f, 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatStep(value: Float): String {
    return if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        value.toString()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NextEpisodeThresholdModeDialog(
    selected: com.nuvio.app.features.player.skip.NextEpisodeThresholdMode,
    onSelect: (com.nuvio.app.features.player.skip.NextEpisodeThresholdMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.entries

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_threshold_mode),
    ) {
        options.forEach { mode ->
            val isSelected = mode == selected
            DialogOption(
                text = stringResource(mode.labelRes),
                selected = isSelected,
                onClick = { onSelect(mode) },
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

private val com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.labelRes: StringResource
    get() = when (this) {
        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.PERCENTAGE ->
            Res.string.settings_playback_threshold_mode_percentage
        com.nuvio.app.features.player.skip.NextEpisodeThresholdMode.MINUTES_BEFORE_END ->
            Res.string.settings_playback_threshold_mode_minutes_before_end
    }
