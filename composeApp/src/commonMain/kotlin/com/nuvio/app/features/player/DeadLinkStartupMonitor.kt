package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches startup only. A source is considered started once the player reports
 * isPlaying=true; changing sources cancels the previous timer and invalidates
 * its callback so stale timeouts cannot switch a newer selection.
 */
internal class DeadLinkStartupMonitor(
    private val scope: CoroutineScope,
    private val timeoutSeconds: () -> Int,
    private val candidateStreams: suspend () -> List<StreamItem>,
    private val onFallback: (StreamItem) -> Unit,
    private val onExhausted: () -> Unit,
) {
    private val log = Logger.withTag("DeadLinkStartupMonitor")
    private var timer: Job? = null
    private var generation = 0L
    private var currentKey: String? = null
    private val attemptedKeys = linkedSetOf<String>()
    private var playbackStarted = false
    private var exhausted = false
    private var closed = false

    fun resetForContent() {
        generation++
        timer?.cancel()
        timer = null
        currentKey = null
        attemptedKeys.clear()
        playbackStarted = false
        exhausted = false
    }

    fun sourceSelected(stream: StreamItem) {
        if (closed || playbackStarted || exhausted) return
        val key = stream.playerSourceIdentityKey() ?: return
        if (currentKey == key && timer?.isActive == true) return
        timer?.cancel()
        generation++
        val sourceGeneration = generation
        currentKey = key
        attemptedKeys += key
        timer = scope.launch {
            delay(DeadLinkTimeoutPolicy.normalizeSeconds(timeoutSeconds()).toLong() * 1000L)
            if (closed || playbackStarted || exhausted || generation != sourceGeneration || currentKey != key) {
                return@launch
            }

            // Discover sources before declaring exhaustion. Keep this job as the
            // active timer while suspended so source changes, playback start, and
            // close() can cancel an in-flight discovery.
            // A transient discovery failure must not permanently stop fallback for
            // this source. Retry with capped backoff until discovery succeeds or this
            // attempt is invalidated by playback, source selection, content reset, or close.
            var discoveredCandidates = emptyList<StreamItem>()
            var discoverySucceeded = false
            var discoveryFailures = 0
            while (!discoverySucceeded) {
                try {
                    discoveredCandidates = candidateStreams()
                    discoverySucceeded = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (closed || playbackStarted || exhausted ||
                        generation != sourceGeneration || currentKey != key
                    ) {
                        return@launch
                    }

                    val retryDelayMs = when (discoveryFailures++) {
                        0 -> 2_000L
                        1 -> 5_000L
                        2 -> 15_000L
                        else -> 30_000L
                    }
                    log.w {
                        "Source discovery failed after startup timeout for $key; retrying in ${retryDelayMs}ms: ${error.message}"
                    }
                    delay(retryDelayMs)
                    if (closed || playbackStarted || exhausted ||
                        generation != sourceGeneration || currentKey != key
                    ) {
                        return@launch
                    }
                }
            }

            if (closed || playbackStarted || exhausted ||
                generation != sourceGeneration || currentKey != key
            ) {
                return@launch
            }

            val candidates = discoveredCandidates
                .asSequence()
                .filter { it.hasPlayableSource }
                .sortedBy(::deadLinkSourcePriority)
                .distinctBy { it.playerSourceIdentityKey() }
                .filter { candidate ->
                    val candidateKey = candidate.playerSourceIdentityKey()
                    candidateKey != null && candidateKey !in attemptedKeys
                }
                .toList()
            val next = candidates.firstOrNull()
            timer = null
            if (next == null) {
                exhausted = true
                log.w { "Startup timeout reached for $key; no untried source remains" }
                onExhausted()
                return@launch
            }

            val nextKey = next.playerSourceIdentityKey()
            if (nextKey != null) {
                attemptedKeys += nextKey
                // Invalidate the old source before notifying the player. This
                // closes the race where a late isPlaying event from the old
                // player marks the newly selected source as healthy.
                currentKey = nextKey
                generation++
            }
            log.i { "Startup timeout reached for $key; falling back to ${next.addonName} · ${next.streamLabel}" }
            onFallback(next)
        }
    }

    fun playbackStateChanged(stream: StreamItem?, isPlaying: Boolean) {
        if (closed || !isPlaying) return
        val key = stream?.playerSourceIdentityKey()
        if (key != null && currentKey != null && key != currentKey) return
        playbackStarted = true
        timer?.cancel()
        timer = null
        log.d { "Playback started; startup fallback disabled" }
    }

    fun close() {
        closed = true
        generation++
        timer?.cancel()
        timer = null
    }
}
