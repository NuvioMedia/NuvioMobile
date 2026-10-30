package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StreamAutoPlayModeDialog(
    selectedMode: StreamAutoPlayMode,
    onModeSelected: (StreamAutoPlayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            StreamAutoPlayMode.MANUAL,
            Res.string.settings_playback_stream_selection_mode_manual,
            Res.string.settings_playback_stream_selection_mode_manual_description,
        ),
        Triple(
            StreamAutoPlayMode.FIRST_STREAM,
            Res.string.settings_playback_stream_selection_mode_first_stream,
            Res.string.settings_playback_stream_selection_mode_first_stream_description,
        ),
        Triple(
            StreamAutoPlayMode.REGEX_MATCH,
            Res.string.settings_playback_stream_selection_mode_regex,
            Res.string.settings_playback_stream_selection_mode_regex_description,
        ),
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_stream_selection_mode),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (mode, titleRes, descriptionRes) ->
                val isSelected = mode == selectedMode
                DialogOption(
                    text = stringResource(titleRes),
                    description = stringResource(descriptionRes),
                    selected = isSelected,
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun StreamAutoPlaySourceDialog(
    pluginsEnabled: Boolean,
    selectedSource: StreamAutoPlaySource,
    onSourceSelected: (StreamAutoPlaySource) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList {
        add(
            Triple(
                StreamAutoPlaySource.ALL_SOURCES,
                if (pluginsEnabled) {
                    Res.string.settings_playback_source_scope_all_sources
                } else {
                    Res.string.settings_playback_source_scope_all_addons
                },
                if (pluginsEnabled) {
                    Res.string.settings_playback_source_scope_all_sources_description
                } else {
                    Res.string.settings_playback_source_scope_all_addons_description
                },
            ),
        )
        add(
            Triple(
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                Res.string.settings_playback_source_scope_installed_addons_only,
                Res.string.settings_playback_source_scope_installed_addons_only_description,
            ),
        )
        if (pluginsEnabled) {
            add(
                Triple(
                    StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                    Res.string.settings_playback_source_scope_enabled_plugins_only,
                    Res.string.settings_playback_source_scope_enabled_plugins_only_description,
                ),
            )
        }
    }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_source_scope),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (source, titleRes, descriptionRes) ->
                val isSelected = source == selectedSource
                DialogOption(
                    text = stringResource(titleRes),
                    description = stringResource(descriptionRes),
                    selected = isSelected,
                    onClick = { onSourceSelected(source) },
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
internal fun StreamAutoPlayProviderSelectionDialog(
    title: String,
    allLabel: String,
    items: List<String>,
    selectedItems: Set<String>,
    onSelectionSaved: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(selectedItems, items) {
        mutableStateOf(selectedItems.intersect(items.toSet()))
    }

    DialogSurface(
        onDismissRequest = {
            onSelectionSaved(selected)
            onDismiss()
        },
        title = title,
    ) {
        DialogOption(
            text = allLabel,
            selected = selected.isEmpty(),
            onClick = { selected = emptySet() },
        )

        if (items.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_playback_no_items_available),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = items.size,
                    key = { items[it] },
                ) { index ->
                    val item = items[index]
                    val isSelected = item in selected
                    DialogOption(
                        text = item,
                        selected = isSelected,
                        onClick = {
                            selected = if (isSelected) selected - item else selected + item
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(Res.string.settings_playback_dialog_save_close),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StreamAutoPlayRegexDialog(
    initialRegex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var regex by remember(initialRegex) { mutableStateOf(initialRegex) }
    var regexError by remember { mutableStateOf<String?>(null) }

    val invalidRegexPattern = stringResource(Res.string.settings_playback_invalid_regex_pattern)
    val presets = listOf(
        stringResource(Res.string.settings_playback_regex_preset_any_1080p) to "(2160p|4k|1080p)",
        stringResource(Res.string.settings_playback_regex_preset_quality_4k_remux) to "(2160p|4k|remux)",
        stringResource(Res.string.settings_playback_regex_preset_quality_1080p_standard) to "(1080p|full\\s*hd)",
        stringResource(Res.string.settings_playback_regex_preset_quality_720p_smaller) to "(720p|webrip|web-dl)",
        stringResource(Res.string.settings_playback_regex_preset_web_sources) to "(web[-\\s]?dl|webrip)",
        stringResource(Res.string.settings_playback_regex_preset_bluray_quality) to "(bluray|b[dr]rip|remux)",
        stringResource(Res.string.settings_playback_regex_preset_hevc_x265) to "(hevc|x265|h\\.265)",
        stringResource(Res.string.settings_playback_regex_preset_avc_x264) to "(x264|h\\.264|avc)",
        stringResource(Res.string.settings_playback_regex_preset_hdr_dolby_vision) to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
        stringResource(Res.string.settings_playback_regex_preset_dolby_atmos_dts) to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
        stringResource(Res.string.settings_playback_regex_preset_english) to "(\\beng\\b|english)",
        stringResource(Res.string.settings_playback_regex_preset_no_cam_ts) to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$",
        stringResource(Res.string.settings_playback_regex_preset_no_remux_hdr) to "(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$",
    )

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_regex_pattern),
    ) {
        Text(
            text = stringResource(Res.string.settings_playback_regex_matches_against),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(Res.string.settings_playback_presets),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                count = presets.size,
                key = { presets[it].first },
            ) { index ->
                val (label, pattern) = presets[index]
                Surface(
                    modifier = Modifier.clickable {
                        regex = pattern
                        regexError = null
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(
                1.dp,
                if (regexError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            ),
        ) {
            BasicTextField(
                value = regex,
                onValueChange = {
                    regex = it
                    regexError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (regex.isBlank()) {
                        Text(
                            text = stringResource(Res.string.settings_playback_regex_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    innerTextField()
                },
            )
        }

        if (regexError != null) {
            Text(
                text = regexError ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_clear),
                onClick = {
                    regex = ""
                    regexError = null
                },
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = {
                    val value = regex.trim()
                    if (value.isNotEmpty()) {
                        val valid = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
                        if (!valid) {
                            regexError = invalidRegexPattern
                            return@DialogButton
                        }
                    }
                    onSave(value)
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
