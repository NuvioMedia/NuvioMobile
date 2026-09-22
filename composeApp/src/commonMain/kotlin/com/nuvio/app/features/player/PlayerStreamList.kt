package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.LocalStreamSizeLabelFormat
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.isSelectableForPlayback
import com.nuvio.app.features.streams.rememberStreamSizeLabelFormat
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_streams_found
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerStreamList(
    streamsUiState: StreamsUiState,
    onStreamSelected: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 8.dp,
        top = 14.dp,
        end = 8.dp,
        bottom = 8.dp,
    ),
    currentStreamUrl: String? = null,
    currentStreamName: String? = null,
    currentLabel: String? = null,
) {
    val debridSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val streams = streamsUiState.allStreams
    val visibleGroups = streamsUiState.filteredGroups

    when {
        streams.isEmpty() && streamsUiState.isAnyLoading -> {
            PlayerModalLoading(modifier = Modifier.padding(vertical = 24.dp))
        }

        streams.isEmpty() -> {
            val error = visibleGroups.firstOrNull { it.error != null }?.error
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = error ?: stringResource(Res.string.compose_player_no_streams_found),
                    color = Color.White.copy(alpha = if (error == null) 0.7f else 0.85f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        else -> {
            val streamKeys = remember(streams) { streams.stablePlayerKeys() }
            val formatStreamSize = rememberStreamSizeLabelFormat()
            CompositionLocalProvider(LocalStreamSizeLabelFormat provides formatStreamSize) {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = contentPadding,
            ) {
                itemsIndexed(
                    items = streams,
                    key = { index, _ -> streamKeys[index] },
                ) { _, stream ->
                    StreamCard(
                        stream = stream,
                        enabled = stream.isSelectableForPlayback(debridSettings.canResolvePlayableLinks),
                        appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks &&
                            !debridSettings.hasCustomStreamFormatting,
                        showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                        showAddonLogo = streamBadgeSettings.showAddonLogo,
                        badgePlacement = streamBadgeSettings.badgePlacement,
                        isCurrent = stream.isCurrentPlayerStream(currentStreamUrl, currentStreamName),
                        currentLabel = currentLabel,
                        onClick = { onStreamSelected(stream) },
                    )
                }
                if (streamsUiState.isAnyLoading) {
                    item {
                        PlayerModalLoading(modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
            }
            }
        }
    }
}
