package com.nuvio.app.features.p2p

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.i18n.localizedP2pUnknownTorrentError
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "P2pStreamingEngine"
private const val DIAGNOSTIC_TAG = "NuvioP2PDiag"
private const val DIAGNOSTIC_SAMPLE_INTERVAL_MS = 1_000L

internal fun buildNuvioEngineConfig(
    stateDirectory: File,
    cacheDirectory: File,
    uploadEnabled: Boolean,
    torrentProfile: P2pTorrentProfile,
    diskCacheCapacityBytes: Long,
): NuvioEngineConfig = NuvioEngineConfig(
    dataDirectory = stateDirectory,
    cacheDirectory = cacheDirectory,
    diskCacheCapacityBytes = diskCacheCapacityBytes,
    torrentProfile = when (torrentProfile) {
        P2pTorrentProfile.SOFT -> NuvioTorrentProfile.Soft
        P2pTorrentProfile.BALANCED -> NuvioTorrentProfile.Balanced
        P2pTorrentProfile.FAST -> NuvioTorrentProfile.Fast
    },
    uploadMode = if (uploadEnabled) {
        NuvioUploadMode.Unlimited
    } else {
        NuvioUploadMode.Disabled
    },
    streamInactivityTimeoutMilliseconds = 0,
)

internal fun migrateNestedPayloadDirectory(cacheDirectory: File) {
    val payloadDirectory = File(cacheDirectory, "payload")
    val nestedDirectory = File(payloadDirectory, "payload")
    val entries = nestedDirectory.listFiles() ?: return
    entries.forEach { entry ->
        val target = File(payloadDirectory, entry.name)
        if (target.exists() || !entry.renameTo(target)) {
            entry.deleteRecursively()
        }
    }
    nestedDirectory.deleteRecursively()
}

internal fun unexpectedStreamStopError(
    requestId: Long,
    eventStreamId: String?,
    currentStreamId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L || currentStreamId == null || eventStreamId != currentStreamId) {
        return null
    }
    return P2pStreamingState.Error(
        message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage,
    )
}

internal fun unexpectedTorrentError(
    requestId: Long,
    eventTorrentId: String?,
    currentTorrentId: String?,
    message: String?,
    fallbackMessage: String,
): P2pStreamingState.Error? {
    if (requestId != 0L ||
        eventTorrentId == null ||
        currentTorrentId == null ||
        eventTorrentId != currentTorrentId
    ) {
        return null
    }
    return P2pStreamingState.Error(
        message?.trim()?.takeIf(String::isNotEmpty) ?: fallbackMessage,
    )
}

