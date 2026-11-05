package com.nuvio.app.features.player

/**
 * Reads the chapters once per source, when it has loaded: libmpv lists them itself, ExoPlayer
 * reads them from the file (when allowed), so a rebuffer must not read them again.
 */
internal suspend fun PlayerScreenRuntime.loadChaptersIfNeeded() {
    val controller = playerController ?: return
    val sourceUrl = activeSourceUrl
    if (playerControllerSourceUrl != sourceUrl || chaptersLoadedForSourceUrl == sourceUrl) return
    val loaded = PlayerChapters.normalize(controller.getChapters())
    if (activeSourceUrl != sourceUrl || playerController !== controller) return
    chaptersLoadedForSourceUrl = sourceUrl
    chapters = loaded
    onChaptersLoaded(loaded)
}

internal fun PlayerScreenRuntime.openChaptersPanel() {
    if (chapters.isEmpty()) return
    showChaptersPanel = true
    showSourcesPanel = false
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.selectChapter(index: Int) {
    val chapter = chapters.getOrNull(index) ?: return
    showChaptersPanel = false
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to chapter.startMs
    playerController?.seekTo(chapter.startMs)
    scheduleProgressSyncAfterSeek()
    controlsVisible = true
}

/**
 * Store raw chapters for skip detection (independent of UI list) and compute intervals.
 */
private fun PlayerScreenRuntime.onChaptersLoaded(chapters: List<PlayerChapter>) {
    skipDetectionChapters = chapters
    skipChaptersComputedForDuration = 0L
    if (playerSettingsUiState.skipIntroEnabled) {
        tryComputeChapterSkipIntervals()
    }
}

private fun PlayerScreenRuntime.tryComputeChapterSkipIntervals(): Boolean {
    val chapters = skipDetectionChapters.takeIf { it.size >= 2 } ?: return false
    val durationMs = playbackSnapshot.durationMs.takeIf { it > 0L } ?: return false
    if (skipChaptersComputedForDuration == durationMs) return false
    skipChaptersComputedForDuration = durationMs
    chapterSkipIntervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
    recomputeMergedSkipIntervals()
    return true
}

/**
 * Called from the playback tick. Retries once duration is known, then stops.
 */
internal fun PlayerScreenRuntime.recomputeChapterSkipIntervalsIfNeeded() {
    if (!playerSettingsUiState.skipIntroEnabled) return
    if (skipChaptersComputedForDuration > 0L) return
    tryComputeChapterSkipIntervals()
}

internal fun PlayerScreenRuntime.clearChapterSkipState() {
    skipDetectionChapters = emptyList()
    chapterSkipIntervals = emptyList()
    skipChaptersComputedForDuration = 0L
}

/** Recompute the merged skipIntervals from chapters (priority) + API (gap-fill). */
internal fun PlayerScreenRuntime.recomputeMergedSkipIntervals() {
    val isMovie = (contentType ?: parentMetaType).equals("movie", ignoreCase = true)
    skipIntervals = mergeSkipIntervals(apiSkipIntervals, chapterSkipIntervals, isMovie = isMovie)
}
