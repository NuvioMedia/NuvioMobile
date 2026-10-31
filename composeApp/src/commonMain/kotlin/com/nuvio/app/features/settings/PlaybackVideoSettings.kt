package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AndroidLibmpvVideoOutput
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.IosHardwareDecoderMode
import com.nuvio.app.features.player.localizedLabel
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackVideoSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showLibmpvVideoOutputDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showIosHardwareDecoderDialog by remember { mutableStateOf(false) }
    var showIosTargetPrimariesDialog by remember { mutableStateOf(false) }
    var showIosTargetTransferDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        if (!isIos) {
            val decoderEnabled = !settings.externalPlayerEnabled
            val exoOptionsEnabled = decoderEnabled && settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv
            val libmpvOptionsVisible = settings.androidPlaybackEngine != AndroidPlaybackEngine.ExoPlayer
            val libmpvOptionsEnabled = decoderEnabled && libmpvOptionsVisible
            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_decoder),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (libmpvOptionsVisible) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_libmpv_video_output),
                            description = settings.androidLibmpvVideoOutput.label,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onClick = { showLibmpvVideoOutputDialog = true },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_libmpv_hardware_decoding),
                            description = stringResource(Res.string.settings_playback_libmpv_hardware_decoding_description),
                            checked = settings.androidLibmpvHardwareDecodingEnabled,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setAndroidLibmpvHardwareDecodingEnabled,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_libmpv_yuv420p),
                            description = stringResource(Res.string.settings_playback_libmpv_yuv420p_description),
                            checked = settings.androidLibmpvYuv420pEnabled,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setAndroidLibmpvYuv420pEnabled,
                        )
                    }
                    if (libmpvOptionsVisible) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_decoder_priority),
                        description = decoderPriorityLabel(settings.decoderPriority),
                        enabled = exoOptionsEnabled,
                        isTablet = isTablet,
                        onClick = { showDecoderPriorityDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_map_dv7_to_hevc),
                        description = stringResource(Res.string.settings_playback_map_dv7_to_hevc_description),
                        checked = settings.mapDV7ToHevc,
                        enabled = exoOptionsEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setMapDV7ToHevc,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_tunneled_playback),
                        description = stringResource(Res.string.settings_playback_tunneled_playback_description),
                        checked = settings.tunnelingEnabled,
                        enabled = exoOptionsEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setTunnelingEnabled,
                    )
                }
            }
        } else {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_ios_video_output),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_hardware_decoder),
                        description = settings.iosHardwareDecoderMode.localizedLabel(),
                        isTablet = isTablet,
                        onClick = { showIosHardwareDecoderDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_extended_dynamic_range),
                        description = stringResource(Res.string.settings_playback_ios_extended_dynamic_range_desc),
                        checked = settings.iosExtendedDynamicRangeEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosExtendedDynamicRangeEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_display_color_hint),
                        description = stringResource(Res.string.settings_playback_ios_display_color_hint_desc),
                        checked = settings.iosTargetColorspaceHintEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosTargetColorspaceHintEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_target_primaries),
                        description = settings.iosTargetPrimaries.label,
                        isTablet = isTablet,
                        onClick = { showIosTargetPrimariesDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_target_transfer),
                        description = settings.iosTargetTransfer.label,
                        isTablet = isTablet,
                        onClick = { showIosTargetTransferDialog = true },
                    )
                }
            }
        }
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = settings.decoderPriority,
            onPrioritySelected = { priority ->
                PlayerSettingsRepository.setDecoderPriority(priority)
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false },
        )
    }

    if (showLibmpvVideoOutputDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_playback_libmpv_video_output_dialog),
            options = AndroidLibmpvVideoOutput.entries,
            selected = settings.androidLibmpvVideoOutput,
            label = { it.label },
            description = { it.description },
            onSelect = {
                PlayerSettingsRepository.setAndroidLibmpvVideoOutput(it)
                showLibmpvVideoOutputDialog = false
            },
            onDismiss = { showLibmpvVideoOutputDialog = false },
        )
    }

    if (showIosHardwareDecoderDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_playback_ios_hw_decoder_dialog),
            options = IosHardwareDecoderMode.entries,
            selected = settings.iosHardwareDecoderMode,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosHardwareDecoderMode(it)
                showIosHardwareDecoderDialog = false
            },
            onDismiss = { showIosHardwareDecoderDialog = false },
        )
    }

    if (showIosTargetPrimariesDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_playback_ios_target_primaries_dialog),
            options = IosTargetPrimaries.entries,
            selected = settings.iosTargetPrimaries,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosTargetPrimaries(it)
                showIosTargetPrimariesDialog = false
            },
            onDismiss = { showIosTargetPrimariesDialog = false },
        )
    }

    if (showIosTargetTransferDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_playback_ios_target_transfer_dialog),
            options = IosTargetTransfer.entries,
            selected = settings.iosTargetTransfer,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosTargetTransfer(it)
                showIosTargetTransferDialog = false
            },
            onDismiss = { showIosTargetTransferDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        0 to Res.string.settings_playback_decoder_device_only,
        1 to Res.string.settings_playback_decoder_prefer_device,
        2 to Res.string.settings_playback_decoder_prefer_app,
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_decoder_priority),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (priority, labelRes) ->
                val isSelected = priority == selectedPriority
                DialogOption(
                    text = stringResource(labelRes),
                    selected = isSelected,
                    onClick = { onPrioritySelected(priority) },
                )
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

private fun decoderPriorityRes(priority: Int): StringResource = when (priority) {
    0 -> Res.string.settings_playback_decoder_device_only
    1 -> Res.string.settings_playback_decoder_prefer_device
    2 -> Res.string.settings_playback_decoder_prefer_app
    else -> Res.string.settings_playback_decoder_prefer_device
}

@Composable
private fun decoderPriorityLabel(priority: Int): String = stringResource(decoderPriorityRes(priority))
