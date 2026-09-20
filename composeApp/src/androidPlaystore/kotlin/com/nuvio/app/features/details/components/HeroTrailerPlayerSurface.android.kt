package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onControllerReady: (com.nuvio.app.features.player.PlayerEngineController) -> Unit,
    onSnapshot: (com.nuvio.app.features.player.PlayerPlaybackSnapshot) -> Unit,
) {
    LaunchedEffect(sourceUrl) {
        onError()
    }
}
