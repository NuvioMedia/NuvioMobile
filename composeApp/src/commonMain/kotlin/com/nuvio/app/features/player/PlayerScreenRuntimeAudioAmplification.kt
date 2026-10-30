package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Each player session starts without amplification, or at the saved level when the user keeps it
 * for the next videos, and the engine follows the current level.
 */
@Composable
internal fun PlayerScreenRuntime.BindAudioAmplificationEffects() {
    LaunchedEffect(Unit) {
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        audioAmplificationDb = if (settings.persistAudioAmplification) settings.audioAmplificationDb else 0
    }

    LaunchedEffect(playerController, audioAmplificationDb) {
        playerController?.setAudioAmplificationDb(audioAmplificationDb)
    }
}

internal fun PlayerScreenRuntime.setAudioAmplification(db: Int) {
    val clamped = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    audioAmplificationDb = clamped
    if (playerSettingsUiState.persistAudioAmplification) {
        PlayerSettingsRepository.setAudioAmplificationDb(clamped)
    }
    playerController?.setAudioAmplificationDb(clamped)
}

/** Keeping the amplification saves the current level for the next sessions. */
internal fun PlayerScreenRuntime.setKeepAudioAmplification(keep: Boolean) {
    PlayerSettingsRepository.setPersistAudioAmplification(keep)
    if (keep) PlayerSettingsRepository.setAudioAmplificationDb(audioAmplificationDb)
}
