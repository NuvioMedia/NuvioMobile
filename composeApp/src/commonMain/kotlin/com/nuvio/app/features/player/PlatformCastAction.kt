package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformCastAction(
    modifier: Modifier = Modifier,
)

@Composable
expect fun PlatformCastSessionEffect(
    url: String,
    title: String,
    positionMs: Long,
    headers: Map<String, String>,
    canCast: Boolean,
    wasPlaying: Boolean,
    onPauseLocal: () -> Unit,
    onRestoreLocal: (positionMs: Long, resumePlayback: Boolean) -> Unit,
    onUnsupported: () -> Unit,
)
