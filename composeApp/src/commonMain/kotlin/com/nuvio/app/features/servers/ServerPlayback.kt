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
        return restart(url, failed, audioStreamIndex = null, subtitleStreamIndex = null)
    }

    suspend fun switchAudio(url: String, audioStreamIndex: Int): ServerPlaybackSession? {
        val current = synchronized(lock) { active[url] } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, audioStreamIndex, current.playback.burnInSubtitles.selectedIndex())
    }

    suspend fun switchSubtitle(url: String, subtitleStreamIndex: Int?): ServerPlaybackSession? {
        val current = synchronized(lock) { active[url] } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, current.playback.audioTracks.selectedIndex(), subtitleStreamIndex)
    }

    fun audioTracks(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY || playback.audioTracks.size < 2) return emptyList()
        return playback.audioTracks
    }

    fun burnInSubtitles(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return emptyList()
        return playback.burnInSubtitles
    }

    fun session(url: String?): ServerPlaybackSession? = url?.let { synchronized(lock) { active[it] } }?.playback

    suspend fun newerResumePositionMs(url: String?, savedAtEpochMs: Long?): Long? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        val state = try {
            withTimeoutOrNull(RESUME_TIMEOUT_MS) {
                current.provider.details(current.session, current.playback.target.item.itemId).userStates.firstOrNull()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Reading the resume point for ${current.label} failed: ${error.serverFailure()}" }
            null
        } ?: return null
        val lastPlayed = state.lastPlayedEpochMs
            ?.takeIf { !state.played && state.positionMs > 0L && state.durationMs > 0L }
            ?: return null
        if (savedAtEpochMs != null && savedAtEpochMs >= lastPlayed) return null
        log.i { "Resuming ${current.label} from the server at ${state.positionMs}ms" }
        return state.positionMs
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

    private suspend fun restart(
        url: String,
        current: ActivePlayback,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
    ): ServerPlaybackSession? {
        val request = ServerPlaybackRequest(
            target = current.playback.target,
            capabilities = capabilities().copy(allowDirectPlay = false),
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
        )
        val playback = runCatching { current.provider.preparePlayback(current.session, request) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { log.w { "Playback restart for ${current.label} failed: ${it.serverFailure()} (${it.message})" } }
            .getOrNull()
            ?: return null
        stop(url)
        synchronized(lock) { active[playback.url] = ActivePlayback(current.provider, current.session, playback) }
        return playback
    }

    private fun List<ServerTrack>.selectedIndex(): Int? = firstOrNull { it.selected }?.index

    private fun capabilities() = ServerPlayerCapabilities(
        directPlayAll = isIos ||
            PlayerSettingsRepository.uiState.value.androidPlaybackEngine == AndroidPlaybackEngine.Libmpv,
    )

    private class ActivePlayback(
        val provider: ServerProvider,
        val session: ServerSession,
        val playback: ServerPlaybackSession,
    ) {
        val label = "${provider.id} item ${playback.target.item.itemId}"
        private val events = Channel<ServerPlaybackEvent>(Channel.UNLIMITED)
        var started = false
            private set
        var lastPositionMs = 0L
        private var paused = false
        private var lastReportPositionMs = 0L
        private var lastReportAtMs = 0L

        init {
            log.i { "Prepared $label as ${playback.playMethod} ${playback.transcodeReasons}" }
            scope.launch {
                for (event in events) {
                    try {
                        val sent = withTimeoutOrNull(REPORT_TIMEOUT_MS) { provider.report(session, playback, event) }
                        when {
                            sent == null -> log.w { "Playback report ${event.type} for $label timed out" }
                            event.type != ServerPlaybackEventType.PROGRESS -> {
                                log.i { "Playback report ${event.type} for $label sent at ${event.positionMs}ms" }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.w { "Playback report ${event.type} for $label failed: ${error.serverFailure()} (${error.message})" }
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
    private const val RESUME_TIMEOUT_MS = 2_000L
}
