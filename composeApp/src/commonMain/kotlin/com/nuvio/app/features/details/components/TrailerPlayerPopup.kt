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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.player.FullscreenPlayerDialog
import com.nuvio.app.features.player.HidePlayerSystemBars
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
    startInFullscreen: Boolean = false,
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

    val playbackState = remember(playbackSource) { TrailerPlaybackState() }

    if (isIos) {
        LockPlayerToLandscape()
        FullscreenPlayerDialog(
            onDismiss = {
                playbackState.controller?.pause()
                onDismiss()
            },
        ) {
            TrailerPlayer(
                source = playbackSource,
                state = playbackState,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
                title = headerSubtitle.ifBlank { headerType },
                onExitFullscreen = {
                    playbackState.controller?.pause()
                    onDismiss()
                },
            )
        }
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var fullscreen by remember { mutableStateOf(startInFullscreen) }

    val dismissPlayer: () -> Unit = {
        playbackState.controller?.pause()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }
    val exitFullscreen = {
        if (startInFullscreen) {
            dismissPlayer()
        } else {
            fullscreen = false
        }
    }

    val currentSource = rememberUpdatedState(playbackSource)
    val currentPlaybackState = rememberUpdatedState(playbackState)
    val currentIsLoading = rememberUpdatedState(isLoading)
    val currentError = rememberUpdatedState(errorMessage)
    val currentOnRetry = rememberUpdatedState(onRetry)
    val currentTitle = rememberUpdatedState(headerSubtitle.ifBlank { headerType })
    val currentOnExit = rememberUpdatedState(if (fullscreen) exitFullscreen else null)

    val trailerPlayer = remember {
        movableContentOf<Modifier> { playerModifier ->
            TrailerPlayer(
                source = currentSource.value,
                state = currentPlaybackState.value,
                isLoading = currentIsLoading.value,
                errorMessage = currentError.value,
                onRetry = currentOnRetry.value,
                modifier = playerModifier,
                title = currentTitle.value,
                onExitFullscreen = currentOnExit.value,
            )
        }
    }

    if (fullscreen) {
        LockPlayerToLandscape()
        HidePlayerSystemBars()
        PlatformBackHandler(enabled = true, onBack = exitFullscreen)
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            if (fullscreen && !startInFullscreen) {
                fullscreen = false
            } else {
                dismissPlayer()
            }
        },
        sheetState = sheetState,
        modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier,
        containerColor = if (fullscreen) Color.Black else MaterialTheme.colorScheme.surface,
        shape = if (fullscreen) RectangleShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        showDragHandle = !fullscreen,
        fullHeight = fullscreen,
        sheetMaxWidth = if (fullscreen) Dp.Unspecified else BottomSheetDefaults.SheetMaxWidth,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.fillMaxSize() else Modifier)
                .padding(horizontal = if (fullscreen) 0.dp else 16.dp)
                .padding(bottom = if (fullscreen) 0.dp else nuvioSafeBottomPadding(14.dp)),
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 12.dp),
        ) {
            if (!fullscreen) {
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

                    IconButton(onClick = { fullscreen = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Fullscreen,
                            contentDescription = stringResource(Res.string.trailer_enter_fullscreen),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = dismissPlayer) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.trailer_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                NuvioBottomSheetDivider()
            }

            trailerPlayer(
                if (fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .aspectRatio(16f / 9f)
                },
            )
        }
    }
}
