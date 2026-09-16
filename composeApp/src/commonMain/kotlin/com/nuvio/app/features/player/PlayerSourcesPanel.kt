package com.nuvio.app.features.player

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.isSelectableForPlayback
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.collections_tab_all
import nuvio.composeapp.generated.resources.compose_action_reload
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.compose_player_no_streams_found
import nuvio.composeapp.generated.resources.compose_player_panel_sources
import nuvio.composeapp.generated.resources.compose_player_playing
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlayerSourcesPanel(
    visible: Boolean,
    streamsUiState: StreamsUiState,
    contentTitle: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    currentEpisodeTitle: String?,
    currentStreamUrl: String?,
    currentStreamName: String?,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val addonGroups = streamsUiState.groups.filter { it.streams.isNotEmpty() || it.isLoading }
    LaunchedEffect(visible, addonGroups, streamsUiState.selectedFilter) {
        if (
            visible &&
            streamsUiState.selectedFilter != null &&
            addonGroups.none { it.addonId == streamsUiState.selectedFilter }
        ) {
            onFilterSelected(null)
        }
    }
    val contentLabel = if (currentSeason != null && currentEpisode != null) {
        buildString {
            append(stringResource(Res.string.compose_player_episode_code_full, currentSeason, currentEpisode))
            currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
    } else {
        contentTitle
    }

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
                title = stringResource(Res.string.compose_player_panel_sources),
            ) {
                PlayerDialogButton(
                    label = stringResource(Res.string.compose_action_reload),
                    onClick = onReload,
                )
                PlayerDialogButton(
                    label = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = contentLabel,
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))

            if (addonGroups.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AddonFilterChip(
                        label = stringResource(Res.string.collections_tab_all),
                        isSelected = streamsUiState.selectedFilter == null,
                        onClick = { onFilterSelected(null) },
                    )
                    addonGroups.forEach { group ->
                        AddonFilterChip(
                            label = group.addonName,
                            isSelected = streamsUiState.selectedFilter == group.addonId,
                            isLoading = group.isLoading,
                            hasError = group.error != null,
                            onClick = { onFilterSelected(group.addonId) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            PlayerStreamList(
                streamsUiState = streamsUiState,
                onStreamSelected = onStreamSelected,
                modifier = Modifier.weight(1f),
                currentStreamUrl = currentStreamUrl,
                currentStreamName = currentStreamName,
                currentLabel = stringResource(Res.string.compose_player_playing),
            )
        }
    }
}

internal fun List<StreamItem>.stablePlayerKeys(): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return map { stream ->
        val base = listOf(
            stream.addonId,
            stream.infoHash ?: stream.clientResolve?.infoHash ?: stream.url ?: stream.externalUrl ?: stream.streamLabel,
            stream.fileIdx ?: stream.clientResolve?.fileIdx ?: -1,
        ).joinToString("::")
        val count = occurrences[base] ?: 0
        occurrences[base] = count + 1
        "$base::$count"
    }
}

internal fun StreamItem.isCurrentPlayerStream(
    currentUrl: String?,
    currentName: String?,
): Boolean {
    if (!currentUrl.isNullOrBlank() && playableDirectUrl == currentUrl) return true
    return !currentName.isNullOrBlank() && streamLabel.equals(currentName, ignoreCase = true) &&
        playableDirectUrl == currentUrl
}
