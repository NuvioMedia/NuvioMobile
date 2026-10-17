package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.isSelectableForPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches startup for the currently selected source.
 *
 * Source discovery is intentionally separate from the startup deadline: after
 * the deadline, this monitor uses candidates already available and keeps
 * observing discovery while other add-ons/plugins finish in the background.
 */
internal class DeadLinkStartupMonitor(
    private val scope: CoroutineScope,
    private val timeoutSeconds: () -> Int,
    private val timeoutEnabled: () -> Boolean = { true },
    private val candidateStreams: suspend () -> List<StreamItem>,
    private val onFallback: (StreamItem) -> Unit,
    private val onExhausted: () -> Unit,
    private val sourceDiscoveryLoading: () -> Boolean = { false },
    private val onDeadLink: (StreamItem) -> Unit = {},
) {
    private val log = Logger.withTag("DeadLinkStartupMonitor")
    private var timer: Job? = null
    private var generation = 0L
    private var currentKey: String? = null
    private val attemptedKeys = linkedSetOf<String>()
    private val deadMarkedKeys = linkedSetOf<String>()
    private var currentStream: StreamItem? = null
    private var playbackStarted = false
    private var buffering = false
    private var awaitingActivation = false
    private var exhausted = false
    private var closed = false

    fun resetForContent() {
        generation++
        timer?.cancel()
        timer = null
        currentKey = null
        attemptedKeys.clear()
        deadMarkedKeys.clear()
        currentStream = null
        playbackStarted = false
        buffering = false
        awaitingActivation = false
        exhausted = false
    }

    /**
     * Called when a stream is selected or becomes the runtime's active stream.
     * A different source always gets a fresh watchdog, even if an earlier source
     * played successfully. A fallback candidate also gets a watchdog while its
     * asynchronous preparation is in progress, so resolver failures cannot end
     * the chain silently.
     */
    fun sourceSelected(stream: StreamItem) {
        if (closed) return
        val key = stream.playerSourceIdentityKey() ?: return
        currentStream = stream
        if (!timeoutEnabled()) {
            timer?.cancel()
            timer = null
            generation++
            currentKey = key
            attemptedKeys += key
            playbackStarted = false
            buffering = false
            awaitingActivation = false
            exhausted = false
            return
        }

        if (currentKey == key) {
            if (awaitingActivation) {
                // The player has now accepted this candidate. Start its full
                // startup window from activation rather than from dispatch.
                awaitingActivation = false
                timer?.cancel()
                timer = null
                generation++
                armTimeout(key, generation)
                return
            }
            if (playbackStarted || timer?.isActive == true) return
            if (exhausted) return
            generation++
            armTimeout(key, generation)
            return
        }

        timer?.cancel()
        timer = null
        generation++
        currentKey = key
        attemptedKeys += key
        playbackStarted = false
        buffering = false
        awaitingActivation = false
        exhausted = false
        if (timeoutEnabled()) armTimeout(key, generation)
    }

    /** Disable fallback monitoring for non-stream playback (e.g. a local file). */
    fun sourceCleared() {
        generation++
        timer?.cancel()
        timer = null
        currentKey = null
        currentStream = null
        playbackStarted = false
        buffering = false
        awaitingActivation = false
        exhausted = false
    }

    fun playbackStateChanged(stream: StreamItem?, isPlaying: Boolean, isLoading: Boolean) {
        if (closed) return
        val key = stream?.playerSourceIdentityKey() ?: return
        if (key != currentKey) return

        if (isPlaying) {
            playbackStarted = true
            buffering = false
            awaitingActivation = false
            timer?.cancel()
            timer = null
            log.d { "Playback started; startup fallback disabled for $key" }
            return
        }

        // Re-arm the deadline when a source returns to a stuck buffering state.
        if (isLoading && !buffering && timeoutEnabled()) {
            buffering = true
            playbackStarted = false
            generation++
            timer?.cancel()
            armTimeout(key, generation)
            log.w { "Playback entered buffering; starting dead-link deadline for $key" }
        } else if (!isLoading) {
            buffering = false
        }
    }

    fun settingsChanged() {
        if (closed) return
        val key = currentKey ?: return
        if (!timeoutEnabled()) {
            timer?.cancel()
            timer = null
            generation++
            return
        }
        if (playbackStarted && !buffering) return
        timer?.cancel()
        timer = null
        generation++
        exhausted = false
        armTimeout(key, generation)
    }

    fun close() {
        closed = true
        generation++
        timer?.cancel()
        timer = null
    }

    private fun armTimeout(key: String, expectedGeneration: Long) {
        if (!timeoutEnabled()) return
        timer = scope.launch {
            delay(DeadLinkTimeoutPolicy.normalizeSeconds(timeoutSeconds()).toLong() * 1000L)
            if (!isCurrent(key, expectedGeneration)) return@launch
            findAndActivateFallback(key, expectedGeneration)
        }
    }

    private suspend fun findAndActivateFallback(key: String, expectedGeneration: Long) {
        val failedStream = currentStream?.takeIf { it.playerSourceIdentityKey() == key }
        if (failedStream != null && deadMarkedKeys.add(key)) {
            log.w { "Startup deadline expired; pruning dead source ${failedStream.addonName} · ${failedStream.streamLabel}" }
            onDeadLink(failedStream)
        }

        var discoveryFailures = 0
        while (isCurrent(key, expectedGeneration)) {
            val discovered = try {
                candidateStreams()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(key, expectedGeneration)) return
                val retryDelayMs = when (discoveryFailures++) {
                    0 -> 2_000L
                    1 -> 5_000L
                    2 -> 15_000L
                    else -> 30_000L
                }
                log.w {
                    "Candidate snapshot failed for $key; retrying in ${retryDelayMs}ms: ${error.message}"
                }
                delay(retryDelayMs)
                continue
            }

            if (!isCurrent(key, expectedGeneration)) return

            val debridEnabled = DebridSettingsRepository.snapshot().enabled
            val next = discovered.asSequence()
                // Do not auto-open an external app: it has no in-player startup
                // signal, so the watchdog cannot know whether it succeeded.
                .filter { !it.shouldOpenExternally && it.isSelectableForPlayback(debridEnabled) }
                .distinctBy { it.playerSourceIdentityKey() }
                .filter { candidate ->
                    val candidateKey = candidate.playerSourceIdentityKey()
                    candidateKey != null && candidateKey !in attemptedKeys
                }
                .sortedBy(::deadLinkSourcePriority)
                .firstOrNull()

            if (next != null) {
                val nextKey = next.playerSourceIdentityKey()
                if (nextKey == null) continue

                // Mark it attempted before dispatch to prevent duplicate/racing
                // callbacks. Keep a watchdog alive while asynchronous server,
                // YouTube, debrid or P2P preparation is underway.
                attemptedKeys += nextKey
                currentKey = nextKey
                currentStream = next
                generation++
                playbackStarted = false
                buffering = false
                awaitingActivation = true
                exhausted = false
                timer = null
                armTimeout(nextKey, generation)
                log.i {
                    "Startup timeout reached for $key; falling back to ${next.addonName} · ${next.streamLabel}"
                }
                onFallback(next)
                return
            }

            if (!sourceDiscoveryLoading()) {
                if (!isCurrent(key, expectedGeneration)) return
                exhausted = true
                timer = null
                log.w { "Startup timeout reached for $key; no untried playable source remains" }
                onExhausted()
                return
            }

            // No untried candidate has arrived yet. Keep the original provider
            // jobs running and re-check their partial results; don't mistake an
            // empty, still-loading snapshot for exhaustion.
            delay(CANDIDATE_POLL_INTERVAL_MS)
        }
    }

    private fun isCurrent(key: String, expectedGeneration: Long): Boolean =
        !closed &&
            (!playbackStarted || buffering) &&
            !exhausted &&
            generation == expectedGeneration &&
            currentKey == key

    private companion object {
        const val CANDIDATE_POLL_INTERVAL_MS = 300L
    }
}
