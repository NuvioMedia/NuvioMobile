package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val HERO_STRETCH_MAX = 200.dp
private const val HERO_STRETCH_DRAG_RESISTANCE = 0.55f
private const val HERO_STRETCH_RELEASE_ABSORB = 0.35f
private const val HERO_STRETCH_FRAME_TO_VELOCITY = 60f
private val HeroStretchReleaseSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 320f,
)
private val HeroStretchBounceSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 110f,
)

@Stable
class HeroStretchState internal constructor(
    private val scope: CoroutineScope,
    private val maxStretchPx: Float,
    private val isAtTop: () -> Boolean,
    private val onStretchReleased: (stretchPx: Float, maxStretchPx: Float) -> Boolean = { _, _ -> false },
) {
    private val stretchAnim = Animatable(0f)
    private var settleJob: Job? = null
    private var flingAbsorbed = false
    private var stretchReleaseHandled = false
    private var stretchReleaseConsumed = false

    val stretchPx: Float get() = stretchAnim.value

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                flingAbsorbed = false
                stretchReleaseHandled = false
                stretchReleaseConsumed = false
            }
            val stretch = stretchAnim.value
            if (available.y < 0f && stretch > 0f) {
                val consumedY = available.y.coerceAtLeast(-stretch)
                snapTo(stretch + consumedY)
                return Offset(0f, consumedY)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.y <= 0f || !isAtTop()) return Offset.Zero

            if (source == NestedScrollSource.UserInput) {
                flingAbsorbed = false
                stretchReleaseHandled = false
                stretchReleaseConsumed = false
                val stretch = stretchAnim.value
                val resistance = HERO_STRETCH_DRAG_RESISTANCE *
                    (1f - stretch / maxStretchPx).coerceIn(0f, 1f)
                snapTo(stretch + available.y * resistance)
            } else if (!flingAbsorbed) {
                flingAbsorbed = true
                if (settleJob?.isActive != true) {
                    settle(available.y * HERO_STRETCH_FRAME_TO_VELOCITY, HeroStretchBounceSpring)
                }
            }
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (stretchAnim.value <= 0.5f) return Velocity.Zero
            val consumed = notifyStretchReleased()
            if (!consumed && settleJob?.isActive != true) {
                settle(available.y * HERO_STRETCH_RELEASE_ABSORB, HeroStretchReleaseSpring)
            }
            return Velocity(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            flingAbsorbed = false
            val stretchConsumed = notifyStretchReleased()
            if (!stretchConsumed && stretchAnim.value > 0.5f && settleJob?.isActive != true) {
                settle(0f, HeroStretchReleaseSpring)
            }
            return if (available.y > 0f && isAtTop()) {
                Velocity(0f, available.y)
            } else {
                Velocity.Zero
            }
        }
    }

    private fun notifyStretchReleased(): Boolean {
        val stretch = stretchAnim.value
        if (stretch <= 0.5f) return stretchReleaseConsumed
        if (!stretchReleaseHandled) {
            stretchReleaseHandled = true
            stretchReleaseConsumed = onStretchReleased(stretch, maxStretchPx)
        }
        return stretchReleaseConsumed
    }

    private fun snapTo(value: Float) {
        scope.launch { stretchAnim.snapTo(value.coerceIn(0f, maxStretchPx)) }
    }

    fun discardStretch() {
        settleJob?.cancel()
        settleJob = null
        scope.launch { stretchAnim.snapTo(0f) }
    }

    private fun settle(initialVelocity: Float, spec: SpringSpec<Float>) {
        settleJob = scope.launch {
            stretchAnim.animateTo(
                targetValue = 0f,
                animationSpec = spec,
                initialVelocity = initialVelocity,
            )
        }
    }
}

@Composable
fun rememberHeroStretchState(
    listState: LazyListState,
    onStretchReleased: (stretchPx: Float, maxStretchPx: Float) -> Boolean = { _, _ -> false },
): HeroStretchState {
    val scope = rememberCoroutineScope()
    val maxStretchPx = with(LocalDensity.current) { HERO_STRETCH_MAX.toPx() }
    val latestOnStretchReleased = rememberUpdatedState(onStretchReleased)
    return remember(listState, maxStretchPx) {
        HeroStretchState(
            scope = scope,
            maxStretchPx = maxStretchPx,
            isAtTop = { !listState.canScrollBackward },
        ) { stretchPx, releasedMaxStretchPx ->
            latestOnStretchReleased.value(stretchPx, releasedMaxStretchPx)
        }
    }
}

fun Modifier.heroStretchHeight(baseHeight: Dp, stretchPx: () -> Float): Modifier =
    layout { measurable, constraints ->
        val height = (baseHeight.roundToPx() + stretchPx().roundToInt()).coerceAtLeast(0)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height),
        )
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

fun Modifier.heroStretchZoom(stretchPx: () -> Float): Modifier = graphicsLayer {
    val zoom = 1f + stretchPx() / size.height.coerceAtLeast(1f)
    transformOrigin = TransformOrigin(0.5f, 0f)
    scaleX = zoom
    scaleY = zoom
}
