package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession

/**
 * Common playback result from an external player.
 * Mirrors ExternalPlayerResultParser.PlaybackResult but lives in commonMain.
 */
data class ExternalPlaybackResult(
    val positionMs: Long,
    val durationMs: Long?,
    val endedByUser: Boolean,
    val playbackSession: WatchProgressPlaybackSession? = null,
    val callbackId: String? = null,
)

@Composable
expect fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean
