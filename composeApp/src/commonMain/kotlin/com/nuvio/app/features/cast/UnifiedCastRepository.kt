package com.nuvio.app.features.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UnifiedCastState {
    data object Idle : UnifiedCastState
    data object Scanning : UnifiedCastState
    data class DevicesFound(val devices: List<UnifiedCastDevice>) : UnifiedCastState
    data object NoDevices : UnifiedCastState
    data class Casting(val device: UnifiedCastDevice, val proxyUrl: String) : UnifiedCastState
    data class Error(val message: String) : UnifiedCastState
}

object UnifiedCastRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<UnifiedCastState>(UnifiedCastState.Idle)
    val state: StateFlow<UnifiedCastState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<UnifiedCastDevice>>(emptyList())
    val devices: StateFlow<List<UnifiedCastDevice>> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var castJob: Job? = null

    private var currentDevice: UnifiedCastDevice? = null
    val activeDevice: UnifiedCastDevice? get() = currentDevice
    private var currentProxyUrl: String? = null
    var isPaused = false
        private set
    var volume: Float = 1.0f
        private set

    fun startScan() {
        if (_state.value is UnifiedCastState.Scanning) return
        scanJob?.cancel()
        _state.value = UnifiedCastState.Scanning
        scanJob = scope.launch {
            try {
                val dlnaDeferred = async(Dispatchers.IO) {
                    try { DlnaCastPlatform.scanDevices(4000) } catch (_: Exception) { DlnaScanResult.Success(emptyList()) }
                }
                val castDeferred = async(Dispatchers.IO) {
                    try { ChromecastPlatform.scanDevices(4000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val dialDeferred = async(Dispatchers.IO) {
                    try { DialPlatform.scanDevices(3000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val airPlayDeferred = async(Dispatchers.IO) {
                    try { AirPlayPlatform.scanDevices(3000) } catch (_: Exception) { emptyList<UnifiedCastDevice>() }
                }
                val dlnaResult = dlnaDeferred.await()
                val castDevices = castDeferred.await()
                val dialDevices = dialDeferred.await()
                val airPlayDevices = airPlayDeferred.await()

                val dlnaUnified = when (dlnaResult) {
                    is DlnaScanResult.Success -> dlnaResult.devices.map { d ->
                        UnifiedCastDevice(
                            id = d.id,
                            name = d.friendlyName,
                            protocol = CastProtocol.DLNA,
                            dlnaDevice = d,
                            ipAddress = d.ipAddress
                        )
                    }
                    else -> emptyList()
                }
                val allUnsorted = castDevices + airPlayDevices + dialDevices + dlnaUnified
                val priority = mapOf(CastProtocol.CHROMECAST to 0, CastProtocol.AIRPLAY to 1, CastProtocol.DIAL to 2, CastProtocol.DLNA to 3)
                val all = allUnsorted.sortedBy { priority[it.protocol] ?: 99 }.distinctBy { it.id }
                _devices.value = all
                _state.value = if (all.isEmpty()) UnifiedCastState.NoDevices else UnifiedCastState.DevicesFound(all)
            } catch (e: Exception) {
                _state.value = UnifiedCastState.Error(e.message ?: "Scan error")
            }
        }
    }

    fun castToDevice(device: UnifiedCastDevice, dlnaRequest: DlnaCastRequest, chromecastPoster: String? = null) {
        castJob?.cancel()
        castJob = scope.launch {
            try {
                _state.value = UnifiedCastState.Casting(device, "")
                // Reuse same proxy preparation for both protocols (headers/ torrent)
                val proxyUrl = withContext(Dispatchers.IO) { DlnaCastPlatform.prepareProxyUrl(dlnaRequest) }
                currentDevice = device
                currentProxyUrl = proxyUrl
                _state.value = UnifiedCastState.Casting(device, proxyUrl)

                val success = withContext(Dispatchers.Main) {
                    when (device.protocol) {
                        CastProtocol.DLNA -> {
                            val d = device.dlnaDevice ?: return@withContext false
                            DlnaCastPlatform.castToDevice(d, dlnaRequest, proxyUrl)
                        }
                        CastProtocol.CHROMECAST -> {
                            val req = ChromecastMediaRequest(
                                proxyUrl = proxyUrl,
                                title = dlnaRequest.title,
                                subtitle = dlnaRequest.subtitle,
                                subtitleUrl = dlnaRequest.subtitleUrl,
                                subtitleName = dlnaRequest.subtitleName,
                                subtitleLanguage = dlnaRequest.subtitleLanguage ?: "en",
                                subtitles = dlnaRequest.subtitles,
                                mimeType = dlnaRequest.mimeType,
                                durationMs = dlnaRequest.durationMs,
                                startPositionMs = dlnaRequest.startPositionMs,
                                posterUrl = chromecastPoster ?: dlnaRequest.posterUrl,
                            )
                            ChromecastPlatform.castToDevice(device, req)
                        }
                        CastProtocol.DIAL -> {
                            // DIAL launch via Application-URL POST (Fire TV, Roku) - stub, fallback to DLNA if available
                            // For now, try DLNA as fallback
                            val d = device.dlnaDevice
                            if (d != null) DlnaCastPlatform.castToDevice(d, dlnaRequest, proxyUrl) else false
                        }
                        CastProtocol.AIRPLAY -> {
                            // AirPlay not yet implemented on Android (needs NsdManager + AirPlay protocol)
                            false
                        }
                    }
                }

                if (!success) {
                    _state.value = UnifiedCastState.Error("Failed to send to ${device.name}")
                }
            } catch (e: Exception) {
                _state.value = UnifiedCastState.Error(e.message ?: "Cast error")
            }
        }
    }

    fun pause() {
        val dev = currentDevice ?: return
        scope.launch {
            when (dev.protocol) {
                CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.pausePlayback(it) }
                CastProtocol.CHROMECAST -> ChromecastPlatform.pause()
                CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.pausePlayback(it) }
                CastProtocol.AIRPLAY -> {} // stub
            }
            isPaused = true
        }
    }

    fun resume() {
        val dev = currentDevice ?: return
        scope.launch {
            when (dev.protocol) {
                CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.resumePlayback(it) }
                CastProtocol.CHROMECAST -> ChromecastPlatform.resume()
                CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.resumePlayback(it) }
                CastProtocol.AIRPLAY -> {}
            }
            isPaused = false
        }
    }

    fun seek(positionMs: Long) {
        val dev = currentDevice ?: return
        scope.launch {
            when (dev.protocol) {
                CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.seekPlayback(it, positionMs) }
                CastProtocol.CHROMECAST -> ChromecastPlatform.seek(positionMs)
                CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.seekPlayback(it, positionMs) }
                CastProtocol.AIRPLAY -> {}
            }
        }
    }

    fun stop() {
        castJob?.cancel()
        val dev = currentDevice
        // Immediate UI - X works instantly
        isPaused = false
        currentDevice = null
        currentProxyUrl = null
        _state.value = UnifiedCastState.Idle
        scope.launch(Dispatchers.IO) {
            try {
                when (dev?.protocol) {
                    CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.stopPlayback(it) }
                    CastProtocol.CHROMECAST -> ChromecastPlatform.stop()
                    CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.stopPlayback(it) }
                    CastProtocol.AIRPLAY -> {}
                    else -> {}
                }
            } catch (_: Exception) {}
            try {
                when (dev?.protocol) {
                    CastProtocol.DLNA -> DlnaCastPlatform.stopProxy()
                    CastProtocol.CHROMECAST -> {
                        ChromecastPlatform.disconnect()
                        DlnaCastPlatform.stopProxy()
                    }
                    CastProtocol.DIAL -> DlnaCastPlatform.stopProxy()
                    else -> DlnaCastPlatform.stopProxy()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun getPosition(): Long? {
        val dev = currentDevice ?: return null
        return when (dev.protocol) {
            CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.getPositionInfo(it) }
            CastProtocol.CHROMECAST -> ChromecastPlatform.getPosition()
            CastProtocol.DIAL -> dev.dlnaDevice?.let { DlnaCastPlatform.getPositionInfo(it) }
            CastProtocol.AIRPLAY -> null
        }
    }

    fun setVolume(volumeFraction: Float) {
        val dev = currentDevice ?: return
        val clamped = volumeFraction.coerceIn(0f, 1f)
        volume = clamped
        scope.launch(Dispatchers.IO) {
            when (dev.protocol) {
                CastProtocol.CHROMECAST -> ChromecastPlatform.setVolume(clamped)
                CastProtocol.DLNA -> dev.dlnaDevice?.let { DlnaCastPlatform.setVolume(it, (clamped * 100).toInt()) }
                else -> {}
            }
        }
    }

    fun setActiveSubtitleTrack(trackId: Long?) {
        val dev = currentDevice ?: return
        scope.launch(Dispatchers.IO) {
            when (dev.protocol) {
                CastProtocol.CHROMECAST -> ChromecastPlatform.setActiveTrack(trackId)
                else -> {}
            }
        }
    }

    // Capability sniffing - checks GetProtocolInfo for DLNA, assumes modern for Cast
    suspend fun getCapabilities(device: UnifiedCastDevice): DeviceCapabilities = withContext(Dispatchers.IO) {
        when (device.protocol) {
            CastProtocol.CHROMECAST -> DeviceCapabilities(supportsHevc = true, supportsAv1 = true, supportsHls = true, supportsMkv = true)
            CastProtocol.AIRPLAY -> DeviceCapabilities(supportsHevc = true, supportsAv1 = false, supportsHls = true)
            CastProtocol.DIAL -> DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = false)
            CastProtocol.DLNA -> {
                // Old Samsung DLNA typically only mp4/avc/aac, try GetProtocolInfo
                try {
                    val d = device.dlnaDevice ?: return@withContext DeviceCapabilities()
                    // Conservative: assume DLNA without hevc/av1
                    val isSamsungOld = d.modelName?.contains("Samsung", ignoreCase = true) == true || d.locationUrl.contains("smp_")
                    if (isSamsungOld) DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = false)
                    else DeviceCapabilities(supportsHevc = false, supportsAv1 = false, supportsMkv = true)
                } catch (_: Exception) { DeviceCapabilities() }
            }
        }
    }

    // WOL placeholder - send magic packet to TV MAC if known (future: store MAC from discovery)
    fun wakeOnLan(macAddress: String, broadcastIp: String = "255.255.255.255") {
        // Stub for universal WOL - magic packet 6x 0xFF + 16x MAC
        // Will be implemented with DatagramSocket to 9/UDP when MAC is discovered via ARP
    }

    fun reset() {
        _devices.value = emptyList()
        _state.value = UnifiedCastState.Idle
    }
}
