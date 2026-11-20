package com.nuvio.app.features.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD = 3
const val MIN_STILL_WATCHING_EPISODE_THRESHOLD = 2
const val MAX_STILL_WATCHING_EPISODE_THRESHOLD = 6

internal const val STILL_WATCHING_COUNTDOWN_SECONDS = 60
private const val STILL_WATCHING_COUNTDOWN_TICK_MS = 1_000L

internal fun shouldEnterStillWatchingPrompt(
    stillWatchingEnabled: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    nextEpisodeHasAired: Boolean,
    consecutiveAutoPlayCount: Int,
    threshold: Int,
): Boolean = stillWatchingEnabled &&
    autoPlayNextEpisodeEnabled &&
    nextEpisodeHasAired &&
    consecutiveAutoPlayCount >= threshold

internal fun nextConsecutiveAutoPlayCount(
    currentCount: Int,
    isAutoPlay: Boolean,
): Int = if (isAutoPlay) currentCount + 1 else 0

internal fun PlayerScreenRuntime.countEpisodeSwitch(isAutoPlay: Boolean) {
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(consecutiveAutoPlayCount, isAutoPlay)
}

internal fun PlayerScreenRuntime.enterStillWatchingPrompt() {
    if (stillWatchingCountdown != null) return
    shouldPlay = false
    playerController?.pause()
    controlsVisible = false
    stillWatchingPlaybackPaused = false
    stillWatchingCountdown = STILL_WATCHING_COUNTDOWN_SECONDS
    stillWatchingJob?.cancel()
    stillWatchingJob = scope.launch {
        for (remaining in (STILL_WATCHING_COUNTDOWN_SECONDS - 1) downTo 0) {
            delay(STILL_WATCHING_COUNTDOWN_TICK_MS)
            stillWatchingCountdown = remaining
        }
        stillWatchingJob = null
        exitFromStillWatching()
    }
}

// Resuming playback while the prompt is open (controls, media session, PiP) answers it.
internal fun PlayerScreenRuntime.onStillWatchingPlaybackChanged(isPlaying: Boolean) {
    if (stillWatchingCountdown == null) return
    if (!isPlaying) {
        stillWatchingPlaybackPaused = true
    } else if (stillWatchingPlaybackPaused) {
        cancelStillWatchingPrompt()
        consecutiveAutoPlayCount = 0
        shouldPlay = true
        nextEpisodeCardDismissed = true
        showNextEpisodeCard = false
    }
}

internal fun PlayerScreenRuntime.onStillWatchingContinue() {
    cancelStillWatchingPrompt()
    consecutiveAutoPlayCount = 0
    playNextEpisode()
}

internal fun PlayerScreenRuntime.exitFromStillWatching() {
    cancelStillWatchingPrompt()
    consecutiveAutoPlayCount = 0
    flushWatchProgress()
    args.onBack()
}

internal fun PlayerScreenRuntime.cancelStillWatchingPrompt() {
    stillWatchingJob?.cancel()
    stillWatchingJob = null
    stillWatchingCountdown = null
    stillWatchingPlaybackPaused = false
}
