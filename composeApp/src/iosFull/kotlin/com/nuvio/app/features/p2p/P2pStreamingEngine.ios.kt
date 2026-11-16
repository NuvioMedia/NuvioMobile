@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.p2p

import cnames.structs.nuvio_engine
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_DISK_CACHE_RECLAIMED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_STREAM_PREPARED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_STREAM_STOPPED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_TORRENT_ERROR
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_EVENT_TORRENT_METADATA_READY
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_STATUS_NO_EVENT
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_STATUS_OK
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_BALANCED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_FAST
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_TORRENT_PROFILE_SOFT
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_UPLOAD_DISABLED
import com.nuvio.app.features.p2p.native.NUVIO_ENGINE_UPLOAD_UNLIMITED
import com.nuvio.app.features.p2p.native.nuvio_engine_add_torrent
import com.nuvio.app.features.p2p.native.nuvio_engine_config
import com.nuvio.app.features.p2p.native.nuvio_engine_config_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_create
import com.nuvio.app.features.p2p.native.nuvio_engine_destroy
import com.nuvio.app.features.p2p.native.nuvio_engine_event
import com.nuvio.app.features.p2p.native.nuvio_engine_event_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_get_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_get_stream_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_poll_event
import com.nuvio.app.features.p2p.native.nuvio_engine_prepare_stream
import com.nuvio.app.features.p2p.native.nuvio_engine_reclaim_disk_cache
import com.nuvio.app.features.p2p.native.nuvio_engine_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_stats_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_status_message
import com.nuvio.app.features.p2p.native.nuvio_engine_stop_stream
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_request
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_request_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_stats
import com.nuvio.app.features.p2p.native.nuvio_engine_stream_stats_init_sized
import com.nuvio.app.features.p2p.native.nuvio_engine_torrent_request
import com.nuvio.app.features.p2p.native.nuvio_engine_torrent_request_init_sized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLog

private const val StatsPollIntervalMs = 250L
private const val StartupStatsPollIntervalMs = 1_000L
private const val CacheStatsPollIntervalMs = 1_000L
private const val MemoryCacheCapacityBytes = 64L * 1024L * 1024L

