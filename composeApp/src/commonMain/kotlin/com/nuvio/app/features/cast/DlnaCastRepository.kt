package com.nuvio.app.features.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Deprecated("Use UnifiedCastRepository instead", ReplaceWith("UnifiedCastRepository"))
object DlnaCastRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<DlnaCastState>(DlnaCastState.Idle)
    val state: StateFlow<DlnaCastState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var proxyJob: Job? = null

    var isProxyRunning: Boolean = false
        private set

    private var currentProxyUrl: String? = null
    private var currentCastDevice: DlnaDevice? = null

    val castDevice: DlnaDevice? get() = currentCastDevice
    val proxyUrl: String? get() = currentProxyUrl

    var isPaused: Boolean = false
        private set

    fun startScan() {
        if (_state.value is DlnaCastState.Scanning) return
        scanJob?.cancel()
        _state.value = DlnaCastState.Scanning
        scanJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    DlnaCastPlatform.scanDevices(timeoutMs = 4000)
                }
                when (result) {
                    is DlnaScanResult.Success -> {
                        _devices.value = result.devices
                        _state.value = if (result.devices.isEmpty()) {
                            DlnaCastState.NoDevices
                        } else {
                            DlnaCastState.DevicesFound(result.devices)
                        }
                    }
                    is DlnaScanResult.Failure -> {
                        _state.value = DlnaCastState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                _state.value = DlnaCastState.Error(e.message ?: "DLNA scan error")
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        if (_state.value is DlnaCastState.Scanning) {
            _state.value = if (_devices.value.isEmpty()) DlnaCastState.NoDevices else DlnaCastState.DevicesFound(_devices.value)
        }
    }

    fun castToDevice(device: DlnaDevice, request: DlnaCastRequest, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        proxyJob?.cancel()
        proxyJob = scope.launch {
            try {
                _state.value = DlnaCastState.Casting(device, "")
                val proxyUrl = withContext(Dispatchers.IO) {
                    DlnaCastPlatform.prepareProxyUrl(request)
                }
                currentProxyUrl = proxyUrl
                currentCastDevice = device
                isProxyRunning = true
                _state.value = DlnaCastState.Casting(device, proxyUrl)
                val success = withContext(Dispatchers.IO) {
                    DlnaCastPlatform.castToDevice(device, request, proxyUrl)
                }
                if (success) {
                    onResult(true, null)
                } else {
                    _state.value = DlnaCastState.Error("Failed to send to TV")
                    onResult(false, "Cast failed")
                }
            } catch (e: Exception) {
                _state.value = DlnaCastState.Error(e.message ?: "Cast error")
                onResult(false, e.message)
            }
        }
    }

    fun pauseCasting() {
        val dev = currentCastDevice ?: return
        scope.launch(Dispatchers.IO) {
            try {
                DlnaCastPlatform.pausePlayback(dev)
                isPaused = true
            } catch (_: Exception) {}
        }
    }

    fun resumeCasting() {
        val dev = currentCastDevice ?: return
        scope.launch(Dispatchers.IO) {
            try {
                DlnaCastPlatform.resumePlayback(dev)
                isPaused = false
            } catch (_: Exception) {}
        }
    }

    fun seekCasting(positionMs: Long) {
        val dev = currentCastDevice ?: return
        scope.launch(Dispatchers.IO) {
            try { DlnaCastPlatform.seekPlayback(dev, positionMs) } catch (_: Exception) {}
        }
    }

    fun stopCasting() {
        proxyJob?.cancel()
        val dev = currentCastDevice
        // Immediate UI feedback - X works instantly
        isProxyRunning = false
        isPaused = false
        currentProxyUrl = null
        currentCastDevice = null
        _state.value = DlnaCastState.Idle
        scope.launch(Dispatchers.IO) {
            try { dev?.let { DlnaCastPlatform.stopPlayback(it) } } catch (_: Exception) {}
            try { DlnaCastPlatform.stopProxy() } catch (_: Exception) {}
        }
    }

    suspend fun getRemotePosition(): Long? {
        val dev = currentCastDevice ?: return null
        return try { DlnaCastPlatform.getPositionInfo(dev) } catch (_: Exception) { null }
    }

    fun reset() {
        stopScan()
        stopCasting()
        _devices.value = emptyList()
        _state.value = DlnaCastState.Idle
    }
}
