package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun SubtitleAppearanceControls(
    style: SubtitleStyleState,
    spacing: Dp,
    onStyleChanged: (SubtitleStyleState) -> Unit,
) {
    var showFonts by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        SubtitleStyleSection(stringResource(Res.string.subtitle_font)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { showFonts = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    subtitleFontLabel(style.appearance.fontId),
                    modifier = Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White.copy(alpha = 0.7f))
            }
        }
        SubtitleValueControl(
            stringResource(Res.string.compose_player_font_size), style.fontSizeSp, subtitleFontSizeRangeSp, 2,
            display = stringResource(Res.string.compose_player_font_size_value, style.fontSizeSp),
        ) { onStyleChanged(style.copy(fontSizeSp = it)) }
        SubtitleToggleControl(stringResource(Res.string.compose_player_bold), style.bold) {
            onStyleChanged(style.copy(bold = it))
        }
        SubtitleColorControl(stringResource(Res.string.compose_player_color), style.textColor) {
            onStyleChanged(style.copy(textColor = it.copy(alpha = style.textColor.alpha)))
        }
        val textOpacity = (style.textColor.alpha * 100).roundToInt()
        SubtitleValueControl(stringResource(Res.string.compose_player_text_opacity), textOpacity, 0..100, 5, "$textOpacity%") {
            onStyleChanged(style.copy(textColor = style.textColor.copy(alpha = it / 100f)))
        }
        SubtitleColorControl(stringResource(Res.string.settings_playback_subtitle_background_color), style.backgroundColor) {
            onStyleChanged(style.copy(backgroundColor = it.copy(alpha = style.backgroundColor.alpha)))
        }
        val backgroundOpacity = (style.backgroundColor.alpha * 100).roundToInt()
        SubtitleValueControl(stringResource(Res.string.subtitle_background_opacity), backgroundOpacity, 0..100, 5, "$backgroundOpacity%") {
            onStyleChanged(style.copy(backgroundColor = style.backgroundColor.copy(alpha = it / 100f)))
        }
        SubtitleValueControl(
            stringResource(Res.string.subtitle_background_roundness), style.appearance.backgroundRadius, 0..24, 2,
            enabled = backgroundOpacity > 0,
        ) { onStyleChanged(style.copy(appearance = style.appearance.copy(backgroundRadius = it))) }
        SubtitleToggleControl(stringResource(Res.string.compose_player_outline), style.outlineEnabled) {
            onStyleChanged(style.copy(outlineEnabled = it))
        }
        if (style.outlineEnabled) {
            SubtitleColorControl(stringResource(Res.string.compose_player_outline_color), style.outlineColor) {
                onStyleChanged(style.copy(outlineColor = it))
            }
        }
        SubtitleToggleControl(stringResource(Res.string.subtitle_shadow), style.appearance.shadowEnabled) {
            onStyleChanged(style.copy(appearance = style.appearance.copy(shadowEnabled = it)))
        }
        if (style.appearance.shadowEnabled) {
            SubtitleValueControl(
                stringResource(Res.string.subtitle_shadow_opacity), style.appearance.shadowOpacity, 0..100, 5,
                display = "${style.appearance.shadowOpacity}%",
            ) { onStyleChanged(style.copy(appearance = style.appearance.copy(shadowOpacity = it))) }
            SubtitleValueControl(stringResource(Res.string.subtitle_shadow_size), style.appearance.shadowSize, 1..8, 1) {
                onStyleChanged(style.copy(appearance = style.appearance.copy(shadowSize = it)))
            }
        }
        SubtitleValueControl(stringResource(Res.string.compose_player_bottom_offset), style.bottomOffset, 0..200, 5) {
            onStyleChanged(style.copy(bottomOffset = it))
        }
    }
    if (showFonts) {
        SubtitleFontPicker(
            selectedId = style.appearance.fontId,
            onSelect = { onStyleChanged(style.copy(appearance = style.appearance.copy(fontId = it))) },
            onDismiss = { showFonts = false },
        )
    }
}

@Composable
private fun SubtitleValueControl(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    display: String = value.toString(),
    enabled: Boolean = true,
    onValueChanged: (Int) -> Unit,
) {
    SubtitleStyleSection(title) {
        SubtitleStyleStepper(
            value = display, enabled = enabled,
            onDecrease = { onValueChanged((value - step).coerceIn(range)) },
            onIncrease = { onValueChanged((value + step).coerceIn(range)) },
        )
    }
}

@Composable
private fun SubtitleToggleControl(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    SubtitleStyleSection(title) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (checked) tokens.colors.accent else Color.White.copy(alpha = 0.08f))
                .semantics { contentDescription = title }
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(if (checked) Res.string.compose_action_on else Res.string.compose_action_off),
                color = if (checked) tokens.colors.onAccent else Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SubtitleColorControl(title: String, color: Color, onColorSelected: (Color) -> Unit) {
    SubtitleStyleSection(title) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubtitleColorSwatches.forEach { swatch ->
                val selected = swatch.copy(alpha = 1f) == color.copy(alpha = 1f)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(swatch)
                        .border(2.dp, Color.White.copy(alpha = if (selected) 1f else 0.22f), CircleShape)
                        .semantics { contentDescription = "$title ${swatch.toStorageHexString()}" }
                        .selectable(selected, role = Role.RadioButton, onClick = { onColorSelected(swatch) }),
                )
            }
        }
    }
}
