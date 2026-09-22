package com.nuvio.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.app.core.ui.PosterOpenMotion
import com.nuvio.app.core.ui.drawLiftedPoster

@Composable
internal fun PosterNavigationArtwork(request: PosterNavigationRequest) {
    val source = remember(request) { request.anchor.source } ?: return
    val density = LocalDensity.current
    val width = with(density) { source.layer.size.width.toDp() }
    val height = with(density) { source.layer.size.height.toDp() }

    Box(
        modifier = Modifier.fillMaxSize()
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(width, height)
            .onPlaced { request.placeArtwork() }
            .graphicsLayer {
                if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
                val viewport = request.viewportBounds.size
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = viewport.width / size.width
                scaleY = viewport.height / size.height
                alpha = PosterOpenMotion.artworkAlpha(request.elapsedMillis)
                val bounds = PosterOpenMotion.bounds(request.anchor.boundsInRoot, request.viewportBounds, request.elapsedMillis)
                val radius = PosterOpenMotion.artworkBlurRadius(size, bounds.size, request.elapsedMillis)
                renderEffect = if (alpha > 0f && radius.width > 0.1f && radius.height > 0.1f) {
                    BlurEffect(radius.width, radius.height, TileMode.Clamp)
                } else {
                    null
                }
            }
            .drawBehind { drawLiftedPoster(source) },
    )
}
