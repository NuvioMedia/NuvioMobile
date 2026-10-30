package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Each player session starts without amplification and at the default center mix level, or at the
 * saved levels when the user keeps them for the next videos, and the engine follows the current
 * levels.
 */
@Composable
internal fun PlayerScreenRuntime.BindAudioAmplificationEffects() {
    LaunchedEffect(Unit) {
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        audioAmplificationDb = if (settings.persistAudioAmplification) settings.audioAmplificationDb else 0
        centerMixLevelDb = if (settings.persistAudioAmplification) settings.centerMixLevelDb else 0
    }

    LaunchedEffect(playerController, audioAmplificationDb) {
        playerController?.setAudioAmplificationDb(audioAmplificationDb)
    }

    LaunchedEffect(playerController, centerMixLevelDb) {
        playerController?.setCenterMixLevelDb(centerMixLevelDb)
    }

    // The decoder decides whether it downmixes once the audio has started, so ask when the panel opens.
    LaunchedEffect(showAudioModal, playerController) {
        if (showAudioModal) centerMixAvailable = playerController?.isCenterMixActive() == true
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

internal fun PlayerScreenRuntime.setCenterMixLevel(db: Int) {
    val clamped = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    centerMixLevelDb = clamped
    if (playerSettingsUiState.persistAudioAmplification) {
        PlayerSettingsRepository.setCenterMixLevelDb(clamped)
    }
    playerController?.setCenterMixLevelDb(clamped)
}

/** Keeping the levels saves the current amplification and center mix for the next sessions. */
internal fun PlayerScreenRuntime.setKeepAudioAmplification(keep: Boolean) {
    PlayerSettingsRepository.setPersistAudioAmplification(keep)
    if (keep) {
        PlayerSettingsRepository.setAudioAmplificationDb(audioAmplificationDb)
        PlayerSettingsRepository.setCenterMixLevelDb(centerMixLevelDb)
    }
}
