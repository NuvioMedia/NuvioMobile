package com.nuvio.app.features.player

private data class CapturedSubtitleSelection(
    val type: String,
    val language: String? = null,
    val name: String? = null,
    val trackId: String? = null,
    val isForced: Boolean? = null,
    val addonId: String? = null,
    val addonUrl: String? = null,
    val addonName: String? = null,
)

private enum class SubtitleCarryOverOutcome {
    Pending,
    Applied,
    Skipped,
}

/**
 * Captures the currently selected audio/subtitle tracks before the playback engine switches, so
 * the selection can be restored on the new engine even though track ids and ordering differ.
 */
internal fun PlayerScreenRuntime.rememberCurrentTrackPreferenceForEngineSwitch(
    targetEngine: AndroidPlaybackEngine,
) {
    val audio = captureCurrentAudioSelectionForEngineSwitch()
    val subtitle = captureCurrentSubtitleSelectionForEngineSwitch()
    if (audio == null && subtitle == null) {
        pendingEngineSwitchTrackPreference = null
        return
    }
    pendingEngineSwitchTrackPreference = PendingEngineSwitchTrackPreference(
        sourceIdentity = activePlaybackIdentity,
        capturedController = playerController,
        targetEngine = targetEngine,
        preference = PersistedPlayerTrackPreference(
            subtitleType = subtitle?.type,
            subtitleLanguage = subtitle?.language,
            subtitleName = subtitle?.name,
            subtitleTrackId = subtitle?.trackId,
            subtitleIsForced = subtitle?.isForced,
            addonSubtitleId = subtitle?.addonId,
            addonSubtitleUrl = subtitle?.addonUrl,
            addonSubtitleAddonName = subtitle?.addonName,
            audioLanguage = audio?.language,
            audioName = audio?.label,
            audioTrackId = audio?.id,
        ),
        hasAudioSelection = audio != null,
        hasSubtitleSelection = subtitle != null,
    )
}

internal fun PlayerScreenRuntime.clearPendingEngineSwitchTrackPreference() {
    pendingEngineSwitchTrackPreference = null
}

private fun PlayerScreenRuntime.captureCurrentAudioSelectionForEngineSwitch(): AudioTrack? {
    val controllerTracks = playerController?.getAudioTracks().orEmpty()
    return controllerTracks.firstOrNull { it.isSelected }
        ?: controllerTracks.getOrNull(selectedAudioIndex)
        ?: audioTracks.firstOrNull { it.index == selectedAudioIndex }
        ?: audioTracks.firstOrNull { it.isSelected }
}

private fun PlayerScreenRuntime.captureCurrentSubtitleSelectionForEngineSwitch(): CapturedSubtitleSelection? {
    selectedAddonSubtitle?.let { addon ->
        return CapturedSubtitleSelection(
            type = PersistedSubtitleSelectionType.ADDON,
            language = addon.language,
            name = addon.display,
            addonId = addon.id,
            addonUrl = addon.url,
            addonName = addon.addonName,
        )
    }

    // Subtitles were explicitly turned off: carry the off-state over as well.
    if (isUserExplicitSubtitleSelection && selectedSubtitleIndex < 0) {
        return CapturedSubtitleSelection(type = PersistedSubtitleSelectionType.DISABLED)
    }

    val controllerTracks = playerController?.getSubtitleTracks().orEmpty()
    val selected = controllerTracks.firstOrNull { it.isSelected }
        ?: controllerTracks.getOrNull(selectedSubtitleIndex)
        ?: subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
        ?: subtitleTracks.firstOrNull { it.isSelected }
    if (selected == null) return null
    return CapturedSubtitleSelection(
        type = PersistedSubtitleSelectionType.INTERNAL,
        language = selected.language,
        name = selected.label,
        trackId = selected.id,
        isForced = selected.isForced,
    )
}

/**
 * Re-applies the captured track selection on the engine that just took over; called from
 * [refreshTracks] as soon as the new engine exposes its track list.
 */
internal fun PlayerScreenRuntime.applyPendingEngineSwitchTrackPreference() {
    val pending = pendingEngineSwitchTrackPreference ?: return
    if (pending.sourceIdentity != activePlaybackIdentity) {
        clearPendingEngineSwitchTrackPreference()
        return
    }
    val controller = playerController ?: return
    if (controller === pending.capturedController) {
        // The engine the selection was captured on is still attached; the new one is not ready.
        return
    }
    val engine = controller.playbackEngine
    if (engine != null && engine != pending.targetEngine) {
        // Another engine took over: the carry-over does not apply.
        clearPendingEngineSwitchTrackPreference()
        return
    }

    var updated = pending
    if (!updated.audioApplied) {
        if (!updated.hasAudioSelection) {
            updated = updated.copy(audioApplied = true)
        } else {
            val track = findCarriedOverAudioTrack(audioTracks, updated.preference)
            when {
                track != null -> {
                    controller.selectAudioTrack(track.index)
                    selectedAudioIndex = track.index
                    isUserExplicitAudioSelection = true
                    preferredAudioSelectionApplied = true
                    updated = updated.copy(audioApplied = true)
                }
                audioTracks.isNotEmpty() -> {
                    // No matching track on the new engine: keep the default selection.
                    updated = updated.copy(audioApplied = true)
                }
            }
        }
    }
    if (!updated.subtitleApplied) {
        val outcome = if (!updated.hasSubtitleSelection) {
            SubtitleCarryOverOutcome.Skipped
        } else {
            applyCarriedOverSubtitleSelection(controller, updated.preference)
        }
        when (outcome) {
            SubtitleCarryOverOutcome.Applied -> updated = updated.copy(subtitleApplied = true)
            SubtitleCarryOverOutcome.Skipped -> updated = updated.copy(subtitleApplied = true)
            SubtitleCarryOverOutcome.Pending -> Unit
        }
    }

    if (updated.audioApplied && updated.subtitleApplied) {
        // The carry-over is the effective selection, so the stored preference must not be
        // restored on top of it (NuvioTV prefers the switch preference over the persisted one).
        trackPreferenceRestoreApplied = true
        pendingEngineSwitchTrackPreference = null
    } else {
        pendingEngineSwitchTrackPreference = updated
    }
}

