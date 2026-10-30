package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Restores the audio delay saved for the output media plays on, when the player remembers it per
 * device, and keeps the engine on the current delay. Without that setting the delay lasts until
 * the player closes.
 */
@Composable
internal fun PlayerScreenRuntime.BindAudioDelayEffects() {
    val outputKey = rememberAudioOutputKey(enabled = playerSettingsUiState.rememberAudioDelayPerDevice)

    LaunchedEffect(outputKey) {
        audioOutputKey = outputKey
        if (outputKey != null) {
            audioDelayMs = PlayerTrackPreferenceStorage.loadAudioDelayMs(outputKey) ?: 0
        }
    }

    LaunchedEffect(playerController, audioDelayMs) {
        playerController?.setAudioDelayMs(audioDelayMs)
    }
}

internal fun PlayerScreenRuntime.setAudioDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayMs = clamped
    audioOutputKey?.let { PlayerTrackPreferenceStorage.saveAudioDelayMs(it, clamped) }
    playerController?.setAudioDelayMs(clamped)
}
