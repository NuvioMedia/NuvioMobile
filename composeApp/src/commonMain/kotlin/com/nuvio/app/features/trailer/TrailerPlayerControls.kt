package com.nuvio.app.features.trailer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.player.PlayPauseControlButton
import com.nuvio.app.features.player.PlayerHeaderIconButton
import com.nuvio.app.features.player.playerHorizontalSafePadding
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerSeekBar
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.trailer_exit_fullscreen
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TrailerPlayerControls(
    state: TrailerPlaybackState,
    canControlPlayback: Boolean,
    title: String,
    onExitFullscreen: (() -> Unit)?,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    val isScrubbing = state.scrubPositionMs != null
    val fullscreen = onExitFullscreen != null
    LaunchedEffect(controlsVisible, state.snapshot.isPlaying, isScrubbing) {
        if (controlsVisible && state.snapshot.isPlaying && !isScrubbing) {
            delay(3_000L)
            controlsVisible = false
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (canControlPlayback) Modifier.pointerInput(controlsVisible) {
                detectTapGestures { controlsVisible = !controlsVisible }
            } else Modifier,
        ),
    ) {
        val metrics = PlayerLayoutMetrics.fromWidth(maxWidth)
        val horizontalPadding = if (fullscreen) {
            playerHorizontalSafePadding() + metrics.horizontalPadding
        } else {
            8.dp
        }
        AnimatedVisibility(
            visible = controlsVisible || !state.snapshot.isPlaying || isScrubbing || !canControlPlayback,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (fullscreen) {
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                            .padding(horizontal = horizontalPadding)
                            .padding(top = metrics.verticalPadding / 4),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            style = MaterialTheme.nuvioTypeScale.titleLg.copy(
                                fontSize = metrics.titleSize,
                                lineHeight = metrics.titleSize * 1.16f,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PlayerHeaderIconButton(
                            icon = Icons.Rounded.FullscreenExit,
                            contentDescription = stringResource(Res.string.trailer_exit_fullscreen),
                            buttonSize = metrics.headerIconSize + 16.dp,
                            iconSize = metrics.headerIconSize,
                            onClick = { onExitFullscreen?.invoke() },
                        )
                    }
                }
                if (canControlPlayback) {
                    if (!state.snapshot.isLoading) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(bottom = metrics.centerLift),
                        ) {
                            PlayPauseControlButton(
                                isPlaying = state.snapshot.isPlaying,
                                isBuffering = false,
                                metrics = metrics,
                                onClick = {
                                    controlsVisible = true
                                    state.togglePlayback()
                                },
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .then(
                                if (fullscreen) Modifier.windowInsetsPadding(
                                    WindowInsets.safeContent.only(WindowInsetsSides.Bottom),
                                ) else Modifier,
                            )
                            .padding(horizontal = horizontalPadding)
                            .padding(bottom = if (fullscreen) metrics.sliderBottomOffset else 4.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            PlayerSeekBar(
                                durationMs = state.snapshot.durationMs,
                                displayedPositionMs = state.scrubPositionMs ?: state.snapshot.positionMs,
                                metrics = metrics,
                                onScrubChange = { state.scrubPositionMs = it },
                                onScrubFinished = { state.seekTo(state.scrubPositionMs ?: it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
