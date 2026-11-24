package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.audiosync.AudioSamplingMode
import com.nuvio.app.features.audiosync.AudioSyncSettings
import com.nuvio.app.features.audiosync.SubtitleSyncStatus
import com.nuvio.app.features.audiosync.ensureAudioSyncInitialized
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.audio_sync_model_delete
import nuvio.composeapp.generated.resources.audio_sync_model_description
import nuvio.composeapp.generated.resources.audio_sync_model_download
import nuvio.composeapp.generated.resources.audio_sync_model_downloading
import nuvio.composeapp.generated.resources.audio_sync_model_failed
import nuvio.composeapp.generated.resources.audio_sync_model_ready
import nuvio.composeapp.generated.resources.audio_sync_model_title
import nuvio.composeapp.generated.resources.audio_sync_sampling_description
import nuvio.composeapp.generated.resources.audio_sync_sampling_high
import nuvio.composeapp.generated.resources.audio_sync_sampling_high_description
import nuvio.composeapp.generated.resources.audio_sync_sampling_low
import nuvio.composeapp.generated.resources.audio_sync_sampling_low_description
import nuvio.composeapp.generated.resources.audio_sync_sampling_off
import nuvio.composeapp.generated.resources.audio_sync_sampling_off_description
import nuvio.composeapp.generated.resources.audio_sync_sampling_title
import nuvio.composeapp.generated.resources.audio_sync_setting_description
import nuvio.composeapp.generated.resources.audio_sync_setting_title
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.isIos

/** Audio fallback settings, beside Auto Sync in Playback -> Subtitles. */
@Composable
internal fun AudioSyncPlaybackSettingsRows(
    isTablet: Boolean,
    enabled: Boolean,
) {
    ensureAudioSyncInitialized()
    val autoSyncOn by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart
    }.collectAsStateWithLifecycle()
    val fallbackOn by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
    var showSamplingDialog by remember { mutableStateOf(false) }

    SettingsSwitchRow(
        title = stringResource(Res.string.audio_sync_setting_title),
        description = stringResource(Res.string.audio_sync_setting_description),
        checked = fallbackOn,
        enabled = enabled && autoSyncOn,
        isTablet = isTablet,
        onCheckedChange = AudioSyncSettings::setFallbackEnabled,
    )
    if (autoSyncOn && fallbackOn) {
        val model by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()
        val storedSampling by AudioSyncSettings.samplingMode.collectAsStateWithLifecycle()
        val sampling = if (isIos && storedSampling == AudioSamplingMode.OFF) AudioSamplingMode.LOW else storedSampling
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.audio_sync_model_title, model.sizeMb),
            description = when {
                model.downloading -> stringResource(
                    Res.string.audio_sync_model_downloading,
                    (model.progress * 100).toInt(),
                )
                model.downloaded -> stringResource(Res.string.audio_sync_model_ready)
                model.error != null -> stringResource(Res.string.audio_sync_model_failed)
                else -> stringResource(Res.string.audio_sync_model_description)
            },
            enabled = enabled && model.supported && !model.downloading,
            isTablet = isTablet,
            trailingContent = {
                ValueBox(
                    text = stringResource(
                        if (model.downloaded) {
                            Res.string.audio_sync_model_delete
                        } else {
                            Res.string.audio_sync_model_download
                        },
                    ),
                )
            },
            onClick = {
                if (model.downloaded) SubtitleSyncStatus.modelActions?.delete()
                else SubtitleSyncStatus.modelActions?.download()
            },
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.audio_sync_sampling_title),
            description = stringResource(Res.string.audio_sync_sampling_description),
            enabled = enabled,
            isTablet = isTablet,
            trailingContent = {
                ValueBox(text = audioSamplingLabel(sampling))
            },
            onClick = { showSamplingDialog = true },
        )
        if (showSamplingDialog) {
            AudioSamplingModeDialog(
                selectedMode = sampling,
                onModeSelected = { mode ->
                    AudioSyncSettings.setSamplingMode(mode)
                    showSamplingDialog = false
                },
                onDismiss = { showSamplingDialog = false },
            )
        }
    }
}

@Composable
private fun AudioSamplingModeDialog(
    selectedMode: AudioSamplingMode,
    onModeSelected: (AudioSamplingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.audio_sync_sampling_title),
        message = stringResource(Res.string.audio_sync_sampling_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AudioSamplingMode.entries.filter { !isIos || it != AudioSamplingMode.OFF }.forEach { mode ->
                DialogOption(
                    text = audioSamplingLabel(mode),
                    description = audioSamplingDescription(mode),
                    selected = mode == selectedMode,
                    onClick = { onModeSelected(mode) },
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

@Composable
private fun audioSamplingLabel(mode: AudioSamplingMode): String = stringResource(
    when (mode) {
        AudioSamplingMode.OFF -> Res.string.audio_sync_sampling_off
        AudioSamplingMode.LOW -> Res.string.audio_sync_sampling_low
        AudioSamplingMode.HIGH -> Res.string.audio_sync_sampling_high
    },
)

@Composable
private fun audioSamplingDescription(mode: AudioSamplingMode): String = stringResource(
    when (mode) {
        AudioSamplingMode.OFF -> Res.string.audio_sync_sampling_off_description
        AudioSamplingMode.LOW -> Res.string.audio_sync_sampling_low_description
        AudioSamplingMode.HIGH -> Res.string.audio_sync_sampling_high_description
    },
)
