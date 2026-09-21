package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_favorites_header
import org.jetbrains.compose.resources.stringResource

private val ChannelColumnWidth = 156.dp
private val RowHeight = 72.dp
private val HourWidth = 180.dp

@Composable
internal fun LiveTvEpgGrid(
    uiState: LiveTvUiState,
    onChannelClick: (LiveTvChannel) -> Unit,
    onToggleFavorite: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val channels = uiState.sortedChannels
    val horizontalScrollState = rememberScrollState()
    val windowDurationMs = (uiState.guideWindowEndMs - uiState.guideWindowStartMs).coerceAtLeast(1L)
    val hourCount = ((windowDurationMs / (60 * 60_000L)) + 1).toInt().coerceAtLeast(1)
    val timelineWidth = HourWidth * hourCount
    val nowOffsetFraction = ((uiState.nowMs - uiState.guideWindowStartMs).toFloat() / windowDurationMs)
        .coerceIn(0f, 1f)

    Row(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(ChannelColumnWidth)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(tokens.colors.surfaceElevated.copy(alpha = 0.92f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (uiState.hasFavorites) {
                    item(key = "favorites-header") {
                        Text(
                            text = stringResource(Res.string.livetv_favorites_header),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(tokens.colors.accent.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                itemsIndexed(
                    items = channels,
                    key = { _, channel -> channel.id },
                ) { index, channel ->
                    val isFavorite = channel.id in uiState.favoriteChannelIds
                    val showFavoritesDivider = uiState.hasFavorites &&
                        index > 0 &&
                        channels[index - 1].id in uiState.favoriteChannelIds &&
                        !isFavorite
                    if (showFavoritesDivider) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(tokens.colors.borderSubtle.copy(alpha = 0.35f)),
                        )
                    }
                    LiveTvChannelCell(
                        channel = channel,
                        isFavorite = isFavorite,
                        highlighted = isFavorite,
                        onChannelClick = onChannelClick,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(horizontalScrollState),
        ) {
            Row(
                modifier = Modifier
                    .width(timelineWidth)
                    .height(40.dp)
                    .background(tokens.colors.surfaceElevated.copy(alpha = 0.92f)),
            ) {
                repeat(hourCount) { index ->
                    val hourMs = uiState.guideWindowStartMs + index * 60 * 60_000L
                    Box(
                        modifier = Modifier
                            .width(HourWidth)
                            .fillMaxHeight()
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = LiveTvTime.formatHourLabel(hourMs),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (uiState.hasFavorites) {
                    item(key = "favorites-spacer") {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }

                itemsIndexed(
                    items = channels,
                    key = { _, channel -> "timeline-${channel.id}" },
                ) { index, channel ->
                    val isFavorite = channel.id in uiState.favoriteChannelIds
                    val showFavoritesDivider = uiState.hasFavorites &&
                        index > 0 &&
                        channels[index - 1].id in uiState.favoriteChannelIds &&
                        !isFavorite
                    if (showFavoritesDivider) {
                        Spacer(
                            modifier = Modifier
                                .width(timelineWidth)
                                .height(1.dp)
                                .background(tokens.colors.borderSubtle.copy(alpha = 0.35f)),
                        )
                    }
                    LiveTvTimelineRow(
                        channel = channel,
                        programs = uiState.epgByChannelId[channel.id].orEmpty(),
                        windowStartMs = uiState.guideWindowStartMs,
                        windowEndMs = uiState.guideWindowEndMs,
                        timelineWidth = timelineWidth,
                        nowOffsetFraction = nowOffsetFraction,
                        highlighted = isFavorite,
                        onChannelClick = onChannelClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvChannelCell(
    channel: LiveTvChannel,
    isFavorite: Boolean,
    highlighted: Boolean,
    onChannelClick: (LiveTvChannel) -> Unit,
    onToggleFavorite: (LiveTvChannel) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(
                if (highlighted) tokens.colors.accent.copy(alpha = 0.05f) else Color.Transparent,
            )
            .clickable { onChannelClick(channel) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { onToggleFavorite(channel) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = null,
                tint = if (isFavorite) tokens.colors.accent else tokens.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.colors.surfaceElevated),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            channel.group?.let { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveTvTimelineRow(
    channel: LiveTvChannel,
    programs: List<EpgProgram>,
    windowStartMs: Long,
    windowEndMs: Long,
    timelineWidth: Dp,
    nowOffsetFraction: Float,
    highlighted: Boolean,
    onChannelClick: (LiveTvChannel) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val windowDurationMs = (windowEndMs - windowStartMs).coerceAtLeast(1L)

    Box(
        modifier = Modifier
            .width(timelineWidth)
            .height(RowHeight)
            .background(
                if (highlighted) tokens.colors.accent.copy(alpha = 0.05f) else Color.Transparent,
            )
            .clickable { onChannelClick(channel) },
    ) {
        programs.forEach { program ->
            val startFraction = ((program.startMs - windowStartMs).toFloat() / windowDurationMs)
                .coerceIn(0f, 1f)
            val endFraction = ((program.endMs - windowStartMs).toFloat() / windowDurationMs)
                .coerceIn(0f, 1f)
            val widthFraction = (endFraction - startFraction).coerceAtLeast(0.04f)
            if (widthFraction <= 0f) return@forEach
            Surface(
                modifier = Modifier
                    .offset(x = timelineWidth * startFraction)
                    .width(timelineWidth * widthFraction)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                shape = RoundedCornerShape(10.dp),
                color = tokens.colors.surfaceElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    tokens.colors.borderSubtle.copy(alpha = 0.4f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${LiveTvTime.formatClock(program.startMs)} - ${LiveTvTime.formatClock(program.endMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = timelineWidth * nowOffsetFraction)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color(0xFFE53935)),
        )
    }
}
