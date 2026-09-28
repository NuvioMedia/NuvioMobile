package com.nuvio.app.features.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.gradientMask
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryDownloadsButton(onClick: () -> Unit) {
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val hasUnseenCompleted by DownloadsRepository.hasUnseenCompleted.collectAsStateWithLifecycle()

    IconButton(onClick = onClick) {
        when {
            downloads.items.any { it.status == DownloadStatus.Downloading } -> FlowingDownloadIcon()
            hasUnseenCompleted -> DownloadIcon(
                modifier = Modifier.gradientMask(MaterialTheme.themePalette.accentBrush()),
                tint = Color.White,
            )
            else -> DownloadIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FlowingDownloadIcon() {
    val palette = MaterialTheme.themePalette
    val dimAlpha = MaterialTheme.nuvio.opacity.overlayLight
    val colors = palette.accentGradient.takeIf { it.size >= 2 }
        ?: listOf(palette.secondary, palette.secondary.copy(alpha = dimAlpha))
    val phase by rememberInfiniteTransition(label = "downloadsFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "downloadsFlowPhase",
    )
    DownloadIcon(
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val offset = phase * size.height * 2f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = Offset(0f, offset),
                        end = Offset(0f, offset + size.height),
                        tileMode = TileMode.Mirror,
                    ),
                    blendMode = BlendMode.SrcIn,
                )
            },
        tint = Color.White,
    )
}

@Composable
private fun DownloadIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Rounded.Download,
        contentDescription = stringResource(Res.string.compose_settings_root_downloads_title),
        modifier = modifier,
        tint = tint,
    )
}
