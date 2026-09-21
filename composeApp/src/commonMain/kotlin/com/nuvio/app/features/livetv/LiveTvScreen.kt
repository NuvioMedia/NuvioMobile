package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_livetv
import nuvio.composeapp.generated.resources.livetv_now_playing
import nuvio.composeapp.generated.resources.livetv_sources_summary
import org.jetbrains.compose.resources.stringResource

@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onChannelPlay: (LiveTvChannel) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()

    ScreenActivityEffect { active ->
        if (active) {
            LiveTvRepository.ensureLoaded()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background),
    ) {
        NuvioScreenHeader(
            title = stringResource(Res.string.compose_nav_livetv),
            actions = {
                IconButton(onClick = { LiveTvRepository.refresh() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                }
            },
        )

        LiveTvSourceSummary(uiState = uiState)

        when {
            uiState.isLoading && uiState.channels.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioLoadingIndicator()
                }
            }

            uiState.errorMessage != null && uiState.channels.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.nuvio.colors.textSecondary,
                    )
                }
            }

            else -> {
                LiveTvEpgGrid(
                    uiState = uiState,
                    onChannelClick = onChannelPlay,
                    onToggleFavorite = { channel ->
                        LiveTvSettingsRepository.toggleFavorite(channel.id)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LiveTvSourceSummary(uiState: LiveTvUiState) {
    val tokens = MaterialTheme.nuvio
    val sources = buildList {
        if (uiState.plutoEnabled) add("Pluto TV")
        if (uiState.xtreamEnabled) add("Xtream Codes")
    }
    if (sources.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.livetv_now_playing),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.livetv_sources_summary,
                    sources.joinToString(" • "),
                    uiState.channels.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        Text(
            text = LiveTvTime.formatClock(uiState.nowMs),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.accent,
        )
    }
}
