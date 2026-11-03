package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.livetv.LiveTvFilterRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.live_tv_favorite
import nuvio.composeapp.generated.resources.live_tv_no_favorites
import nuvio.composeapp.generated.resources.live_tv_player_channels
import nuvio.composeapp.generated.resources.live_tv_all_channels
import nuvio.composeapp.generated.resources.live_tv_favorites
import nuvio.composeapp.generated.resources.live_tv_choose_category
import nuvio.composeapp.generated.resources.live_tv_no_channels_found
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveTvChannelsPanel(
    visible: Boolean,
    currentStreamUrl: String,
    onChannelSelected: (LiveTvChannel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    val channels = remember(uiState.channels, uiState.favoriteUrls) {
        uiState.channels
            .filterNot { com.nuvio.app.features.livetv.isLikelyCategoryHeading(it.name) }
            .sortedWith(
                compareByDescending<LiveTvChannel> { it.streamUrl in uiState.favoriteUrls }
                    .thenBy { it.name.lowercase() },
            )
    }
    val groups = remember(uiState.channels) {
        uiState.channels
            .filterNot { com.nuvio.app.features.livetv.isLikelyCategoryHeading(it.name) }
            .map { it.group }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    var selectedGroup by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val filteredChannels = remember(channels, selectedGroup, favoritesOnly) {
        channels.filter { channel ->
            (selectedGroup.isBlank() || channel.group == selectedGroup) &&
                (!favoritesOnly || channel.streamUrl in uiState.favoriteUrls)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)),
        exit = fadeOut(tween(NuvioTokens.Motion.normalMillis)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(tokens.colors.overlayScrim.copy(alpha = tokens.opacity.medium)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(NuvioTokens.Motion.sheetEnterMillis)) { it / 3 } +
                    fadeIn(tween(NuvioTokens.Motion.sheetEnterMillis)),
                exit = slideOutVertically(tween(NuvioTokens.Motion.sheetExitMillis)) { it / 3 } +
                    fadeOut(tween(NuvioTokens.Motion.sheetExitMillis)),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = tokens.components.playerPanelMaxWidth)
                        .fillMaxWidth(0.92f)
                        .heightIn(max = tokens.components.dialogMaxWidth + NuvioTokens.Space.s40)
                        .clip(tokens.shapes.playerPanel)
                        .background(tokens.colors.surfaceSheet)
                        .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.playerPanel)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.spacing.sheetPadding, vertical = tokens.spacing.cardPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.live_tv_player_channels),
                            color = tokens.colors.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(Res.string.action_close),
                            modifier = Modifier
                                .clickable(onClick = onDismiss)
                                .padding(8.dp),
                            color = tokens.colors.accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (channels.isNotEmpty()) {
                        LiveTvFilterRow(
                            groups = groups,
                            selectedGroup = selectedGroup,
                            favoritesOnly = favoritesOnly,
                            allLabel = stringResource(Res.string.live_tv_all_channels),
                            favoritesLabel = stringResource(Res.string.live_tv_favorites),
                            categoryLabel = stringResource(Res.string.live_tv_choose_category),
                            onAllSelected = {
                                favoritesOnly = false
                                selectedGroup = ""
                            },
                            onFavoritesSelected = {
                                favoritesOnly = true
                                selectedGroup = ""
                            },
                            onGroupSelected = {
                                favoritesOnly = false
                                selectedGroup = it
                            },
                            modifier = Modifier.padding(
                                start = tokens.spacing.sheetPadding,
                                end = tokens.spacing.sheetPadding,
                                bottom = tokens.spacing.controlGap,
                            ),
                        )
                    }

                    if (filteredChannels.isEmpty()) {
                        val emptyMessage = when {
                            favoritesOnly -> stringResource(Res.string.live_tv_no_favorites)
                            selectedGroup.isNotBlank() -> stringResource(Res.string.live_tv_no_channels_found)
                            else -> stringResource(Res.string.live_tv_no_channels_found)
                        }
                        Text(
                            text = emptyMessage,
                            modifier = Modifier.padding(tokens.spacing.sheetPadding),
                            color = tokens.colors.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = tokens.spacing.sheetPadding,
                                end = tokens.spacing.sheetPadding,
                                bottom = tokens.spacing.sheetPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                        ) {
                            items(filteredChannels, key = LiveTvChannel::id) { channel ->
                                LiveTvPlayerChannelRow(
                                    channel = channel,
                                    selected = channel.streamUrl == currentStreamUrl,
                                    favorite = channel.streamUrl in uiState.favoriteUrls,
                                    onFavoriteClick = { LiveTvRepository.toggleFavorite(channel) },
                                    onClick = { onChannelSelected(channel) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvPlayerChannelRow(
    channel: LiveTvChannel,
    selected: Boolean,
    favorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(if (selected) tokens.colors.overlaySelected else tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(7.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = channel.name,
                color = tokens.colors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(Res.string.live_tv_favorite),
                tint = if (favorite) tokens.colors.warning else tokens.colors.textMuted,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.accent,
            )
        }
    }
}
