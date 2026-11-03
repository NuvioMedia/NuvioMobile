package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformCastAction(
    modifier: Modifier,
) = Unit

@Composable
actual fun PlatformCastSessionEffect(
    url: String,
    title: String,
    positionMs: Long,
    headers: Map<String, String>,
    canCast: Boolean,
    onPauseLocal: () -> Unit,
    onResumeLocal: (Long) -> Unit,
    onUnsupported: () -> Unit,
) = Unit
