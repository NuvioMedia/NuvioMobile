package com.nuvio.app.features.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.Slider
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.player_video_settings_brightness
import nuvio.composeapp.generated.resources.player_video_settings_contrast
import nuvio.composeapp.generated.resources.player_video_settings_deband
import nuvio.composeapp.generated.resources.player_video_settings_deband_desc
import nuvio.composeapp.generated.resources.player_video_settings_gamma
import nuvio.composeapp.generated.resources.player_video_settings_hdr_peak_detection
import nuvio.composeapp.generated.resources.player_video_settings_hdr_peak_detection_desc
import nuvio.composeapp.generated.resources.player_video_settings_interpolation
import nuvio.composeapp.generated.resources.player_video_settings_interpolation_desc
import nuvio.composeapp.generated.resources.player_video_settings_output_preset
import nuvio.composeapp.generated.resources.player_video_settings_reset_tuning
import nuvio.composeapp.generated.resources.player_video_settings_saturation
import nuvio.composeapp.generated.resources.player_video_settings_title
import nuvio.composeapp.generated.resources.player_video_settings_tone_mapping
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun IosVideoSettingsModal(
    visible: Boolean,
    settings: PlayerSettingsUiState,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(
                title = stringResource(Res.string.player_video_settings_title),
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.player_video_settings_reset_tuning),
                    onClick = {
                        PlayerSettingsRepository.resetIosVideoOutputTuning()
                        onSettingsChanged()
                    },
                )
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OptionGroup(
                    title = stringResource(Res.string.player_video_settings_output_preset),
                    options = IosVideoOutputPreset.entries,
                    selected = settings.iosVideoOutputPreset,
                    label = { it.localizedLabel() },
                    description = { it.localizedDescription() },
                    onSelect = {
                        PlayerSettingsRepository.setIosVideoOutputPreset(it)
                        onSettingsChanged()
                    },
                )

                ToggleRow(
                    title = stringResource(Res.string.player_video_settings_hdr_peak_detection),
                    description = stringResource(Res.string.player_video_settings_hdr_peak_detection_desc),
                    checked = settings.iosHdrComputePeakEnabled,
                    onCheckedChange = {
                        PlayerSettingsRepository.setIosHdrComputePeakEnabled(it)
                        onSettingsChanged()
                    },
                )

                OptionGroup(
                    title = stringResource(Res.string.player_video_settings_tone_mapping),
                    options = IosToneMappingMode.entries,
                    selected = settings.iosToneMappingMode,
                    label = { it.label },
                    onSelect = {
                        PlayerSettingsRepository.setIosToneMappingMode(it)
                        onSettingsChanged()
                    },
                )

                ToggleRow(
                    title = stringResource(Res.string.player_video_settings_deband),
                    description = stringResource(Res.string.player_video_settings_deband_desc),
                    checked = settings.iosDebandEnabled,
                    onCheckedChange = {
                        PlayerSettingsRepository.setIosDebandEnabled(it)
                        onSettingsChanged()
                    },
                )
                ToggleRow(
                    title = stringResource(Res.string.player_video_settings_interpolation),
                    description = stringResource(Res.string.player_video_settings_interpolation_desc),
                    checked = settings.iosInterpolationEnabled,
                    onCheckedChange = {
                        PlayerSettingsRepository.setIosInterpolationEnabled(it)
                        onSettingsChanged()
                    },
                )

                PictureSlider(
                    title = stringResource(Res.string.player_video_settings_brightness),
                    value = settings.iosBrightness,
                    onValueChanged = {
                        PlayerSettingsRepository.setIosBrightness(it)
                        onSettingsChanged()
                    },
                )
                PictureSlider(
                    title = stringResource(Res.string.player_video_settings_contrast),
                    value = settings.iosContrast,
                    onValueChanged = {
                        PlayerSettingsRepository.setIosContrast(it)
                        onSettingsChanged()
                    },
                )
                PictureSlider(
                    title = stringResource(Res.string.player_video_settings_saturation),
                    value = settings.iosSaturation,
                    onValueChanged = {
                        PlayerSettingsRepository.setIosSaturation(it)
                        onSettingsChanged()
                    },
                )
                PictureSlider(
                    title = stringResource(Res.string.player_video_settings_gamma),
                    value = settings.iosGamma,
                    onValueChanged = {
                        PlayerSettingsRepository.setIosGamma(it)
                        onSettingsChanged()
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PictureSlider(
    title: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(text = value.toString(), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChanged(it.roundToInt().coerceIn(-50, 50)) },
            valueRange = -50f..50f,
            steps = 99,
        )
    }
}

@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: @Composable ((T) -> String)? = null,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = label(option), color = MaterialTheme.colorScheme.onSurface)
                            val subtitle = description?.invoke(option)
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
