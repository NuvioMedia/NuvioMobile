package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun SubtitleAppearanceSettings(
    style: SubtitleStyleState,
    isTablet: Boolean = false,
    enabled: Boolean = true,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    var showFonts by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF182A35), Color(0xFF53646B), Color(0xFF1C2934))),
                RoundedCornerShape(16.dp),
            ).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.subtitle_preview), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Box(Modifier.fillMaxWidth().heightIn(min = 88.dp), contentAlignment = Alignment.Center) {
                StyledSubtitleText(stringResource(Res.string.subtitle_preview_text), style)
            }
        }
        Text(
            stringResource(Res.string.subtitle_appearance_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup(isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.subtitle_font),
                description = subtitleFontLabel(style.appearance.fontId),
                enabled = enabled, isTablet = isTablet, onClick = { showFonts = true },
            )
            SettingsGroupDivider(isTablet)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_playback_subtitle_size),
                value = style.fontSizeSp,
                valueText = { stringResource(Res.string.compose_player_font_size_value, it) },
                valueRange = subtitleFontSizeRangeSp, step = 2, isTablet = isTablet, enabled = enabled,
                onValueChange = { onStyleChanged(style.copy(fontSizeSp = it)) },
            )
            SettingsGroupDivider(isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_subtitle_bold),
                checked = style.bold, enabled = enabled, isTablet = isTablet,
                onCheckedChange = { onStyleChanged(style.copy(bold = it)) },
            )
            SettingsGroupDivider(isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_subtitle_text_color),
                description = subtitleColorLabel(style.textColor),
                enabled = enabled, isTablet = isTablet, onClick = { colorTarget = "text" },
            )
            SettingsSliderRow(
                title = stringResource(Res.string.compose_player_text_opacity),
                value = (style.textColor.alpha * 100).roundToInt(), valueText = { "$it%" },
                valueRange = 0..100, step = 5, isTablet = isTablet, enabled = enabled,
                onValueChange = { onStyleChanged(style.copy(textColor = style.textColor.copy(alpha = it / 100f))) },
            )
        }
        SettingsGroup(isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_subtitle_background_color),
                description = subtitleColorLabel(style.backgroundColor.copy(alpha = 1f)),
                enabled = enabled, isTablet = isTablet, onClick = { colorTarget = "background" },
            )
            SettingsSliderRow(
                title = stringResource(Res.string.subtitle_background_opacity),
                value = (style.backgroundColor.alpha * 100).roundToInt(), valueText = { "$it%" },
                valueRange = 0..100, step = 5, isTablet = isTablet, enabled = enabled,
                onValueChange = { onStyleChanged(style.copy(backgroundColor = style.backgroundColor.copy(alpha = it / 100f))) },
            )
            SettingsSliderRow(
                title = stringResource(Res.string.subtitle_background_roundness),
                value = style.appearance.backgroundRadius, valueText = { it.toString() },
                valueRange = 0..24, step = 2, isTablet = isTablet, enabled = enabled && style.backgroundColor.alpha > 0,
                onValueChange = { onStyleChanged(style.copy(appearance = style.appearance.copy(backgroundRadius = it))) },
            )
        }
        SettingsGroup(isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_subtitle_outline),
                checked = style.outlineEnabled, enabled = enabled, isTablet = isTablet,
                onCheckedChange = { onStyleChanged(style.copy(outlineEnabled = it)) },
            )
            if (style.outlineEnabled) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_subtitle_outline_color),
                    description = subtitleColorLabel(style.outlineColor),
                    enabled = enabled, isTablet = isTablet, onClick = { colorTarget = "outline" },
                )
            }
            SettingsGroupDivider(isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.subtitle_shadow),
                description = stringResource(Res.string.subtitle_shadow_description),
                checked = style.appearance.shadowEnabled, enabled = enabled, isTablet = isTablet,
                onCheckedChange = { onStyleChanged(style.copy(appearance = style.appearance.copy(shadowEnabled = it))) },
            )
            if (style.appearance.shadowEnabled) {
                SettingsSliderRow(
                    title = stringResource(Res.string.subtitle_shadow_opacity),
                    value = style.appearance.shadowOpacity, valueText = { "$it%" },
                    valueRange = 0..100, step = 5, isTablet = isTablet, enabled = enabled,
                    onValueChange = { onStyleChanged(style.copy(appearance = style.appearance.copy(shadowOpacity = it))) },
                )
                SettingsSliderRow(
                    title = stringResource(Res.string.subtitle_shadow_size),
                    value = style.appearance.shadowSize, valueText = { it.toString() },
                    valueRange = 1..8, step = 1, isTablet = isTablet, enabled = enabled,
                    onValueChange = { onStyleChanged(style.copy(appearance = style.appearance.copy(shadowSize = it))) },
                )
            }
        }
        SettingsGroup(isTablet) {
            SettingsSliderRow(
                title = stringResource(Res.string.settings_playback_subtitle_vertical_offset),
                value = style.bottomOffset, valueText = { it.toString() },
                valueRange = 0..200, step = 5, isTablet = isTablet, enabled = enabled,
                onValueChange = { onStyleChanged(style.copy(bottomOffset = it)) },
            )
        }
        TextButton(onClick = { onStyleChanged(style.resetAppearance()) }, enabled = enabled) {
            Text(stringResource(Res.string.subtitle_reset_appearance))
        }
    }
    if (showFonts) {
        SubtitleFontPicker(
            selectedId = style.appearance.fontId,
            onSelect = { onStyleChanged(style.copy(appearance = style.appearance.copy(fontId = it))) },
            onDismiss = { showFonts = false },
        )
    }
    colorTarget?.let { target ->
        val color = when (target) {
            "background" -> style.backgroundColor
            "outline" -> style.outlineColor
            else -> style.textColor
        }
        val title = when (target) {
            "background" -> Res.string.settings_playback_subtitle_background_color
            "outline" -> Res.string.settings_playback_subtitle_outline_color
            else -> Res.string.settings_playback_subtitle_text_color
        }
        SubtitleColorDialog(
            title = stringResource(title),
            colors = SubtitleColorSwatches + Color.Black,
            selectedColor = color.copy(alpha = 1f),
            onColorSelected = {
                val selected = it.copy(alpha = color.alpha)
                onStyleChanged(when (target) {
                    "background" -> style.copy(backgroundColor = selected)
                    "outline" -> style.copy(outlineColor = selected)
                    else -> style.copy(textColor = selected)
                })
                colorTarget = null
            },
            onDismiss = { colorTarget = null },
        )
    }
}
