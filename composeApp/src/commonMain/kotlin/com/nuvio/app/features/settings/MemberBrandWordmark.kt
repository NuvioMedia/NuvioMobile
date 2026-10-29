package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.LocalScreenActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.MemberTier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val MemberWordmarkHeightRatio = 132f / 344f
private const val MemberWordmarkVerticalOffsetRatio = -12f / 344f
internal const val MemberBadgeGradientAngleDegrees = 100f
internal const val MemberBadgeGradientWidthMultiplier = 2.4f
internal const val MemberBadgeSweepHalfDurationMs = 2_750

internal data class MemberBadgeStyle(
    val label: String,
    val colorStops: List<Pair<Float, Color>>,
)

internal fun MemberTier.badgeStyle(): MemberBadgeStyle = when (this) {
    MemberTier.SUPPORTER -> MemberBadgeStyle(
        label = "Supporter",
        colorStops = listOf(
            0f to Color(0xFFD4843D),
            0.5f to Color(0xFFFFDE90),
            1f to Color(0xFFD4843D),
        ),
    )

    MemberTier.SUPPORTER_PLUS -> MemberBadgeStyle(
        label = "Supporter+",
        colorStops = listOf(
            0f to Color(0xFF91A8FF),
            0.52f to Color(0xFFF08BD8),
            0.78f to Color(0xFFFF9B8E),
            1f to Color(0xFF91A8FF),
        ),
    )
}

@Composable
internal fun MemberBrandWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val access by remember {
        MemberAccessRepository.ensureStarted()
        MemberAccessRepository.access
    }.collectAsStateWithLifecycle()

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBrandWordmark(
            modifier = Modifier.height(height),
            contentDescription = contentDescription,
        )
        val tier = access.tier
        AnimatedVisibility(
            visible = tier != null,
            enter = fadeIn(tween(360)) +
                expandHorizontally(tween(420), expandFrom = Alignment.Start) +
                scaleIn(tween(420), initialScale = 0.94f),
        ) {
            if (tier != null) {
                MemberBadge(
                    tier = tier,
                    height = height,
                )
            }
        }
    }
}

@Composable
private fun MemberBadge(
    tier: MemberTier,
    height: Dp,
) {
    val badgeStyle = remember(tier) { tier.badgeStyle() }
    var badgeSize by remember(tier) { mutableStateOf(IntSize.Zero) }
    val gradientBrush = rememberMemberBadgeGradientBrush(
        style = badgeStyle,
        size = badgeSize,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(height * 0.14f))
        Text(
            text = badgeStyle.label,
            style = TextStyle(
                brush = gradientBrush,
                fontSize = (height.value * MemberWordmarkHeightRatio).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .offset(y = height * MemberWordmarkVerticalOffsetRatio)
                .onSizeChanged { badgeSize = it },
        )
    }
}

@Composable
internal fun rememberMemberBadgeGradientBrush(
    style: MemberBadgeStyle,
    size: IntSize,
): Brush {
    val gradientProgress = if (LocalScreenActive.current) {
        val gradientTransition = rememberInfiniteTransition(label = "memberBadgeGradient")
        val progress by gradientTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MemberBadgeSweepHalfDurationMs,
                    easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "memberBadgeGradientProgress",
        )
        progress
    } else {
        0f
    }
    return remember(style, size, gradientProgress) {
        memberBadgeGradientBrush(
            style = style,
            size = size,
            progress = gradientProgress,
        )
    }
}

private fun memberBadgeGradientBrush(
    style: MemberBadgeStyle,
    size: IntSize,
    progress: Float,
): Brush {
    val textWidth = size.width.toFloat().coerceAtLeast(1f)
    val textHeight = size.height.toFloat().coerceAtLeast(1f)
    val gradientWidth = textWidth * MemberBadgeGradientWidthMultiplier
    val gradientLeft = -(gradientWidth - textWidth) * progress.coerceIn(0f, 1f)
    val angleRadians = MemberBadgeGradientAngleDegrees * (PI.toFloat() / 180f)
    val directionX = sin(angleRadians)
    val directionY = -cos(angleRadians)
    val gradientLineLength = abs(gradientWidth * directionX) + abs(textHeight * directionY)
    val center = Offset(
        x = gradientLeft + gradientWidth / 2f,
        y = textHeight / 2f,
    )
    val halfVector = Offset(
        x = directionX * gradientLineLength / 2f,
        y = directionY * gradientLineLength / 2f,
    )

    return Brush.linearGradient(
        colorStops = style.colorStops.toTypedArray(),
        start = center - halfVector,
        end = center + halfVector,
    )
}
