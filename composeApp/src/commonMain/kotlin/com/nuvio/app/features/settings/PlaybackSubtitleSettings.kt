package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.toStorageHexString
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackSubtitleSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showPreferredSubtitleDialog by remember { mutableStateOf(false) }
    var showSecondarySubtitleDialog by remember { mutableStateOf(false) }
    var showLibassRenderScaleDialog by remember { mutableStateOf(false) }
    var showLibassRenderModeDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_subtitles),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_preferred_subtitle_language),
                    description = when (settings.preferredSubtitleLanguage) {
                        SubtitleLanguageOption.NONE -> stringResource(Res.string.settings_playback_option_none)
                        SubtitleLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
                        SubtitleLanguageOption.FORCED -> stringResource(Res.string.settings_playback_option_forced)
                        else -> languageLabelForCode(settings.preferredSubtitleLanguage)
                    },
                    enabled = !settings.externalPlayerEnabled || settings.externalPlayerForwardSubtitles,
                    isTablet = isTablet,
                    onClick = { showPreferredSubtitleDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_secondary_subtitle_language),
                    description = languageLabelForCode(settings.secondaryPreferredSubtitleLanguage),
                    enabled = !settings.externalPlayerEnabled || settings.externalPlayerForwardSubtitles,
                    isTablet = isTablet,
                    onClick = { showSecondarySubtitleDialog = true },
                )
                if (!isIos) {
                    SettingsGroupDivider(isTablet = isTablet)
                    AutoSyncPlaybackSettingsRows(
                        isTablet = isTablet,
                        enabled = !settings.externalPlayerEnabled,
                        preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_strip_sdh),
                    description = stringResource(Res.string.settings_playback_subtitle_strip_sdh_description),
                    checked = settings.subtitleStyle.stripSdh,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            settings.subtitleStyle.copy(stripSdh = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_use_forced),
                    description = stringResource(Res.string.settings_playback_subtitle_use_forced_description),
                    checked = settings.subtitleStyle.useForcedSubtitles,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            settings.subtitleStyle.copy(useForcedSubtitles = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_show_preferred_only),
                    description = stringResource(Res.string.settings_playback_subtitle_show_preferred_only_description),
                    checked = settings.subtitleStyle.showOnlyPreferredLanguages,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            settings.subtitleStyle.copy(showOnlyPreferredLanguages = enabled),
                        )
                    },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_subtitle_rendering),
            isTablet = isTablet,
        ) {
            val subtitleRenderingEnabled = !settings.externalPlayerEnabled
            SubtitleAppearanceSettings(
                style = settings.subtitleStyle,
                isTablet = isTablet,
                enabled = subtitleRenderingEnabled,
                onStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
            )
            val showLibassSettings = !isIos && settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv
            if (showLibassSettings) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_enable_libass),
                        description = stringResource(Res.string.settings_playback_enable_libass_description),
                        checked = settings.useLibass,
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setUseLibass,
                    )
                    if (settings.useLibass) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_render_mode),
                            description = if (settings.tunnelingEnabled) {
                                stringResource(Res.string.settings_playback_render_mode_tunneled)
                            } else {
                                when (settings.libassRenderMode) {
                                    "OVERLAY" -> stringResource(Res.string.settings_playback_render_mode_overlay)
                                    else -> stringResource(Res.string.settings_playback_render_mode_video_effect)
                                }
                            },
                            enabled = subtitleRenderingEnabled && !settings.tunnelingEnabled,
                            isTablet = isTablet,
                            onClick = { showLibassRenderModeDialog = true },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_render_scale),
                            description = libassRenderScaleLabel(settings.libassRenderScale),
                            enabled = subtitleRenderingEnabled,
                            isTablet = isTablet,
                            onClick = { showLibassRenderScaleDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showPreferredSubtitleDialog) {
        LanguageSelectionDialog(
            title = stringResource(Res.string.settings_playback_preferred_subtitle_language),
            options = listOf(
                LanguageSelectionOption(SubtitleLanguageOption.NONE, stringResource(Res.string.settings_playback_option_none)),
                LanguageSelectionOption(SubtitleLanguageOption.DEVICE, stringResource(Res.string.settings_playback_option_device_language)),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, stringResource(Res.string.settings_playback_option_forced)),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, stringResource(option.labelRes))
            },
            selectedValue = settings.preferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredSubtitleLanguage(value ?: SubtitleLanguageOption.NONE)
                showPreferredSubtitleDialog = false
            },
            onDismiss = { showPreferredSubtitleDialog = false },
        )
    }

    if (showSecondarySubtitleDialog) {
        LanguageSelectionDialog(
            title = stringResource(Res.string.settings_playback_secondary_subtitle_language),
            options = listOf(
                LanguageSelectionOption(null, stringResource(Res.string.settings_playback_option_none)),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, stringResource(Res.string.settings_playback_option_forced)),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, stringResource(option.labelRes))
            },
            selectedValue = settings.secondaryPreferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(value)
                showSecondarySubtitleDialog = false
            },
            onDismiss = { showSecondarySubtitleDialog = false },
        )
    }

    if (showLibassRenderScaleDialog) {
        LibassRenderScaleDialog(
            selectedScale = settings.libassRenderScale,
            onScaleSelected = { scale ->
                PlayerSettingsRepository.setLibassRenderScale(scale)
                showLibassRenderScaleDialog = false
            },
            onDismiss = { showLibassRenderScaleDialog = false },
        )
    }

    if (showLibassRenderModeDialog) {
        LibassRenderModeDialog(
            selectedMode = settings.libassRenderMode,
            onModeSelected = { mode ->
                PlayerSettingsRepository.setLibassRenderMode(mode)
                showLibassRenderModeDialog = false
            },
            onDismiss = { showLibassRenderModeDialog = false },
        )
    }
}