actual object P2pStreamingEngine {
    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
        val diskCacheCapacityBytes: Long,
    )

    private data class NativeEvent(
        val type: UInt,
        val requestId: ULong,
        val torrentId: String?,
        val message: String?,
        val fileIndex: UInt?,
        val fileSize: ULong,
        val streamId: String?,
        val streamUrl: String?,
    )

    private data class NativeStream(
        val id: String,
        val url: String,
        val torrentId: String,
        val fileIndex: Int,
        val fileSize: Long,
    )

    private data class AggregateStats(
        val peers: Int,
        val seeds: Int,
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val payloadDownloaded: Long,
        val diskUsed: Long,
        val diskProtected: Long,
        val diskReclaimed: Long,
    )

    private data class StreamStats(
        val fileSize: Long,
        val contiguousReady: Long,
        val verified: Long,
        val delivered: Long,
    )

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private var engine: CPointer<nuvio_engine>? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private var currentTorrentId: String? = null
    private var currentStream: NativeStream? = null
    private var statsJob: Job? = null
    private var cacheStatsJob: Job? = null
    private var generation = 0L
    private val knownTorrentIds = mutableSetOf<String>()

    actual suspend fun startStream(request: P2pStreamRequest): String = lifecycleMutex.withLock {
        stopLocked(shutdownEngine = false)
        generation += 1
        val streamGeneration = generation
        _state.value = P2pStreamingState.Connecting()
        var phase = "build_magnet"
        var startupStatsJob: Job? = null
        var preparedStream: NativeStream? = null
        try {
            val magnet = buildP2pMagnetUri(
                request.infoHash,
                (DefaultTrackers + request.trackers).distinct(),
            )
            phase = "ensure_engine"
            val activeEngine = ensureEngine()
            val baseline = readAggregateStats(activeEngine).payloadDownloaded
            startupStatsJob = startStartupStatsPolling(activeEngine, streamGeneration) { phase }

            phase = "add_magnet"
            val canonicalHash = canonicalP2pInfoHash(request.infoHash)
            val torrentId = if (canonicalHash in knownTorrentIds) {
                canonicalHash
            } else {
                addMagnet(activeEngine, magnet).also(knownTorrentIds::add)
            }
            currentCoroutineContext().ensureActive()

            phase = "prepare_stream"
            val stream = prepareStream(
                activeEngine,
                torrentId,
                request.fileIdx,
                request.filename,
            )
            preparedStream = stream
            currentCoroutineContext().ensureActive()
            currentTorrentId = torrentId
            currentStream = stream
            phase = "attach_route"

            val aggregate = readAggregateStats(activeEngine)
            publishStreaming(streamGeneration, stream, aggregate, baseline, null)
            startStatsPolling(activeEngine, stream, streamGeneration, baseline)
            log("stream ready fileIndex=${stream.fileIndex} fileBytes=${stream.fileSize}")
            stream.url
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                preparedStream?.let { runCatching { stopNativeStream(engine, it.id) } }
            }
            currentTorrentId = null
            currentStream = null
            _state.value = P2pStreamingState.Idle
            throw cancellation
        } catch (error: Throwable) {
            preparedStream?.let { runCatching { stopNativeStream(engine, it.id) } }
            currentTorrentId = null
            currentStream = null
            val message = error.message ?: "Unable to start torrent stream"
            _state.value = P2pStreamingState.Error(message)
            log("stream failed phase=$phase error=$message")
            throw P2pStreamingException(message)
        } finally {
            startupStatsJob?.cancel()
        }
    }

    actual suspend fun clearCache(): P2pCacheClearResult = lifecycleMutex.withLock {
        check(_state.value !is P2pStreamingState.Connecting &&
            _state.value !is P2pStreamingState.Streaming) {
            "Torrent cache cannot be cleared during active playback"
        }
        _cacheState.value = _cacheState.value.copy(isClearing = true)
        try {
            val activeEngine = ensureEngine()
            val before = readAggregateStats(activeEngine)
            reclaimDiskCache(activeEngine)
            val after = readAggregateStats(activeEngine)
            updateCacheState(after)
            P2pCacheClearResult(
                reclaimedBytes = (after.diskReclaimed - before.diskReclaimed).coerceAtLeast(0L),
                remainingBytes = after.diskUsed,
                protectedBytes = after.diskProtected,
            )
        } finally {
            _cacheState.value = _cacheState.value.copy(isClearing = false)
        }
    }

    actual fun stopStream() {
        scope.launch {
            lifecycleMutex.withLock { stopLocked(shutdownEngine = false) }
        }
    }

    actual fun shutdown() {
        scope.launch {
            lifecycleMutex.withLock { stopLocked(shutdownEngine = true) }
        }
    }

    private suspend fun stopLocked(shutdownEngine: Boolean) {
        generation += 1
        statsJob?.cancelAndJoin()
        statsJob = null
        val stream = currentStream
        currentStream = null
        currentTorrentId = null
        _state.value = P2pStreamingState.Idle
        stream?.let { runCatching { stopNativeStream(engine, it.id) } }
        if (shutdownEngine) closeEngine()
    }

    private fun startStartupStatsPolling(
        activeEngine: CPointer<nuvio_engine>,
        streamGeneration: Long,
        phase: () -> String,
    ): Job = scope.launch {
        while (isActive && generation == streamGeneration) {
            runCatching { readAggregateStats(activeEngine) }.getOrNull()?.let { stats ->
                if (_state.value is P2pStreamingState.Connecting) {
                    _state.value = P2pStreamingState.Connecting(
                        phase = phase(),
                        downloadSpeed = stats.downloadSpeed,
                        uploadSpeed = stats.uploadSpeed,
                        peers = stats.peers,
                        seeds = stats.seeds,
                    )
                }
            }
            delay(StartupStatsPollIntervalMs)
        }
    }

    private fun startStatsPolling(
        activeEngine: CPointer<nuvio_engine>,
        stream: NativeStream,
        streamGeneration: Long,
        payloadBaseline: Long,
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && generation == streamGeneration) {
                try {
                    val aggregate = readAggregateStats(activeEngine)
                    val route = readStreamStats(activeEngine, stream.id)
                    publishStreaming(streamGeneration, stream, aggregate, payloadBaseline, route)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (generation == streamGeneration) {
                        _state.value = P2pStreamingState.Error(
                            error.message ?: "Torrent stream stopped unexpectedly"
                        )
                    }
                    return@launch
                }
                delay(StatsPollIntervalMs)
            }
        }
    }

    private fun publishStreaming(
        streamGeneration: Long,
        stream: NativeStream,
        aggregate: AggregateStats,
        payloadBaseline: Long,
        route: StreamStats?,
    ) {
        if (generation != streamGeneration || currentStream?.id != stream.id) return
        _state.value = P2pStreamingState.Streaming(
            localUrl = stream.url,
            downloadSpeed = aggregate.downloadSpeed,
            uploadSpeed = aggregate.uploadSpeed,
            peers = aggregate.peers,
            seeds = aggregate.seeds,
            bufferProgress = ratio(route?.contiguousReady ?: 0L, route?.fileSize ?: stream.fileSize),
            totalProgress = ratio(route?.verified ?: 0L, route?.fileSize ?: stream.fileSize),
            downloadedBytes = (aggregate.payloadDownloaded - payloadBaseline).coerceAtLeast(0L),
            verifiedBytes = route?.verified ?: 0L,
            deliveredBytes = route?.delivered ?: 0L,
        )
    }

    private suspend fun ensureEngine(): CPointer<nuvio_engine> {
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val configuration = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes,
        )
        engine?.takeIf { engineConfigurationKey == configuration }?.let { return it }
        closeEngine()

        val stateDirectory = "${NSHomeDirectory()}/Library/Application Support/NuvioEngine/state"
        val cacheDirectory = "${NSHomeDirectory()}/Library/Caches/NuvioEngine"
        createDirectory(stateDirectory)
        createDirectory(cacheDirectory)
        migrateNestedPayloadDirectory(cacheDirectory)
        val created = memScoped {
            val config = alloc<nuvio_engine_config>()
            nuvio_engine_config_init_sized(config.ptr, sizeOf<nuvio_engine_config>().toUInt())
            config.data_directory = stateDirectory.cstr.getPointer(this)
            config.cache_directory = cacheDirectory.cstr.getPointer(this)
            config.memory_cache_capacity_bytes = MemoryCacheCapacityBytes.toULong()
            config.disk_cache_capacity_bytes = configuration.diskCacheCapacityBytes.toULong()
            config.upload_mode = if (configuration.uploadEnabled) {
                NUVIO_ENGINE_UPLOAD_UNLIMITED
            } else {
                NUVIO_ENGINE_UPLOAD_DISABLED
            }
            config.upload_limit_bytes_per_second = 0uL
            config.stream_inactivity_timeout_milliseconds = 0u
            config.warm_torrent_timeout_milliseconds = 60_000u
            config.tls_ca_bundle_path = null
            config.torrent_profile = when (configuration.torrentProfile) {
                P2pTorrentProfile.SOFT -> NUVIO_ENGINE_TORRENT_PROFILE_SOFT
                P2pTorrentProfile.BALANCED -> NUVIO_ENGINE_TORRENT_PROFILE_BALANCED
                P2pTorrentProfile.FAST -> NUVIO_ENGINE_TORRENT_PROFILE_FAST
            }
            val output = alloc<CPointerVar<nuvio_engine>>()
            checkStatus(nuvio_engine_create(config.ptr, output.ptr))
            output.value ?: throw P2pStreamingException("Nuvio Engine returned an empty handle")
        }
        engine = created
        engineConfigurationKey = configuration
        startCacheStatsPolling(created)
        log("engine created configuration=$configuration")
        return created
    }

    private suspend fun closeEngine() {
        val activeEngine = engine ?: return
        engine = null
        engineConfigurationKey = null
        knownTorrentIds.clear()
        cacheStatsJob?.cancelAndJoin()
        cacheStatsJob = null
        nuvio_engine_destroy(activeEngine)
    }

    private fun startCacheStatsPolling(activeEngine: CPointer<nuvio_engine>) {
        cacheStatsJob?.cancel()
        cacheStatsJob = scope.launch {
            while (isActive) {
                runCatching { readAggregateStats(activeEngine) }.getOrNull()?.let(::updateCacheState)
                delay(CacheStatsPollIntervalMs)
            }
        }
    }

    private suspend fun addMagnet(activeEngine: CPointer<nuvio_engine>, magnet: String): String {
        val requestId = memScoped {
            val request = alloc<nuvio_engine_torrent_request>()
            nuvio_engine_torrent_request_init_sized(
                request.ptr,
                sizeOf<nuvio_engine_torrent_request>().toUInt(),
            )
            request.magnet_uri = magnet.cstr.getPointer(this)
            request.source_type = 0u
            val output = alloc<ULongVar>()
            checkStatus(nuvio_engine_add_torrent(activeEngine, request.ptr, output.ptr))
            output.value
        }
        return awaitEvent(activeEngine, requestId, NUVIO_ENGINE_EVENT_TORRENT_METADATA_READY)
            .torrentId
            ?: throw P2pStreamingException("Torrent metadata event omitted its identifier")
    }

    private suspend fun prepareStream(
        activeEngine: CPointer<nuvio_engine>,
        torrentId: String,
        fileIndex: Int?,
        filename: String?,
    ): NativeStream {
        val requestId = memScoped {
            val request = alloc<nuvio_engine_stream_request>()
            nuvio_engine_stream_request_init_sized(
                request.ptr,
                sizeOf<nuvio_engine_stream_request>().toUInt(),
            )
            request.torrent_id = torrentId.cstr.getPointer(this)
            request.file_index = fileIndex?.toUInt() ?: UInt.MAX_VALUE
            request.filename_hint = filename?.cstr?.getPointer(this)
            val output = alloc<ULongVar>()
            checkStatus(nuvio_engine_prepare_stream(activeEngine, request.ptr, output.ptr))
            output.value
        }
        val event = awaitEvent(activeEngine, requestId, NUVIO_ENGINE_EVENT_STREAM_PREPARED)
        return NativeStream(
            id = event.streamId
                ?: throw P2pStreamingException("Stream event omitted its identifier"),
            url = event.streamUrl
                ?: throw P2pStreamingException("Stream event omitted its URL"),
            torrentId = event.torrentId ?: torrentId,
            fileIndex = event.fileIndex?.toInt() ?: fileIndex ?: -1,
            fileSize = event.fileSize.toLong(),
        )
    }

    private suspend fun stopNativeStream(activeEngine: CPointer<nuvio_engine>?, streamId: String) {
        if (activeEngine == null) return
        val requestId = memScoped {
            val output = alloc<ULongVar>()
            checkStatus(nuvio_engine_stop_stream(activeEngine, streamId, output.ptr))
            output.value
        }
        awaitEvent(activeEngine, requestId, NUVIO_ENGINE_EVENT_STREAM_STOPPED)
    }

    private suspend fun reclaimDiskCache(activeEngine: CPointer<nuvio_engine>) {
        val requestId = memScoped {
            val output = alloc<ULongVar>()
            checkStatus(nuvio_engine_reclaim_disk_cache(activeEngine, 0uL, output.ptr))
            output.value
        }
        awaitEvent(activeEngine, requestId, NUVIO_ENGINE_EVENT_DISK_CACHE_RECLAIMED)
    }

    private suspend fun awaitEvent(
        activeEngine: CPointer<nuvio_engine>,
        requestId: ULong,
        expectedType: UInt,
    ): NativeEvent {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val event = pollEvent(activeEngine)) {
                null -> delay(20L)
                else -> {
                    if (event.requestId == requestId && event.type == NUVIO_ENGINE_EVENT_TORRENT_ERROR) {
                        throw P2pStreamingException(event.message ?: "Torrent engine command failed")
                    }
                    if (event.requestId == requestId && event.type == expectedType) return event
                }
            }
        }
    }

    private fun pollEvent(activeEngine: CPointer<nuvio_engine>): NativeEvent? = memScoped {
        val event = alloc<nuvio_engine_event>()
        nuvio_engine_event_init_sized(event.ptr, sizeOf<nuvio_engine_event>().toUInt())
        val status = nuvio_engine_poll_event(activeEngine, event.ptr)
        if (status == NUVIO_ENGINE_STATUS_NO_EVENT) return@memScoped null
        checkStatus(status)
        NativeEvent(
            type = event.type,
            requestId = event.request_id,
            torrentId = event.torrent_id.toKString().ifBlank { null },
            message = event.message.toKString().ifBlank { null },
            fileIndex = event.file_index.takeUnless { it == UInt.MAX_VALUE },
            fileSize = event.file_size,
            streamId = event.stream_id.toKString().ifBlank { null },
            streamUrl = event.stream_url.toKString().ifBlank { null },
        )
    }

    private fun readAggregateStats(activeEngine: CPointer<nuvio_engine>): AggregateStats = memScoped {
        val stats = alloc<nuvio_engine_stats>()
        nuvio_engine_stats_init_sized(stats.ptr, sizeOf<nuvio_engine_stats>().toUInt())
        checkStatus(nuvio_engine_get_stats(activeEngine, stats.ptr))
        AggregateStats(
            peers = stats.connected_peers.toInt(),
            seeds = stats.connected_seeds.toInt(),
            downloadSpeed = stats.download_rate_bytes_per_second.toLong(),
            uploadSpeed = stats.upload_rate_bytes_per_second.toLong(),
            payloadDownloaded = stats.total_payload_download_bytes.toLong(),
            diskUsed = stats.disk_cache_used_bytes.toLong(),
            diskProtected = stats.disk_cache_protected_bytes.toLong(),
            diskReclaimed = stats.disk_cache_reclaimed_bytes.toLong(),
        )
    }

    private fun readStreamStats(
        activeEngine: CPointer<nuvio_engine>,
        streamId: String,
    ): StreamStats = memScoped {
        val stats = alloc<nuvio_engine_stream_stats>()
        nuvio_engine_stream_stats_init_sized(
            stats.ptr,
            sizeOf<nuvio_engine_stream_stats>().toUInt(),
        )
        checkStatus(nuvio_engine_get_stream_stats(activeEngine, streamId, stats.ptr))
        StreamStats(
            fileSize = stats.file_size.toLong(),
            contiguousReady = stats.contiguous_ready_bytes.toLong(),
            verified = stats.verified_file_bytes.toLong(),
            delivered = stats.delivered_bytes.toLong(),
        )
    }

    private fun updateCacheState(stats: AggregateStats) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = stats.diskUsed,
            protectedBytes = stats.diskProtected,
            hasMeasurement = true,
        )
    }

    private fun checkStatus(status: UInt) {
        if (status == NUVIO_ENGINE_STATUS_OK) return
        val message = nuvio_engine_status_message(status)?.toKString()
            ?: "Nuvio Engine status $status"
        throw P2pStreamingException(message)
    }

    private fun createDirectory(path: String) {
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )) { "Could not create Nuvio Engine directory" }
    }

    private fun migrateNestedPayloadDirectory(cacheDirectory: String) {
        val manager = NSFileManager.defaultManager
        val payloadDirectory = "$cacheDirectory/payload"
        val nestedDirectory = "$payloadDirectory/payload"
        val entries = manager.contentsOfDirectoryAtPath(nestedDirectory, error = null) ?: return
        entries.filterIsInstance<String>().forEach { name ->
            val source = "$nestedDirectory/$name"
            val target = "$payloadDirectory/$name"
            if (manager.fileExistsAtPath(target) ||
                !manager.moveItemAtPath(source, toPath = target, error = null)
            ) {
                manager.removeItemAtPath(source, error = null)
            }
        }
        manager.removeItemAtPath(nestedDirectory, error = null)
    }

    private fun ratio(value: Long, total: Long): Float =
        if (total <= 0L) 0f else (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

    private fun log(message: String) {
        NSLog("NuvioP2PDiag: $message")
    }

    private val DefaultTrackers = listOf(
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
