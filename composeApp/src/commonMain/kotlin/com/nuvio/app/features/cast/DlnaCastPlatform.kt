package com.nuvio.app.features.cast

internal expect object DlnaCastPlatform {
    suspend fun scanDevices(timeoutMs: Int = 4000): DlnaScanResult
    suspend fun prepareProxyUrl(request: DlnaCastRequest): String
    suspend fun castToDevice(device: DlnaDevice, request: DlnaCastRequest, proxyUrl: String): Boolean
    suspend fun stopPlayback(device: DlnaDevice): Boolean
    suspend fun pausePlayback(device: DlnaDevice): Boolean
    suspend fun resumePlayback(device: DlnaDevice): Boolean
    suspend fun seekPlayback(device: DlnaDevice, positionMs: Long): Boolean
    suspend fun getPositionInfo(device: DlnaDevice): Long?
    suspend fun setVolume(device: DlnaDevice, volumePercent: Int): Boolean
    suspend fun getLocalIpAddress(): String?
    fun stopProxy()
}