@Composable
internal fun subtitleColorLabel(color: Color): String {
    return if (color.alpha == 0f) {
        stringResource(Res.string.settings_playback_subtitle_color_transparent)
    } else {
        color.toStorageHexString()
    }
}

private fun libassRenderScaleLabel(scale: String): String = when (scale) {
    "FULL" -> "1:1"
    "THREE_QUARTER" -> "\u00BE"
    "HALF" -> "\u00BD"
    "THIRD" -> "\u2153"
    "QUARTER" -> "\u00BC"
    else -> scale
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LibassRenderScaleDialog(
    selectedScale: String,
    onScaleSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "FULL" to "1:1",
        "THREE_QUARTER" to "\u00BE",
        "HALF" to "\u00BD",
        "THIRD" to "\u2153",
        "QUARTER" to "\u00BC",
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_render_scale),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selectedScale
                DialogOption(
                    text = label,
                    selected = isSelected,
                    onClick = { onScaleSelected(value) },
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
@OptIn(ExperimentalMaterial3Api::class)
private fun LibassRenderModeDialog(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        "OVERLAY" to Pair(
            Res.string.settings_playback_render_mode_overlay,
            Res.string.settings_playback_render_mode_overlay_description,
        ),
        "VIDEO_EFFECT" to Pair(
            Res.string.settings_playback_render_mode_video_effect,
            Res.string.settings_playback_render_mode_video_effect_description,
        ),
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_render_mode),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, res) ->
                DialogOption(
                    text = stringResource(res.first),
                    description = stringResource(res.second),
                    selected = value == selectedMode,
                    onClick = { onModeSelected(value) },
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun SubtitleColorDialog(
    title: String,
    colors: List<Color>,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { color ->
                DialogOption(
                    text = subtitleColorLabel(color),
                    selected = selectedColor.toStorageHexString() == color.toStorageHexString(),
                    onClick = { onColorSelected(color) },
                    leading = {
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (color.alpha == 0f) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                color
                            },
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            ),
                        ) {}
                    },
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
