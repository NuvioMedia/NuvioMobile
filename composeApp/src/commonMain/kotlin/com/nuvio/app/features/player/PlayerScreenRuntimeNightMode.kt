package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Night mode is a saved setting, not a per-session level: the engine follows it whether it is
 * changed in the player's audio panel or in Settings.
 */
@Composable
internal fun PlayerScreenRuntime.BindNightModeEffects() {
    val nightMode = playerSettingsUiState.nightMode
    LaunchedEffect(playerController, nightMode) {
        playerController?.setNightMode(nightMode)
    }
}

internal fun PlayerScreenRuntime.setNightMode(mode: NightMode) {
    PlayerSettingsRepository.setNightMode(mode)
    playerController?.setNightMode(mode)
}
