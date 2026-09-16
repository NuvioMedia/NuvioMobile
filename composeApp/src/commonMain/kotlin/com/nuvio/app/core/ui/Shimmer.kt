package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

@Stable
private class SkeletonAnimation {
    val progress = Animatable(0f)
    var consumers by mutableIntStateOf(0)
}

private val LocalSkeletonAnimation = staticCompositionLocalOf<SkeletonAnimation?> { null }

@Composable
internal fun SkeletonAnimationProvider(content: @Composable () -> Unit) {
    val animation = remember { SkeletonAnimation() }
    val isActive by remember { derivedStateOf { animation.consumers > 0 } }
    if (isActive) {
        LaunchedEffect(animation) {
            animation.progress.snapTo(0f)
            animation.progress.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            )
        }
    }
    CompositionLocalProvider(LocalSkeletonAnimation provides animation, content = content)
}

@Composable
internal fun rememberSkeletonProgress(): State<Float> {
    val animation = LocalSkeletonAnimation.current
    SkeletonAnimationConsumer(animation)
    return animation?.progress?.asState() ?: remember { mutableFloatStateOf(0f) }
}

@Composable
private fun SkeletonAnimationConsumer(animation: SkeletonAnimation?) {
    val active = LocalScreenActive.current
    DisposableEffect(animation, active) {
        if (animation != null && active) animation.consumers++
        onDispose {
            if (animation != null && active) animation.consumers--
        }
    }
}

@Composable
internal fun Modifier.skeleton(
    shape: Shape = RoundedCornerShape(6.dp),
): Modifier {
    val progress = rememberSkeletonProgress()
    val base = MaterialTheme.nuvio.colors.skeleton
    val highlight = MaterialTheme.nuvio.colors.shimmer
    var rootWidth by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    return clip(shape)
        .onGloballyPositioned { coordinates ->
            rootWidth = coordinates.findRootCoordinates().size.width.toFloat()
            offsetX = coordinates.positionInRoot().x
        }
        .drawWithCache {
            val width = rootWidth.takeIf { it > 0f } ?: size.width
            val bandWidth = width * 0.7f
            val shoulder = lerp(base, highlight, 0.35f)
            val stops = arrayOf(
                0f to base,
                0.25f to shoulder,
                0.5f to highlight,
                0.75f to shoulder,
                1f to base,
            )
            onDrawBehind {
                val start = (width + bandWidth) * progress.value - bandWidth - offsetX
                drawRect(
                    Brush.linearGradient(
                        colorStops = stops,
                        start = Offset(start, 0f),
                        end = Offset(start + bandWidth, 0f),
                    ),
                )
            }
        }
}
