package com.nuvio.app.features.trailer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.player.EnterImmersivePlayerMode
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.trailer_unable_to_play
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrailerPlayer(
    source: TrailerPlaybackSource?,
    state: TrailerPlaybackState,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "",
    onExitFullscreen: (() -> Unit)? = null,
) {
    val error = errorMessage ?: state.error
    EnterImmersivePlayerMode(keepScreenAwake = error == null && state.playWhenReady && !state.snapshot.isEnded)
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (source != null && !isLoading && error == null) {
            TrailerPlaybackSurface(
                source = source,
                state = state,
                resizeMode = PlayerResizeMode.Fit,
            )
        }
        when {
            error == null && (isLoading || source != null && state.snapshot.isLoading) -> {
                NuvioLoadingIndicator(color = Color.White)
            }
            error != null -> Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.trailer_unable_to_play),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                }
            }
        }
        TrailerPlayerControls(
            state = state,
            canControlPlayback = source != null && !isLoading && error == null,
            title = title,
            onExitFullscreen = onExitFullscreen,
        )
    }
}

@Composable
private fun TrailerPlaybackSurface(
    source: TrailerPlaybackSource,
    state: TrailerPlaybackState,
    resizeMode: PlayerResizeMode,
) {
    val presentationId = remember { state.presentationId }
    val resumePositionMs = remember { state.snapshot.positionMs }
    var restoringPosition by remember { mutableStateOf(resumePositionMs > 0L) }
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }

    LaunchedEffect(controller, snapshot.isLoading, snapshot.durationMs, restoringPosition) {
        val player = controller
        if (restoringPosition && player != null && !snapshot.isLoading && snapshot.durationMs > 0L &&
            presentationId == state.presentationId
        ) {
            player.seekTo(resumePositionMs.coerceAtMost(snapshot.durationMs))
            restoringPosition = false
        }
    }
    DisposableEffect(state) {
        onDispose {
            if (state.controller === controller) state.controller = null
        }
    }
    PlatformPlayerSurface(
        sourceUrl = source.videoUrl,
        sourceAudioUrl = source.audioUrl,
        useYoutubeChunkedPlayback = true,
        modifier = Modifier.fillMaxSize(),
        playWhenReady = state.playWhenReady && !restoringPosition && !state.snapshot.isEnded &&
            presentationId == state.presentationId,
        resizeMode = resizeMode,
        useNativeController = false,
        onControllerReady = {
            controller = it
            if (presentationId == state.presentationId) state.controller = it
        },
        onSnapshot = {
            snapshot = it
            if (!restoringPosition && presentationId == state.presentationId) state.snapshot = it
        },
        onError = { if (presentationId == state.presentationId) state.error = it },
    )
    if (restoringPosition) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}
