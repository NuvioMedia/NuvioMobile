package com.nuvio.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.nuvio.app.core.ui.PosterOpenMotion

internal fun posterNavigationEntry(
    key: NavKey,
    entry: NavEntry<NavKey>,
    state: PosterNavigationState,
): NavEntry<NavKey> {
    val metadata = if (key == state.active?.to) {
        entry.metadata + NavDisplay.transitionSpec {
            EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished
        }
    } else {
        entry.metadata
    }
    return NavEntry(key = key, contentKey = entry.contentKey, metadata = metadata) {
        PosterNavigationEntry(key, state) { entry.Content() }
    }
}

@Composable
private fun PosterNavigationEntry(
    route: NavKey,
    state: PosterNavigationState,
    content: @Composable () -> Unit,
) {
    val request = state.active
    val incoming = request != null && route == request.to
    val outgoing = request != null && route == request.from
    PosterNavigationMotion(request, incoming, outgoing, state)
    val openingRequest = remember { request?.takeIf { incoming } }

    Box(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            state.updateViewport(
                route,
                Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())),
            )
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (outgoing) {
                    val scale = PosterOpenMotion.backgroundScale(request.elapsedMillis)
                    scaleX = scale
                    scaleY = scale
                    alpha = PosterOpenMotion.backgroundAlpha(request.elapsedMillis)
                    val radius = PosterOpenMotion.blurRadiusForSigma(
                        PosterOpenMotion.backgroundBlurSigmaDp(request.elapsedMillis).dp.toPx(),
                    )
                    renderEffect = if (alpha > 0f && radius > 0.1f) BlurEffect(radius, radius, TileMode.Clamp) else null
                } else if (incoming && size.width > 0f && size.height > 0f) {
                    val end = request.viewportBounds
                    val bounds = PosterOpenMotion.bounds(request.anchor.boundsInRoot, end, request.elapsedMillis)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = bounds.width / size.width
                    scaleY = bounds.height / size.height
                    translationX = bounds.left - end.left
                    translationY = bounds.top - end.top
                    val radius = request.anchor.cornerRadius.toPx()
                    shape = PosterNavigationShape(radius / scaleX, radius / scaleY)
                    clip = true
                }
            }.background(MaterialTheme.colorScheme.background),
        ) {
            if (openingRequest == null || openingRequest.contentReady) {
                content()
            }
            if (incoming) {
                PosterNavigationArtwork(request)
            }
        }
    }
}

private class PosterNavigationShape(private val radiusX: Float, private val radiusY: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radiusX, radiusY)))
}
