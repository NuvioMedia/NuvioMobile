package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
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
    currentStream: PlayerCurrentStream?,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
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

            PlayerProviderFilterRow(
                streamsUiState = streamsUiState,
                onFilterSelected = onFilterSelected,
                onRefresh = onReload,
            )

            Spacer(Modifier.height(16.dp))

            PlayerStreamList(
                streamsUiState = streamsUiState,
                onStreamSelected = onStreamSelected,
                modifier = Modifier.weight(1f),
                currentStream = currentStream,
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

data class PlayerCurrentStream(
    val url: String? = null,
    val name: String? = null,
    val description: String? = null,
    val addonId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
)

internal fun List<StreamItem>.currentPlayerStreamIndex(current: PlayerCurrentStream?): Int {
    if (current == null) return -1
    val isSameAddon = { stream: StreamItem ->
        current.addonId.isNullOrBlank() || stream.addonId == current.addonId
    }

    if (!current.infoHash.isNullOrBlank()) {
        val hashMatch = indexOfFirst { stream ->
            stream.p2pInfoHash.equals(current.infoHash, ignoreCase = true) &&
                (current.fileIdx == null || stream.p2pFileIdx == current.fileIdx) &&
                isSameAddon(stream)
        }
        if (hashMatch >= 0) return hashMatch
    }

    if (!current.url.isNullOrBlank()) {
        val urlMatch = indexOfFirst { stream -> stream.playableDirectUrl == current.url }
        if (urlMatch >= 0) return urlMatch
    }

    // Addons may issue a new URL per request, so match what identifies the release instead.
    // Filenames and templates can repeat across streams, so only trust a single result.
    if (!current.filename.isNullOrBlank()) {
        val filenameMatches = indices.filter { index ->
            val stream = this[index]
            (stream.behaviorHints.filename ?: stream.clientResolve?.filename) == current.filename &&
                isSameAddon(stream)
        }
        filenameMatches.singleOrNull()?.let { return it }
    }

    if (!current.name.isNullOrBlank()) {
        val nameMatches = indices.filter { index ->
            val stream = this[index]
            stream.streamLabel.equals(current.name, ignoreCase = true) &&
                isSameAddon(stream) &&
                (current.description == null || stream.streamSubtitle == current.description)
        }
        nameMatches.singleOrNull()?.let { return it }
    }

    return -1
}
