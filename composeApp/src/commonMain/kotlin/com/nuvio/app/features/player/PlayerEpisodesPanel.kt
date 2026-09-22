package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioAnimatedWatchedBadge
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.compose_action_reload
import nuvio.composeapp.generated.resources.compose_player_episode_code_episode_only
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.compose_player_no_episodes_available
import nuvio.composeapp.generated.resources.compose_player_panel_episodes
import nuvio.composeapp.generated.resources.compose_player_panel_streams
import nuvio.composeapp.generated.resources.compose_player_playing
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlayerEpisodesPanel(
    visible: Boolean,
    episodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    episodeStreamsState: EpisodeStreamsPanelState,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (MetaVideo) -> Unit,
    onEpisodeStreamFilterSelected: (String?) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onBackToEpisodes: () -> Unit,
    onReloadEpisodeStreams: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSidePanel(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            PlayerPanelHeader(
                title = if (episodeStreamsState.showStreams) {
                    stringResource(Res.string.compose_player_panel_streams)
                } else {
                    stringResource(Res.string.compose_player_panel_episodes)
                },
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (episodeStreamsState.showStreams) {
                EpisodeStreamsPanelContent(
                    state = episodeStreamsState,
                    onFilterSelected = onEpisodeStreamFilterSelected,
                    onStreamSelected = onEpisodeStreamSelected,
                    onBack = onBackToEpisodes,
                    onReload = onReloadEpisodeStreams,
                    modifier = Modifier.weight(1f),
                )
            } else {
                EpisodesListPanelContent(
                    episodes = episodes,
                    parentMetaType = parentMetaType,
                    parentMetaId = parentMetaId,
                    currentSeason = currentSeason,
                    currentEpisode = currentEpisode,
                    progressByVideoId = progressByVideoId,
                    watchedKeys = watchedKeys,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    onSeasonSelected = onSeasonSelected,
                    onEpisodeSelected = onEpisodeSelected,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

data class EpisodeStreamsPanelState(
    val showStreams: Boolean = false,
    val selectedEpisode: MetaVideo? = null,
    val streamsUiState: StreamsUiState = StreamsUiState(),
)

@Composable
private fun EpisodesListPanelContent(
    episodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (MetaVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val groupedEpisodes = remember(episodes) {
        episodes
            .filter { it.season != null || it.episode != null }
            .groupBy { it.season?.coerceAtLeast(0) ?: 0 }
    }
    val availableSeasons = remember(groupedEpisodes) {
        groupedEpisodes.keys.filter { it > 0 }.sorted() + groupedEpisodes.keys.filter { it == 0 }
    }
    var selectedSeason by remember(currentSeason, availableSeasons) {
        mutableIntStateOf(
            when {
                currentSeason != null && currentSeason in availableSeasons -> currentSeason
                availableSeasons.isNotEmpty() -> availableSeasons.first()
                else -> 1
            },
        )
    }
    val seasonEpisodes = remember(groupedEpisodes, selectedSeason) {
        (groupedEpisodes[selectedSeason] ?: emptyList()).sortedBy { it.episode ?: 0 }
    }
    val seasonListState = rememberLazyListState()
    val episodeListState = rememberLazyListState()
    var positionedSeasonRow by remember(availableSeasons) { mutableStateOf(false) }
    var positionedEpisodeList by remember(selectedSeason) { mutableStateOf(false) }

    LaunchedEffect(selectedSeason, availableSeasons) {
        val index = availableSeasons.indexOf(selectedSeason)
        if (index >= 0) {
            if (positionedSeasonRow) seasonListState.animateScrollToItem(index)
            else {
                seasonListState.scrollToItem(index)
                positionedSeasonRow = true
            }
        }
    }

    LaunchedEffect(selectedSeason, seasonEpisodes, currentSeason, currentEpisode) {
        if (seasonEpisodes.isEmpty()) return@LaunchedEffect
        val currentIndex = if (selectedSeason == currentSeason && currentEpisode != null) {
            seasonEpisodes.indexOfFirst { it.season == currentSeason && it.episode == currentEpisode }
        } else {
            -1
        }
        val targetIndex = currentIndex.takeIf { it >= 0 } ?: 0
        if (positionedEpisodeList) episodeListState.animateScrollToItem(targetIndex)
        else {
            episodeListState.scrollToItem(targetIndex)
            positionedEpisodeList = true
        }
    }

    Column(modifier = modifier) {
        if (availableSeasons.isNotEmpty()) {
            LazyRow(
                state = seasonListState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                items(availableSeasons, key = { it }) { season ->
                    EpisodeSeasonChip(
                        label = if (season == 0) {
                            stringResource(Res.string.episodes_specials)
                        } else {
                            stringResource(Res.string.episodes_season, season)
                        },
                        isSelected = selectedSeason == season,
                        onClick = {
                            selectedSeason = season
                            onSeasonSelected(season)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (seasonEpisodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.compose_player_no_episodes_available),
                    color = tokens.colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = episodeListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
            ) {
                itemsIndexed(
                    items = seasonEpisodes,
                    key = { index, episode -> "${episode.season}:${episode.episode}:${episode.id}#$index" },
                ) { _, episode ->
                    val isCurrent = episode.season == currentSeason && episode.episode == currentEpisode
                    val episodeVideoId = buildPlaybackVideoId(
                        parentMetaId = parentMetaId,
                        seasonNumber = episode.season,
                        episodeNumber = episode.episode,
                        fallbackVideoId = episode.id,
                    )
                    val isWatched = progressByVideoId[episodeVideoId]?.isEffectivelyCompleted == true ||
                        WatchingState.isEpisodeWatched(
                            watchedKeys = watchedKeys,
                            metaType = parentMetaType,
                            metaId = parentMetaId,
                            episode = episode,
                        )
                    EpisodeRow(
                        episode = episode,
                        isCurrent = isCurrent,
                        isWatched = isWatched,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        onClick = { onEpisodeSelected(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeSeasonChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (isSelected) Color(0xFFF5F5F5) else tokens.colors.surfaceCard)
            .border(
                1.dp,
                if (isSelected) Color.Transparent else tokens.colors.borderDefault,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else tokens.colors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: MetaVideo,
    isCurrent: Boolean,
    isWatched: Boolean,
    blurUnwatchedEpisodes: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val cardShape = RoundedCornerShape(16.dp)
    val shouldBlurArtwork = blurUnwatchedEpisodes && !isWatched
    val playingDescription = stringResource(Res.string.compose_player_playing)
    val episodeCode = when {
        episode.season != null && episode.episode != null -> stringResource(
            Res.string.compose_player_episode_code_full,
            episode.season,
            episode.episode,
        )
        episode.episode != null -> stringResource(
            Res.string.compose_player_episode_code_episode_only,
            episode.episode,
        )
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(tokens.colors.surfaceCard)
            .then(
                if (isCurrent) {
                    Modifier.border(width = 2.dp, color = tokens.colors.focusRing, shape = cardShape)
                } else {
                    Modifier
                },
            )
            .semantics {
                if (isCurrent) stateDescription = playingDescription
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surfacePopover),
        ) {
            episode.thumbnail?.let { thumbnail ->
                AsyncImage(
                    model = thumbnail,
                    contentDescription = episode.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (shouldBlurArtwork) Modifier.blur(NuvioTokens.Space.s18) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
            if (episodeCode != null) {
                Text(
                    text = episodeCode,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            NuvioAnimatedWatchedBadge(
                isVisible = isWatched,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = episode.title,
                color = tokens.colors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.released?.takeIf { it.isNotBlank() }?.let { released ->
                Text(
                    text = formatReleaseDateForDisplay(released),
                    color = tokens.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    color = tokens.colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EpisodeStreamsPanelContent(
    state: EpisodeStreamsPanelState,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val episode = state.selectedEpisode ?: return
    val streamsUiState = state.streamsUiState

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerDialogButton(
                label = stringResource(Res.string.action_back),
                onClick = onBack,
            )
            PlayerDialogButton(
                label = stringResource(Res.string.compose_action_reload),
                onClick = onReload,
            )
            Text(
                text = buildString {
                    if (episode.season != null && episode.episode != null) {
                        append(
                            stringResource(
                                Res.string.compose_player_episode_code_full,
                                episode.season,
                                episode.episode,
                            ),
                        )
                    }
                    if (episode.title.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(episode.title)
                    }
                },
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        PlayerProviderFilterRow(
            streamsUiState = streamsUiState,
            onFilterSelected = onFilterSelected,
        )

        Spacer(Modifier.height(16.dp))

        PlayerStreamList(
            streamsUiState = streamsUiState,
            onStreamSelected = { stream -> onStreamSelected(stream, episode) },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        )
    }
}
