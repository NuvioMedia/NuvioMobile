package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import kotlinx.coroutines.flow.collect

private fun List<AddonSubtitle>.toAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.language,
            name = subtitle.display,
        )
    }

private fun PlayerScreenRuntime.currentAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    addonSubtitles.toAutoSyncCandidates()

internal fun PlayerScreenRuntime.configureAutoSyncController(
    controller: PlayerEngineController,
) {
    controller.setAutoSyncSubtitleCandidates(currentAutoSyncCandidates())
    controller.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
        val appliedSubtitle = addonSubtitles.firstOrNull { it.url == subtitleUrl }
        selectedAddonSubtitleId = appliedSubtitle?.selectionKey ?: subtitleUrl
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        preferredSubtitleSelectionApplied = true
        if (appliedSubtitle != null && isUserExplicitSubtitleSelection) {
            persistAddonSubtitlePreference(appliedSubtitle)
        }

        val appliedDelayMs = delayMs.coerceIn(
            SUBTITLE_DELAY_MIN_MS,
            SUBTITLE_DELAY_MAX_MS,
        )
        subtitleDelayMs = appliedDelayMs
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(
            playbackSession.videoId,
            appliedDelayMs,
        )
    }
}

@Composable
internal fun PlayerScreenRuntime.BindAutoSyncRuntimeEffects() {
    val activeController = playerController
    DisposableEffect(activeController) {
        // Runs once playerController is set, which Nuvio only does for the live playback key.
        activeController?.let { configureAutoSyncController(it) }
        onDispose { activeController?.setAutoSyncAppliedListener(null) }
    }
    LaunchedEffect(playerController, playbackSnapshot.isLoading, isLoadingAddonSubtitles, addonSubtitles) {
        // Add-ons can finish after Nuvio has completed its first track scan with no subtitle.
        // Retry that automatic pick only for AutoSync, while the user still has no selection.
        if (playerController !is AutoSyncPlayerController || playbackSnapshot.isLoading) return@LaunchedEffect
        if (!AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value) return@LaunchedEffect
        if (isLoadingAddonSubtitles || addonSubtitles.isEmpty()) return@LaunchedEffect
        if (!preferredSubtitleSelectionApplied || isUserExplicitSubtitleSelection) return@LaunchedEffect
        if (selectedSubtitleIndex >= 0 || selectedAddonSubtitleId != null) return@LaunchedEffect
        preferredSubtitleSelectionApplied = false
        refreshTracks()
    }
    LaunchedEffect(playerController, externalSubtitles) {
        val controller = playerController ?: return@LaunchedEffect
        SubtitleRepository.addonSubtitles.collect { repositorySubtitles ->
            controller.setAutoSyncSubtitleCandidates(
                mergeStreamAndAddonSubtitles(
                    repositorySubtitles,
                    externalSubtitles,
                ).toAutoSyncCandidates(),
            )
        }
    }
}

/** The user picked an addon subtitle: let AutoSync check it without replacing their choice. */
internal fun PlayerScreenRuntime.attachSelectedAddonSubtitleWithAutoSync(url: String) {
    subtitleAutoSyncState = SubtitleAutoSyncUiState()
    playerController?.setSubtitleUriWithSelectedAutoSync(url)
}

internal fun PlayerScreenRuntime.maybeAutoSyncRestoredSubtitleAtStart(url: String): Boolean {
    val controller = playerController ?: return false
    if (controller !is AutoSyncPlayerController) return false
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)) {
        return false
    }
    controller.setSubtitleUriWithAutoSync(url)
    return true
}

internal fun PlayerScreenRuntime.maybeAutoSyncPreferredSubtitleAtStart(
    subtitle: AddonSubtitle,
): Boolean {
    if (isUserExplicitSubtitleSelection) return false
    val preferredLanguage =
        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ?: return false
    if (
        preferredLanguage.isBlank() ||
        preferredLanguage == SubtitleLanguageOption.NONE ||
        preferredLanguage == SubtitleLanguageOption.FORCED
    ) {
        return false
    }

    val controller = playerController ?: return false
    if (controller !is AutoSyncPlayerController) return false
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)) {
        return false
    }
    controller.setSubtitleUriWithAutoSync(subtitle.url)
    return true
}
