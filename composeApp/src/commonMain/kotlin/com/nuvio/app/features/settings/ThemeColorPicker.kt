package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.core.ui.HsvColor
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.core.ui.nuvio
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.custom_theme_brightness
import nuvio.composeapp.generated.resources.custom_theme_hue
import nuvio.composeapp.generated.resources.custom_theme_saturation
import org.jetbrains.compose.resources.stringResource

private val colorSwatches = listOf(
    0xFF6B6B, 0xFFB37A, 0xFFD45C, 0x22D37C, 0x4DE3FF,
    0x3185F5, 0xB75AFF, 0xEC70A9, 0xFFFFFF,
)

@Composable
internal fun ThemeColorPicker(
    color: Int,
    colorIndex: Int,
    onColorChanged: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var hsv by remember(colorIndex) { mutableStateOf(HsvColor.fromRgb(color)) }
    var emittedColor by remember(colorIndex) { mutableStateOf(color) }
    LaunchedEffect(color, colorIndex) {
        if (color != emittedColor) {
            hsv = HsvColor.fromRgb(color)
            emittedColor = color
        }
    }
    fun updateColor(value: HsvColor) {
        hsv = value
        emittedColor = value.toRgb()
        onColorChanged(emittedColor)
    }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            colorSwatches.forEach { swatch ->
                Box(
                    Modifier
                        .size(NuvioTokens.Space.s48)
                        .clip(CircleShape)
                        .selectable(color == swatch, role = Role.RadioButton, onClick = { onColorChanged(swatch) })
                        .semantics { contentDescription = formatHexColor(swatch) }
                        .padding(NuvioTokens.Space.s6)
                        .border(
                            tokens.borders.medium,
                            if (color == swatch) tokens.colors.textPrimary else tokens.colors.borderDefault,
                            CircleShape,
                        )
                        .padding(NuvioTokens.Space.s4)
                        .clip(CircleShape)
                        .background(Color(swatch or 0xFF000000.toInt())),
                )
            }
        }
        ColorChannelSlider(
            title = stringResource(Res.string.custom_theme_hue),
            value = hsv.hue,
            valueRange = 0f..359f,
            valueLabel = "${hsv.hue.roundToInt()}°",
            brush = remember { Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }) },
            onValueChange = { updateColor(hsv.copy(hue = it)) },
        )
        ColorChannelSlider(
            title = stringResource(Res.string.custom_theme_saturation),
            value = hsv.saturation,
            valueLabel = "${(hsv.saturation * 100).roundToInt()}%",
            brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hsv.hue, 1f, 1f))),
            onValueChange = { updateColor(hsv.copy(saturation = it)) },
        )
        ColorChannelSlider(
            title = stringResource(Res.string.custom_theme_brightness),
            value = hsv.brightness,
            valueLabel = "${(hsv.brightness * 100).roundToInt()}%",
            brush = Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hsv.hue, hsv.saturation, 1f))),
            onValueChange = { updateColor(hsv.copy(brightness = it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorChannelSlider(
    title: String,
    value: Float,
    valueLabel: String,
    brush: Brush,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val tokens = MaterialTheme.nuvio
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
            track = {
                Box(
                    Modifier.fillMaxWidth().height(NuvioTokens.Space.s8)
                        .clip(tokens.shapes.chip).background(brush),
                )
            },
        )
    }
}
