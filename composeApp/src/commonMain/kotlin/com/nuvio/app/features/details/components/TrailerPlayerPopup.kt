package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.player.FullscreenPlayerDialog
import com.nuvio.app.features.player.LockPlayerToLandscape
import com.nuvio.app.features.trailer.TrailerPlaybackState
import com.nuvio.app.features.trailer.TrailerPlayer
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailerPlayerPopup(
    visible: Boolean,
    trailerTitle: String,
    trailerType: String,
    contentTitle: String,
    playbackSource: TrailerPlaybackSource?,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    if (!visible) return

    val headerType = trailerType.trim().ifBlank { stringResource(Res.string.detail_tab_trailer) }
    val headerSubtitle = buildList {
        if (trailerTitle.isNotBlank() && !trailerTitle.equals(headerType, ignoreCase = true)) {
            add(trailerTitle)
        }
        if (contentTitle.isNotBlank()) {
            add(contentTitle)
        }
    }.joinToString(separator = " • ")

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val playbackState = remember(playbackSource) { TrailerPlaybackState() }
    var fullscreen by remember { mutableStateOf(false) }
    val toggleFullscreen = {
        playbackState.changePresentation()
        fullscreen = !fullscreen
    }

    val exitFullscreen = {
        if (isIos) {
            playbackState.controller?.pause()
            onDismiss()
        } else {
            toggleFullscreen()
        }
    }

    if (isIos || fullscreen) {
        LockPlayerToLandscape()
        FullscreenPlayerDialog(onDismiss = exitFullscreen) {
            TrailerPlayer(
                source = playbackSource,
                state = playbackState,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
                title = headerSubtitle.ifBlank { headerType },
                onExitFullscreen = exitFullscreen,
            )
        }
        return
    }

    val dismissSheet: () -> Unit = {
        playbackState.controller?.pause()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = nuvioSafeBottomPadding(14.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = headerType,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (headerSubtitle.isNotBlank()) {
                        Text(
                            text = headerSubtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(onClick = toggleFullscreen) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(Res.string.trailer_enter_fullscreen),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = dismissSheet) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.trailer_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            NuvioBottomSheetDivider()

            TrailerPlayer(
                source = playbackSource,
                state = playbackState,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .aspectRatio(16f / 9f),
            )
        }
    }
}
