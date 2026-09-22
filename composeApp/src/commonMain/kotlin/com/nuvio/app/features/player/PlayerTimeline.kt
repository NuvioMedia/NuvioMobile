package com.nuvio.app.features.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal val PlayerTimelineContentInset = 2.dp

@Composable
internal fun PlayerTimelineDetails(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    releaseInfo: String?,
    streamTitle: String,
    providerName: String,
    isPlaying: Boolean,
    metrics: PlayerLayoutMetrics,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    Column(Modifier.padding(horizontal = PlayerTimelineContentInset)) {
        Text(
            text = title,
            style = typeScale.titleLg.copy(
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * 1.16f,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (seasonNumber != null && episodeNumber != null) {
            val episodeCode = stringResource(Res.string.compose_player_episode_code, seasonNumber, episodeNumber)
            Text(
                text = if (episodeTitle.isNullOrBlank()) episodeCode else "$episodeCode • $episodeTitle",
                style = typeScale.bodyMd.copy(fontSize = metrics.episodeInfoSize),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!releaseInfo.isNullOrBlank()) {
            Text(
                text = releaseInfo,
                style = typeScale.labelSm.copy(fontSize = metrics.metadataSize),
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val source = streamTitle.ifBlank { providerName }.replace("\n", " · ")
        if (!isPlaying && source.isNotBlank()) {
            Text(
                text = stringResource(Res.string.compose_player_via, source),
                style = typeScale.labelSm.copy(fontSize = metrics.metadataSize),
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTimeline(
    snapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val durationMs = snapshot.durationMs.coerceAtLeast(0L)
    val rangeEnd = durationMs.coerceAtLeast(1L).toFloat()
    val bufferedFraction = (snapshot.bufferedPositionMs.toFloat() / rangeEnd).coerceIn(0f, 1f)
    val accent = MaterialTheme.colorScheme.primary
    val accentBrush = MaterialTheme.themePalette.accentBrush()
    val description = stringResource(Res.string.player_seek_position)
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = enabled && durationMs > 0L && (isPressed || isDragged)
    val trackThickness by animateDpAsState(
        targetValue = if (isInteracting) 10.dp else 6.dp,
        animationSpec = tween(durationMillis = if (isInteracting) 140 else 180),
        label = "playerTimelineThickness",
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Slider(
            value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { value ->
                val position = value.toLong().coerceIn(0L, durationMs)
                scrubPosition = position
                onScrubChange(position)
            },
            onValueChangeFinished = {
                onScrubFinished((scrubPosition ?: displayedPositionMs).coerceIn(0L, durationMs))
                scrubPosition = null
            },
            valueRange = 0f..rangeEnd,
            enabled = enabled && durationMs > 0L,
            interactionSource = interactionSource,
            thumb = {},
            track = { state ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .drawBehind {
                            val trackHeight = trackThickness.toPx()
                            val trackOrigin = Offset(0f, 35.dp.toPx() - trackHeight / 2)
                            val radius = CornerRadius(trackHeight / 2)
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.3f),
                                topLeft = trackOrigin,
                                size = Size(size.width, trackHeight),
                                cornerRadius = radius,
                            )
                            drawRoundRect(
                                color = accent.copy(alpha = 0.35f),
                                topLeft = trackOrigin,
                                size = Size(size.width * bufferedFraction, trackHeight),
                                cornerRadius = radius,
                            )
                            drawRoundRect(
                                brush = accentBrush,
                                topLeft = trackOrigin,
                                size = Size(size.width * (state.value / rangeEnd).coerceIn(0f, 1f), trackHeight),
                                cornerRadius = radius,
                            )
                        },
                )
            },
            modifier = modifier
                .fillMaxWidth()
                .height(24.dp)
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                .requiredHeight(48.dp)
                .semantics { contentDescription = description },
        )
    }
}