private fun PlayerScreenRuntime.applyCarriedOverSubtitleSelection(
    controller: PlayerEngineController,
    preference: PersistedPlayerTrackPreference,
): SubtitleCarryOverOutcome {
    return when (preference.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            controller.selectSubtitleTrack(-1)
            selectedSubtitleIndex = -1
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            preferredSubtitleSelectionApplied = true
            isUserExplicitSubtitleSelection = true
            SubtitleCarryOverOutcome.Applied
        }
        PersistedSubtitleSelectionType.ADDON -> {
            val subtitle = findPersistedAddonSubtitle(addonSubtitles, preference)
            when {
                subtitle != null -> {
                    selectedAddonSubtitleId = subtitle.selectionKey
                    selectedSubtitleIndex = -1
                    useCustomSubtitles = true
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                    controller.setSubtitleUri(subtitle.url)
                    SubtitleCarryOverOutcome.Applied
                }
                isLoadingAddonSubtitles -> SubtitleCarryOverOutcome.Pending
                else -> SubtitleCarryOverOutcome.Skipped
            }
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            val track = findCarriedOverSubtitleTrack(subtitleTracks, preference)
            when {
                track != null -> {
                    if (useCustomSubtitles) {
                        controller.clearExternalSubtitleAndSelect(track.index)
                    } else {
                        controller.selectSubtitleTrack(track.index)
                    }
                    selectedSubtitleIndex = track.index
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    preferredSubtitleSelectionApplied = true
                    isUserExplicitSubtitleSelection = true
                    SubtitleCarryOverOutcome.Applied
                }
                subtitleTracks.isNotEmpty() -> SubtitleCarryOverOutcome.Skipped
                else -> SubtitleCarryOverOutcome.Pending
            }
        }
        else -> SubtitleCarryOverOutcome.Skipped
    }
}

/**
 * Finds the audio track of the new engine that corresponds to the captured one. Track ids are
 * engine specific (libmpv numeric ids, ExoPlayer format ids), so the match is by label and
 * language, like the persisted preference restore.
 */
private fun findCarriedOverAudioTrack(
    tracks: List<AudioTrack>,
    preference: PersistedPlayerTrackPreference,
): AudioTrack? {
    val targetName = preference.audioName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val targetLanguage = preference.audioLanguage?.trim()?.takeIf { it.isNotBlank() }

    if (targetName != null) {
        tracks.firstOrNull { track ->
            track.label.trim().lowercase() == targetName &&
                (targetLanguage == null || audioTrackMatchesLanguage(track, targetLanguage))
        }?.let { return it }
    }
    if (targetName != null) {
        tracks.firstOrNull { track -> track.label.trim().lowercase() == targetName }?.let { return it }
    }
    if (targetLanguage != null) {
        findPersistedAudioTrackIndex(tracks, preference.copy(audioTrackId = null))
            .takeIf { it >= 0 }
            ?.let { index -> tracks.firstOrNull { it.index == index } }
            ?.let { return it }
        tracks.firstOrNull { track -> audioTrackMatchesLanguage(track, targetLanguage) }?.let { return it }
    }
    return null
}

/**
 * Finds the subtitle track of the new engine that corresponds to the captured one.
 */
private fun findCarriedOverSubtitleTrack(
    tracks: List<SubtitleTrack>,
    preference: PersistedPlayerTrackPreference,
): SubtitleTrack? {
    // The track id is only meaningful within the engine that reported it.
    val effectivePreference = preference.copy(subtitleTrackId = null)
    findPersistedSubtitleTrackIndex(tracks, effectivePreference)
        .takeIf { it >= 0 }
        ?.let { index -> tracks.firstOrNull { it.index == index } }
        ?.let { return it }
    findPersistedSubtitleTrackIndex(tracks, effectivePreference.copy(subtitleIsForced = null))
        .takeIf { it >= 0 }
        ?.let { index -> tracks.firstOrNull { it.index == index } }
        ?.let { return it }
    // Last resort: the same label.
    val targetName = preference.subtitleName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    if (targetName != null) {
        tracks.firstOrNull { track -> track.label.trim().lowercase() == targetName }?.let { return it }
    }
    return null
}