actual object P2pStreamingEngine {
    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
    )

    private data class DetachedStream(
        val engine: NuvioEngine?,
        val streamId: String?,
    )

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val startMutex = Mutex()
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineEventsJob: Job? = null
    private var streamGeneration = 0L
    @Volatile
    private var currentTorrentId: String? = null
    @Volatile
    private var currentStreamId: String? = null
    private var appContext: Context? = null
    @Volatile
    private var engine: NuvioEngine? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private val knownTorrentIds = mutableSetOf<String>()
    private var diagnosticRequestSequence = 0L

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun startStream(request: P2pStreamRequest): String = withContext(Dispatchers.IO) {
        startMutex.withLock { startStreamLocked(request) }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(_state.value !is P2pStreamingState.Streaming &&
                _state.value !is P2pStreamingState.Connecting) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
            try {
                val activeEngine = ensureEngine()
                val before = activeEngine.stats.value
                activeEngine.reclaimDiskCache(0L)
                delay(DIAGNOSTIC_SAMPLE_INTERVAL_MS + 100L)
                val after = activeEngine.stats.value
                updateCacheState(after.diskCacheUsedBytes, after.diskCacheProtectedBytes)
                P2pCacheClearResult(
                    reclaimedBytes = (after.diskCacheReclaimedBytes - before.diskCacheReclaimedBytes)
                        .coerceAtLeast(0L),
                    remainingBytes = after.diskCacheUsedBytes,
                    protectedBytes = after.diskCacheProtectedBytes,
                )
            } finally {
                _cacheState.value = _cacheState.value.copy(isClearing = false)
            }
        }
    }

    private suspend fun startStreamLocked(request: P2pStreamRequest): String {
        val requestSequence = nextDiagnosticRequestSequence()
        val startedAtMs = SystemClock.elapsedRealtime()
        val phase = AtomicReference("stop_previous")
        Log.i(
            DIAGNOSTIC_TAG,
            "start request=$requestSequence phase=accepted hash=${diagnosticId(request.infoHash)} " +
                "fileIndex=${request.fileIdx ?: -1} filenameHint=${!request.filename.isNullOrBlank()} " +
                "requestTrackers=${request.trackers.size}",
        )
        stopStreamNow(shutdownEngine = false)
        logPhase(requestSequence, startedAtMs, phase.get())
        val generation = beginStreamGeneration()

        var activeEngine: NuvioEngine? = null
        var payloadDownloadBaseline = 0L
        var preparedStream: NuvioStream? = null
        var attached = false
        var startupStatsJob: Job? = null
        return try {
            phase.set("build_magnet")
            val magnetUri = buildP2pMagnetUri(
                request.infoHash,
                (DEFAULT_TRACKERS + request.trackers).distinct(),
            )
            logPhase(requestSequence, startedAtMs, phase.get())

            phase.set("ensure_engine")
            val resolvedEngine = ensureEngine()
            activeEngine = resolvedEngine
            payloadDownloadBaseline = resolvedEngine.stats.value.totalPayloadDownloadBytes
            ensureCurrentGeneration(generation)
            logPhase(requestSequence, startedAtMs, phase.get())
            startupStatsJob = startStartupStatsPolling(
                activeEngine = resolvedEngine,
                generation = generation,
                phase = phase,
                requestSequence = requestSequence,
                startedAtMs = startedAtMs,
            )

            phase.set("add_magnet")
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            val reusedTorrent = canonicalHash in knownTorrentIds
            Log.i(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} begin elapsedMs=${elapsedSince(startedAtMs)} " +
                    "cached=$reusedTorrent hash=${diagnosticId(canonicalHash)}",
            )
            val torrentId = if (reusedTorrent) {
                canonicalHash
            } else {
                resolvedEngine.addMagnet(magnetUri).also { knownTorrentIds += it }
            }
            ensureCurrentGeneration(generation)
            Log.i(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} complete elapsedMs=${elapsedSince(startedAtMs)} " +
                    "torrent=${diagnosticId(torrentId)} cached=$reusedTorrent",
            )

            phase.set("prepare_stream")
            Log.i(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} begin elapsedMs=${elapsedSince(startedAtMs)} " +
                    "fileIndex=${request.fileIdx ?: -1}",
            )
            val stream = resolvedEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = request.fileIdx,
                filenameHint = request.filename,
            )
            preparedStream = stream
            Log.i(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} complete elapsedMs=${elapsedSince(startedAtMs)} " +
                    "stream=${diagnosticId(stream.id)} selectedFile=${stream.fileIndex} fileBytes=${stream.fileSize} " +
                    "url=${diagnosticLoopbackUrl(stream.url)}",
            )
            currentCoroutineContext().ensureActive()
            phase.set("attach_route")
            if (!attachStreamIfCurrent(generation, torrentId, stream.id)) {
                withContext(NonCancellable) {
                    stopPreparedStream(resolvedEngine, stream.id)
                }
                preparedStream = null
                throw CancellationException("P2P stream start was cancelled")
            }
            attached = true
            logPhase(requestSequence, startedAtMs, phase.get())

            startStatsPolling(
                activeEngine = resolvedEngine,
                stream = stream,
                generation = generation,
                requestSequence = requestSequence,
                startedAtMs = startedAtMs,
                payloadDownloadBaseline = payloadDownloadBaseline,
            )
            val initialAggregate = resolvedEngine.stats.value
            if (!publishStreamingIfCurrent(
                    generation = generation,
                    state = P2pStreamingState.Streaming(
                        localUrl = stream.url,
                        downloadSpeed = initialAggregate.downloadRateBytesPerSecond,
                        uploadSpeed = initialAggregate.uploadRateBytesPerSecond,
                        peers = initialAggregate.connectedPeers,
                        seeds = initialAggregate.connectedSeeds,
                        bufferProgress = 0f,
                        totalProgress = 0f,
                        downloadedBytes = (initialAggregate.totalPayloadDownloadBytes -
                            payloadDownloadBaseline).coerceAtLeast(0L),
                    ),
                )) {
                throw CancellationException("P2P stream start was cancelled")
            }
            Log.i(TAG, "Nuvio Engine stream ready: ${stream.url}")
            Log.i(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=route_ready elapsedMs=${elapsedSince(startedAtMs)} " +
                    "generation=$generation stream=${diagnosticId(stream.id)}",
            )
            stream.url
        } catch (cancellation: CancellationException) {
            Log.w(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} cancelled elapsedMs=${elapsedSince(startedAtMs)} " +
                    "generation=$generation",
            )
            withContext(NonCancellable) {
                cleanupFailedStart(
                    generation = generation,
                    activeEngine = activeEngine,
                    preparedStream = preparedStream,
                    attached = attached,
                    terminalState = P2pStreamingState.Idle,
                )
            }
            throw cancellation
        } catch (error: Exception) {
            Log.e(
                DIAGNOSTIC_TAG,
                "start request=$requestSequence phase=${phase.get()} failed elapsedMs=${elapsedSince(startedAtMs)} " +
                    "generation=$generation error=${diagnosticMessage(error.message)}",
                error,
            )
            val terminalState = P2pStreamingState.Error(
                error.message ?: localizedP2pUnknownTorrentError()
            )
            withContext(NonCancellable) {
                cleanupFailedStart(
                    generation = generation,
                    activeEngine = activeEngine,
                    preparedStream = preparedStream,
                    attached = attached,
                    terminalState = terminalState,
                )
            }
            throw error
        } finally {
            startupStatsJob?.cancel()
        }
    }

    actual fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    actual fun shutdown() {
        scheduleStop(shutdownEngine = true)
    }

    private fun scheduleStop(shutdownEngine: Boolean) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val detached = detachActiveStream()
        Log.i(
            DIAGNOSTIC_TAG,
            "cleanup scheduled shutdownEngine=$shutdownEngine stream=${diagnosticId(detached.streamId)}",
        )
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(detached, shutdownEngine)
            Log.i(
                DIAGNOSTIC_TAG,
                "cleanup complete shutdownEngine=$shutdownEngine stream=${diagnosticId(detached.streamId)} " +
                    "elapsedMs=${elapsedSince(startedAtMs)}",
            )
        }
    }

    private suspend fun stopStreamNow(shutdownEngine: Boolean) {
        val startedAtMs = SystemClock.elapsedRealtime()
        cleanupJob?.join()
        val detached = detachActiveStream()
        cleanupDetachedStream(detached, shutdownEngine)
        Log.i(
            DIAGNOSTIC_TAG,
            "cleanup synchronous shutdownEngine=$shutdownEngine stream=${diagnosticId(detached.streamId)} " +
                "elapsedMs=${elapsedSince(startedAtMs)}",
        )
    }

    private fun detachActiveStream(): DetachedStream {
        val detached: Pair<DetachedStream, Job?> = synchronized(lifecycleLock) {
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = P2pStreamingState.Idle
            value to job
        }
        detached.second?.cancel()
        return detached.first
    }

    private fun detachGenerationIfCurrent(
        generation: Long,
        terminalState: P2pStreamingState,
    ): DetachedStream? {
        val detached: Pair<DetachedStream, Job?>? = synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized null
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = terminalState
            value to job
        }
        detached?.second?.cancel()
        return detached?.first
    }

    private suspend fun cleanupDetachedStream(detached: DetachedStream, shutdownEngine: Boolean) {
        detached.streamId?.let { streamId ->
            stopPreparedStream(detached.engine, streamId)
        }
        if (shutdownEngine) {
            closeEngine(detached.engine)
        }
    }

    private suspend fun cleanupFailedStart(
        generation: Long,
        activeEngine: NuvioEngine?,
        preparedStream: NuvioStream?,
        attached: Boolean,
        terminalState: P2pStreamingState,
    ) {
        if (attached) {
            detachGenerationIfCurrent(generation, terminalState)?.let { detached ->
                cleanupDetachedStream(detached, shutdownEngine = false)
            }
        } else {
            preparedStream?.let { stream -> stopPreparedStream(activeEngine, stream.id) }
            detachGenerationIfCurrent(generation, terminalState)
        }
    }

    private suspend fun stopPreparedStream(activeEngine: NuvioEngine?, streamId: String) {
        try {
            activeEngine?.stopStream(streamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Error stopping Nuvio Engine stream route", error)
        }
    }

    private suspend fun ensureEngine(): NuvioEngine {
        val startedAtMs = SystemClock.elapsedRealtime()
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configurationKey = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        engine?.takeIf { engineConfigurationKey == configurationKey }?.let {
            Log.i(
                DIAGNOSTIC_TAG,
                "engine reuse configuration=$configurationKey elapsedMs=${elapsedSince(startedAtMs)}",
            )
            return it
        }

        Log.i(
            DIAGNOSTIC_TAG,
            "engine create begin configuration=$configurationKey replacing=${engine != null}",
        )
        closeEngine(engine)
        currentCoroutineContext().ensureActive()
        val context = requireContext()
        val stateDirectory = File(context.noBackupFilesDir, "nuvio-engine/state")
        val cacheDirectory = File(context.cacheDir, "nuvio-engine")
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }
        migrateNestedPayloadDirectory(cacheDirectory)
        return NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = configurationKey.uploadEnabled,
                torrentProfile = configurationKey.torrentProfile,
                diskCacheCapacityBytes = configurationKey.diskCacheCapacityBytes,
            )
        ).also { created ->
            engine = created
            engineConfigurationKey = configurationKey
            observeEngineEvents(created)
            Log.i(
                TAG,
                "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion})"
            )
            Log.i(
                DIAGNOSTIC_TAG,
                "engine create complete configuration=$configurationKey elapsedMs=${elapsedSince(startedAtMs)} " +
                    "version=${NuvioEngine.version} backend=${NuvioEngine.protocolBackendVersion}",
            )
        }
    }

    private suspend fun closeEngine(target: NuvioEngine?) {
        if (target == null) return
        val startedAtMs = SystemClock.elapsedRealtime()
        Log.i(DIAGNOSTIC_TAG, "engine shutdown begin active=${engine === target}")
        if (engine === target) {
            engineEventsJob?.cancel()
            engineEventsJob = null
            engine = null
            engineConfigurationKey = null
            knownTorrentIds.clear()
        }
        withContext(NonCancellable) {
            try {
                target.shutdown()
            } catch (error: Exception) {
                Log.w(TAG, "Error shutting down Nuvio Engine", error)
            }
        }
        Log.i(
            DIAGNOSTIC_TAG,
            "engine shutdown complete elapsedMs=${elapsedSince(startedAtMs)}",
        )
    }

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            launch {
                activeEngine.stats.collect { stats ->
                    if (engine === activeEngine) {
                        updateCacheState(stats.diskCacheUsedBytes, stats.diskCacheProtectedBytes)
                    }
                }
            }
            activeEngine.events.collect { event ->
                Log.i(
                    DIAGNOSTIC_TAG,
                    "event type=${event.type} sequence=${event.sequence} requestId=${event.requestId} " +
                        "dropped=${event.droppedEvents} torrent=${diagnosticId(event.torrentId)} " +
                        "stream=${diagnosticId(event.streamId)} fileIndex=${event.fileIndex ?: -1} " +
                        "fileBytes=${event.fileSize} message=${diagnosticMessage(event.message)}",
                )
                if (engine !== activeEngine) return@collect
                when (event.type) {
                    NuvioEventType.TorrentError -> {
                        val fallbackMessage = localizedP2pUnknownTorrentError()
                        synchronized(lifecycleLock) {
                            if (engine !== activeEngine) return@synchronized
                            val terminalError = unexpectedTorrentError(
                                requestId = event.requestId,
                                eventTorrentId = event.torrentId,
                                currentTorrentId = currentTorrentId,
                                message = event.message,
                                fallbackMessage = fallbackMessage,
                            ) ?: return@synchronized
                            streamGeneration += 1
                            statsJob?.cancel()
                            statsJob = null
                            _state.value = terminalError
                        }
                    }
                    NuvioEventType.StreamStopped -> {
                        if (event.requestId != 0L) return@collect
                        val fallbackMessage = localizedP2pUnknownTorrentError()
                        synchronized(lifecycleLock) {
                            if (engine !== activeEngine) return@synchronized
                            val error = unexpectedStreamStopError(
                                requestId = event.requestId,
                                eventStreamId = event.streamId,
                                currentStreamId = currentStreamId,
                                message = event.message,
                                fallbackMessage = fallbackMessage,
                            ) ?: return@synchronized
                            streamGeneration += 1
                            currentTorrentId = null
                            currentStreamId = null
                            statsJob?.cancel()
                            statsJob = null
                            _state.value = error
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startStatsPolling(
        activeEngine: NuvioEngine,
        stream: NuvioStream,
        generation: Long,
        requestSequence: Long,
        startedAtMs: Long,
        payloadDownloadBaseline: Long,
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var nextDiagnosticSampleAtMs = 0L
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                val currentState = _state.value
                if (currentState is P2pStreamingState.Streaming) {
                    val route = try {
                        activeEngine.currentStreamStats(stream.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.w(TAG, "Error sampling Nuvio Engine stream progress", error)
                        null
                    }
                    val aggregate = activeEngine.stats.value
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs >= nextDiagnosticSampleAtMs) {
                        nextDiagnosticSampleAtMs = nowMs + DIAGNOSTIC_SAMPLE_INTERVAL_MS
                        Log.i(
                            DIAGNOSTIC_TAG,
                            "sample request=$requestSequence elapsedMs=${elapsedSince(startedAtMs)} " +
                                "generation=$generation stream=${diagnosticId(stream.id)} " +
                                "http=${aggregate.activeHttpRequests} pendingReads=${aggregate.pendingPieceReads} " +
                                "peers=${aggregate.connectedPeers} seeds=${aggregate.connectedSeeds} " +
                                "known=${aggregate.knownPeers} candidates=${aggregate.connectCandidates} " +
                                "interested=${aggregate.interestedPeers} unchoked=${aggregate.unchokedPeers} " +
                                "downloading=${aggregate.downloadingPeers} snubbed=${aggregate.snubbedPeers} " +
                                "connecting=${aggregate.connectingPeers} handshaking=${aggregate.handshakingPeers} " +
                                "targetPeers=${aggregate.targetPiecePeers} " +
                                "targetUnchoked=${aggregate.targetPieceUnchokedPeers} " +
                                "targetDownloading=${aggregate.targetPieceDownloadingPeers} " +
                                "offTargetDownloading=${aggregate.offTargetDownloadingPeers} " +
                                "pendingBlocks=${aggregate.pendingBlockRequests} " +
                                "targetBlocks=${aggregate.targetBlockRequests} " +
                                "timedOutBlocks=${aggregate.timedOutBlockRequests} " +
                                "downBps=${aggregate.downloadRateBytesPerSecond} upBps=${aggregate.uploadRateBytesPerSecond} " +
                                "payloadDown=${aggregate.totalPayloadDownloadBytes} " +
                                "trackerReplies=${aggregate.trackerReplyEvents} " +
                                "trackerErrors=${aggregate.trackerErrorEvents} " +
                                "trackerPeers=${aggregate.trackerPeersReturned} " +
                                "dhtReplies=${aggregate.dhtReplyEvents} dhtPeers=${aggregate.dhtPeersReturned} " +
                                "peerConnects=${aggregate.peerConnectEvents} " +
                                "peerDisconnects=${aggregate.peerDisconnectEvents} " +
                                "disconnectTimeouts=${aggregate.peerDisconnectTimeouts} " +
                                "disconnectConnectFailures=${aggregate.peerDisconnectConnectFailures} " +
                                "disconnectRedundant=${aggregate.peerDisconnectRedundant} " +
                                "disconnectTurnover=${aggregate.peerDisconnectTurnover} " +
                                "disconnectOther=${aggregate.peerDisconnectOther} " +
                                "finishedTransitions=${aggregate.torrentFinishedEvents} " +
                                "contiguous=${route?.contiguousReadyBytes ?: -1L} " +
                                "verified=${route?.verifiedFileBytes ?: -1L} delivered=${route?.deliveredBytes ?: -1L} " +
                                "demands=${route?.activeDemands ?: -1} scheduledPieces=${route?.scheduledPieces ?: -1} " +
                                "blockingPieces=${route?.blockingPieces ?: -1} " +
                                "blockingPrimary=${route?.primaryBlockingPiece ?: -1} " +
                                "blockingSecondary=${route?.secondaryBlockingPiece ?: -1} " +
                                "demandPrimary=${route?.primaryDemandStart ?: -1L}-${route?.primaryDemandEnd ?: -1L} " +
                                "demandSecondary=${route?.secondaryDemandStart ?: -1L}-${route?.secondaryDemandEnd ?: -1L} " +
                                "lastReadyPiece=${route?.lastReadyPiece ?: -1} " +
                                "scheduleRevision=${route?.scheduleRevision ?: -1L} " +
                                "fileBytes=${route?.fileSize ?: stream.fileSize} activeTorrents=${aggregate.activeTorrents} " +
                                "activeStreams=${aggregate.activeStreams} warm=${aggregate.warmTorrents} " +
                                "quiesced=${aggregate.quiescedTorrents} memUsed=${aggregate.memoryCacheUsedBytes} " +
                                "memHits=${aggregate.memoryCacheHits} memMisses=${aggregate.memoryCacheMisses} " +
                                "diskUsed=${aggregate.diskCacheUsedBytes} diskProtected=${aggregate.diskCacheProtectedBytes} " +
                                "diskOverBudget=${aggregate.diskCacheOverBudget}",
                        )
                    }
                    updateStreamingIfCurrent(generation) { latestState ->
                        latestState.copy(
                            downloadSpeed = aggregate.downloadRateBytesPerSecond,
                            uploadSpeed = aggregate.uploadRateBytesPerSecond,
                            peers = aggregate.connectedPeers,
                            seeds = aggregate.connectedSeeds,
                            bufferProgress = route?.bufferProgress ?: latestState.bufferProgress,
                            totalProgress = route?.fileProgress ?: latestState.totalProgress,
                            downloadedBytes = (aggregate.totalPayloadDownloadBytes -
                                payloadDownloadBaseline).coerceAtLeast(0L),
                            verifiedBytes = route?.verifiedFileBytes ?: latestState.verifiedBytes,
                            deliveredBytes = route?.deliveredBytes ?: latestState.deliveredBytes,
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun updateCacheState(usedBytes: Long, protectedBytes: Long) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = usedBytes,
            protectedBytes = protectedBytes,
            hasMeasurement = true,
        )
    }

    private fun startStartupStatsPolling(
        activeEngine: NuvioEngine,
        generation: Long,
        phase: AtomicReference<String>,
        requestSequence: Long,
        startedAtMs: Long,
    ): Job = scope.launch {
        while (isActive) {
            val aggregate = activeEngine.stats.value
            updateConnectingIfCurrent(
                generation = generation,
                phase = phase.get(),
                downloadSpeed = aggregate.downloadRateBytesPerSecond,
                uploadSpeed = aggregate.uploadRateBytesPerSecond,
                peers = aggregate.connectedPeers,
                seeds = aggregate.connectedSeeds,
            )
            Log.i(
                DIAGNOSTIC_TAG,
                "startupSample request=$requestSequence phase=${phase.get()} elapsedMs=${elapsedSince(startedAtMs)} " +
                    "http=${aggregate.activeHttpRequests} pendingReads=${aggregate.pendingPieceReads} " +
                    "peers=${aggregate.connectedPeers} seeds=${aggregate.connectedSeeds} " +
                    "known=${aggregate.knownPeers} candidates=${aggregate.connectCandidates} " +
                    "interested=${aggregate.interestedPeers} unchoked=${aggregate.unchokedPeers} " +
                    "downloading=${aggregate.downloadingPeers} snubbed=${aggregate.snubbedPeers} " +
                    "connecting=${aggregate.connectingPeers} handshaking=${aggregate.handshakingPeers} " +
                    "pendingBlocks=${aggregate.pendingBlockRequests} " +
                    "targetBlocks=${aggregate.targetBlockRequests} " +
                    "timedOutBlocks=${aggregate.timedOutBlockRequests} " +
                    "downBps=${aggregate.downloadRateBytesPerSecond} upBps=${aggregate.uploadRateBytesPerSecond} " +
                    "payloadDown=${aggregate.totalPayloadDownloadBytes} activeTorrents=${aggregate.activeTorrents} " +
                    "trackerReplies=${aggregate.trackerReplyEvents} trackerErrors=${aggregate.trackerErrorEvents} " +
                    "trackerPeers=${aggregate.trackerPeersReturned} dhtReplies=${aggregate.dhtReplyEvents} " +
                    "dhtPeers=${aggregate.dhtPeersReturned} peerConnects=${aggregate.peerConnectEvents} " +
                    "peerDisconnects=${aggregate.peerDisconnectEvents} " +
                    "activeStreams=${aggregate.activeStreams} warm=${aggregate.warmTorrents} " +
                    "quiesced=${aggregate.quiescedTorrents}",
            )
            delay(DIAGNOSTIC_SAMPLE_INTERVAL_MS)
        }
    }

    private fun beginStreamGeneration(): Long = synchronized(lifecycleLock) {
        streamGeneration += 1
        _state.value = P2pStreamingState.Connecting()
        streamGeneration
    }

    private fun updateConnectingIfCurrent(
        generation: Long,
        phase: String,
        downloadSpeed: Long,
        uploadSpeed: Long,
        peers: Int,
        seeds: Int,
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation || _state.value !is P2pStreamingState.Connecting) {
            return@synchronized
        }
        _state.value = P2pStreamingState.Connecting(
            phase = phase,
            downloadSpeed = downloadSpeed,
            uploadSpeed = uploadSpeed,
            peers = peers,
            seeds = seeds,
        )
    }

    private fun nextDiagnosticRequestSequence(): Long = synchronized(lifecycleLock) {
        diagnosticRequestSequence += 1
        diagnosticRequestSequence
    }

    private fun attachStreamIfCurrent(
        generation: Long,
        torrentId: String,
        streamId: String,
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized false
        currentTorrentId = torrentId
        currentStreamId = streamId
        true
    }

    private fun publishStreamingIfCurrent(
        generation: Long,
        state: P2pStreamingState.Streaming,
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation || currentStreamId == null) {
            return@synchronized false
        }
        _state.value = state
        true
    }

    private fun updateStreamingIfCurrent(
        generation: Long,
        update: (P2pStreamingState.Streaming) -> P2pStreamingState.Streaming,
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized
        val current = _state.value as? P2pStreamingState.Streaming ?: return@synchronized
        _state.value = update(current)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("P2P stream start was cancelled")
        }
    }

    private fun requireContext(): Context =
        appContext ?: throw P2pStreamingException("P2P streaming engine is not initialized")

    private fun logPhase(requestSequence: Long, startedAtMs: Long, phase: String) {
        Log.i(
            DIAGNOSTIC_TAG,
            "start request=$requestSequence phase=$phase complete elapsedMs=${elapsedSince(startedAtMs)}",
        )
    }

    private fun elapsedSince(startedAtMs: Long): Long =
        (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)

    private fun diagnosticId(value: String?): String =
        value?.trim()?.take(12)?.ifBlank { "none" } ?: "none"

    private fun diagnosticLoopbackUrl(value: String): String = runCatching {
        val uri = android.net.Uri.parse(value)
        "${uri.scheme}://${uri.host}:${uri.port}/…"
    }.getOrDefault("unparseable")

    private fun diagnosticMessage(value: String?): String =
        value?.replace('\n', ' ')?.replace('\r', ' ')?.take(160) ?: "none"

    private val DEFAULT_TRACKERS = listOf(
        "udp://zer0day.ch:1337/announce",
        "udp://tracker.publictracker.xyz:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "http://tracker.renfei.net:8080/announce",
        "udp://udp.tracker.projectk.org:23333/announce",
        "udp://tracker.tryhackx.org:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.startwork.cv:1337/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.plx.im:6969/announce",
        "udp://tracker.nyaa.vc:6969/announce",
        "udp://tracker.iperson.xyz:6969/announce",
        "udp://tracker.gmi.gd:6969/announce",
        "udp://tracker.fnix.net:6969/announce",
        "udp://tracker.flatuslifir.is:6969/announce",
        "udp://tracker.ducks.party:1984/announce",
        "udp://tracker.bluefrog.pw:2710/announce",
    )
}
