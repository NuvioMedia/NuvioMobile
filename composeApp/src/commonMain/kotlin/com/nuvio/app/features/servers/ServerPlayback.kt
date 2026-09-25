package com.nuvio.app.features.servers

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.isIos
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Clock

internal object ServerPlayback {
    private val log = Logger.withTag("ServerPlayback")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val active = mutableMapOf<String, ActivePlayback>()

    suspend fun prepare(stream: StreamItem): StreamItem {
        val target = stream.serverTarget ?: return stream
        val (provider, playback, session) = ServerRepository.call(target.item.connectionId) { provider, session ->
            Triple(provider, provider.preparePlayback(session, ServerPlaybackRequest(target, capabilities())), session)
        }
        val orphans = synchronized(lock) {
            val unstarted = active.filterValues { !it.started }.keys.toList()
            active[playback.url] = ActivePlayback(provider, session, playback)
            unstarted.mapNotNull(active::remove)
        }
        orphans.forEach { it.stop() }
        return stream.copy(
            url = playback.url,
            externalSubtitles = playback.subtitles + stream.externalSubtitles,
            behaviorHints = stream.behaviorHints.copy(
                proxyHeaders = playback.headers.takeIf { it.isNotEmpty() }?.let { StreamProxyHeaders(request = it) },
            ),
        )
    }

    suspend fun fallback(url: String): ServerPlaybackSession? {
        val failed = synchronized(lock) { active[url] } ?: return null
        if (failed.playback.playMethod != ServerPlayMethod.DIRECT_PLAY) return null
        val request = ServerPlaybackRequest(failed.playback.target, capabilities().copy(allowDirectPlay = false))
        val playback = runCatching { failed.provider.preparePlayback(failed.session, request) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?: return null
        synchronized(lock) { active[playback.url] = ActivePlayback(failed.provider, failed.session, playback) }
        stop(url)
        return playback
    }

    fun isServerSource(url: String?): Boolean = url != null && synchronized(lock) { url in active }

    fun onPlaybackSnapshot(
        url: String?,
        positionMs: Long,
        isPlaying: Boolean,
        isLoading: Boolean,
        isEnded: Boolean,
    ) {
        val playback = url?.let { synchronized(lock) { active[it] } } ?: return
        if (isEnded) {
            playback.lastPositionMs = positionMs
            stop(url)
            return
        }
        playback.onSnapshot(positionMs, isPlaying, isLoading)
    }

    fun stop(url: String?) {
        val playback = url?.let { synchronized(lock) { active.remove(it) } } ?: return
        playback.stop()
    }

    private fun capabilities() = ServerPlayerCapabilities(
        directPlayAll = isIos ||
            PlayerSettingsRepository.uiState.value.androidPlaybackEngine == AndroidPlaybackEngine.Libmpv,
    )

    private class ActivePlayback(
        val provider: ServerProvider,
        val session: ServerSession,
        val playback: ServerPlaybackSession,
    ) {
        private val events = Channel<ServerPlaybackEvent>(Channel.UNLIMITED)
        var started = false
            private set
        var lastPositionMs = 0L
        private var paused = false
        private var lastReportPositionMs = 0L
        private var lastReportAtMs = 0L

        init {
            scope.launch {
                for (event in events) {
                    try {
                        withTimeoutOrNull(REPORT_TIMEOUT_MS) { provider.report(session, playback, event) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.w { "Playback report ${event.type} failed: ${error.serverFailure()}" }
                    }
                }
            }
        }

        fun onSnapshot(positionMs: Long, isPlaying: Boolean, isLoading: Boolean) = synchronized(lock) {
            lastPositionMs = positionMs
            if (!started) {
                if (isPlaying && !isLoading) {
                    started = true
                    send(ServerPlaybackEventType.START, positionMs, isPaused = false)
                }
                return@synchronized
            }
            if (isLoading) return@synchronized
            val now = nowMs()
            val elapsed = now - lastReportAtMs
            val expected = lastReportPositionMs + if (paused) 0L else elapsed
            when {
                paused == isPlaying -> {
                    paused = !isPlaying
                    send(if (paused) ServerPlaybackEventType.PAUSE else ServerPlaybackEventType.RESUME, positionMs, paused)
                }
                abs(positionMs - expected) > SEEK_THRESHOLD_MS ||
                    (isPlaying && elapsed >= PROGRESS_INTERVAL_MS) -> {
                    send(ServerPlaybackEventType.PROGRESS, positionMs, paused)
                }
            }
        }

        fun stop() = synchronized(lock) {
            send(ServerPlaybackEventType.STOP, lastPositionMs, isPaused = true)
            events.close()
        }

        private fun send(type: ServerPlaybackEventType, positionMs: Long, isPaused: Boolean) {
            lastReportAtMs = nowMs()
            lastReportPositionMs = positionMs
            events.trySend(ServerPlaybackEvent(type, positionMs, isPaused))
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private const val PROGRESS_INTERVAL_MS = 10_000L
    private const val SEEK_THRESHOLD_MS = 5_000L
    private const val REPORT_TIMEOUT_MS = 10_000L
}
