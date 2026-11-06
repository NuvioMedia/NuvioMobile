package com.nuvio.app.features.details.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.TrackingRatings
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.rating_value
import org.jetbrains.compose.resources.stringResource

private const val StarCount = 5

@Composable
fun RatingStars(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 44.dp,
) {
    val tokens = MaterialTheme.nuvio
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var cascadeFrom by remember { mutableIntStateOf(0) }
    val description = stringResource(Res.string.rating_value, value)

    fun select(next: Int) {
        val clamped = next.coerceIn(1, TrackingRatings.MAX)
        if (clamped == currentValue) return
        cascadeFrom = min(currentValue, clamped) / 2
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        currentOnValueChange(clamped)
    }

    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = 0f..TrackingRatings.MAX.toFloat(),
                    steps = TrackingRatings.MAX - 1,
                )
                setProgress { target ->
                    select(target.roundToInt())
                    true
                }
            }
            .pointerInput(rtl) {
                fun valueAt(x: Float): Int {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val position = (if (rtl) width - x else x).coerceIn(0f, width)
                    return ceil(position / width * TrackingRatings.MAX).toInt()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    select(valueAt(down.position.x))
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        select(valueAt(change.position.x))
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(starSize / 7),
    ) {
        repeat(StarCount) { index ->
            Star(
                fraction = (value / 2f - index).coerceIn(0f, 1f),
                delayMillis = (index - cascadeFrom).coerceAtLeast(0) * 40L,
                size = starSize,
                rtl = rtl,
                filledColor = tokens.colors.accent,
                emptyColor = tokens.colors.textPrimary.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun Star(
    fraction: Float,
    delayMillis: Long,
    size: Dp,
    rtl: Boolean,
    filledColor: Color,
    emptyColor: Color,
) {
    val fill by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "rating_star_fill",
    )
    val scale = remember { Animatable(1f) }
    var settled by remember { mutableFloatStateOf(fraction) }

    LaunchedEffect(fraction) {
        val grew = fraction > settled
        settled = fraction
        if (!grew) return@LaunchedEffect
        delay(delayMillis)
        scale.animateTo(1.2f, tween(durationMillis = 90, easing = FastOutSlowInEasing))
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = emptyColor,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = filledColor,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val width = this.size.width * fill
                    clipRect(
                        left = if (rtl) this.size.width - width else 0f,
                        right = if (rtl) this.size.width else width,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
    }
}
