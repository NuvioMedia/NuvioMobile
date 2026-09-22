package com.nuvio.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NuvioLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.nuvio.colors.textSecondary,
    size: Dp = NuvioTokens.Space.s40,
    active: Boolean = LocalScreenActive.current,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        val frame = rememberLoadingIndicatorFrame(active)

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val scale = this.size.minDimension / 600f
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val spokes = List(12) { index ->
                        val angle = (index * 30 - 180) * PI / 180
                        val cosine = cos(angle).toFloat()
                        val sine = sin(angle).toFloat()
                        val start = center + Offset(2f * cosine - 104f * sine, 2f * sine + 104f * cosine) * scale
                        val end = center + Offset(2f * cosine - 206f * sine, 2f * sine + 206f * cosine) * scale
                        start to end
                    }
                    onDrawBehind {
                        val currentFrame = frame.value.coerceIn(0f, 60f)
                        for (index in spokes.lastIndex downTo 0) {
                            val (start, end) = spokes[index]
                            drawLine(
                                color = color,
                                start = start,
                                end = end,
                                strokeWidth = 40f * scale,
                                cap = StrokeCap.Round,
                                alpha = loadingSpokeAlpha(index, currentFrame),
                            )
                        }
                    }
                },
        )
    }
}

@Composable
internal fun rememberLoadingIndicatorFrame(active: Boolean): State<Float> =
    if (active) {
        rememberInfiniteTransition(label = "loading_indicator").animateFloat(
            initialValue = 0f,
            targetValue = 61f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1016, easing = LinearEasing)),
            label = "loading_frame",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

private fun loadingSpokeAlpha(index: Int, frame: Float): Float {
    if (index == 0) return lerp(100f, 0f, frame / 60f) / 100f

    val peakFrame = index * 5f
    val initialOpacity = index * 8f
    val opacity = when {
        frame < peakFrame - 1f -> lerp(initialOpacity, 0f, frame / (peakFrame - 1f))
        frame < peakFrame -> lerp(0f, 100f, frame - (peakFrame - 1f))
        else -> lerp(100f, initialOpacity, (frame - peakFrame) / (60f - peakFrame))
    }
    return opacity / 100f
}
