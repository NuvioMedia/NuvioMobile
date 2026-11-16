package com.nuvio.app.features.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,

    /**
     * A selection restored from an earlier episode: check it first and keep it if it fits;
     * only when it does not, search the other same-language subtitles like a startup run.
     */
    SELECTED_THEN_SEARCH,
}

/** Whether a failed check of the selected subtitle should be followed by a full search. */
internal fun AutoSyncCandidateScope.searchAfterSelectedFailed(
    outcome: AutoSyncAnalysisOutcome?,
): Boolean =
    this == AutoSyncCandidateScope.SELECTED_THEN_SEARCH &&
        // Nothing in the video to compare with: other subtitles cannot fit either.
        outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
        outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null
