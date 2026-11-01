package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Slider as MaterialSlider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    trackBrush: Brush? = null,
) {
    val tokens = MaterialTheme.nuvio
    val state = remember(valueRange, steps) { SliderState(steps = steps, trackRange = valueRange) }
    state.value = value
    val interactionSource = remember { MutableInteractionSource() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val thumbColor = when {
        !enabled -> tokens.colors.textMuted
        trackBrush != null -> tokens.colors.textPrimary
        else -> tokens.colors.accent
    }
    val activeColor = if (enabled) tokens.colors.accent else tokens.colors.textDisabled
    val inactiveColor = tokens.colors.textPrimary.copy(alpha = if (enabled) 0.18f else 0.08f)

    MaterialSlider(
        state = state,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        thumb = {
            Box(
                Modifier
                    .size(20.dp)
                    .background(thumbColor, CircleShape)
                    .indication(interactionSource, ripple(bounded = false, radius = 20.dp)),
            )
        },
        track = { sliderState ->
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(inactiveColor),
                ) {
                    if (trackBrush != null) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    scaleX = if (isRtl) -1f else 1f
                                    alpha = if (enabled) 1f else NuvioTokens.Opacity.disabled
                                }
                                .background(trackBrush),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth(sliderState.coercedValueAsFraction)
                                .fillMaxHeight()
                                .background(activeColor),
                        )
                    }
                }
            }
        },
    )
}
