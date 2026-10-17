package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Monitors initial startup for the selected source. Only playback events
 * accepted for the active PlaybackKey should be passed into this monitor.
 */
internal class DeadLinkStartupMonitor(
    private val scope: CoroutineScope,
    private val timeoutSeconds: () -> Int,
    private val candidateStreams: () -> List<StreamItem>,
    private val onFallback: (StreamItem) -> Unit,
    private val onExhausted: () -> Unit,
) {
    private var timer: Job? = null
    private var generation = 0L
    private var activeSourceId: String? = null
    private var started = false
    private var timeoutHandled = false
    private var closed = false
    private val attemptedIds = linkedSetOf<String>()

    fun resetForContent() {
        generation += 1
        timer?.cancel()
        timer = null
        activeSourceId = null
        started = false
        timeoutHandled = false
        attemptedIds.clear()
    }

    fun sourceSelected(stream: StreamItem) {
        if (closed) return
        val id = stream.deadLinkStableId()
        if (id == activeSourceId) return

        generation += 1
        timer?.cancel()
        timer = null
        activeSourceId = id
        started = false
        timeoutHandled = false
        attemptedIds += id

        val expectedGeneration = generation
        timer = scope.launch {
            delay(DeadLinkTimeoutPolicy.normalizeSeconds(timeoutSeconds()).toLong() * 1_000L)
            if (closed || expectedGeneration != generation || activeSourceId != id || started || timeoutHandled) {
                return@launch
            }
            timeoutHandled = true
            val next = nextCandidate()
            if (next == null) {
                onExhausted()
            } else {
                // Mark it before invoking the callback to block duplicate retries.
                attemptedIds += next.deadLinkStableId()
                activeSourceId = null
                timer = null
                onFallback(next)
            }
        }
    }

    fun playbackStateChanged(stream: StreamItem?, isPlaying: Boolean) {
        if (closed) return
        val id = stream?.deadLinkStableId() ?: return
        if (id != activeSourceId) return
        if (isPlaying) {
            started = true
            timer?.cancel()
            timer = null
        }
    }

    fun close() {
        closed = true
        generation += 1
        timer?.cancel()
        timer = null
        activeSourceId = null
        attemptedIds.clear()
    }

    private fun nextCandidate(): StreamItem? = candidateStreams()
        .asSequence()
        .filter { stream ->
            stream.playableDirectUrl != null &&
                !stream.shouldOpenExternally &&
                !stream.isDirectDebridStream &&
                !stream.needsServerPreparation &&
                !stream.needsLocalDebridResolve &&
                stream.youTubeIdToResolve == null &&
                (stream.isInstalledAddonStream ||
                    stream.addonId.startsWith("plugin:") ||
                    stream.addonId.startsWith("plugin-repo:"))
        }
        .distinctBy { it.deadLinkStableId() }
        .filterNot { it.deadLinkStableId() in attemptedIds }
        .sortedBy(::deadLinkSourcePriority)
        .firstOrNull()
}

internal fun deadLinkSourcePriority(stream: StreamItem): Int = when {
    stream.isInstalledAddonStream -> 0
    stream.addonId.startsWith("plugin:") -> 1
    else -> 2
}

internal fun StreamItem.deadLinkStableId(): String {
    val address = playableDirectUrl
        ?: p2pInfoHash
        ?: youTubeIdToResolve
        ?: externalOpenUrl
        ?: clientResolve?.let { "${it.service}:${it.infoHash}:${it.fileIdx}:${it.filename}" }
        ?: serverTarget?.let { "${it.item.encode()}:${it.mediaSourceId}" }
        ?: streamLabel
    return "${addonId.trim().lowercase()}|${address.trim()}"
}
